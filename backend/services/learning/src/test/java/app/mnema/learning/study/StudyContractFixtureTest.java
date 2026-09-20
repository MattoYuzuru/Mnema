package app.mnema.learning.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        JsonNode authoring = fixture("authoring.json");
        JsonNode session = fixture("session.json");
        JsonNode attempts = fixture("attempts.json");

        assertThat(authoring.path("createTyped").path("exercise").path("bindings")).hasSize(1);
        assertThat(authoring.path("createTyped").path("exercise").path("bindings").get(0).path("role").textValue())
                .isEqualTo("ASSESSED");

        JsonNode presentation = session.path("active").path("presentations").get(0);
        assertThat(presentation.path("bindings")).filteredOn(binding ->
                "ASSESSED".equals(binding.path("role").textValue())).hasSize(1);
        assertThat(presentation.path("objectiveId").isTextual()).isTrue();

        JsonNode submit = attempts.path("typedSubmit");
        assertThat(submit.has("mode")).isFalse();
        assertThat(submit.has("deckRevisionId")).isFalse();
        assertThat(submit.has("exerciseRevisionId")).isFalse();
        assertThat(submit.has("bindings")).isFalse();
        assertThat(submit.has("correctAnswer")).isFalse();

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

        ObjectNode unknown = fixture("authoring.json").deepCopy();
        unknown.put("clientAuthority", true);
        assertThatThrownBy(() -> validate(unknown, schema.path("$defs").path("authoringDocument"), schema, "$"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown property");

        ObjectNode missing = fixture("attempts.json").deepCopy();
        missing.withObject("typedSubmit").remove("attemptId");
        assertThatThrownBy(() -> validate(missing, schema.path("$defs").path("attemptsDocument"), schema, "$"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attemptId");

        ObjectNode nestedUnknown = fixture("attempts.json").deepCopy();
        nestedUnknown.withObject("typedSubmit").withObject("response").put("correctAnswer", "spoofed");
        assertThatThrownBy(() -> validate(nestedUnknown, schema.path("$defs").path("attemptsDocument"), schema, "$"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must match exactly one schema");

        ObjectNode invalidMode = fixture("session.json").deepCopy();
        invalidMode.withObject("startReplay").remove("sourceSessionId");
        assertThatThrownBy(() -> validate(invalidMode, schema.path("$defs").path("sessionDocument"), schema, "$"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must match exactly one schema");

        ObjectNode forgedEffect = fixture("attempts.json").deepCopy();
        forgedEffect.withObject("practiceOutcome").put("mode", "SCHEDULED");
        assertThatThrownBy(() -> validate(forgedEffect, schema.path("$defs").path("attemptsDocument"), schema, "$"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must match exactly one schema");
    }

    @Test
    void reducerMatrixIsCompleteAndGoldenCasesAreExecutable() throws Exception {
        JsonNode reducer = fixture("reducer-v1.json");
        assertThat(reducer.path("intervals")).hasSize(8);
        assertThat(reducer.path("transitions")).hasSize(12);

        Set<String> combinations = new HashSet<>();
        for (JsonNode transition : reducer.path("transitions")) {
            assertThat(transition.path("evidenceClass").textValue()).isNotEqualTo("NONE");
            combinations.add(transition.path("result").textValue() + ":"
                    + transition.path("evidenceClass").textValue());
        }
        assertThat(combinations).hasSize(12);

        for (JsonNode golden : reducer.path("goldenCases")) {
            JsonNode transition = matchingTransition(reducer.path("transitions"), golden);
            int before = golden.path("beforeLevel").intValue();
            int levels = transition.path("levels").intValue();
            int after = switch (transition.path("operation").textValue()) {
                case "ADD" -> Math.max(before,
                        Math.min(before + levels, transition.path("cap").intValue()));
                case "SUBTRACT" -> Math.max(0, before - levels);
                case "SET" -> levels;
                default -> throw new IllegalStateException("Unknown reducer operation");
            };
            assertThat(after).isEqualTo(golden.path("afterLevel").intValue());
            Instant due = Instant.parse(golden.path("acceptedAt").textValue())
                    .plus(Duration.parse(reducer.path("intervals").get(after).textValue()));
            assertThat(due).isEqualTo(Instant.parse(golden.path("nextDue").textValue()));
        }

        ObjectNode projection = JSON.createObjectNode();
        projection.set("configId", reducer.path("configId"));
        projection.set("intervals", reducer.path("intervals"));
        projection.set("reducerId", reducer.path("reducerId"));
        projection.set("reducerVersion", reducer.path("reducerVersion"));
        projection.set("transitions", reducer.path("transitions"));
        String hash = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(JSON.writeValueAsBytes(canonical(projection))));
        assertThat(fixture("session.json").path("active").path("reducer").path("configHash").textValue())
                .isEqualTo(hash);
        assertThat(fixture("attempts.json").path("scheduledOutcome").path("transition")
                .path("configHash").textValue()).isEqualTo(hash);
    }

    @Test
    void adversarialSuitePinsCriticalIsolationAndIdempotencyCases() throws Exception {
        JsonNode cases = fixture("adversarial.json").path("cases");
        Set<String> ids = new HashSet<>();
        for (JsonNode testCase : cases) ids.add(testCase.path("id").textValue());

        assertThat(cases).hasSize(20);
        assertThat(ids).hasSize(20).contains("A-01", "A-03", "A-05", "A-10", "A-11", "A-15",
                "A-16", "A-17", "A-18", "A-19", "A-20");
        assertThat(find(cases, "A-03").path("persistedEffect").path("transitions").intValue()).isEqualTo(1);
        assertThat(find(cases, "A-05").path("persistedEffect").path("transitions").intValue()).isZero();
        assertThat(find(cases, "A-11").path("persistedEffect").path("stateTransitions").intValue()).isZero();
        assertThat(find(cases, "A-13").path("persistedEffect").path("fullScan").booleanValue()).isFalse();
        assertThat(find(cases, "A-20").path("persistedEffect").path("evaluationRuns").intValue()).isZero();
    }

    @Test
    void progressAndRestartPinObservableStateAndRecovery() throws Exception {
        JsonNode flows = fixture("flows.json").path("flows");
        assertThat(flows).hasSize(10);
        for (JsonNode flow : flows) {
            assertThat(resolveFixturePointer(flow.path("requestFixture").textValue()).isMissingNode()).isFalse();
            assertThat(resolveFixturePointer(flow.path("responseFixture").textValue()).isMissingNode()).isFalse();
        }
        JsonNode sessions = fixture("session.json");
        assertThat(sessions.path("startScheduled").path("mode").textValue()).isEqualTo("SCHEDULED");
        assertThat(sessions.path("active").path("mode").textValue()).isEqualTo("SCHEDULED");
        assertThat(sessions.path("startReplay").path("mode").textValue()).isEqualTo("REPLAY");
        assertThat(sessions.path("activeReplay").path("mode").textValue()).isEqualTo("REPLAY");
        assertThat(sessions.path("startPractice").path("mode").textValue()).isEqualTo("PRACTICE");
        assertThat(sessions.path("activePractice").path("mode").textValue()).isEqualTo("PRACTICE");
        JsonNode progress = fixture("progress.json");
        assertThat(progress.path("response").path("items").get(0).path("state").textValue())
                .isEqualTo("ON_TRACK");
        assertThat(progress.path("response").path("items").get(1).path("state").textValue())
                .isEqualTo("NOT_STARTED");
        assertThat(progress.path("response").has("percentage")).isFalse();

        JsonNode replaySources = fixture("replay-sources.json");
        assertThat(replaySources.path("response").path("items")).hasSize(1);
        assertThat(replaySources.path("response").path("localStudyDate").textValue())
                .isEqualTo("2026-09-20");
        assertThat(replaySources.path("emptyResponse").path("items")).isEmpty();

        JsonNode restart = fixture("restart.json");
        assertThat(restart.path("precondition").path("objectiveStates").get(0).path("learningEpoch").textValue())
                .isEqualTo("0");
        assertThat(restart.path("persistedEffect").path("objectiveStates").get(0).path("learningEpoch").textValue())
                .isEqualTo("1");
        assertThat(restart.path("persistedEffect").path("historyRowsDeleted").intValue()).isZero();
    }

    private static JsonNode matchingTransition(JsonNode transitions, JsonNode golden) {
        for (JsonNode candidate : transitions) {
            if (golden.path("result").textValue().equals(candidate.path("result").textValue())
                    && golden.path("evidenceClass").textValue()
                    .equals(candidate.path("evidenceClass").textValue())) return candidate;
        }
        throw new IllegalArgumentException("Missing reducer row for golden case");
    }

    private static JsonNode find(JsonNode cases, String id) {
        for (JsonNode testCase : cases) if (id.equals(testCase.path("id").textValue())) return testCase;
        throw new IllegalArgumentException("Missing adversarial case " + id);
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            TreeSet<String> names = new TreeSet<>();
            value.fieldNames().forEachRemaining(names::add);
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
            String reference = schema.path("$ref").textValue();
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
        if (schema.has("pattern") && value.isTextual()
                && !value.textValue().matches(schema.path("pattern").textValue())) invalid(path, "does not match pattern");
        if (value.isObject()) {
            for (JsonNode required : schema.path("required")) {
                if (!value.has(required.textValue())) invalid(path, "missing required " + required.textValue());
            }
            JsonNode properties = schema.path("properties");
            value.fieldNames().forEachRemaining(name -> {
                if (properties.has(name)) validate(value.path(name), properties.path(name), root, path + "." + name);
                else if (schema.path("additionalProperties").isBoolean()
                        && !schema.path("additionalProperties").booleanValue()) invalid(path, "unknown property " + name);
            });
        }
        if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.path("minItems").intValue()) invalid(path, "too few items");
            if (schema.has("maxItems") && value.size() > schema.path("maxItems").intValue()) invalid(path, "too many items");
            if (schema.has("items")) for (int index = 0; index < value.size(); index++)
                validate(value.get(index), schema.path("items"), root, path + "[" + index + "]");
        }
    }

    private static boolean matchesType(JsonNode value, JsonNode type) {
        if (type.isArray()) {
            for (JsonNode candidate : type) if (matchesType(value, candidate)) return true;
            return false;
        }
        return switch (type.textValue()) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
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
}
