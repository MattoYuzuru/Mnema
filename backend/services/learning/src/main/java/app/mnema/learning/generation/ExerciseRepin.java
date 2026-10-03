package app.mnema.learning.generation;

import app.mnema.learning.generation.PinnedMaterials.Pinned;
import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.Revision;
import app.mnema.learning.generation.SourceDrift.Drift;
import app.mnema.learning.generation.exercise.ExerciseValidator;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The server-side re-pin of a proposed exercise whose material has a newer head revision (decision 8 of the generation contract),
 * with no model call. It succeeds only if every {@code MATERIAL} block of the exercise still reads as text in the new revision
 * (by the stable node ids), and the exercise, with its pins moved, passes the same checks that read the material text, the
 * publication parser and the probes. Then a {@code REPIN} revision replaces the current one and the artifact stays PROPOSED with
 * {@code repinStatus AUTO_REPINNED}; otherwise (a vanished or unreadable node, a deleted material, a failed check) it becomes
 * STALE with {@code NEEDS_USER_DECISION}. Each outcome is one short transaction of its own, committed before the approval goes on.
 */
@Component
class ExerciseRepin {
    private static final int MAX_REVISIONS = 30;

    private final GenerationRepository repository;
    private final SessionLifecycle lifecycle;
    private final SourceDrift drift;
    private final PinnedMaterials materials;
    private final ExerciseValidator validator;

    ExerciseRepin(GenerationRepository repository, SessionLifecycle lifecycle, SourceDrift drift, PinnedMaterials materials,
                  ExerciseValidator validator) {
        this.repository = repository;
        this.lifecycle = lifecycle;
        this.drift = drift;
        this.materials = materials;
        this.validator = validator;
    }

    /**
     * @param expectedVersion the version of the artifact the caller read; a newer one is not touched (the caller's own checks
     *                        answer it)
     * @return the artifact as it is now (unchanged when no pin moved, or re-pinned), or empty when it became STALE
     */
    @Transactional
    Optional<Artifact> repin(UUID sessionId, UUID artifactId, long expectedVersion) {
        SessionLifecycle.Tx tx = lifecycle.lock(sessionId);
        if (tx == null) return Optional.empty();
        Artifact artifact = repository.artifact(sessionId, artifactId).orElse(null);
        if (artifact == null) return Optional.empty();
        // the caller has just checked both: a change in between is a lost race, which the version precondition answers (412)
        if (!artifact.state().equals("PROPOSED") || artifact.rowVersion() != expectedVersion) throw new VersionConflictException();
        List<Drift> drifted = drift.drifted(tx.session, artifact);
        if (drifted.isEmpty()) return Optional.of(artifact);

        Optional<Moved> moved = drifted.stream().anyMatch(Drift::gone) || artifact.revisionCount() >= MAX_REVISIONS ? Optional.empty()
                : move(tx.session, artifact, drifted);
        if (moved.isEmpty()) {
            tx.events.add(SessionLifecycle.artifactEvent(repository.markStale(artifact)));
            lifecycle.flush(tx);
            return Optional.empty();
        }
        UUID revisionId = UUID.randomUUID();
        Revision old = moved.get().previous();
        repository.insertRevision(new Revision(revisionId, artifactId, artifact.revisionCount() + 1, "REPIN", moved.get().payload(),
                old.handles(), old.promptVersion(), old.modelRoute(), old.validation(), Instant.now()), sessionId, tx.session.ownerId());
        Artifact repinned = repository.repin(artifact, revisionId, drift.repinned(artifact, drifted), artifact.title(),
                artifact.revisionCount() + 1);
        tx.events.add(SessionLifecycle.artifactEvent(repinned));
        lifecycle.flush(tx);
        return Optional.of(repinned);
    }

    private record Moved(Revision previous, ObjectNode payload) { }

    /**
     * What the model was shown is what the exercise stands on (for a REVISE_EXERCISE session: the blocks the exercise quotes): every block it saw (the node ids kept on the revision; all quotable
     * blocks of the pinned revision when there are none) must exist in the head with exactly the same plain text. A material that was
     * edited in a block the model read is a different material for the question, even when every quoted node still exists.
     */
    private static boolean unchanged(Revision previous, Pinned seen, Pinned head, boolean revision) {
        List<UUID> shown = new ArrayList<>();
        if (revision) {
            // a revised exercise stands on the blocks it quotes (the model was shown the whole material, but the exercise is the owner's own)
            SessionViews.quoted(previous.payload().path("command").path("exercise").path("content"),
                    quote -> shown.add(UUID.fromString(quote.path("nodeId").stringValue(""))));
        } else {
            previous.handles().forEach(entry -> shown.add(UUID.fromString(entry.stringValue(""))));
            if (shown.isEmpty()) seen.blocks().forEach(block -> shown.add(block.nodeId()));
        }
        for (UUID node : shown) {
            Optional<String> before = seen.text(node);
            if (before.isEmpty() || !before.equals(head.text(node))) return false;
        }
        return true;
    }

    /** The payload with its pins moved to the head, or empty when the exercise cannot honestly follow the material. */
    private Optional<Moved> move(Rows.Session session, Artifact artifact, List<Drift> drifted) {
        JsonNode current = drifted.getFirst().current();
        UUID member = UUID.fromString(current.path("memberKey").stringValue(""));
        UUID head = UUID.fromString(current.path("itemRevisionId").stringValue(""));
        Optional<Revision> previous = repository.revision(artifact.artifactId(), artifact.currentRevisionId());
        Optional<Pinned> pinned = materials.read(session.ownerId(), session.deckId(), member, head);
        Optional<Pinned> seen = materials.read(session.ownerId(), session.deckId(), member,
                UUID.fromString(artifact.sourceRefs().path(0).path("itemRevisionId").stringValue("")));
        if (previous.isEmpty() || pinned.isEmpty() || seen.isEmpty() || !unchanged(previous.get(), seen.get(), pinned.get(), SessionLifecycle.isRevision(session))) return Optional.empty();
        JsonNode command = previous.get().payload().path("command");
        ObjectNode exercise = (ObjectNode) command.path("exercise").deepCopy();
        if (!exercise.path("subject").path("memberKey").stringValue("").equals(member.toString())) return Optional.empty();
        ((ObjectNode) exercise.path("subject")).put("itemRevisionId", head.toString());
        boolean[] followable = {true};
        SessionViews.quoted(exercise.path("content"), quote -> {
            UUID node = UUID.fromString(quote.path("nodeId").stringValue(""));
            if (!quote.path("memberKey").stringValue("").equals(member.toString()) || !pinned.get().quotable(node)) followable[0] = false;
            else ((ObjectNode) quote).put("itemRevisionId", head.toString());
        });
        if (!followable[0]) return Optional.empty();

        ObjectNode full = Json.object().put("commandId", ExerciseContexts.PLACEHOLDER_COMMAND.toString())
                .put("expectedDeckRevisionId", ExerciseContexts.PLACEHOLDER_DECK_REVISION.toString());
        full.set("objective", command.path("objective").deepCopy());
        full.set("exercise", exercise);
        List<String> blockTexts = new ArrayList<>();
        pinned.get().blocks().forEach(block -> blockTexts.add(block.text()));
        if (!validator.revalidate(full, pinned.get()::text, blockTexts).isEmpty()) return Optional.empty();

        ObjectNode payload = Json.object().put("kind", "EXERCISE_COMMAND");
        ObjectNode moved = payload.putObject("command");
        moved.set("objective", command.path("objective").deepCopy());
        moved.set("exercise", exercise);
        return Optional.of(new Moved(previous.get(), payload));
    }
}
