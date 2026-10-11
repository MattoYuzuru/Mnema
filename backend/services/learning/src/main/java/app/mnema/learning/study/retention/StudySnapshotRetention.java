package app.mnema.learning.study.retention;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The snapshot purge of Study (#460, V59). A session older than {@code learning.study.retention.snapshot-horizon} loses what only
 * resume, replay, reveals, answers and disputes read: its presentations and everything that hangs from them, and the presentations
 * its start receipt carried. Its receipts, evidence, transitions, state and the session row itself stay. A generation of candidates
 * that no session with a snapshot reads and the deck no longer uses is then evicted in chunks.
 *
 * <p>Each call is one short transaction that takes the work with {@code FOR UPDATE SKIP LOCKED}, so two passes (two processes) never
 * delete the same session and neither waits for the other.
 */
@Service
public class StudySnapshotRetention {
    private final StudySnapshotRepository repository;
    private final StudyRetentionSettings settings;

    StudySnapshotRetention(StudySnapshotRepository repository, StudyRetentionSettings settings) {
        this.repository = repository;
        this.settings = settings;
    }

    /** Rows deleted or changed by one batch of sessions. */
    public record SessionBatch(int sessions, int hints, int transcripts, int assessments, int pairs,
                               int exposures, int presentations, int receipts, int generationsEvicting) {
        static final SessionBatch NONE = new SessionBatch(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** What one eviction step did: candidates deleted and whether the generation row went with them. */
    public record Eviction(int candidates, boolean generationRemoved) {
        public int rows() { return candidates + (generationRemoved ? 1 : 0); }
    }

    @Transactional(timeout = 10)
    public SessionBatch purgeSessions() {
        List<StudySnapshotRepository.SessionRef> sessions = repository.lockOldest(settings.snapshotHorizon(), settings.sessionBatch());
        if (sessions.isEmpty()) return SessionBatch.NONE;
        repository.allowDeletes();
        StudySnapshotRepository.Purged purged = repository.purge(sessions);
        List<UUID> decks = sessions.stream().map(StudySnapshotRepository.SessionRef::deckId).distinct().toList();
        int evicting = repository.markEvicting(repository.lockGenerationsOf(decks));
        return new SessionBatch(sessions.size(), purged.hints(), purged.transcripts(),
                purged.assessments(), purged.pairs(), purged.exposures(), purged.presentations(), purged.receipts(), evicting);
    }

    /**
     * The bounded sweep of stale generations (the eviction after a purge only looks at the decks of the sessions it purged, so a deck that was edited
     * or deleted after its last session was purged would keep its old generation for ever): the {@code generation-sweep} least recently looked-at
     * generations older than the horizon are examined and the stale ones are made unreachable. Returns how many.
     */
    @Transactional(timeout = 10)
    public int sweepGenerations() {
        return repository.markEvicting(repository.lockForSweep(settings.snapshotHorizon(), settings.generationSweep()));
    }

    /** One chunk of the oldest generation being evicted; empty when there is none. */
    @Transactional(timeout = 30)
    public Eviction evictChunk() {
        StudySnapshotRepository.GenerationRef generation = repository.lockEvicting();
        if (generation == null) return new Eviction(0, false);
        repository.allowDeletes();
        int chunk = settings.generationChunk();
        int deleted = repository.deleteCandidates(generation.generationId(), chunk);
        boolean last = deleted < chunk;
        if (last) repository.deleteGeneration(generation);
        return new Eviction(deleted, last);
    }
}
