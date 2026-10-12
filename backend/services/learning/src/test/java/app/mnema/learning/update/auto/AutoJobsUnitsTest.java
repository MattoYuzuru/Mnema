package app.mnema.learning.update.auto;

import app.mnema.learning.fork.DeckPreparingException;
import app.mnema.learning.platform.api.ContentUnavailableException;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.LearningJobs;
import app.mnema.learning.platform.jobs.PermanentJobException;
import app.mnema.learning.platform.jobs.RetryableJobException;
import app.mnema.learning.platform.jobs.Slice;
import app.mnema.learning.update.apply.ApplyService;
import app.mnema.learning.update.apply.ApplyService.AutoOutcome;
import app.mnema.learning.update.apply.ApplyService.AutoResult;
import app.mnema.learning.update.auto.FanoutRepository.Candidate;
import app.mnema.learning.update.plan.UpdatePlanStaleException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Updates/6 part B (#445): the kill switch {@code learning.updates.auto.enabled} and the mapping of what the apply throws to what the executor does with a job, with the
 * collaborators replaced by mocks, so that "writes nothing" is a proof that nothing was even asked: the dependencies of the three classes are the only way they could write.
 */
class AutoJobsUnitsTest {
    private static final UUID DECK = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID REVISION = UUID.randomUUID();

    private static AutoUpdateSettings settings(boolean enabled) {
        return new AutoUpdateSettings(enabled, 50, Duration.ofDays(30), 25, 200);
    }

    private static ObjectNode payload(UUID deck) {
        return AutoUpdateFanout.payload(deck, REVISION);
    }

    private static Job job(String queue, UUID account, ObjectNode payload, ObjectNode progress, long count) {
        return new Job(UUID.randomUUID(), queue, account, payload, progress, count, 1);
    }

    /** A repository whose source publishes {@link #REVISION}. */
    private static FanoutRepository repository() {
        FanoutRepository repository = mock(FanoutRepository.class);
        when(repository.published(DECK)).thenReturn(Optional.of(REVISION));
        return repository;
    }

    private static Clock fixed() { return Clock.fixed(Instant.parse("2026-10-12T09:00:00Z"), ZoneOffset.UTC); }

    // ---- the hook of the publication ----

    @Test
    void thePublicationHookEnqueuesOneSystemJobPerRevisionWhenOnAndNothingWhenOff() {
        LearningJobs jobs = mock(LearningJobs.class);
        new AutoUpdateFanout(jobs, settings(false)).published(DECK, REVISION);
        verifyNoInteractions(jobs);

        new AutoUpdateFanout(jobs, settings(true)).published(DECK, REVISION);
        verify(jobs).enqueue(eq("deck.update.fanout"), eq(DECK + ":" + REVISION), isNull(), eq(payload(DECK)));
    }

    // ---- the fan-out ----

    @Test
    void aFanOutQueuedBeforeTheSwitchWentOffEndsWithoutReadingOrEnqueueingAnything() {
        FanoutRepository repository = mock(FanoutRepository.class);
        LearningJobs jobs = mock(LearningJobs.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        FanoutJob handler = new FanoutJob(repository, jobs, settings(false), new AutoThrottle(() -> 50, fixed()), meters);
        Slice slice = handler.run(job("deck.update.fanout", null, payload(DECK), null, 4));
        assertThat(slice).isEqualTo(Slice.done(4));
        verifyNoInteractions(repository, jobs);
        assertThat(meters.get("mnema_deck_update_fanout_total").tag("outcome", "disabled").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aFanOutWithNoAllowanceLeftWaitsWithItsCursorAndTouchesNothing() {
        FanoutRepository repository = repository();
        LearningJobs jobs = mock(LearningJobs.class);
        AutoThrottle throttle = new AutoThrottle(() -> 2, fixed());
        assertThat(throttle.acquire(2)).isEqualTo(2);
        FanoutJob handler = new FanoutJob(repository, jobs, settings(true), throttle, new SimpleMeterRegistry());
        UUID after = UUID.randomUUID();
        ObjectNode cursor = tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode().put("after", after.toString());
        Slice slice = handler.run(job("deck.update.fanout", null, payload(DECK), cursor, 2));
        assertThat(slice).isInstanceOf(Slice.Continue.class);
        Slice.Continue paused = (Slice.Continue) slice;
        assertThat(paused.delay()).isEqualTo(Duration.ofMinutes(1));
        assertThat(paused.progress()).isEqualTo(cursor);
        assertThat(paused.progressCount()).isEqualTo(2);
        verify(repository, org.mockito.Mockito.never()).page(any(), any(), any(), any(), anyInt());
    }

    @Test
    void aFanOutHandsOverTheActiveCopiesInOrderStopsWhenTheAllowanceIsUsedAndGivesBackWhatItDidNotNeed() {
        FanoutRepository repository = repository();
        LearningJobs jobs = mock(LearningJobs.class);
        UUID idle = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID owner1 = UUID.randomUUID();
        UUID owner2 = UUID.randomUUID();
        UUID owner3 = UUID.randomUUID();
        when(repository.page(eq(DECK), eq(REVISION), isNull(), any(Instant.class), eq(200))).thenReturn(List.of(new Candidate(idle, UUID.randomUUID(), false),
                new Candidate(first, owner1, true), new Candidate(second, owner2, true), new Candidate(third, owner3, true)));
        AutoThrottle throttle = new AutoThrottle(() -> 2, fixed());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        FanoutJob handler = new FanoutJob(repository, jobs, settings(true), throttle, meters);

        Slice slice = handler.run(job("deck.update.fanout", null, payload(DECK), null, 0));
        verify(jobs).enqueue("deck.update.auto", first + ":" + REVISION, owner1, payload(first));
        verify(jobs).enqueue("deck.update.auto", second + ":" + REVISION, owner2, payload(second));
        assertThat(slice).isInstanceOf(Slice.Continue.class);
        Slice.Continue next = (Slice.Continue) slice;
        assertThat(next.delay()).isEqualTo(Duration.ZERO);
        assertThat(next.progressCount()).isEqualTo(2);
        assertThat(next.progress().path("after").stringValue(null)).as("the last copy LOOKED AT, the third one was not consumed").isEqualTo(second.toString());
        assertThat(throttle.acquire(10)).as("the allowance of 2 was used").isZero();
        assertThat(meters.get("mnema_deck_update_fanout_copies_total").tag("outcome", "enqueued").counter().count()).isEqualTo(2.0);
        assertThat(meters.get("mnema_deck_update_fanout_copies_total").tag("outcome", "inactive").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aSourceWhoseCopiesAreAllIdleIsWalkedByTheCursorAndNothingIsHandedOver() {
        FanoutRepository repository = repository();
        LearningJobs jobs = mock(LearningJobs.class);
        List<Candidate> idle = new java.util.ArrayList<>();
        for (int index = 0; index < 200; index++) idle.add(new Candidate(new UUID(0, index + 1), UUID.randomUUID(), false));
        when(repository.page(eq(DECK), eq(REVISION), isNull(), any(Instant.class), eq(200))).thenReturn(idle);
        AutoThrottle throttle = new AutoThrottle(() -> 5, fixed());
        FanoutJob handler = new FanoutJob(repository, jobs, settings(true), throttle, new SimpleMeterRegistry());
        Slice slice = handler.run(job("deck.update.fanout", null, payload(DECK), null, 0));
        assertThat(slice).isInstanceOf(Slice.Continue.class);
        assertThat(((Slice.Continue) slice).progress().path("after").stringValue(null)).isEqualTo(new UUID(0, 200).toString());
        verify(jobs, org.mockito.Mockito.never()).enqueue(any(), any(), any(), any());
        // a page that is not full is the end; the allowance was given back, so the next slice finds all of it
        when(repository.page(eq(DECK), eq(REVISION), eq(new UUID(0, 200)), any(Instant.class), anyInt())).thenReturn(List.of());
        ObjectNode cursor = ((Slice.Continue) slice).progress();
        assertThat(handler.run(job("deck.update.fanout", null, payload(DECK), cursor, 0))).isEqualTo(Slice.done(0));
        assertThat(throttle.acquire(5)).as("nothing of the allowance was spent").isEqualTo(5);
    }

    @Test
    void aFanOutWhoseRevisionIsNotThePublishedOneAnyMoreEndsAtOnceBecauseTheNewerFanOutCoversIt() {
        FanoutRepository repository = mock(FanoutRepository.class);
        LearningJobs jobs = mock(LearningJobs.class);
        AutoThrottle throttle = new AutoThrottle(() -> 5, fixed());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        FanoutJob handler = new FanoutJob(repository, jobs, settings(true), throttle, meters);
        // a newer publication, or a source that has nothing published at all
        for (Optional<UUID> now : List.of(Optional.of(UUID.randomUUID()), Optional.<UUID>empty())) {
            when(repository.published(DECK)).thenReturn(now);
            assertThat(handler.run(job("deck.update.fanout", null, payload(DECK), null, 3))).isEqualTo(Slice.done(3));
        }
        verify(repository, org.mockito.Mockito.never()).page(any(), any(), any(), any(), anyInt());
        verifyNoInteractions(jobs);
        assertThat(throttle.acquire(5)).as("it asked for nothing of the allowance").isEqualTo(5);
        assertThat(meters.get("mnema_deck_update_fanout_total").tag("outcome", "superseded").counter().count()).isEqualTo(2.0);
    }

    @Test
    void aFanOutWithABrokenPayloadIsAPermanentFailure() {
        FanoutJob handler = new FanoutJob(mock(FanoutRepository.class), mock(LearningJobs.class), settings(true), new AutoThrottle(() -> 5, fixed()), new SimpleMeterRegistry());
        ObjectNode broken = tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode().put("deckId", "nope");
        assertThatThrownBy(() -> handler.run(job("deck.update.fanout", null, broken, null, 0))).isInstanceOfSatisfying(PermanentJobException.class,
                failure -> assertThat(failure.code()).isEqualTo("payload_invalid"));
    }

    // ---- the automatic apply ----

    @Test
    void anAutomaticApplyQueuedBeforeTheSwitchWentOffEndsWithoutAskingForAnything() {
        ApplyService applies = mock(ApplyService.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AutoApplyJob handler = new AutoApplyJob(applies, settings(false), meters);
        assertThat(handler.run(job("deck.update.auto", OWNER, payload(DECK), null, 0))).isEqualTo(Slice.done(0));
        verifyNoInteractions(applies);
        assertThat(meters.get("mnema_deck_update_auto_total").tag("outcome", "disabled").counter().count()).isEqualTo(1.0);
    }

    @Test
    void anAutomaticApplyReportsWhatItTookAndLeftAndCountsIt() {
        ApplyService applies = mock(ApplyService.class);
        when(applies.applyAuto(OWNER, DECK)).thenReturn(new AutoResult(AutoOutcome.APPLIED, 7, 2, 12, 2));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AutoApplyJob handler = new AutoApplyJob(applies, settings(true), meters);
        assertThat(handler.run(job("deck.update.auto", OWNER, payload(DECK), null, 0))).isEqualTo(Slice.done(7));
        assertThat(meters.get("mnema_deck_update_auto_total").tag("outcome", "applied").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("mnema_deck_update_auto_units_total").tag("result", "applied").counter().count()).isEqualTo(7.0);
        assertThat(meters.get("mnema_deck_update_auto_units_total").tag("result", "left_to_owner").counter().count()).isEqualTo(2.0);
        assertThat(meters.get("mnema_deck_update_auto_plan_units").summary().max()).isEqualTo(12.0);
    }

    @Test
    void unitsOfAnApplyThatWasOnlyQueuedAsTheCombinedJobAreSubmittedNotApplied() {
        ApplyService applies = mock(ApplyService.class);
        when(applies.applyAuto(OWNER, DECK)).thenReturn(new AutoResult(AutoOutcome.QUEUED, 40, 0, 40, 0));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        assertThat(new AutoApplyJob(applies, settings(true), meters).run(job("deck.update.auto", OWNER, payload(DECK), null, 0))).isEqualTo(Slice.done(40));
        assertThat(meters.get("mnema_deck_update_auto_total").tag("outcome", "queued").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("mnema_deck_update_auto_units_total").tag("result", "submitted").counter().count()).isEqualTo(40.0);
        assertThat(meters.find("mnema_deck_update_auto_units_total").tag("result", "applied").counter()).as("the combined job may still fail").isNull();
    }

    @Test
    void whatTheApplyThrowsIsRetriedOrEndsTheJobWithAShortCode() {
        ApplyService applies = mock(ApplyService.class);
        AutoApplyJob handler = new AutoApplyJob(applies, settings(true), new SimpleMeterRegistry());
        Job job = job("deck.update.auto", OWNER, payload(DECK), null, 0);
        // the copy or the plan moved while the slice ran: the executor tries again with a fresh plan
        for (RuntimeException stale : new RuntimeException[] {new VersionConflictException(), new UpdatePlanStaleException()}) {
            org.mockito.Mockito.doThrow(stale).when(applies).applyAuto(OWNER, DECK);
            assertThatThrownBy(() -> handler.run(job)).isInstanceOfSatisfying(RetryableJobException.class, failure -> assertThat(failure.code()).isEqualTo("stale_plan"));
        }
        // nothing a retry cures
        record Case(RuntimeException thrown, String code) { }
        for (Case refused : new Case[] {new Case(new ContentUnavailableException(), "taken_down"), new Case(new ResourceNotFoundException(), "not_found"),
                new Case(new InvalidRequestException(), "plan_invalid"), new Case(new IdempotencyConflictException(), "plan_invalid"), new Case(new DeckPreparingException(), "deck_preparing")}) {
            org.mockito.Mockito.doThrow(refused.thrown()).when(applies).applyAuto(OWNER, DECK);
            assertThatThrownBy(() -> handler.run(job)).as(refused.code()).isInstanceOfSatisfying(PermanentJobException.class, failure -> assertThat(failure.code()).isEqualTo(refused.code()));
        }
        // a job without an account or a deck is a defect of whoever enqueued it
        assertThatThrownBy(() -> handler.run(job("deck.update.auto", null, payload(DECK), null, 0))).isInstanceOf(PermanentJobException.class);
        assertThatThrownBy(() -> handler.run(job("deck.update.auto", OWNER, tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode(), null, 0)))
                .isInstanceOf(PermanentJobException.class);
    }
}
