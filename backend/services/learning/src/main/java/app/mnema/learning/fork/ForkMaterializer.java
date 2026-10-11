package app.mnema.learning.fork;

import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.JobHandler;
import app.mnema.learning.platform.jobs.JobState;
import app.mnema.learning.platform.jobs.PermanentJobException;
import app.mnema.learning.platform.jobs.Slice;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The materialization of a copy (community decks architecture §5 and §12, queue {@value ForkService#QUEUE}): it writes the heads of the copy
 * ({@code deck_head_item}, {@code deck_head_exercise}, {@code objective_head}) from the manifests that the copy's revision 0 pins, which are the roots of the revision its source
 * PUBLISHED. It reads nothing from the source's heads, journal or drafts and writes no Study row, exemplar, draft, capture or workshop row: a copy starts with its materials and
 * exercises, nothing of the author's workspace or the learners' history.
 *
 * <p><b>Slices.</b> One call is one transaction of at most {@code learning.community.forks.slice-size} manifest entries (200): first the members manifest, then the exercises manifest
 * with the objective heads that their ASSESSED bindings pin, then, in the slice that finishes the last entry, the verification and {@code materialized_at}. The cursor is
 * {@code {phase, next}}, the ordinal of the next entry of the phase in the manifest; the executor commits it with the slice, so a killed worker resumes at the last committed
 * cursor and the end of the job (the state change, the verification, {@code materialized_at}) is committed once, by the slice that also sets {@code SUCCEEDED}.
 *
 * <p><b>Exact by construction and by verification.</b> Each head row is the lineage revision named by {@code (scope, key, descriptor object of the manifest entry)}; a statement
 * that finds fewer revisions than it sent entries fails the job permanently ({@code manifest_unresolved}). The final verification compares the counts of the heads with
 * {@code member_count}/{@code exercise_count}, checks that the exercise ordinals are exactly {@code 0..n-1} and that the objective heads equal what the heads' bindings pin
 * ({@code verification_failed}). The database refuses a head write on a preparing copy from any other transaction (trigger {@code deck_fork_preparing_guard}); this transaction names itself with
 * {@code mnema.fork_materializing}.
 *
 * <p><b>Failure.</b> A storage or database hiccup is retried by the executor; a permanent failure ends the job {@code FAILED}, and the copy stays preparing with the flag {@code failed}
 * until its owner retries (cursor kept) or deletes it. A copy deleted meanwhile ends the job without further writes.
 */
@Component
public class ForkMaterializer implements JobHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ForkMaterializer.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Entries per statement and per manifest read (the VALUES list of one insert). */
    private static final int CHUNK = 100;

    private enum Phase { MEMBERS, EXERCISES }

    private final MaterializationRepository repository;
    private final ManifestPages manifests;
    private final ForkSettings settings;
    private final MeterRegistry meters;

    ForkMaterializer(MaterializationRepository repository, ManifestPages manifests, ForkSettings settings, MeterRegistry meters) {
        this.repository = repository;
        this.manifests = manifests;
        this.settings = settings;
        this.meters = meters;
    }

    @Override
    public String queue() {
        return ForkService.QUEUE;
    }

    @Override
    public Slice run(Job job) {
        UUID deck = deckOf(job.payload());
        MaterializationRepository.Copy copy = repository.lock(deck).orElseThrow(() -> new PermanentJobException("copy_missing"));
        if (copy.materialized() || copy.gone()) {
            // done already (a duplicate job) or deleted meanwhile: nothing to write
            return Slice.done(job.progressCount());
        }
        repository.allowHeadWrites(deck);
        Phase phase = phase(job.progress());
        int next = next(job.progress(), phase == Phase.MEMBERS ? copy.memberCount() : copy.exerciseCount());
        int budget = settings.sliceSize;
        long done = job.progressCount();

        if (phase == Phase.MEMBERS) {
            int end = Math.min(copy.memberCount(), next + budget);
            copyMembers(copy, next, end);
            done += end - next;
            budget -= end - next;
            next = end;
            if (next == copy.memberCount()) {
                phase = Phase.EXERCISES;
                next = 0;
            }
        }
        if (phase == Phase.EXERCISES && budget > 0) {
            int end = Math.min(copy.exerciseCount(), next + budget);
            copyExercises(copy, next, end);
            done += end - next;
            next = end;
        }
        if (phase == Phase.EXERCISES && next == copy.exerciseCount()) {
            verify(copy);
            repository.markMaterialized(deck);
            meters.timer("mnema_deck_fork_materialization_seconds").record(Duration.between(copy.forkedAt(), Instant.now()));
            return Slice.done(done);
        }
        return Slice.proceed(cursor(phase, next), done);
    }

    private void copyMembers(MaterializationRepository.Copy copy, int from, int end) {
        for (int at = from; at < end; at += CHUNK) {
            int size = Math.min(CHUNK, end - at);
            List<Entry> entries = manifests.members(copy.scopeId(), copy.membersRootId(), copy.memberCount(), at, size);
            if (entries.size() != size || repository.insertItemHeads(copy.deckId(), copy.scopeId(), entries) != size) {
                throw new PermanentJobException("manifest_unresolved");
            }
        }
    }

    private void copyExercises(MaterializationRepository.Copy copy, int from, int end) {
        for (int at = from; at < end; at += CHUNK) {
            int size = Math.min(CHUNK, end - at);
            List<Entry> entries = manifests.exercises(copy.scopeId(), copy.exercisesRootId(), copy.exerciseCount(), at, size);
            if (entries.size() != size || repository.insertExerciseHeads(copy.deckId(), copy.scopeId(), entries, at) != size) {
                throw new PermanentJobException("manifest_unresolved");
            }
            repository.upsertObjectiveHeads(copy.deckId(), copy.scopeId(), entries);
        }
    }

    private void verify(MaterializationRepository.Copy copy) {
        MaterializationRepository.Verification found = repository.verify(copy.deckId());
        boolean ordinals = copy.exerciseCount() == 0 ? found.exercises() == 0
                : found.firstOrdinal() == 0 && found.lastOrdinal() == copy.exerciseCount() - 1;
        if (found.items() != copy.memberCount() || found.exercises() != copy.exerciseCount() || !ordinals
                || found.objectives() != found.expectedObjectives() || found.objectiveMismatches() != 0) {
            throw new PermanentJobException("verification_failed");
        }
    }

    /** The compensation hook has nothing to undo: the copy simply stays preparing and the read model derives "failed" from the job's state. */
    @Override
    public void onEnded(Job job, JobState state) {
        meters.counter("mnema_deck_fork_materialization_total", "outcome", state.name().toLowerCase(java.util.Locale.ROOT)).increment();
        LOG.warn("deck_fork_materialization_ended job_id={} state={} progress_count={}", job.id(), state, job.progressCount());
    }

    private static UUID deckOf(ObjectNode payload) {
        try {
            return UUID.fromString(payload.path("deckId").stringValue(""));
        } catch (IllegalArgumentException invalid) {
            throw new PermanentJobException("payload_invalid");
        }
    }

    private static Phase phase(ObjectNode progress) {
        if (progress == null) return Phase.MEMBERS;
        try {
            return Phase.valueOf(progress.path("phase").stringValue("").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new PermanentJobException("cursor_invalid");
        }
    }

    private static int next(ObjectNode progress, int count) {
        if (progress == null) return 0;
        JsonNode next = progress.path("next");
        if (!next.isInt() || next.intValue() < 0 || next.intValue() > count) throw new PermanentJobException("cursor_invalid");
        return next.intValue();
    }

    private static ObjectNode cursor(Phase phase, int next) {
        return JSON.createObjectNode().put("phase", phase.name().toLowerCase(java.util.Locale.ROOT)).put("next", next);
    }
}
