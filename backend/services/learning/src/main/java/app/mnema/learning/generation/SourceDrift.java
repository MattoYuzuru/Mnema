package app.mnema.learning.generation;

import app.mnema.learning.generation.GenerationRepository.NoteState;
import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.Rows.Source;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Whether the sources an artifact was written from still are what it pinned: a note by its {@code row_version}, a
 * {@code SOURCE} material by being its head. A {@code STYLE_EXAMPLE} only had to exist and never makes an artifact stale.
 * Drift is detected here, by approval in a short transaction of its own before the publish transaction, and by the worker
 * ({@code ContextBuilder}); a GET never detects it (contract decision 4).
 */
@Component
class SourceDrift {
    private final GenerationRepository repository;
    private final SessionLifecycle lifecycle;

    SourceDrift(GenerationRepository repository, SessionLifecycle lifecycle) {
        this.repository = repository;
        this.lifecycle = lifecycle;
    }

    /** A pin that no longer holds: {@code gone} when the note or material does not exist any more. */
    record Drift(SourceUnavailableException.Unavailable source, boolean gone, JsonNode current) { }

    /**
     * The pins of {@code artifact} that no longer hold. {@code current} is the pin to use now (null when the source is gone),
     * so a retry can re-pin to what exists.
     */
    List<Drift> drifted(Session session, Artifact artifact) {
        List<Source> sources = repository.sources(session.sessionId());
        List<UUID> notes = new ArrayList<>();
        List<UUID> members = new ArrayList<>();
        for (JsonNode ref : artifact.sourceRefs()) {
            String type = ref.path("type").stringValue("");
            if (type.equals("NOTE")) notes.add(UUID.fromString(ref.path("noteId").stringValue("")));
            else if (!type.equals("EXERCISE")) members.add(UUID.fromString(ref.path("memberKey").stringValue("")));
        }
        Map<UUID, NoteState> states = repository.noteStates(session.ownerId(), session.deckId(), notes, false);
        Map<UUID, UUID> heads = repository.headRevisions(session.ownerId(), session.deckId(), members);
        List<Drift> drifted = new ArrayList<>();
        for (JsonNode ref : artifact.sourceRefs()) {
            if (ref.path("type").stringValue("").equals("EXERCISE")) {
                // the exercise a REVISE_EXERCISE artifact was made of: its head must still be that revision
                UUID exercise = UUID.fromString(ref.path("exerciseId").stringValue(""));
                UUID revision = UUID.fromString(ref.path("exerciseRevisionId").stringValue(""));
                UUID head = repository.exerciseHeadRevision(session.ownerId(), session.deckId(), exercise).orElse(null);
                if (head == null) {
                    drifted.add(new Drift(new SourceUnavailableException.Unavailable("EXERCISE", exercise), true, null));
                } else if (!head.equals(revision)) {
                    drifted.add(new Drift(new SourceUnavailableException.Unavailable("EXERCISE", exercise), false,
                            Json.object().put("type", "EXERCISE").put("exerciseId", exercise.toString()).put("exerciseRevisionId", head.toString())));
                }
            } else if (ref.path("type").stringValue("").equals("NOTE")) {
                UUID note = UUID.fromString(ref.path("noteId").stringValue(""));
                NoteState state = states.get(note);
                long pin = Long.parseLong(ref.path("noteRowVersion").stringValue("0"));
                if (state == null) {
                    drifted.add(new Drift(new SourceUnavailableException.Unavailable("NOTE", note), true, null));
                } else if (state.rowVersion() != pin) {
                    drifted.add(new Drift(new SourceUnavailableException.Unavailable("NOTE", note), false,
                            Json.object().put("type", "NOTE").put("noteId", note.toString())
                                    .put("noteRowVersion", Long.toString(state.rowVersion()))));
                }
            } else {
                UUID member = UUID.fromString(ref.path("memberKey").stringValue(""));
                UUID revision = UUID.fromString(ref.path("itemRevisionId").stringValue(""));
                // a material pin is bound to the head when it is a SOURCE of the session; an exercise's one pin always is (it is the
                // material the exercise is about), also after a retry moved it to a revision the session did not pin
                if (!artifact.targetKind().equals("EXERCISE") && !isSource(sources, member, revision)) continue;
                UUID head = heads.get(member);
                if (head == null) {
                    drifted.add(new Drift(new SourceUnavailableException.Unavailable("ITEM", member), true, null));
                } else if (!head.equals(revision)) {
                    drifted.add(new Drift(new SourceUnavailableException.Unavailable("ITEM", member), false,
                            Json.object().put("type", "ITEM").put("memberKey", member.toString()).put("itemRevisionId", head.toString())));
                }
            }
        }
        return drifted;
    }

    private static boolean isSource(List<Source> sources, UUID member, UUID revision) {
        return sources.stream().anyMatch(source -> source.type().equals("ITEM") && source.role().equals("SOURCE")
                && member.equals(source.memberKey()) && revision.equals(source.itemRevisionId()));
    }

    /** The pins of {@code artifact} with every drifted one replaced by its current value ({@code gone} ones must not exist here). */
    ArrayNode repinned(Artifact artifact, List<Drift> drifted) {
        Map<String, JsonNode> replacement = new HashMap<>();
        for (Drift drift : drifted) replacement.put(drift.source().type() + ":" + drift.source().id(), drift.current());
        ArrayNode refs = Json.array();
        for (JsonNode ref : artifact.sourceRefs()) {
            String type = ref.path("type").stringValue("");
            String id = type.equals("NOTE") ? ref.path("noteId").stringValue("")
                    : type.equals("EXERCISE") ? ref.path("exerciseId").stringValue("") : ref.path("memberKey").stringValue("");
            JsonNode replaced = replacement.get(ref.path("type").stringValue("") + ":" + id);
            refs.add(replaced == null ? ref.deepCopy() : replaced);
        }
        return refs;
    }

    /**
     * Approval found {@code candidates} (PROPOSED artifacts) drifted: in this short transaction of its own each one that is
     * still PROPOSED at the version seen becomes {@code STALE} (repin status {@code NEEDS_USER_DECISION}: no re-pin job
     * exists yet) with its event, and the commit makes it final before the approval answers {@code 409 SOURCE_STALE}.
     *
     * @return the artifacts that are STALE now
     */
    @Transactional
    List<UUID> markStale(UUID sessionId, Map<UUID, Long> candidates) {
        SessionLifecycle.Tx tx = lifecycle.lock(sessionId);
        if (tx == null) return List.of();
        List<UUID> stale = new ArrayList<>();
        for (Map.Entry<UUID, Long> candidate : candidates.entrySet()) {
            Optional<Artifact> found = repository.artifact(sessionId, candidate.getKey());
            if (found.isEmpty()) continue;
            Artifact artifact = found.get();
            if (artifact.state().equals("STALE")) {
                stale.add(artifact.artifactId());
            } else if (artifact.state().equals("PROPOSED") && artifact.rowVersion() == candidate.getValue()) {
                tx.events.add(SessionLifecycle.artifactEvent(repository.markStale(artifact)));
                stale.add(artifact.artifactId());
            }
        }
        if (tx.events.isEmpty()) tx.touch = false;
        lifecycle.flush(tx);
        return stale;
    }
}
