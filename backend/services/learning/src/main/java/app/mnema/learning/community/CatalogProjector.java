package app.mnema.learning.community;

import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import app.mnema.learning.catalog.exercise.ExerciseType;
import app.mnema.learning.library.AliasNormalizer;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.JobHandler;
import app.mnema.learning.platform.jobs.JobState;
import app.mnema.learning.platform.jobs.LearningJobs;
import app.mnema.learning.platform.jobs.PermanentJobException;
import app.mnema.learning.platform.jobs.Slice;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The catalogue projector (community decks architecture sections 3 and 10, queue {@value CatalogOutbox#PROJECT_QUEUE}): makes {@code public_deck} say, for ONE deck, what the
 * deck is right now. The payload is only the deck id; <b>the job reads the current state and never an event</b>, so any number of jobs of one deck, run in any order, converge.
 * They are serialized per deck by a transaction-level advisory lock (a slice is a transaction), so two of them never interleave inside a statement.
 *
 * <ol>
 *   <li>The deck does not belong (not PUBLIC, no published revision, hidden by moderation, deleted): the row is deleted and the job ends. Lowering the level, hiding and deleting
 *       all end here; un-hiding and promoting to PUBLIC end in the next step.</li>
 *   <li>First slice (and again whenever the published revision has moved under a scan): {@code catalog_listing} (the facts the catalogue keeps while the deck has no row) is touched, which
 *       stamps {@code first_listed_at} ONCE, the first time the deck is found listed; the media bits are computed only if the published revision changed; one upsert then writes the metadata, the
 *       composition counts and COPIES the date, the media bits and the scan result of the listing, all from the PUBLISHED revision. The generated columns ({@code listed}, the vectors) follow by themselves.</li>
 *   <li>If the row's {@code projected_revision_id} (the listing's {@code scanned_revision_id}) is not the published revision, the exercises manifest of that revision is scanned for the mechanics, {@code learning.community.catalog.slice-size} entries
 *       (500) per slice, in chunks of {@value #CHUNK} (the reader's page); the bits are OR-ed into the cursor {@code {revisionId, next, mask}}, and the slice that reads the last entry writes the mask
 *       and {@code projected_revision_id} in the same transaction that ends the job. A republication that changed only the metadata, a rebuild of an unchanged deck and a replay read no manifest.</li>
 * </ol>
 *
 * <p><b>Restart.</b> Every slice re-reads the deck first. If the published revision is not the one the cursor scans, the scan starts over from the beginning of the new revision (steps 2 and 3 again); if the deck
 * no longer belongs, the row is deleted. A cursor is therefore never trusted beyond the revision it names. The mechanics of the previous revision stay in the row until the new scan finishes, so a deck does not flicker out of a filter.
 *
 * <p><b>Nothing but the deck's advisory lock is taken.</b> A hiding, a lowering or a deletion deletes the row in its own transaction and enqueues its own job (see {@link CatalogOutbox}); a slice that raced
 * with it and wrote after it is corrected by that job, which runs later and judges the state again in its own snapshot.
 * Failure: a storage or database hiccup is retried by the executor; an unresolvable manifest ends the job {@code FAILED} ({@code manifest_unresolved}), the row keeps its previous mechanics, and the rebuild is the repair.
 */
@Component
public class CatalogProjector implements JobHandler {
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT);
    private static final Logger LOG = LoggerFactory.getLogger(CatalogProjector.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Manifest entries per read: the page size the owner's and the public lists read. */
    static final int CHUNK = 100;

    private final ProjectionRepository repository;
    private final ManifestPages manifests;
    private final CatalogSettings settings;
    private final MeterRegistry meters;
    private final LearningJobs jobs;
    private final RankSettings rank;
    private final Clock clock;

    @Autowired
    CatalogProjector(ProjectionRepository repository, ManifestPages manifests, CatalogSettings settings, MeterRegistry meters, LearningJobs jobs, RankSettings rank) {
        this(repository, manifests, settings, meters, jobs, rank, Clock.systemUTC());
    }

    /** Without the rank job: for the tests of the projection itself, which want no job enqueued by the projector. */
    CatalogProjector(ProjectionRepository repository, ManifestPages manifests, CatalogSettings settings, MeterRegistry meters) {
        this(repository, manifests, settings, meters, null, null, Clock.systemUTC());
    }

    CatalogProjector(ProjectionRepository repository, ManifestPages manifests, CatalogSettings settings, MeterRegistry meters, LearningJobs jobs, RankSettings rank, Clock clock) {
        this.repository = repository;
        this.manifests = manifests;
        this.settings = settings;
        this.meters = meters;
        this.jobs = jobs;
        this.rank = rank;
        this.clock = clock;
    }

    @Override
    public String queue() {
        return CatalogOutbox.PROJECT_QUEUE;
    }

    @Override
    public Slice run(Job job) {
        UUID deck = deckOf(job.payload());
        repository.lock(deck);
        Optional<ProjectionRepository.Published> found = repository.published(deck);
        if (found.isEmpty()) {
            outcome(repository.remove(deck) ? "removed" : "absent");
            return Slice.done(job.progressCount());
        }
        ProjectionRepository.Published published = found.orElseThrow();
        Cursor cursor = Cursor.read(job.progress(), published);
        if (cursor == null) {
            // the first slice, or the published revision moved under a scan: remember the listing facts, (re)write the row, then see whether its mechanics are those of this revision
            repository.touchListing(published);
            Optional<ProjectionRepository.Listing> listing = repository.listing(deck);
            if (listing.isEmpty()) {
                outcome("moved");
                return Slice.done(job.progressCount());
            }
            // the media bits depend on the published revision only: a republication computes them, a tag edit and a row made again reuse them
            if (!published.revisionId().equals(listing.orElseThrow().mediaRevisionId())) {
                repository.storeMedia(deck, published.revisionId(), CatalogMedia.maskOf(repository.mediaTypes(deck)));
            }
            boolean wasListed = repository.projected(deck).map(ProjectionRepository.Projected::listed).orElse(false);
            repository.upsert(published, AliasNormalizer.normalize(published.title()));
            Optional<ProjectionRepository.Projected> row = repository.projected(deck);
            if (!wasListed && row.isPresent() && row.orElseThrow().listed()) rankSoon();
            if (row.isEmpty() || !row.orElseThrow().publishedRevisionId().equals(published.revisionId())) {
                // the publication moved between the state read and the upsert (the change enqueued its own job), or the deck is gone: nothing more to do here
                outcome("moved");
                return Slice.done(job.progressCount());
            }
            if (published.revisionId().equals(row.orElseThrow().projectedRevisionId())) {
                outcome("current");
                return Slice.done(job.progressCount());
            }
            cursor = new Cursor(published.revisionId(), 0, 0);
        }
        return scan(published, cursor, job.progressCount());
    }

    /**
     * A deck that has just become listed (its first listing, or a re-listing after a hide or a demotion) must reach «Популярные» and «Учат сейчас» within about a minute, not up to
     * the next hourly generation: the system rank job is enqueued, in this transaction, with the dedupe key of the current UTC MINUTE, so there is at most one extra generation a
     * minute however many decks are listed in it (the hourly one stays). Not enqueued when the rank is switched off.
     */
    private void rankSoon() {
        if (jobs == null || !rank.enabled) return;
        String minute = MINUTE.format(clock.instant().atZone(ZoneOffset.UTC));
        jobs.enqueue(CatalogRank.QUEUE, "rank:" + minute, null, JSON.createObjectNode().put("minute", minute));
        meters.counter("mnema_catalog_rank_requested_total", "reason", "listed").increment();
    }

    private Slice scan(ProjectionRepository.Published published, Cursor cursor, long done) {
        int end = Math.min(published.exerciseCount(), cursor.next() + settings.sliceSize);
        int mask = cursor.mask();
        for (int at = cursor.next(); at < end; at += CHUNK) {
            int size = Math.min(CHUNK, end - at);
            List<Entry> entries = manifests.exercises(published.scopeId(), published.exercisesRootId(), published.exerciseCount(), at, size);
            if (entries.size() != size) throw new PermanentJobException("manifest_unresolved");
            mask |= mechanics(published, entries);
        }
        if (end < published.exerciseCount()) return Slice.proceed(cursor(published.revisionId(), end, mask), end);
        if (!repository.finishScan(published.deckId(), published.revisionId(), mask)) {
            // the row was removed or moved to another revision between the slices: start over (an empty cursor names no revision, so the next slice rewrites the row)
            outcome("restarted");
            return Slice.proceed(JSON.createObjectNode(), done);
        }
        outcome("projected");
        return Slice.done(end);
    }

    /** The bits of the exercises of one chunk; every entry must be a real, resolvable exercise revision of the lineage. */
    private int mechanics(ProjectionRepository.Published published, List<Entry> entries) {
        int resolved = 0;
        int mask = 0;
        for (ProjectionRepository.ExerciseKind kind : repository.exerciseKinds(published.scopeId(), entries)) {
            resolved += kind.count();
            // a disabled exercise is not studied, so it does not advertise its mechanic
            if (kind.enabled()) mask |= CatalogMechanic.maskOf(type(kind.type()), kind.audio());
        }
        if (resolved != entries.size()) throw new PermanentJobException("manifest_unresolved");
        return mask;
    }

    /** The compensation hook has nothing to undo: the row keeps what it had, and the rebuild repairs a deck whose job failed. */
    @Override
    public void onEnded(Job job, JobState state) {
        meters.counter("mnema_catalog_projection_ended_total", "outcome", state.name().toLowerCase(Locale.ROOT)).increment();
        LOG.warn("catalog_projection_ended job_id={} deck_id={} state={} progress_count={}", job.id(), job.payload().path("deckId").stringValue(""), state, job.progressCount());
    }

    private void outcome(String outcome) {
        meters.counter("mnema_catalog_projection_total", "outcome", outcome).increment();
    }

    private static ExerciseType type(String stored) {
        return ExerciseType.fromWire(stored).orElseThrow(() -> new IllegalStateException("Unknown exercise type in a published manifest"));
    }

    private static UUID deckOf(ObjectNode payload) {
        try {
            return UUID.fromString(payload.path("deckId").stringValue(""));
        } catch (IllegalArgumentException invalid) {
            throw new PermanentJobException("payload_invalid");
        }
    }

    private static ObjectNode cursor(UUID revision, int next, int mask) {
        return JSON.createObjectNode().put("revisionId", revision.toString()).put("next", next).put("mask", mask);
    }

    /** Where a scan stands: the revision it reads, the next manifest ordinal and the bits found so far. */
    private record Cursor(UUID revisionId, int next, int mask) {
        /** {@code null} when there is no cursor, or it names another revision than the published one (the scan starts over). */
        static Cursor read(ObjectNode progress, ProjectionRepository.Published published) {
            if (progress == null) return null;
            JsonNode revision = progress.path("revisionId");
            if (!revision.isString()) return null;
            UUID named;
            try {
                named = UUID.fromString(revision.stringValue(""));
            } catch (IllegalArgumentException invalid) {
                throw new PermanentJobException("cursor_invalid");
            }
            if (!named.equals(published.revisionId())) return null;
            JsonNode next = progress.path("next");
            JsonNode mask = progress.path("mask");
            if (!next.isInt() || next.intValue() < 0 || next.intValue() > published.exerciseCount()
                    || !mask.isInt() || mask.intValue() < 0 || mask.intValue() > CatalogMechanic.ALL) {
                throw new PermanentJobException("cursor_invalid");
            }
            return new Cursor(named, next.intValue(), mask.intValue());
        }
    }
}
