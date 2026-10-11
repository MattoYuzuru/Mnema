package app.mnema.learning.update.apply;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

/** The answers of the apply command ({@code contracts/decks/updates.json}): the stored receipt of an applied command, and the acknowledgement of a command that runs as a job. */
final class ApplyAnswer {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ApplyAnswer() { }

    /**
     * @param revision the new revision of the copy, {@code null} when the command changed no head, order or text (it only recorded decisions and moved the base)
     * @param base the copy's base after the command: the published revision the plan was read against
     */
    static ObjectNode applied(UUID command, UUID revision, int applied, int declined, int pending, int alsoAccepted, UUID base) {
        ObjectNode answer = JSON.createObjectNode().put("commandId", command.toString()).put("state", "APPLIED");
        if (revision == null) answer.putNull("deckRevisionId"); else answer.put("deckRevisionId", revision.toString());
        return answer.put("applied", applied).put("declined", declined).put("pending", pending).put("alsoAccepted", alsoAccepted).put("baseRevisionId", base.toString());
    }

    static ObjectNode applying(UUID command) {
        return JSON.createObjectNode().put("commandId", command.toString()).put("state", "APPLYING");
    }
}
