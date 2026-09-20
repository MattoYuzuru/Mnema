package app.mnema.learning.study.attempt;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Strict attempt envelope; all assessment authority remains in the stored presentation. */
public record AttemptCommand(UUID attemptId, UUID presentationId, String nonce, Response response,
                             List<String> hintsUsed, String confidence, int durationMs, ObjectNode payload) {
    private static final int MAX_BYTES = 8_192;
    private static final ContentJsonReader JSON = new ContentJsonReader(MAX_BYTES, 8, 200);
    private static final Set<String> CONFIDENCE = Set.of("KNEW", "UNSURE", "GUESSED");

    public AttemptCommand {
        attemptId = UuidPolicy.requireCommandId(attemptId);
        presentationId = UuidPolicy.requireEntityId(presentationId, "presentationId");
        hintsUsed = List.copyOf(hintsUsed);
        payload = payload.deepCopy();
    }

    @Override public ObjectNode payload() { return payload.deepCopy(); }

    public ObjectNode envelope(UUID deckId, UUID sessionId) {
        ObjectNode value = payload();
        value.put("deckId", deckId.toString()).put("sessionId", sessionId.toString());
        return value;
    }

    public static AttemptCommand read(InputStream input) {
        try {
            JsonNode body = JSON.read(input.readNBytes(MAX_BYTES + 1));
            fields(body, Set.of("attemptId", "presentationId", "nonce", "response", "hintsUsed",
                    "confidence", "durationMs"));
            String nonce = text(body.path("nonce"), 100, false);
            if (nonce.length() < 16) throw invalid();
            if (!body.path("hintsUsed").isArray() || body.path("hintsUsed").size() > 8) throw invalid();
            List<String> hints = new ArrayList<>();
            Set<String> distinct = new HashSet<>();
            body.path("hintsUsed").forEach(value -> {
                String hint = text(value, 64, false);
                if (!distinct.add(hint)) throw invalid();
                hints.add(hint);
            });
            String confidence = body.path("confidence").isNull() ? null : text(body.path("confidence"), 16, false);
            if (confidence != null && !CONFIDENCE.contains(confidence)) throw invalid();
            JsonNode duration = body.path("durationMs");
            if (!duration.isIntegralNumber() || !duration.canConvertToInt()
                    || duration.intValue() < 0 || duration.intValue() > 3_600_000) throw invalid();
            return new AttemptCommand(id(body.path("attemptId"), true), id(body.path("presentationId"), false),
                    nonce, response(body.path("response")), hints, confidence, duration.intValue(), (ObjectNode) body);
        } catch (IOException | IllegalArgumentException exception) { throw invalid(); }
    }

    private static Response response(JsonNode value) {
        if (!value.path("kind").isTextual()) throw invalid();
        return switch (value.path("kind").textValue()) {
            case "TEXT" -> {
                fields(value, Set.of("kind", "text"));
                yield new TextResponse(text(value.path("text"), 4_096, true));
            }
            case "SELF_CHECK" -> {
                fields(value, Set.of("kind", "rating"));
                try { yield new SelfCheckResponse(SelfRating.valueOf(text(value.path("rating"), 32, false))); }
                catch (IllegalArgumentException exception) { throw invalid(); }
            }
            case "CHOICE" -> {
                fields(value, Set.of("kind", "optionId"));
                yield new ChoiceResponse(id(value.path("optionId"), false));
            }
            case "CANCEL" -> {
                fields(value, Set.of("kind"));
                yield new CancelResponse();
            }
            default -> throw invalid();
        };
    }

    private static String text(JsonNode value, int maxBytes, boolean blank) {
        if (!value.isTextual() || (!blank && value.textValue().isBlank())
                || value.textValue().getBytes(StandardCharsets.UTF_8).length > maxBytes) throw invalid();
        return value.textValue();
    }

    private static UUID id(JsonNode value, boolean command) {
        if (!value.isTextual() || value.textValue().length() != 36) throw invalid();
        try {
            UUID id = UUID.fromString(value.textValue());
            id = command ? UuidPolicy.requireCommandId(id) : UuidPolicy.requireEntityId(id, "id");
            if (!id.toString().equals(value.textValue())) throw invalid();
            return id;
        } catch (IllegalArgumentException exception) { throw invalid(); }
    }

    private static void fields(JsonNode value, Set<String> expected) {
        if (!value.isObject() || !value.properties().stream().map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()).equals(expected)) throw invalid();
    }

    private static InvalidRequestException invalid() { return new InvalidRequestException(); }

    public sealed interface Response permits TextResponse, SelfCheckResponse, ChoiceResponse, CancelResponse { }
    public record TextResponse(String text) implements Response { }
    public record SelfCheckResponse(SelfRating rating) implements Response { }
    public record ChoiceResponse(UUID optionId) implements Response { }
    public record CancelResponse() implements Response { }
    public enum SelfRating { NOT_RECALLED, HINTED, PARTIAL, FULL }
}
