package app.mnema.learning.study;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudyContractFixtureTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void fixturesAreValidAndKeepAuthorityOnTheServer() throws Exception {
        JsonNode mechanics = fixture("mechanics.json");
        JsonNode session = fixture("session.json");
        JsonNode attempts = fixture("attempts.json");

        // Publication carries one subject and no bindings: the server derives them.
        for (String name : new String[] {"createSelfCheck", "createFreeResponseAudio", "createCloze",
                "createChoiceVideoMultiple", "createMatchMixed", "createOrder", "createCategorize"}) {
            JsonNode exercise = mechanics.path(name).path("exercise");
            assertThat(exercise.has("bindings")).isFalse();
            assertThat(exercise.path("subject").has("memberKey")).isTrue();
            assertThat(exercise.path("schemaVersion").intValue()).isEqualTo(2);
        }
        // A learner presentation never carries the key, correct ids, accepted strings, titles or bindings.
        mechanics.path("presentations").forEach(presentation -> {
            String raw = presentation.toString();
            assertThat(presentation.has("bindings")).isFalse();
            assertThat(presentation.has("reference")).isFalse();
            assertThat(presentation.has("options")).isFalse();
            assertThat(raw).doesNotContain("answerKey", "accepted", "correctOptionIds", "\"title\":\"Слово", "\"pairs\"");
        });
        JsonNode presentation = session.path("active").path("presentations").get(0);
        assertThat(presentation.has("bindings")).isFalse();
        assertThat(presentation.path("objectiveId").isString()).isTrue();

        JsonNode submit = attempts.path("freeResponseSubmit");
        assertThat(submit.has("mode")).isFalse();
        assertThat(submit.has("hintsUsed")).isFalse();
        assertThat(submit.has("deckRevisionId")).isFalse();
        assertThat(submit.has("exerciseRevisionId")).isFalse();
        assertThat(submit.has("bindings")).isFalse();
        assertThat(submit.has("correctAnswer")).isFalse();
        mechanics.path("submits").forEach(command -> assertThat(command.has("hintsUsed")).isFalse());

        JsonNode practice = attempts.path("practiceOutcome");
        assertThat(practice.path("canonicalEffects").booleanValue()).isFalse();
        assertThat(practice.path("evidence").isNull()).isTrue();
        assertThat(practice.path("transition").isNull()).isTrue();
        assertThat(attempts.path("notAssessedOutcome").path("transition").isNull()).isTrue();
    }

    @Test
    void everySharedFixtureValidatesAgainstTheStrictSharedSchema() throws Exception {
        JsonNode schema = fixture("study.schema.json");
        var definitions = java.util.Map.of(
                "authoring.json", "authoringDocument",
                "mechanics.json", "mechanicsDocument",
                "session.json", "sessionDocument",
                "attempts.json", "attemptsDocument",
                "reducer-v1.json", "reducerDocument",
                "adversarial.json", "adversarialDocument",
                "flows.json", "flowsDocument",
                "progress.json", "progressDocument",
                "replay-sources.json", "replaySourcesDocument",
                "restart.json", "restartDocument");

        definitions.forEach((file, definition) -> validate(fixtureUnchecked(file),
                schema.path("$defs").path(definition), schema, "$"));

        ObjectNode unknown = (ObjectNode) fixture("authoring.json").deepCopy();
        unknown.put("clientAuthority", true);
        assertThatThrownBy(() -> validate(unknown, schema.path("$defs").path("authoringDocument"), schema, "$"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown property");

        ObjectNode missing = (ObjectNode) fixture("attempts.json").deepCopy();
        missing.withObject("freeResponseSubmit").remove("attemptId");
        assertThatThrownBy(() -> validate(missing, schema.path("$defs").path("attemptsDocument"), schema, "$"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attemptId");

        ObjectNode nestedUnknown = (ObjectNode) fixture("attempts.json").deepCopy();
        nestedUnknown.withObject("freeResponseSubmit").withObject("response").put("correctAnswer", "spoofed");
        assertThatThrownBy(() -> validate(nestedUnknown, schema.path("$defs").path("attemptsDocument"), schema, "$"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must match exactly one schema");

        ObjectNode invalidMode = (ObjectNode) fixture("session.json").deepCopy();
        invalidMode.withObject("startReplay").remove("sourceSessionId");
        assertThatThrownBy(() -> validate(invalidMode, schema.path("$defs").path("sessionDocument"), schema, "$"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must match exactly one schema");

        ObjectNode forgedEffect = (ObjectNode) fixture("attempts.json").deepCopy();
        forgedEffect.withObject("practiceOutcome").put("mode", "SCHEDULED");
        assertThatThrownBy(() -> validate(forgedEffect, schema.path("$defs").path("attemptsDocument"), schema, "$"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must match exactly one schema");
    }

    @Test
    void thePreviewFixtureValidatesAndItsSchemaRejectsPublicationAuthorityAndUnknownShapes() throws Exception {
        JsonNode schema = fixture("study.schema.json");
        JsonNode definition = schema.path("$defs").path("previewDocument");
        validate(fixture("preview.json"), definition, schema, "$");

        for (java.util.function.Consumer<ObjectNode> corrupt : java.util.List.<java.util.function.Consumer<ObjectNode>>of(
                root -> root.withObject("submitCloze").withObject("exercise").put("enabled", true),
                root -> root.withObject("submitCloze").withObject("exercise").putObject("subject"),
                root -> root.withObject("submitCloze").withObject("exercise").put("schemaVersion", 1),
                root -> root.withObject("submitCloze").withObject("exercise").put("type", "CLOZE_SINGLE"),
                root -> root.withObject("submitCloze").withObject("exercise").remove("answerKey"),
                root -> root.withObject("submitCloze").withObject("exercise").withObject("answerKey").put("kind", "TEXT"),
                root -> root.withObject("submitOrder").withObject("exercise").withObject("answerKey")
                        .put("kind", "CATEGORIZE"),
                root -> root.withObject("submitOrder").withObject("exercise").withObject("evaluatorPolicy")
                        .put("id", "deterministic-categorize"),
                root -> root.withObject("submitOrder").withObject("exercise").put("enabled", true),
                root -> root.withObject("submitOrder").withObject("action").withObject("response")
                        .withArray("sequence").removeAll(),
                root -> root.withObject("submitOrder").withObject("action").withObject("response")
                        .put("kind", "CATEGORIZE"),
                root -> root.withObject("submitCategorize").withObject("exercise").withObject("content")
                        .withArray("categories").removeAll(),
                root -> ((ObjectNode) root.withObject("submitCategorize").withObject("exercise").withObject("content")
                        .withArray("categories").get(0)).put("label", "x".repeat(81)),
                root -> ((ObjectNode) root.withObject("submitCategorize").withObject("action").withObject("response")
                        .withArray("assignments").get(0)).remove("categoryId"),
                root -> root.withObject("submitOrderResult").withObject("feedback").withArray("positions").addObject()
                        .put("position", -1),
                root -> root.withObject("submitCategorizeResult").withObject("feedback").withArray("assignments")
                        .addObject().put("itemId", "x"),
                root -> root.withObject("submitCloze").put("attemptId", "cccccccc-cccc-4ccc-8ccc-cccccccccc11"),
                root -> root.withObject("submitCloze").remove("action"),
                root -> root.withObject("submitCloze").withObject("action").put("hintsUsed", 1),
                root -> root.withObject("submitCloze").withObject("action").remove("pairMistakes"),
                root -> root.withObject("submitCloze").withObject("action").put("transcriptRevealed", "no"),
                root -> root.withObject("submitCloze").withObject("action").putObject("response").put("kind", "CANCEL"),
                root -> root.withObject("submitCloze").withObject("action").put("kind", "CANCEL"),
                root -> root.withObject("submitCloze").withObject("action").withArray("hintedBlankIds")
                        .add("b1a00000-0000-4000-8000-000000000003"),
                root -> root.withObject("hint").withObject("action").put("nonce", "1234567890123456"),
                root -> root.withObject("pairCheck").withObject("action").put("presentationId",
                        "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbb15"),
                root -> root.withObject("hintResult").put("presentationId", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbb13"),
                root -> root.withObject("hintResult").remove("firstLetter"),
                root -> root.withObject("submitClozeResult").putNull("evidence"),
                root -> root.withObject("submitClozeResult").putNull("transition"),
                root -> root.withObject("submitClozeResult").withObject("feedback").put("result", "WRONG"),
                root -> root.withObject("pairCheckResult").put("feedback", "x"),
                root -> root.put("clientAuthority", true),
                root -> root.remove("aiSemanticResult"))) {
            ObjectNode broken = (ObjectNode) fixture("preview.json").deepCopy();
            corrupt.accept(broken);
            assertThatThrownBy(() -> validate(broken, definition, schema, "$"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theMechanicsSchemaRejectsLegacyShapesAndClientAuthority() throws Exception {
        JsonNode schema = fixture("study.schema.json");
        JsonNode definition = schema.path("$defs").path("mechanicsDocument");
        for (java.util.function.Consumer<ObjectNode> corrupt : java.util.List.<java.util.function.Consumer<ObjectNode>>of(
                root -> root.withObject("createCloze").withObject("exercise").put("type", "CLOZE_SINGLE"),
                root -> root.withObject("createCloze").withObject("exercise").put("schemaVersion", 1),
                root -> root.withObject("createCloze").withObject("exercise").putArray("bindings"),
                root -> root.withObject("createCloze").withObject("exercise").remove("answerKey"),
                root -> root.withObject("createCloze").withObject("exercise").withObject("answerKey").put("kind", "TEXT"),
                root -> root.withObject("createChoiceVideoMultiple").withObject("objective").put("answerContract", "x"),
                root -> drop(root.withObject("createMatchMixed").withObject("exercise").withObject("answerKey")
                        .withArray("pairs"), 3, 2, 1),
                root -> root.withObject("submits").withObject("freeResponse").putArray("hintsUsed"),
                root -> root.withObject("submits").withObject("match").withObject("response")
                        .withArray("pairs").insertObject(0).put("cueId", "x"),
                root -> root.withObject("presentations").withObject("choice").putArray("options"),
                root -> root.withObject("presentations").withObject("cloze").put("reference", "map"),
                root -> root.withObject("presentations").withObject("match").withObject("content")
                        .withArray("left").addObject().put("itemId", "not-an-id"),
                root -> ((ObjectNode) root.withObject("presentations").withObject("freeResponse").withObject("content")
                        .withArray("prompt").get(0)).put("title", "leaked author label"),
                root -> root.withObject("feedback").withObject("match").withArray("pairs").addObject().put("cueId", "x"),
                root -> root.withObject("evidence").withObject("clozeHinted").put("evidenceClass", "NONE"),
                root -> root.withObject("createOrder").withObject("exercise").withObject("answerKey")
                        .withArray("sequence").add("0d000000-0000-4000-8000-000000000001"),
                root -> drop(root.withObject("createOrder").withObject("exercise").withObject("content")
                        .withArray("items"), 5, 4, 3, 2, 1),
                root -> ((ObjectNode) root.withObject("createOrder").withObject("exercise").withObject("content")
                        .withArray("items").get(0).withArray("blocks").get(0)).put("text", "x".repeat(1_001)),
                root -> root.withObject("createOrder").withObject("exercise").withObject("evaluatorPolicy")
                        .put("id", "deterministic-match"),
                root -> ((ObjectNode) root.withObject("createCategorize").withObject("exercise").withObject("content")
                        .withArray("items").get(0).withArray("blocks").get(0)).put("text", "x".repeat(301)),
                root -> root.withObject("createCategorize").withObject("exercise").withObject("answerKey")
                        .withArray("assignments").addObject().put("itemId", "9a000000-0000-4000-8000-000000000001"),
                root -> root.withObject("createCategorize").withObject("exercise").withObject("content")
                        .withArray("categories").addObject().put("categoryId", "ca000000-0000-4000-8000-000000000009"),
                root -> root.withObject("presentations").withObject("order").withObject("content").put("sequence", "x"),
                root -> root.withObject("presentations").withObject("categorize").withObject("content")
                        .withArray("items").addObject().put("itemId", "not-an-id"),
                root -> ((ObjectNode) root.withObject("presentations").withObject("categorize").withObject("content")
                        .withArray("items").get(1).withArray("blocks").get(0)).put("title", "leaked author label"),
                root -> root.withObject("submits").withObject("order").withObject("response").put("kind", "CATEGORIZE"),
                root -> root.withObject("submits").withObject("categorize").withObject("response")
                        .withArray("assignments").insertObject(0).put("optionId", "x"),
                root -> root.withObject("feedback").withObject("order").withArray("correctSequence").removeAll(),
                root -> root.withObject("feedback").withObject("categorize").withArray("assignments").removeAll(),
                root -> root.withObject("evidence").withObject("orderIncorrect").put("evidenceClass", "NONE"),
                root -> root.remove("createCategorize"),
                root -> root.withObject("capabilities").withObject("aiAssessment").putNull("reason"),
                root -> root.withObject("capabilities").withObject("aiAssessment").put("available", true),
                root -> root.withObject("capabilityUnavailableProblem").put("status", 400))) {
            ObjectNode broken = (ObjectNode) fixture("mechanics.json").deepCopy();
            corrupt.accept(broken);
            assertThatThrownBy(() -> validate(broken, definition, schema, "$"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void reducerMatrixIsCompleteAndGoldenCasesAreExecutable() throws Exception {
        JsonNode reducer = fixture("reducer-v1.json");
        assertThat(reducer.path("intervals")).hasSize(8);
        assertThat(reducer.path("transitions")).hasSize(12);

        Set<String> combinations = new HashSet<>();
        for (JsonNode transition : reducer.path("transitions")) {
            assertThat(transition.path("evidenceClass").stringValue(null)).isNotEqualTo("NONE");
            combinations.add(transition.path("result").stringValue(null) + ":"
                    + transition.path("evidenceClass").stringValue(null));
        }
        assertThat(combinations).hasSize(12);

        for (JsonNode golden : reducer.path("goldenCases")) {
            JsonNode transition = matchingTransition(reducer.path("transitions"), golden);
            int before = golden.path("beforeLevel").intValue();
            int levels = transition.path("levels").intValue();
            int after = switch (transition.path("operation").stringValue(null)) {
                case "ADD" -> Math.max(before,
                        Math.min(before + levels, transition.path("cap").intValue()));
                case "SUBTRACT" -> Math.max(0, before - levels);
                case "SET" -> levels;
                default -> throw new IllegalStateException("Unknown reducer operation");
            };
            assertThat(after).isEqualTo(golden.path("afterLevel").intValue());
            Instant due = Instant.parse(golden.path("acceptedAt").stringValue(null))
                    .plus(Duration.parse(reducer.path("intervals").get(after).stringValue(null)));
            assertThat(due).isEqualTo(Instant.parse(golden.path("nextDue").stringValue(null)));
        }

        ObjectNode projection = JSON.createObjectNode();
        projection.set("configId", reducer.path("configId"));
        projection.set("intervals", reducer.path("intervals"));
        projection.set("reducerId", reducer.path("reducerId"));
        projection.set("reducerVersion", reducer.path("reducerVersion"));
        projection.set("transitions", reducer.path("transitions"));
        String hash = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(JSON.writeValueAsBytes(canonical(projection))));
        assertThat(fixture("session.json").path("active").path("reducer").path("configHash").stringValue(null))
                .isEqualTo(hash);
        assertThat(fixture("attempts.json").path("scheduledOutcome").path("transition")
                .path("configHash").stringValue(null)).isEqualTo(hash);
    }

    @Test
    void adversarialSuitePinsCriticalIsolationAndIdempotencyCases() throws Exception {
        JsonNode cases = fixture("adversarial.json").path("cases");
        Set<String> ids = new HashSet<>();
        for (JsonNode testCase : cases) ids.add(testCase.path("id").stringValue(null));

        assertThat(cases).hasSize(24);
        assertThat(ids).hasSize(24).contains("A-01", "A-03", "A-05", "A-10", "A-11", "A-15",
                "A-16", "A-17", "A-18", "A-19", "A-20", "A-21", "A-22", "A-23", "A-24");
        assertThat(find(cases, "A-03").path("persistedEffect").path("transitions").intValue()).isEqualTo(1);
        assertThat(find(cases, "A-05").path("persistedEffect").path("transitions").intValue()).isZero();
        assertThat(find(cases, "A-11").path("persistedEffect").path("stateTransitions").intValue()).isZero();
        assertThat(find(cases, "A-13").path("persistedEffect").path("fullScan").booleanValue()).isFalse();
        assertThat(find(cases, "A-20").path("persistedEffect").path("evaluationRuns").intValue()).isZero();
    }

    @Test
    void progressAndRestartPinObservableStateAndRecovery() throws Exception {
        JsonNode flows = fixture("flows.json").path("flows");
        assertThat(flows).hasSize(12);
        for (JsonNode flow : flows) {
            assertThat(resolveFixturePointer(flow.path("requestFixture").stringValue(null)).isMissingNode()).isFalse();
            assertThat(resolveFixturePointer(flow.path("responseFixture").stringValue(null)).isMissingNode()).isFalse();
        }
        JsonNode sessions = fixture("session.json");
        assertThat(sessions.path("startScheduled").path("mode").stringValue(null)).isEqualTo("SCHEDULED");
        assertThat(sessions.path("active").path("mode").stringValue(null)).isEqualTo("SCHEDULED");
        assertThat(sessions.path("startReplay").path("mode").stringValue(null)).isEqualTo("REPLAY");
        assertThat(sessions.path("activeReplay").path("mode").stringValue(null)).isEqualTo("REPLAY");
        assertThat(sessions.path("startPractice").path("mode").stringValue(null)).isEqualTo("PRACTICE");
        assertThat(sessions.path("activePractice").path("mode").stringValue(null)).isEqualTo("PRACTICE");
        JsonNode progress = fixture("progress.json");
        assertThat(progress.path("response").path("items").get(0).path("state").stringValue(null))
                .isEqualTo("ON_TRACK");
        assertThat(progress.path("response").path("items").get(1).path("state").stringValue(null))
                .isEqualTo("NOT_STARTED");
        assertThat(progress.path("response").has("percentage")).isFalse();

        JsonNode replaySources = fixture("replay-sources.json");
        assertThat(replaySources.path("response").path("items")).hasSize(1);
        assertThat(replaySources.path("response").path("localStudyDate").stringValue(null))
                .isEqualTo("2026-09-20");
        assertThat(replaySources.path("emptyResponse").path("items")).isEmpty();

        JsonNode restart = fixture("restart.json");
        assertThat(restart.path("precondition").path("objectiveStates").get(0).path("learningEpoch").stringValue(null))
                .isEqualTo("0");
        assertThat(restart.path("persistedEffect").path("objectiveStates").get(0).path("learningEpoch").stringValue(null))
                .isEqualTo("1");
        assertThat(restart.path("persistedEffect").path("historyRowsDeleted").intValue()).isZero();
    }

    private static JsonNode matchingTransition(JsonNode transitions, JsonNode golden) {
        for (JsonNode candidate : transitions) {
            if (golden.path("result").stringValue(null).equals(candidate.path("result").stringValue(null))
                    && golden.path("evidenceClass").stringValue(null)
                    .equals(candidate.path("evidenceClass").stringValue(null))) return candidate;
        }
        throw new IllegalArgumentException("Missing reducer row for golden case");
    }

    private static JsonNode find(JsonNode cases, String id) {
        for (JsonNode testCase : cases) if (id.equals(testCase.path("id").stringValue(null))) return testCase;
        throw new IllegalArgumentException("Missing adversarial case " + id);
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            TreeSet<String> names = new TreeSet<>();
            value.propertyNames().forEach(names::add);
            names.forEach(name -> sorted.set(name, canonical(value.path(name))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            value.forEach(element -> array.add(canonical(element)));
            return array;
        }
        return value;
    }

    private static void validate(JsonNode value, JsonNode schema, JsonNode root, String path) {
        if (schema.has("oneOf")) {
            int matches = 0;
            for (JsonNode candidate : schema.path("oneOf")) {
                try {
                    validate(value, candidate, root, path);
                    matches++;
                } catch (IllegalArgumentException ignored) {
                    // A oneOf candidate is expected to reject non-matching shapes.
                }
            }
            if (matches != 1) invalid(path, "must match exactly one schema");
            return;
        }
        if (schema.has("$ref")) {
            String reference = schema.path("$ref").stringValue(null);
            validate(value, root.at(reference.substring(1)), root, path);
            return;
        }
        if (schema.has("const") && !value.equals(schema.path("const"))) invalid(path, "does not match const");
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode option : schema.path("enum")) found |= value.equals(option);
            if (!found) invalid(path, "is not in enum");
        }
        if (schema.has("type") && !matchesType(value, schema.path("type"))) invalid(path, "has wrong type");
        if (value.isString() && schema.has("minLength") && value.stringValue(null).length() < schema.path("minLength").intValue()) {
            invalid(path, "too short");
        }
        if (value.isString() && schema.has("maxLength") && value.stringValue(null).length() > schema.path("maxLength").intValue()) {
            invalid(path, "too long");
        }
        if (value.isIntegralNumber() && schema.has("minimum") && value.longValue() < schema.path("minimum").longValue()) {
            invalid(path, "below minimum");
        }
        if (value.isIntegralNumber() && schema.has("maximum") && value.longValue() > schema.path("maximum").longValue()) {
            invalid(path, "above maximum");
        }
        if (schema.has("pattern") && value.isString()
                && !value.stringValue(null).matches(schema.path("pattern").stringValue(null))) invalid(path, "does not match pattern");
        if (value.isObject()) {
            for (JsonNode required : schema.path("required")) {
                if (!value.has(required.stringValue(null))) invalid(path, "missing required " + required.stringValue(null));
            }
            JsonNode properties = schema.path("properties");
            value.propertyNames().forEach(name -> {
                if (properties.has(name)) validate(value.path(name), properties.path(name), root, path + "." + name);
                else if (schema.path("additionalProperties").isBoolean()
                        && !schema.path("additionalProperties").booleanValue(false)) invalid(path, "unknown property " + name);
            });
        }
        if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.path("minItems").intValue()) invalid(path, "too few items");
            if (schema.has("maxItems") && value.size() > schema.path("maxItems").intValue()) invalid(path, "too many items");
            if (schema.path("uniqueItems").booleanValue(false) && new HashSet<>(java.util.stream.StreamSupport
                    .stream(value.spliterator(), false).toList()).size() != value.size()) invalid(path, "duplicate items");
            if (schema.has("items")) for (int index = 0; index < value.size(); index++)
                validate(value.get(index), schema.path("items"), root, path + "[" + index + "]");
        }
    }

    private static boolean matchesType(JsonNode value, JsonNode type) {
        if (type.isArray()) {
            for (JsonNode candidate : type) if (matchesType(value, candidate)) return true;
            return false;
        }
        return switch (type.stringValue(null)) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isString();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }

    private static void invalid(String path, String reason) {
        throw new IllegalArgumentException(path + " " + reason);
    }

    private static JsonNode fixtureUnchecked(String name) {
        try {
            return fixture(name);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static JsonNode resolveFixturePointer(String reference) throws Exception {
        String[] parts = reference.split("#", 2);
        return parts.length == 1 ? fixture(parts[0]) : fixture(parts[0]).at(parts[1]);
    }

    private static JsonNode fixture(String name) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/study/" + name))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot find repository root");
        return JSON.readTree(Files.readString(root.resolve("contracts/study/" + name)));
    }

    private static void drop(tools.jackson.databind.node.ArrayNode array, int... indexes) {
        for (int index : indexes) array.remove(index);
    }
}
