package app.mnema.learning.study.attempt;

import app.mnema.learning.catalog.exercise.ExerciseContent;
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
                             String confidence, int durationMs, ObjectNode payload) {
    private static final int MAX_BYTES = 262_144;
    private static final ContentJsonReader JSON = new ContentJsonReader(MAX_BYTES, 8, 20_000);
    private static final Set<String> CONFIDENCE = Set.of("KNEW", "UNSURE", "GUESSED");
    private static final int MAX_BLANKS = 12;
    private static final int MAX_OPTIONS = 12;

    public AttemptCommand {
        attemptId = UuidPolicy.requireCommandId(attemptId);
        presentationId = UuidPolicy.requireEntityId(presentationId, "presentationId");
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
            // Hint use is recorded by the server; a client claim is an unknown field.
            fields(body, Set.of("attemptId", "presentationId", "nonce", "response", "confidence", "durationMs"));
            String nonce = text(body.path("nonce"), 100, false);
            if (nonce.length() < 16) throw invalid();
            String confidence = body.path("confidence").isNull() ? null : text(body.path("confidence"), 16, false);
            if (confidence != null && !CONFIDENCE.contains(confidence)) throw invalid();
            JsonNode duration = body.path("durationMs");
            if (!duration.isIntegralNumber() || !duration.canConvertToInt()
                    || duration.intValue() < 0 || duration.intValue() > 3_600_000) throw invalid();
            return new AttemptCommand(id(body.path("attemptId"), true), id(body.path("presentationId"), false),
                    nonce, response(body.path("response")), confidence, duration.intValue(), (ObjectNode) body);
        } catch (IOException | IllegalArgumentException exception) { throw invalid(); }
    }

    /** The response union shared with the stateless author preview; CANCEL is a Study-only terminal command. */
    static Response response(JsonNode value) {
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
            case "CLOZE" -> {
                fields(value, Set.of("kind", "blanks"));
                JsonNode blanks = value.path("blanks");
                if (!blanks.isArray() || blanks.isEmpty() || blanks.size() > MAX_BLANKS) throw invalid();
                List<BlankText> result = new ArrayList<>();
                Set<UUID> distinct = new HashSet<>();
                for (JsonNode blank : blanks) {
                    fields(blank, Set.of("blankId", "text"));
                    UUID blankId = id(blank.path("blankId"), false);
                    if (!distinct.add(blankId)) throw invalid();
                    result.add(new BlankText(blankId, text(blank.path("text"), 1_024, true)));
                }
                yield new ClozeResponse(List.copyOf(result));
            }
            case "CHOICE" -> {
                fields(value, Set.of("kind", "optionIds"));
                JsonNode options = value.path("optionIds");
                if (!options.isArray() || options.isEmpty() || options.size() > MAX_OPTIONS) throw invalid();
                List<UUID> ids = new ArrayList<>();
                Set<UUID> distinct = new HashSet<>();
                for (JsonNode option : options) {
                    UUID optionId = id(option, false);
                    if (!distinct.add(optionId)) throw invalid();
                    ids.add(optionId);
                }
                yield new ChoiceResponse(ids);
            }
            case "MATCH" -> {
                fields(value, Set.of("kind", "pairs"));
                JsonNode pairs = value.path("pairs");
                if (!pairs.isArray() || pairs.size() < 2 || pairs.size() > 6) throw invalid();
                List<MatchPair> result = new ArrayList<>();
                Set<UUID> lefts = new HashSet<>();
                Set<UUID> rights = new HashSet<>();
                for (JsonNode pair : pairs) {
                    fields(pair, Set.of("leftId", "rightId"));
                    UUID left = id(pair.path("leftId"), false);
                    UUID right = id(pair.path("rightId"), false);
                    if (!lefts.add(left) || !rights.add(right)) throw invalid();
                    result.add(new MatchPair(left, right));
                }
                yield new MatchResponse(List.copyOf(result));
            }
            case "ORDER" -> {
                fields(value, Set.of("kind", "sequence"));
                JsonNode sequence = value.path("sequence");
                if (!sequence.isArray() || sequence.size() < ExerciseContent.MIN_ORDER_ITEMS
                        || sequence.size() > ExerciseContent.MAX_ORDER_ITEMS) throw invalid();
                List<UUID> ids = new ArrayList<>();
                Set<UUID> distinct = new HashSet<>();
                for (JsonNode item : sequence) {
                    UUID itemId = id(item, false);
                    if (!distinct.add(itemId)) throw invalid();
                    ids.add(itemId);
                }
                yield new OrderResponse(ids);
            }
            case "CATEGORIZE" -> {
                fields(value, Set.of("kind", "assignments"));
                JsonNode assignments = value.path("assignments");
                if (!assignments.isArray() || assignments.size() < ExerciseContent.MIN_CATEGORIZE_ITEMS
                        || assignments.size() > ExerciseContent.MAX_CATEGORIZE_ITEMS) throw invalid();
                List<CategoryAssignment> result = new ArrayList<>();
                Set<UUID> items = new HashSet<>();
                for (JsonNode assignment : assignments) {
                    fields(assignment, Set.of("itemId", "categoryId"));
                    UUID itemId = id(assignment.path("itemId"), false);
                    if (!items.add(itemId)) throw invalid();
                    result.add(new CategoryAssignment(itemId, id(assignment.path("categoryId"), false)));
                }
                yield new CategorizeResponse(List.copyOf(result));
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

    static UUID id(JsonNode value, boolean command) {
        if (!value.isTextual() || value.textValue().length() != 36) throw invalid();
        try {
            UUID id = UUID.fromString(value.textValue());
            id = command ? UuidPolicy.requireCommandId(id) : UuidPolicy.requireEntityId(id, "id");
            if (!id.toString().equals(value.textValue())) throw invalid();
            return id;
        } catch (IllegalArgumentException exception) { throw invalid(); }
    }

    static void fields(JsonNode value, Set<String> expected) {
        if (!value.isObject() || !value.properties().stream().map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()).equals(expected)) throw invalid();
    }

    private static InvalidRequestException invalid() { return new InvalidRequestException(); }

    public sealed interface Response permits TextResponse, SelfCheckResponse, ClozeResponse, ChoiceResponse,
            MatchResponse, OrderResponse, CategorizeResponse, CancelResponse { }
    public record TextResponse(String text) implements Response { }
    public record SelfCheckResponse(SelfRating rating) implements Response { }
    public record BlankText(UUID blankId, String text) { }
    public record ClozeResponse(List<BlankText> blanks) implements Response {
        public ClozeResponse { blanks = List.copyOf(blanks); }
    }
    public record ChoiceResponse(List<UUID> optionIds) implements Response {
        public ChoiceResponse { optionIds = List.copyOf(optionIds); }
    }
    public record MatchPair(UUID leftId, UUID rightId) { }
    public record MatchResponse(List<MatchPair> pairs) implements Response { }
    public record OrderResponse(List<UUID> sequence) implements Response {
        public OrderResponse { sequence = List.copyOf(sequence); }
    }
    /** One item placed in one category; categories may repeat across assignments, items may not. */
    public record CategoryAssignment(UUID itemId, UUID categoryId) { }
    public record CategorizeResponse(List<CategoryAssignment> assignments) implements Response {
        public CategorizeResponse { assignments = List.copyOf(assignments); }
    }
    public record CancelResponse() implements Response { }
    public enum SelfRating { NOT_RECALLED, HINTED, PARTIAL, FULL }
}
