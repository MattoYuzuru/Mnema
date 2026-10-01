package app.mnema.learning.study.attempt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;

import static app.mnema.learning.support.ContractFixtures.JSON;
import static app.mnema.learning.support.ContractFixtures.fixture;
import static app.mnema.learning.support.ContractFixtures.mechanic;

/** Builds author-preview requests from the shared {@code mechanics.json} and {@code preview.json} fixtures. */
final class PreviewRequests {
    private PreviewRequests() { }

    /** The editor's exercise: a publication exercise without deck subject and enabled flag. */
    static ObjectNode exercise(JsonNode published) {
        ObjectNode exercise = published.deepCopy();
        exercise.remove("enabled");
        exercise.remove("subject");
        return exercise;
    }

    /** The exercise of a {@code mechanics.json} publication command. */
    static ObjectNode mechanicExercise(String command) {
        return exercise(mechanic(command).path("exercise"));
    }

    static ObjectNode request(ObjectNode exercise, ObjectNode action) {
        ObjectNode request = JSON.createObjectNode();
        request.set("exercise", exercise);
        request.set("action", action);
        return request;
    }

    static ObjectNode previewFixture(String name) { return (ObjectNode) fixture("preview.json").path(name).deepCopy(); }

    static ObjectNode submit(JsonNode response, List<UUID> hinted, boolean pairMistakes, boolean transcript) {
        ObjectNode action = JSON.createObjectNode().put("kind", "SUBMIT");
        action.set("response", response.deepCopy());
        var ids = action.putArray("hintedBlankIds");
        hinted.forEach(id -> ids.add(id.toString()));
        action.put("pairMistakes", pairMistakes).put("transcriptRevealed", transcript);
        return action;
    }

    static ObjectNode submit(JsonNode response) { return submit(response, List.of(), false, false); }

    static ObjectNode pairCheck(String left, String right) {
        return JSON.createObjectNode().put("kind", "PAIR_CHECK").put("leftId", left).put("rightId", right);
    }

    static ObjectNode hint(String blank) { return JSON.createObjectNode().put("kind", "HINT").put("blankId", blank); }
}
