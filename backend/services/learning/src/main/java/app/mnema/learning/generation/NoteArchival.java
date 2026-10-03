package app.mnema.learning.generation;

import app.mnema.learning.catalog.authoring.CaptureService;
import app.mnema.learning.generation.GenerationRepository.NoteState;
import app.mnema.learning.generation.GenerationRepository.UsedNote;
import app.mnema.learning.generation.Rows.Session;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Archive the notes the session used": exactly the {@code NOTE} sources of the artifacts that were published or handed
 * off, each archived through the capture command with the row version the session pinned. A note that changed since is
 * skipped and reported ({@code CHANGED}), never archived silently; one already archived or deleted is reported too, so the
 * command is idempotent and a second call changes nothing. The state of a note is read at the time of the call: nothing is
 * stored about it, and no session state changes.
 */
@Component
class NoteArchival {
    /** The two numbers of {@code sessionDetail.notes}: notes used by the session, and those that can still be archived. */
    record Counts(int used, int archivable) { }

    private final GenerationRepository repository;
    private final CaptureService captures;

    NoteArchival(GenerationRepository repository, CaptureService captures) {
        this.repository = repository;
        this.captures = captures;
    }

    /** Why a used note is not archived now, or null when it is archivable: still its pinned version and not archived. */
    private static String skipReason(NoteState state, long pin) {
        if (state == null) return "DELETED";
        if (state.archived()) return "ALREADY_ARCHIVED";
        if (state.rowVersion() != pin) return "CHANGED";
        return null;
    }

    Counts counts(Session session) {
        List<UsedNote> used = repository.usedNotes(session.sessionId());
        if (used.isEmpty()) return new Counts(0, 0);
        Map<UUID, NoteState> states = repository.noteStates(session.ownerId(), session.deckId(),
                used.stream().map(UsedNote::noteId).toList(), false);
        int archivable = (int) used.stream().filter(note -> skipReason(states.get(note.noteId()), note.pinnedRowVersion()) == null).count();
        return new Counts(used.size(), archivable);
    }

    /** Archives what can be; runs in the command's transaction, with the note rows locked so the pinned version holds. */
    ObjectNode archive(Session session) {
        List<UsedNote> used = repository.usedNotes(session.sessionId());
        Map<UUID, NoteState> states = repository.noteStates(session.ownerId(), session.deckId(),
                used.stream().map(UsedNote::noteId).toList(), true);
        ObjectNode result = Json.object();
        ArrayNode archived = result.putArray("archived");
        ArrayNode skipped = result.putArray("skipped");
        for (UsedNote note : used) {
            String reason = skipReason(states.get(note.noteId()), note.pinnedRowVersion());
            if (reason == null) {
                captures.archive(session.ownerId(), note.noteId(), note.pinnedRowVersion());
                archived.addObject().put("noteId", note.noteId().toString());
            } else {
                skipped.addObject().put("noteId", note.noteId().toString()).put("reason", reason);
            }
        }
        return result;
    }
}
