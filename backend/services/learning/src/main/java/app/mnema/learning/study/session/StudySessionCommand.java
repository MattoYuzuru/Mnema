package app.mnema.learning.study.session;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

/** Strict server-owned Study mode and bounded budget request. */
public record StudySessionCommand(UUID commandId, Mode mode, int maxPresentations, UUID sourceSessionId,
                                  boolean includeNew, PracticeOrder practiceOrder, ObjectNode payload) {
    private static final int MAX_BYTES = 8_192;
    private static final ContentJsonReader JSON = new ContentJsonReader(MAX_BYTES, 8, 80);

    public StudySessionCommand {
        commandId = UuidPolicy.requireCommandId(commandId);
        payload = payload.deepCopy();
    }

    @Override public ObjectNode payload() { return payload.deepCopy(); }

    public ObjectNode envelope(UUID deckId) {
        ObjectNode result = payload();
        result.put("deckId", deckId.toString());
        return result;
    }

    public static StudySessionCommand read(InputStream input) {
        try {
            JsonNode body = JSON.read(input.readNBytes(MAX_BYTES + 1));
            if (!body.path("mode").isTextual()) throw invalid();
            Mode mode = Mode.valueOf(body.path("mode").textValue());
            Set<String> expected = switch (mode) {
                case SCHEDULED -> Set.of("commandId", "mode", "budget");
                case REPLAY -> Set.of("commandId", "mode", "sourceSessionId", "budget");
                case PRACTICE -> Set.of("commandId", "mode", "includeNew", "order", "budget");
            };
            fields(body, expected);
            JsonNode budget = body.path("budget");
            fields(budget, Set.of("maxPresentations"));
            if (!budget.path("maxPresentations").canConvertToInt()) throw invalid();
            int maximum = budget.path("maxPresentations").intValue();
            if (maximum < 1 || maximum > 100) throw invalid();
            UUID source = mode == Mode.REPLAY ? id(body.path("sourceSessionId")) : null;
            boolean includeNew = false;
            PracticeOrder order = null;
            if (mode == Mode.PRACTICE) {
                if (!body.path("includeNew").isBoolean() || !body.path("order").isTextual()) throw invalid();
                includeNew = body.path("includeNew").booleanValue();
                order = PracticeOrder.valueOf(body.path("order").textValue());
            }
            return new StudySessionCommand(id(body.path("commandId")), mode, maximum, source,
                    includeNew, order, (ObjectNode) body);
        } catch (IOException | IllegalArgumentException exception) { throw invalid(); }
    }

    private static UUID id(JsonNode value) {
        if (!value.isTextual() || value.textValue().length() != 36) throw invalid();
        try {
            UUID result = UuidPolicy.requireEntityId(UUID.fromString(value.textValue()), "id");
            if (!result.toString().equals(value.textValue())) throw invalid();
            return result;
        } catch (IllegalArgumentException exception) { throw invalid(); }
    }

    private static void fields(JsonNode value, Set<String> expected) {
        if (!value.isObject() || !value.properties().stream().map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()).equals(expected)) throw invalid();
    }

    private static InvalidRequestException invalid() { return new InvalidRequestException(); }

    public enum Mode { SCHEDULED, REPLAY, PRACTICE }
    public enum PracticeOrder { SEEDED, WEAKEST_FIRST }
}
