package app.mnema.learning.generation;

import app.mnema.learning.ai.AiFailure;
import app.mnema.learning.ai.AiResult;
import app.mnema.learning.ai.SpeechSynthesis;
import app.mnema.learning.media.GeneratedMediaStager;
import app.mnema.learning.media.GeneratedMediaStager.VerifiedMedia;
import app.mnema.learning.media.MediaCatalog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The shared part of the speech steps on scripted doubles: the cache protocol, fallback filing, interruption and the wait for verification. */
class SpeechClipsTest {
    private static final SpeechSynthesis.Identity PRIMARY = new SpeechSynthesis.Identity("google", "m", "v1", "wav", "Kore");
    private static final SpeechSynthesis.Identity FALLBACK = new SpeechSynthesis.Identity("yandex", "k", "v1", "mp3", "alena");
    private static final SpeechClips.Clip CLIP = new SpeechClips.Clip("привет", "ru", "female", 0);
    private static final VerifiedMedia MEDIA = new VerifiedMedia(UUID.randomUUID(), List.of());

    /** A stager that answers what it is told. */
    private static final class Stager implements GeneratedMediaStager {
        final List<UUID> staged = new ArrayList<>();
        final Queue<State> states = new ArrayDeque<>();
        boolean adopt = true;
        boolean adoptThrows;
        boolean stageThrows;
        Optional<VerifiedMedia> verified = Optional.of(MEDIA);

        @Override
        public void stage(UUID owner, UUID assetId, MediaCatalog.Kind kind, String mimeType, byte[] bytes) {
            if (stageThrows) throw new IllegalStateException("down");
            staged.add(assetId);
        }

        @Override
        public void reserve(UUID owner, UUID assetId, MediaCatalog.Kind kind, String mimeType, byte[] bytes) { }

        @Override
        public State assetState(UUID owner, UUID assetId) { return states.size() > 1 ? states.poll() : states.peek(); }

        @Override public Optional<VerifiedMedia> verified(UUID owner, UUID assetId) { return verified; }

        @Override
        public boolean adopt(UUID owner, UUID assetId, VerifiedMedia media) {
            if (adoptThrows) throw new IllegalStateException("db");
            return adopt;
        }
    }

    private static final class Control implements StepExecutor.StepControl {
        boolean cancelled;
        boolean lost;

        @Override public boolean cancelled() { return cancelled; }

        @Override public void callStarted() { }

        @Override public void callEnded() { }

        @Override public boolean lost() { return lost; }
    }

    private final SpeechSynthesis speech = mock(SpeechSynthesis.class);
    private final SpeechCache cache = mock(SpeechCache.class);
    private final Stager stager = new Stager();
    private final Control control = new Control();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SpeechClips clips = new SpeechClips(speech, cache, stager, meters, Duration.ofMillis(5));
    private final UUID owner = UUID.randomUUID();
    private final UUID asset = UUID.randomUUID();

    private StepClaim claim() {
        return new StepClaim(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), owner, "TTS", "TTS", UUID.randomUUID(), 1, Instant.now().plusSeconds(60),
                Json.object());
    }

    private static SpeechSynthesis.Audio audio(SpeechSynthesis.Identity identity) {
        return new SpeechSynthesis.Audio(new byte[] {1, 2, 3}, "audio/wav", 6, 1_000, identity, 321);
    }

    private SpeechClips.Outcome stage(Instant deadline) { return clips.stage(claim(), control, owner, asset, CLIP, deadline); }

    @Test
    void aTextWithPersonalDataIsRefusedBeforeTheCacheAndAnyProvider() {
        for (String text : new String[] {"Напишите мне: anna@example.com", "Звоните +7 (916) 123-45-67", "Карта 4111 1111 1111 1111", "mail  anna@example.com  now"}) {
            SpeechClips.Outcome outcome = clips.stage(claim(), control, owner, asset, new SpeechClips.Clip(text, "ru", "female", 0), Instant.now().plusSeconds(5));

            assertThat(outcome).as(text).isEqualTo(new SpeechClips.Outcome.Failed("PERSONAL_DATA"));
        }
        verify(speech, never()).synthesize(any());
        verify(speech, never()).identity(any(), any());
        verify(cache, never()).claim(any());
        assertThat(stager.staged).isEmpty();
    }

    @Test
    void aTextWithoutPersonalDataPassesUnchangedToTheProvider() {
        when(speech.identity("en", "female")).thenReturn(Optional.of(PRIMARY));
        when(cache.claim(any())).thenReturn(new SpeechCache.Claim.Won(UUID.randomUUID()));
        when(speech.synthesize(any())).thenReturn(AiResult.ok(audio(PRIMARY)));
        // dates, decimals, years and a URL with digits are not personal data: the redaction leaves them as they are
        String text = "In 2026 the rate was 3.14 on 12.10.2026, see https://example.org/a/12345678901.";

        SpeechClips.Outcome outcome = clips.stage(claim(), control, owner, asset, new SpeechClips.Clip(text, "en", "female", 0), Instant.now().plusSeconds(5));

        assertThat(outcome).isInstanceOf(SpeechClips.Outcome.Staged.class);
        org.mockito.ArgumentCaptor<SpeechSynthesis.Request> sent = org.mockito.ArgumentCaptor.forClass(SpeechSynthesis.Request.class);
        verify(speech).synthesize(sent.capture());
        assertThat(sent.getValue().text()).isEqualTo(text);
    }

    @Test
    void aHitIsAdoptedWithoutAProviderCall() {
        when(speech.identity("ru", "female")).thenReturn(Optional.of(PRIMARY));
        when(cache.claim(any())).thenReturn(new SpeechCache.Claim.Hit(MEDIA));

        SpeechClips.Outcome outcome = stage(Instant.now().plusSeconds(5));

        assertThat(outcome).isEqualTo(new SpeechClips.Outcome.Staged(asset, false, 0, null));
        verify(speech, never()).synthesize(any());
    }

    @Test
    void aHitThatCannotBeAdoptedIsDroppedAndTheNextRoundSynthesises() {
        when(speech.identity("ru", "female")).thenReturn(Optional.of(PRIMARY));
        UUID token = UUID.randomUUID();
        when(cache.claim(any())).thenReturn(new SpeechCache.Claim.Hit(MEDIA), new SpeechCache.Claim.Won(token));
        stager.adopt = false;
        stager.states.add(GeneratedMediaStager.State.MISSING);
        when(speech.synthesize(any())).thenReturn(AiResult.ok(audio(PRIMARY)));

        SpeechClips.Outcome outcome = stage(Instant.now().plusSeconds(5));

        verify(cache).drop(any());
        assertThat(outcome).isInstanceOfSatisfying(SpeechClips.Outcome.Staged.class, staged -> {
            assertThat(staged.synthesized()).isTrue();
            assertThat(staged.costMicros()).isEqualTo(321);
            assertThat(staged.pending().token()).isEqualTo(token);
            assertThat(staged.pending().byteLength()).isEqualTo(3);
        });
        assertThat(stager.staged).containsExactly(asset);

    }

    @Test
    void aFailedAdoptionOrAnExistingAssetDoesNotDropTheEntry() {
        when(speech.identity("ru", "female")).thenReturn(Optional.of(PRIMARY));
        when(cache.claim(any())).thenReturn(new SpeechCache.Claim.Hit(MEDIA));
        stager.adoptThrows = true;
        assertThatThrownBy(() -> stage(Instant.now().plusSeconds(5))).isInstanceOf(IllegalStateException.class);
        stager.adoptThrows = false;
        stager.adopt = false;
        stager.states.add(GeneratedMediaStager.State.VERIFYING);
        assertThat(stage(Instant.now().plusSeconds(5))).isEqualTo(new SpeechClips.Outcome.Failed("PROVIDER_UNAVAILABLE"));
        verify(cache, never()).drop(any());
    }

    @Test
    void aResumedClipTakesOverTheEntryOfTheEarlierAttemptAndTheStepAssetIsAFunctionOfTheStep() {
        when(speech.identity("ru", "female")).thenReturn(Optional.of(PRIMARY));
        UUID token = UUID.randomUUID();
        when(cache.claim(any())).thenReturn(new SpeechCache.Claim.Won(token));
        SpeechClips.Outcome.Staged staged = clips.resume(asset, CLIP, true, 777);
        assertThat(staged.synthesized()).isTrue();
        assertThat(staged.costMicros()).isEqualTo(777);
        assertThat(staged.pending().token()).isEqualTo(token);
        assertThat(clips.resume(asset, CLIP, false, 0).pending()).isNull();
        UUID step = UUID.randomUUID();
        assertThat(SpeechClips.assetOf(step, 0)).isEqualTo(SpeechClips.assetOf(step, 0)).isNotEqualTo(SpeechClips.assetOf(step, 1));
        assertThat(SpeechClips.assetOf(step, 0).version()).isEqualTo(4);
    }

    @Test
    void aBusyEntryIsWaitedForUntilItIsReadyOrTheDeadlinePasses() {
        when(speech.identity("ru", "female")).thenReturn(Optional.of(PRIMARY));
        when(cache.claim(any())).thenReturn(new SpeechCache.Claim.Busy(), new SpeechCache.Claim.Busy(), new SpeechCache.Claim.Hit(MEDIA));
        assertThat(stage(Instant.now().plusSeconds(5))).isEqualTo(new SpeechClips.Outcome.Staged(asset, false, 0, null));

        when(cache.claim(any())).thenReturn(new SpeechCache.Claim.Busy());
        assertThat(stage(Instant.now().plusMillis(30))).isEqualTo(new SpeechClips.Outcome.Failed("DEADLINE_EXCEEDED"));
        verify(speech, never()).synthesize(any());
        assertThat(meters.counter("mnema_tts_cache_total", "outcome", "busy").count()).isGreaterThanOrEqualTo(3);
        assertThat(meters.counter("mnema_tts_cache_total", "outcome", "hit").count()).isEqualTo(1);
        assertThat(meters.counter("mnema_tts_cache_total", "outcome", "miss").count()).isZero();
    }

    @Test
    void aMissIsCountedWhenTheCallerWinsTheEntry() {
        when(speech.identity("ru", "female")).thenReturn(Optional.of(PRIMARY));
        when(cache.claim(any())).thenReturn(new SpeechCache.Claim.Won(UUID.randomUUID()));
        when(speech.synthesize(any())).thenReturn(AiResult.ok(audio(PRIMARY)));
        stage(Instant.now().plusSeconds(5));
        assertThat(meters.counter("mnema_tts_cache_total", "outcome", "miss").count()).isEqualTo(1);
    }

    @Test
    void nothingThatCanServeTheClipFailsAsUnavailableAndALostOrCancelledClaimStops() {
        when(speech.identity("ru", "female")).thenReturn(Optional.empty());
        assertThat(stage(Instant.now().plusSeconds(5))).isEqualTo(new SpeechClips.Outcome.Failed("PROVIDER_UNAVAILABLE"));
        control.lost = true;
        assertThat(stage(Instant.now().plusSeconds(5))).isEqualTo(new SpeechClips.Outcome.Interrupted(false));
        control.lost = false;
        control.cancelled = true;
        assertThat(stage(Instant.now().plusSeconds(5))).isEqualTo(new SpeechClips.Outcome.Interrupted(true));
    }

    @Test
    void aFailedLostOrCancelledSynthesisGivesTheEntryBack() {
        when(speech.identity("ru", "female")).thenReturn(Optional.of(PRIMARY));
        UUID token = UUID.randomUUID();
        when(cache.claim(any())).thenReturn(new SpeechCache.Claim.Won(token));
        when(speech.synthesize(any())).thenReturn(AiResult.failed(new AiFailure.Transient("x")));
        assertThat(stage(Instant.now().plusSeconds(5))).isEqualTo(new SpeechClips.Outcome.Failed("PROVIDER_UNAVAILABLE"));

        org.mockito.Mockito.doAnswer(call -> {
            control.lost = true;
            return AiResult.ok(audio(PRIMARY));
        }).when(speech).synthesize(any());
        assertThat(stage(Instant.now().plusSeconds(5))).isEqualTo(new SpeechClips.Outcome.Interrupted(false));
        control.lost = false;
        org.mockito.Mockito.doAnswer(call -> {
            control.cancelled = true;
            return AiResult.ok(audio(PRIMARY));
        }).when(speech).synthesize(any());
        assertThat(stage(Instant.now().plusSeconds(5))).isEqualTo(new SpeechClips.Outcome.Interrupted(true));
        verify(cache, org.mockito.Mockito.times(3)).abandon(any(), org.mockito.ArgumentMatchers.eq(token));

        control.cancelled = false;
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(speech).synthesize(any());
        assertThatThrownBy(() -> stage(Instant.now().plusSeconds(5))).isInstanceOf(IllegalStateException.class);
        verify(cache, org.mockito.Mockito.times(4)).abandon(any(), org.mockito.ArgumentMatchers.eq(token));
    }

    @Test
    void aClipThatCannotBeStagedGivesTheEntryBackAndFails() {
        when(speech.identity("ru", "female")).thenReturn(Optional.of(PRIMARY));
        UUID token = UUID.randomUUID();
        when(cache.claim(any())).thenReturn(new SpeechCache.Claim.Won(token));
        when(speech.synthesize(any())).thenReturn(AiResult.ok(audio(PRIMARY)));
        stager.stageThrows = true;

        assertThat(stage(Instant.now().plusSeconds(5))).isEqualTo(new SpeechClips.Outcome.Failed("PROVIDER_UNAVAILABLE"));
        verify(cache).abandon(any(), org.mockito.ArgumentMatchers.eq(token));
    }

    @Test
    void aFallbackAnswerIsFiledUnderTheProviderThatMadeItAndTheRequestedEntryIsReleased() {
        when(speech.identity("ru", "female")).thenReturn(Optional.of(PRIMARY));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(cache.claim(any())).thenReturn(new SpeechCache.Claim.Won(first), new SpeechCache.Claim.Won(second));
        when(speech.synthesize(any())).thenReturn(AiResult.ok(audio(FALLBACK)));

        SpeechClips.Outcome outcome = stage(Instant.now().plusSeconds(5));

        verify(cache).abandon(any(), org.mockito.ArgumentMatchers.eq(first));
        assertThat(outcome).isInstanceOfSatisfying(SpeechClips.Outcome.Staged.class, staged -> {
            assertThat(staged.pending().token()).isEqualTo(second);
            assertThat(staged.pending().key().identity()).isEqualTo(FALLBACK);
        });

        // the fallback's own entry is already taken: the clip is served and simply not cached
        when(cache.claim(any())).thenReturn(new SpeechCache.Claim.Won(first), new SpeechCache.Claim.Busy());
        assertThat(stage(Instant.now().plusSeconds(5))).isInstanceOfSatisfying(SpeechClips.Outcome.Staged.class, staged -> assertThat(staged.pending()).isNull());
    }

    // ------------------------------------------------------------------ await

    private SpeechClips.Outcome.Staged pending() {
        SpeechCache.Key key = SpeechCache.key("привет", "ru", PRIMARY, 0);
        return new SpeechClips.Outcome.Staged(asset, true, 1, new SpeechClips.Pending(key, UUID.randomUUID(), 1_000, 3));
    }

    @Test
    void aVerifiedClipIsPublishedAndOneThatCannotBeReadBackReleasesItsEntry() {
        SpeechClips.Outcome.Staged staged = pending();
        stager.states.add(GeneratedMediaStager.State.VERIFYING);
        stager.states.add(GeneratedMediaStager.State.READY);
        when(cache.publish(any(), any(), any(), anyLong(), anyLong())).thenReturn(true);

        assertThat(clips.await(claim(), control, owner, staged, Instant.now().plusSeconds(5))).isEqualTo(SpeechClips.Verdict.READY);
        verify(cache).publish(staged.pending().key(), staged.pending().token(), MEDIA, 1_000, 3);

        stager.states.clear();
        stager.states.add(GeneratedMediaStager.State.READY);
        stager.verified = Optional.empty();
        assertThat(clips.await(claim(), control, owner, staged, Instant.now().plusSeconds(5))).isEqualTo(SpeechClips.Verdict.READY);
        verify(cache).abandon(staged.pending().key(), staged.pending().token());
    }

    @Test
    void aRejectedOrCancelledClipReleasesItsEntryAndALateOrLostOneLeavesItToItsLease() {
        SpeechClips.Outcome.Staged staged = pending();
        stager.states.add(GeneratedMediaStager.State.REJECTED);
        assertThat(clips.await(claim(), control, owner, staged, Instant.now().plusSeconds(5))).isEqualTo(SpeechClips.Verdict.REJECTED);
        stager.states.clear();
        stager.states.add(GeneratedMediaStager.State.MISSING);
        assertThat(clips.await(claim(), control, owner, staged, Instant.now().plusSeconds(5))).isEqualTo(SpeechClips.Verdict.REJECTED);
        verify(cache, org.mockito.Mockito.times(2)).abandon(staged.pending().key(), staged.pending().token());

        stager.states.clear();
        stager.states.add(GeneratedMediaStager.State.VERIFYING);
        control.cancelled = true;
        assertThat(clips.await(claim(), control, owner, staged, Instant.now().plusSeconds(5))).isEqualTo(SpeechClips.Verdict.CANCELLED);
        verify(cache, org.mockito.Mockito.times(3)).abandon(staged.pending().key(), staged.pending().token());

        control.cancelled = false;
        control.lost = true;
        assertThat(clips.await(claim(), control, owner, staged, Instant.now().plusSeconds(5))).isEqualTo(SpeechClips.Verdict.LOST);
        control.lost = false;
        assertThat(clips.await(claim(), control, owner, staged, Instant.now().plusMillis(20))).isEqualTo(SpeechClips.Verdict.LATE);
        verify(cache, org.mockito.Mockito.times(3)).abandon(any(), any());
        verify(cache, never()).publish(any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void theLeaseOfTheWinnerIsRenewedWhileItWaitsForTheVerification() {
        SpeechClips.Outcome.Staged staged = pending();

        assertThat(awaitAcrossRenewalAndDeadline(staged)).isEqualTo(SpeechClips.Verdict.LATE);
        verify(cache).renew(staged.pending().key(), staged.pending().token());
    }

    @Test
    void aClipWithoutAPendingCacheEntryDoesNotRenewWhileItWaitsForTheVerification() {
        SpeechClips.Outcome.Staged staged = new SpeechClips.Outcome.Staged(asset, false, 0, null);

        assertThat(awaitAcrossRenewalAndDeadline(staged)).isEqualTo(SpeechClips.Verdict.LATE);
        verify(cache, never()).renew(any(), any());
    }

    /** Logical verification polls cross the renewal interval before the deadline, independently of host scheduling. */
    private SpeechClips.Verdict awaitAcrossRenewalAndDeadline(SpeechClips.Outcome.Staged staged) {
        Instant started = Instant.parse("2026-10-07T12:00:00Z");
        AtomicReference<Instant> now = new AtomicReference<>(started);
        GeneratedMediaStager verification = mock(GeneratedMediaStager.class);
        when(verification.assetState(owner, asset)).thenAnswer(ignored -> {
            now.updateAndGet(time -> time.plusSeconds(16));
            return GeneratedMediaStager.State.VERIFYING;
        });
        SpeechClips waiting = new SpeechClips(speech, cache, verification, meters, Duration.ofMillis(5));
        StepClaim held = claim();

        // Static mocks are thread-scoped; await and this scripted stager run synchronously on the test thread.
        try (MockedStatic<Instant> time = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenAnswer(ignored -> now.get());
            return waiting.await(held, control, owner, staged, started.plusSeconds(30));
        }
    }
}
