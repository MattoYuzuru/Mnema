package app.mnema.learning.update.apply;

import app.mnema.learning.fork.DeckPreparingException;
import app.mnema.learning.library.ManifestDiff;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.platform.jobs.JobState;
import app.mnema.learning.platform.jobs.JobStatus;
import app.mnema.learning.platform.jobs.LearningJobs;
import app.mnema.learning.update.CombinedPublication;
import app.mnema.learning.update.CombinedUnits;
import app.mnema.learning.update.apply.ApplyOps.Built;
import app.mnema.learning.update.apply.ApplyOps.Lists;
import app.mnema.learning.update.apply.ApplyOps.Shape;
import app.mnema.learning.update.apply.ApplyOps.Text;
import app.mnema.learning.update.apply.ApplyRepository.Copy;
import app.mnema.learning.update.apply.ApplyRepository.Revision;
import app.mnema.learning.update.apply.ApplySelection.Fate;
import app.mnema.learning.update.plan.Decision;
import app.mnema.learning.update.plan.ApplyProgress;
import app.mnema.learning.update.plan.PlanUnit;
import app.mnema.learning.update.plan.UnitKind;
import app.mnema.learning.update.plan.UpdatePlan;
import app.mnema.learning.update.plan.UpdatePlanService;
import app.mnema.learning.update.plan.UpdatePlanStaleException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The apply command of a copy's update plan (Updates/4, #443; community decks architecture sections 5 and 7, contract {@code contracts/decks/updates.json}): the owner names what to
 * do with the units of the plan he read, and ONE new revision of the copy is made from the author's published revisions, together with the new base and the recorded decisions.
 *
 * <p><b>The client names units, the server builds the operations.</b> The plan is recomputed here ({@link UpdatePlanService#plan}, its own repeatable-read snapshot) and must have
 * the {@code planVersion} the client sent, else {@code 409 UPDATE_PLAN_STALE} and nothing is written. A client never sends a revision id or an ordinal: every revision adopted is the
 * author's state of a unit of the recomputed plan, which comes from the source's published manifests only (the security boundary the Updates/2 review required), and every place
 * comes from the frontier diff of the copy's head against the published revision ({@link ApplyOps}).
 *
 * <p><b>One publication.</b> The operations, the pins and the decision rows go to {@link CombinedPublication#submit} (up to {@code learning.updates.sync-max-units} units in the
 * request, a job above that). {@link ApplyFinalWriter} then checks the pins again under the lock of the copy's link, in the same transaction as the heads, and writes the base, the
 * decisions and the receipt of the answer; so the plan can have been stale only for the instant between the recomputation and that check, and then nothing is written.
 *
 * <p><b>Idempotent by the command id.</b> The answer is stored with the publication; the same command (same body) answers it again, flagged as a replay, even when the plan has
 * changed since (it is looked up before the plan is read); the same id with another body is {@code IdempotencyConflictException}. A command that runs as a job answers
 * {@code APPLYING} again until the job ends.
 */
@Service
public class ApplyService {
    private static final Logger LOG = LoggerFactory.getLogger(ApplyService.class);
    private static final int READ_PAGE = 100;

    /** The answer of an apply command. */
    public sealed interface Outcome permits Applied, Queued { }

    /** The command was applied (now, or by an earlier identical request when {@code replayed}); {@code answer} is the stored answer. */
    public record Applied(ObjectNode answer, boolean replayed) implements Outcome { }

    /** The command runs as a job; its progress is the {@code APPLYING} state of the plan, its end the stored answer (the same command again). */
    public record Queued(UUID commandId) implements Outcome {
        public ObjectNode answer() { return ApplyAnswer.applying(commandId); }
    }

    private final UpdatePlanService plans;
    private final ManifestDiff diffs;
    private final ManifestPages pages;
    private final CombinedPublication publication;
    private final CommandReceiptService receipts;
    private final ApplyRepository repository;
    private final ApplyProgress progress;
    private final LearningJobs jobs;
    private final MeterRegistry meters;

    ApplyService(UpdatePlanService plans, ManifestDiff diffs, ManifestPages pages, CombinedPublication publication, CommandReceiptService receipts, ApplyRepository repository,
                 ApplyProgress progress, LearningJobs jobs, MeterRegistry meters) {
        this.plans = plans;
        this.diffs = diffs;
        this.pages = pages;
        this.publication = publication;
        this.receipts = receipts;
        this.repository = repository;
        this.progress = progress;
        this.jobs = jobs;
        this.meters = meters;
    }

    /**
     * @throws ResourceNotFoundException when the deck is not the caller's live copy
     * @throws DeckPreparingException when the copy is not materialized yet
     * @throws ForkDetachedException when the copy was detached
     * @throws UpdateSourceUnavailableException when the source cannot be read by the owner any more
     * @throws UpdatePlanStaleException when the plan changed after the client read it (or another apply of the copy is running)
     * @throws app.mnema.learning.platform.api.InvalidRequestException for a choice the plan does not take, naming the unit
     * @throws IdempotencyConflictException when the command id was used for another command
     */
    @Transactional(propagation = Propagation.NEVER)
    public Outcome apply(UUID owner, UUID deckId, ApplyCommand command) {
        UuidPolicy.requireEntityId(owner, "owner");
        UuidPolicy.requireEntityId(deckId, "deckId");
        long started = System.nanoTime();
        // ownership first: another account's deck, a deck that is not a live copy and an unknown id are the same opaque 404, also for a command id somebody else used
        Copy copy = repository.copy(owner, deckId).orElseThrow(ResourceNotFoundException::new);
        ObjectNode request = command.envelope(deckId);
        CommandIdentity identity = ApplyReceipt.identity(owner, command.commandId());
        Optional<JsonNode> done = receipts.replay(identity, request);
        if (done.isPresent()) return replayed(done.get());

        Optional<ApplyProgress.Progress> running = progress.of(owner, deckId);
        if (running.isPresent() && running.get().running() && !running.get().commandId().equals(command.commandId())) throw new UpdatePlanStaleException();

        UpdatePlan plan = plans.plan(owner, deckId);
        switch (plan.state()) {
            case PREPARING -> throw new DeckPreparingException();
            case DETACHED -> throw new ForkDetachedException();
            case SOURCE_UNAVAILABLE -> throw new UpdateSourceUnavailableException();
            case READY, UP_TO_DATE -> { }
        }
        if (!plan.planVersion().equals(command.planVersion())) {
            // the command may have been applied by a job that ended between the first look and the plan: its answer, not a conflict
            done = receipts.replay(identity, request);
            if (done.isPresent()) return replayed(done.get());
            throw new UpdatePlanStaleException();
        }

        Revision head = repository.revision(deckId, plan.forkHeadRevisionId()).orElseThrow(() -> new IllegalStateException("The head revision of the plan is missing"));
        Revision published = repository.revision(copy.upstreamDeckId(), plan.upstreamRevisionId())
                .orElseThrow(() -> new IllegalStateException("The published revision of the plan is missing"));
        // the exercises of the copy that are about a material the author removed: removing it needs them removed (the engine's rule, modelled by the selection)
        Set<UUID> removedMaterials = new HashSet<>();
        for (PlanUnit unit : plan.units()) {
            if (unit.kind() == UnitKind.MATERIAL && unit.authorRevisionId() == null) removedMaterials.add(unit.key());
        }
        ApplySelection selection = ApplySelection.of(plan, command, removedMaterials.isEmpty() ? Map.of() : repository.boundHeadExercises(deckId, copy.scopeId(), removedMaterials));
        Set<String> recorded = new HashSet<>();
        for (Decision decision : plans.decisions(deckId)) {
            if (decision.verdict() != Decision.Verdict.PENDING) recorded.add(decision.kind().unitId(decision.key()));
        }
        Shape members = new Shape(diffs.members(copy.scopeId(), head.membersRootId(), head.memberCount(), published.membersRootId(), published.memberCount()), head.memberCount());
        Shape exercises = new Shape(diffs.exercises(copy.scopeId(), head.exercisesRootId(), head.exerciseCount(), published.exercisesRootId(), published.exerciseCount()),
                head.exerciseCount());
        Built ops = ApplyOps.build(selection, members, exercises, new Text(published.title(), published.description()), () -> lists(copy.scopeId(), head, published));
        ApplyWrites writes = new ApplyWrites(command.commandId(), plan.planVersion(), plan.baseRevisionId(), plan.upstreamRevisionId(), request, selection.count(Fate.ACCEPT),
                selection.count(Fate.DECLINE), selection.count(Fate.PENDING), selection.also(), ApplyRows.build(selection, recorded));
        CombinedUnits units = new CombinedUnits(command.commandId(), deckId, owner, plan.forkHeadRevisionId(), ops.metadata(), ops.items(), ops.exercises(), ops.objectives(),
                writes.toFinalWrites());

        CombinedPublication.Outcome outcome;
        try {
            outcome = publication.submit(units);
        } catch (InvalidRequestException refused) {
            // the selection models the engine's rules; whatever of them it did not foresee must not surface as a bare 400
            if (!refused.extension().members().isEmpty()) throw refused;
            meters.counter("mnema_deck_update_apply_total", "outcome", "refused").increment();
            throw InvalidRequestException.because("publication_refused");
        } catch (VersionConflictException moved) {
            // the head moved, or a place did not describe the manifest, or the pins no longer hold under the lock: the plan is stale
            meters.counter("mnema_deck_update_apply_total", "outcome", "stale").increment();
            throw new UpdatePlanStaleException();
        }
        if (outcome instanceof CombinedPublication.Queued queued) {
            Optional<JobStatus> job = jobs.status(queued.jobId());
            if (job.isPresent() && (job.get().state() == JobState.FAILED || job.get().state() == JobState.CANCELLED)) {
                // a job of this very command ended without applying it: the command is not restarted (the engine's rule); read the plan again
                throw new UpdatePlanStaleException();
            }
            if (job.isEmpty() || job.get().state() != JobState.SUCCEEDED) {
                meters.counter("mnema_deck_update_apply_total", "outcome", "queued").increment();
                LOG.info("deck_update_apply path=job deck_id={} command_id={} units={} applied={} declined={} pending={} also={}", deckId, command.commandId(),
                        units.unitCount(), writes.applied(), writes.declined(), writes.pending(), writes.alsoAccepted());
                return new Queued(command.commandId());
            }
        }
        JsonNode answer = receipts.replay(identity, request).orElseThrow(IdempotencyConflictException::new);
        boolean replayed = outcome instanceof CombinedPublication.Applied applied ? applied.replayed() : true;
        meters.counter("mnema_deck_update_apply_total", "outcome", replayed ? "replayed" : "applied").increment();
        if (!replayed) {
            LOG.info("deck_update_apply path=sync deck_id={} command_id={} units={} applied={} declined={} pending={} also={} micros={}", deckId, command.commandId(),
                    units.unitCount(), writes.applied(), writes.declined(), writes.pending(), writes.alsoAccepted(), (System.nanoTime() - started) / 1_000);
        }
        return new Applied((ObjectNode) answer.deepCopy(), replayed);
    }

    private Applied replayed(JsonNode answer) {
        meters.counter("mnema_deck_update_apply_total", "outcome", "replayed").increment();
        return new Applied((ObjectNode) answer.deepCopy(), true);
    }

    /** The members of the copy's head and of the published revision, in order (the ORDER unit needs both; a scan, 100 entries per read). */
    private Lists lists(UUID scope, Revision head, Revision published) {
        if (head.memberCount() > ApplyOps.ORDER_SCAN_LIMIT || published.memberCount() > ApplyOps.ORDER_SCAN_LIMIT) {
            throw new ResourceLimitExceededException(ProblemExtension.limit(ApplyOps.ORDER_SCAN_LIMIT));
        }
        return new Lists(keys(scope, head.membersRootId(), head.memberCount()), keys(scope, published.membersRootId(), published.memberCount()));
    }

    private List<UUID> keys(UUID scope, UUID root, int count) {
        List<UUID> keys = new ArrayList<>(count);
        for (int at = 0; at < count; at += READ_PAGE) pages.members(scope, root, count, at, READ_PAGE).forEach(entry -> keys.add(entry.key()));
        return keys;
    }
}
