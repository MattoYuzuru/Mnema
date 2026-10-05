package app.mnema.learning.generation;

import app.mnema.learning.ai.AiResult;
import app.mnema.learning.ai.SpeechSynthesis;
import app.mnema.learning.generation.StepExecutor.StepControl;
import app.mnema.learning.media.GeneratedMediaStager;
import app.mnema.learning.media.MediaCatalog;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Makes one clip into an asset of the owner, the part every speech step shares: the cache first, else one provider call, then the media pipeline.
 * Nothing here holds a database transaction while it calls the provider, stages or waits (each cache and media write is its own short transaction).
 *
 * <p>{@link #stage} ends with the asset staged ({@link Outcome.Staged}: READY at once for a cache hit, VERIFYING after a synthesis) or with the code the
 * step fails with. For a synthesis, {@link #await} then waits for the verification and only a clip that passed it is published to the cache; a rejected
 * clip is never cached, so the garbage of a provider cannot be served twice.
 */
@Component
class SpeechClips {
    private static final Logger LOG = LoggerFactory.getLogger(SpeechClips.class);
    private static final Duration DEFAULT_POLL = Duration.ofMillis(500);
    private static final Duration RENEW = Duration.ofSeconds(15);

    /** What one clip is made of. {@code text} is normalised by the cache key and the provider call alike. */
    record Clip(String text, String lang, String voice, int take) { }

    /** The cache entry a synthesis owns until its clip is verified. */
    record Pending(SpeechCache.Key key, UUID token, long durationMs, long byteLength) { }

    sealed interface Outcome {
        /** The asset exists. {@code synthesized}: this run called a provider (a cache miss: the step is debited); {@code pending} is null for a hit. */
        record Staged(UUID assetId, boolean synthesized, long costMicros, Pending pending) implements Outcome { }

        record Failed(String code) implements Outcome { }

        /** The lease is gone ({@code cancelled} false: write nothing) or the owner cancelled ({@code cancelled} true: end cancelled). */
        record Interrupted(boolean cancelled) implements Outcome { }
    }

    /** How the wait for a staged clip ended. */
    enum Verdict { READY, REJECTED, LATE, LOST, CANCELLED }

    private final SpeechSynthesis speech;
    private final SpeechCache cache;
    private final GeneratedMediaStager stager;
    private final Duration poll;
    private final MeterRegistry meters;

    @Autowired
    SpeechClips(SpeechSynthesis speech, SpeechCache cache, GeneratedMediaStager stager, MeterRegistry meters) {
        this(speech, cache, stager, meters, DEFAULT_POLL);
    }

    SpeechClips(SpeechSynthesis speech, SpeechCache cache, GeneratedMediaStager stager, MeterRegistry meters, Duration poll) {
        this.meters = meters;
        this.speech = speech;
        this.cache = cache;
        this.stager = stager;
        this.poll = poll;
    }

    Outcome stage(StepClaim claim, StepControl control, UUID owner, UUID assetId, Clip clip, Instant deadline) {
        while (true) {
            if (control.lost() || Thread.currentThread().isInterrupted()) return new Outcome.Interrupted(false);
            if (control.cancelled()) return new Outcome.Interrupted(true);
            var identity = speech.identity(clip.lang(), clip.voice());
            if (identity.isEmpty()) return new Outcome.Failed("PROVIDER_UNAVAILABLE");
            SpeechCache.Key key = SpeechCache.key(clip.text(), clip.lang(), identity.get(), clip.take());
            SpeechCache.Claim found = cache.claim(key);
            switch (found) {
                case SpeechCache.Claim.Hit hit -> {
                    outcome("hit");
                    // a failure of the adoption (the database) propagates: the step fails and retries, the entry is not at fault
                    if (stager.adopt(owner, assetId, hit.media())) return new Outcome.Staged(assetId, false, 0, null);
                    // only an entry whose blobs are gone is dropped (the next round synthesises); an asset that already exists is no fault of the entry
                    if (stager.assetState(owner, assetId) != GeneratedMediaStager.State.MISSING) return new Outcome.Failed("PROVIDER_UNAVAILABLE");
                    cache.drop(key);
                }
                case SpeechCache.Claim.Busy busy -> {
                    outcome("busy");
                    if (Instant.now().plus(poll).isAfter(deadline)) return new Outcome.Failed("DEADLINE_EXCEEDED");
                    sleep();
                }
                case SpeechCache.Claim.Won won -> {
                    outcome("miss");
                    return synthesize(claim, control, owner, assetId, clip, key, won.token());
                }
            }
        }
    }

    /** The hit ratio of the speech cache: one count per claim of {@link #stage} ({@code busy} is a poll behind another step's synthesis). */
    private void outcome(String outcome) {
        meters.counter("mnema_tts_cache_total", "outcome", outcome).increment();
    }

    /**
     * The asset of an earlier attempt of the step is already staged: only its verification is left. {@code synthesized} (the call journal says a provider
     * answered for the step) keeps the step debited as a cache miss, at {@code costMicros} (what the journal recorded); the cache entry the earlier
     * attempt held is taken over, when its lease ran out, so the verified clip is still published.
     */
    Outcome.Staged resume(UUID assetId, Clip clip, boolean synthesized, long costMicros) {
        Pending pending = null;
        if (synthesized) {
            var identity = speech.identity(clip.lang(), clip.voice());
            if (identity.isPresent()) {
                SpeechCache.Key key = SpeechCache.key(clip.text(), clip.lang(), identity.get(), clip.take());
                // duration and size are read back from the verified blobs when the entry is published (0 = unknown)
                if (cache.claim(key) instanceof SpeechCache.Claim.Won won) pending = new Pending(key, won.token(), 0, 0);
            }
        }
        return new Outcome.Staged(assetId, synthesized, costMicros, pending);
    }

    /** The asset of clip {@code index} of a step: a function of both, so a retry of the step resumes the asset an earlier attempt staged. */
    static UUID assetOf(UUID stepId, int index) {
        byte[] hash;
        try {
            hash = java.security.MessageDigest.getInstance("SHA-256").digest(("speech-clip/" + stepId + "/" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x40);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(hash);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private Outcome synthesize(StepClaim claim, StepControl control, UUID owner, UUID assetId, Clip clip, SpeechCache.Key key, UUID token) {
        AiResult<SpeechSynthesis.Audio> result;
        control.callStarted();
        try {
            result = speech.synthesize(new SpeechSynthesis.Request(SpeechCache.normalize(clip.text()), clip.lang(), clip.voice(), clip.take(),
                    claim.stepId(), claim.attempt()));
        } catch (RuntimeException failure) {
            cache.abandon(key, token);
            throw failure;
        } finally {
            control.callEnded();
        }
        if (control.lost() || control.cancelled() || result instanceof AiResult.Failed<SpeechSynthesis.Audio>) {
            cache.abandon(key, token);
            if (control.lost()) return new Outcome.Interrupted(false);
            if (control.cancelled()) return new Outcome.Interrupted(true);
            return new Outcome.Failed("PROVIDER_UNAVAILABLE");
        }
        SpeechSynthesis.Audio audio = ((AiResult.Ok<SpeechSynthesis.Audio>) result).value();
        SpeechCache.Key owned = key;
        UUID ownedToken = token;
        if (!audio.identity().equals(key.identity())) {
            // a fallback route answered: the entry is filed under what actually made the clip, and the requested one is released
            cache.abandon(key, token);
            owned = SpeechCache.key(clip.text(), clip.lang(), audio.identity(), clip.take());
            if (cache.claim(owned) instanceof SpeechCache.Claim.Won won) {
                ownedToken = won.token();
            } else {
                owned = null;
                ownedToken = null;
            }
        }
        try {
            stager.stage(owner, assetId, MediaCatalog.Kind.AUDIO, audio.mimeType(), audio.bytes());
        } catch (RuntimeException unavailable) {
            LOG.warn("generation_media_stage_failed step_id={} error_type={}", claim.stepId(), unavailable.getClass().getSimpleName());
            if (owned != null) cache.abandon(owned, ownedToken);
            return new Outcome.Failed("PROVIDER_UNAVAILABLE");
        }
        return new Outcome.Staged(assetId, true, audio.costMicros(),
                owned == null ? null : new Pending(owned, ownedToken, audio.durationMs(), audio.bytes().length));
    }

    /**
     * Polls the asset until it is READY, REJECTED or FAILED or {@code deadline} passes. A synthesised clip that became READY is published to the cache;
     * one that did not releases its entry. A cache hit is READY at once.
     */
    Verdict await(StepClaim claim, StepControl control, UUID owner, Outcome.Staged staged, Instant deadline) {
        Instant renewed = Instant.now();
        Verdict verdict = null;
        while (verdict == null) {
            GeneratedMediaStager.State state = stager.assetState(owner, staged.assetId());
            switch (state) {
                case READY -> verdict = Verdict.READY;
                case REJECTED, FAILED, MISSING -> verdict = Verdict.REJECTED;
                case VERIFYING -> {
                    if (control.lost() || Thread.currentThread().isInterrupted()) verdict = Verdict.LOST;
                    else if (control.cancelled()) verdict = Verdict.CANCELLED;
                    else if (Instant.now().plus(poll).isAfter(deadline)) verdict = Verdict.LATE;
                    else {
                        if (staged.pending() != null && Instant.now().isAfter(renewed.plus(RENEW))) {
                            cache.renew(staged.pending().key(), staged.pending().token());
                            renewed = Instant.now();
                        }
                        sleep();
                    }
                }
            }
        }
        Pending pending = staged.pending();
        if (pending != null) {
            if (verdict == Verdict.READY) {
                var verified = stager.verified(owner, staged.assetId());
                if (verified.isEmpty() || !cache.publish(pending.key(), pending.token(), verified.get(), pending.durationMs(), pending.byteLength())) {
                    cache.abandon(pending.key(), pending.token());
                }
            } else if (verdict == Verdict.REJECTED || verdict == Verdict.CANCELLED) {
                cache.abandon(pending.key(), pending.token());
            }
            // LATE and LOST leave the entry to its lease: the retry of the step (or the verification finishing) can still publish it
        }
        return verdict;
    }

    private void sleep() {
        try {
            Thread.sleep(poll);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
