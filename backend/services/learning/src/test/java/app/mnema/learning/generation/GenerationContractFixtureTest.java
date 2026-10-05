package app.mnema.learning.generation;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.catalog.content.NativeMediaReferences;
import app.mnema.learning.catalog.exercise.ExerciseCommand;
import app.mnema.learning.platform.json.ContentJsonReader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static app.mnema.learning.support.ContractFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The AI-00 contracts ({@code contracts/generation}, {@code contracts/usage}, {@code contracts/notifications}) and the
 * prompt skeleton are executable: fixtures are read by the repository's own parsers and the tables that humans read are
 * compared with the JSON that machines read. No runtime code exists yet, so nothing here calls a compiler; it checks the
 * golden outputs and the shapes the future compilers must reproduce.
 */
class GenerationContractFixtureTest {
    private static final Path ROOT = repositoryRoot();
    private static final Path GENERATION = ROOT.resolve("contracts/generation");
    private static final Path USAGE = ROOT.resolve("contracts/usage");
    private static final Path NOTIFICATIONS = ROOT.resolve("contracts/notifications");
    private static final Path MBM = GENERATION.resolve("mbm-v1");
    private static final Path EXERCISES = GENERATION.resolve("exercises");
    private static final Path PROMPTS = ROOT.resolve("backend/services/learning/src/main/resources/ai/prompts");
    private static final ContentJsonReader JSON = new ContentJsonReader(4_194_304, 64, 2_000_000);
    private static final NativeDocumentReader NATIVE = new NativeDocumentReader();
    private static final String RESERVED_ROOT = "00000000-0000-4000-8000-000000000000";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]*)}}");
    private static final Pattern PLACEHOLDER_SYNTAX = Pattern.compile("[a-z][a-z_]*(\\.[a-z][a-z_]*)*(\\|\"[^\"{}]*\")?");

    @Test
    void everyContractJsonFileIsWellFormedAndFreeOfDuplicateKeys() throws IOException {
        List<Path> files = jsonFiles(GENERATION, USAGE, NOTIFICATIONS);
        assertThat(files).hasSizeGreaterThan(100);
        for (Path file : files) json(file);
    }

    @Test
    void everyDollarRefResolvesToAnExistingValue() throws IOException {
        int references = 0;
        for (Path file : jsonFiles(GENERATION, USAGE, NOTIFICATIONS)) {
            references += resolveReferences(file, json(file));
        }
        assertThat(references).isGreaterThan(10);
    }

    // ------------------------------------------------------------------ MBM v1

    @Test
    void everyValidMbmGoldenDocumentIsAcceptedByTheNativeReaderWithDeterministicIds() throws IOException {
        List<Path> cases = files(MBM.resolve("valid"), ".mbm");
        assertThat(cases.size()).isGreaterThanOrEqualTo(15);
        Set<String> nodeTypes = new TreeSet<>();
        for (Path mbm : cases) {
            String name = baseName(mbm, ".mbm");
            Path nativeFile = MBM.resolve("valid/" + name + ".native.json");
            Path meta = MBM.resolve("valid/" + name + ".meta.json");
            assertThat(nativeFile).as(name + " golden document").exists();
            assertThat(meta).as(name + " meta").exists();
            assertThat(Files.readString(mbm, StandardCharsets.UTF_8)).as(name + " source").isNotBlank();

            NativeDocument document = NATIVE.read(Files.readAllBytes(nativeFile));
            assertThat(document.hasUnsupportedContent()).as(name).isFalse();

            JsonNode metadata = json(meta);
            int slots = metadata.path("expected").path("slots").size();
            assertThat(NativeMediaReferences.from(document)).as(name + " media references").hasSize(slots);

            boolean edit = "EDIT".equals(metadata.path("input").path("mode").stringValue(null));
            Set<String> preserved = new HashSet<>();
            metadata.path("input").path("handles").properties()
                    .forEach(handle -> preserved.add(handle.getValue().path("nodeId").stringValue(null)));
            List<String> ids = new ArrayList<>();
            List<String> assets = new ArrayList<>();
            preOrder(json(nativeFile).path("root"), ids, assets, nodeTypes);
            assertThat(ids.get(0)).as(name + " root id").isEqualTo(edit ? RESERVED_ROOT : sequential("8000", 1));
            int counter = edit ? 0 : 1;
            for (int index = 1; index < ids.size(); index++) {
                if (preserved.contains(ids.get(index))) continue;
                assertThat(ids.get(index)).as(name + " allocator order").isEqualTo(sequential("8000", ++counter));
            }
            assertMbmStructure(name, Files.readString(mbm, StandardCharsets.UTF_8), json(nativeFile), slots);
            for (JsonNode warning : metadata.path("expected").path("warnings")) {
                assertThat(warning.path("line").intValue()).as(name + " warning line").isBetween(0, sourceLines(Files.readString(mbm, StandardCharsets.UTF_8)).size());
            }
            for (int index = 0; index < assets.size(); index++) {
                assertThat(assets.get(index)).as(name + " asset order").isEqualTo(sequential("a000", index + 1));
            }
            if (name.equals("edit-range")) {
                assertThat(ids).contains("7d000000-0000-4000-8000-000000000003", "7d000000-0000-4000-8000-000000000004");
            }
        }
        // every node type the compiler can emit is covered; youtube has no MBM directive
        assertThat(nodeTypes).containsAll(List.of("doc", "paragraph", "heading", "blockquote", "bullet_list", "ordered_list",
                "list_item", "text", "ruby", "link", "divider", "image", "audio", "video", "table", "mermaid"));
    }

    @Test
    void mbmFixtureFilesComeInCompleteSetsAndCoverEveryDirectiveAndEveryErrorCode() throws IOException {
        JsonNode codes = json(MBM.resolve("codes.json")).path("codes");
        Map<String, JsonNode> byCode = new LinkedHashMap<>();
        codes.forEach(code -> assertThat(byCode.put(code.path("code").stringValue(null), code)).isNull());

        Set<String> usedErrors = new TreeSet<>();
        for (Path mbm : files(MBM.resolve("invalid"), ".mbm")) {
            String name = baseName(mbm, ".mbm");
            assertThat(MBM.resolve("invalid/" + name + ".meta.json")).exists();
            JsonNode errors = json(MBM.resolve("invalid/" + name + ".errors.json"));
            assertThat(errors.isArray() && errors.size() > 0).as(name).isTrue();
            long previousLine = 0;
            long previousColumn = 0;
            for (JsonNode error : errors) {
                String code = error.path("code").stringValue(null);
                assertThat(byCode).as(name).containsKey(code);
                assertThat(byCode.get(code).path("severity").stringValue(null)).as(name).isEqualTo("ERROR");
                usedErrors.add(code);
                long line = error.path("line").longValue();
                long column = error.has("column") ? error.path("column").longValue() : 0;
                assertThat(line).as(name + " line").isBetween(1L, (long) sourceLines(Files.readString(mbm, StandardCharsets.UTF_8)).size());
                assertThat(line > previousLine || (line == previousLine && column >= previousColumn))
                        .as(name + " findings are ordered by line then column").isTrue();
                previousLine = line;
                previousColumn = column;
            }
        }
        Set<String> usedWarnings = new TreeSet<>();
        for (Path meta : files(MBM.resolve("valid"), ".meta.json")) {
            for (JsonNode warning : json(meta).path("expected").path("warnings")) {
                String code = warning.path("code").stringValue(null);
                assertThat(byCode).containsKey(code);
                assertThat(byCode.get(code).path("severity").stringValue(null)).isEqualTo("WARNING");
                usedWarnings.add(code);
            }
        }
        for (JsonNode code : codes) {
            String name = code.path("code").stringValue(null);
            assertThat(name).startsWith("MBM_");
            if (code.path("fixtureExempt").booleanValue(false)) continue;
            boolean warning = "WARNING".equals(code.path("severity").stringValue(null));
            assertThat(warning ? usedWarnings : usedErrors).as("fixture for " + name).contains(name);
        }

        // directives: each appears in a valid source
        String valid = String.join("\n", readAll(files(MBM.resolve("valid"), ".mbm")));
        for (String directive : List.of("::table", "::mermaid", "::audio", "::image{slot=\"i1\" mode=\"search\"", "::image{slot=\"i1\" mode=\"generate\"",
                "::video", "::sources")) {
            assertThat(valid).as(directive).contains(directive);
        }
        // the README names every code
        String readme = Files.readString(MBM.resolve("README.md"));
        assertThat(readme).contains("codes.json").contains("#303");
        byCode.keySet().forEach(code -> assertThat(readme).as("README table names " + code).contains("`" + code + "`"));
    }

    // ------------------------------------------------------------ exercises

    @Test
    void exerciseFixturesMatchTheOutputSchemaAndTheSharedPublicationParser() throws IOException {
        JsonNode schema = json(EXERCISES.resolve("output.schema.json"));
        assertKnownKeywords(schema, "$");
        JsonNode lint = json(EXERCISES.resolve("lint.json"));
        Set<String> lintCodes = new TreeSet<>();
        Set<String> exempt = new TreeSet<>();
        lint.path("codes").forEach(code -> {
            lintCodes.add(code.path("code").stringValue(null));
            if (code.path("fixtureExempt").booleanValue(false)) exempt.add(code.path("code").stringValue(null));
        });
        Set<String> mechanics = new TreeSet<>();
        Set<String> coveredLint = new TreeSet<>();

        List<Path> fixtures = files(EXERCISES.resolve("fixtures"), ".json");
        assertThat(fixtures.size()).isGreaterThanOrEqualTo(28);
        for (Path file : fixtures) {
            String name = file.getFileName().toString();
            JsonNode fixture = json(file);
            assertThat(fixture.path("description").stringValue("")).as(name).isNotBlank();
            JsonNode output = fixture.path("modelOutput");
            JsonNode lintExpected = fixture.path("expectedLint");
            boolean schemaFailure = lintExpected.isArray() && lintExpected.size() == 1
                    && "SCHEMA_INVALID".equals(lintExpected.get(0).stringValue(null));
            if (schemaFailure) {
                assertThatThrownBy(() -> validate(output, schema, schema, "$")).as(name).isInstanceOf(IllegalArgumentException.class);
            } else {
                validate(output, schema, schema, "$");
            }
            assertThat(fixture.has("expectedCommand")).as(name + " has exactly one expectation")
                    .isNotEqualTo(fixture.has("expectedLint"));

            if (fixture.has("expectedLint")) {
                List<String> codes = new ArrayList<>();
                lintExpected.forEach(code -> codes.add(code.stringValue(null)));
                assertThat(codes).as(name).isNotEmpty().isSorted().doesNotHaveDuplicates();
                assertThat(lintCodes).as(name).containsAll(codes);
                coveredLint.addAll(codes);
                continue;
            }

            JsonNode command = fixture.path("expectedCommand");
            ExerciseCommand parsed = ExerciseCommand.readCreate(bytes(command));
            String mechanic = output.path("exercises").get(0).path("mechanic").stringValue(null);
            assertThat(parsed.exercise().type().name()).as(name).isEqualTo(mechanic);
            assertThat(parsed.exercise().content().toString()).isEqualTo(command.path("exercise").path("content").toString());
            assertThat(parsed.exercise().answerKey().toString()).isEqualTo(command.path("exercise").path("answerKey").toString());
            assertThat(command.path("exercise").path("schemaVersion").intValue()).isEqualTo(2);
            assertThat(command.path("exercise").has("bindings")).isFalse();
            assertThat(command.path("commandId").stringValue(null)).isEqualTo(fixture.path("context").path("commandId").stringValue(null));
            mechanics.add(mechanic);
            assertMaterialBlocksResolveThroughTheContext(name, fixture);
            assertProbesAgreeWithTheKey(name, mechanic, fixture);
        }
        assertThat(mechanics).containsExactlyInAnyOrder("SELF_CHECK", "FREE_RESPONSE", "CLOZE", "CHOICE", "MATCH", "ORDER", "CATEGORIZE");
        Set<String> uncovered = new TreeSet<>(lintCodes);
        uncovered.removeAll(coveredLint);
        uncovered.removeAll(exempt);
        assertThat(uncovered).as("lint codes without a failing fixture").isEmpty();
    }

    private static void assertMaterialBlocksResolveThroughTheContext(String name, JsonNode fixture) {
        Set<String> nodeIds = new HashSet<>();
        fixture.path("context").path("materials").properties().forEach(material -> material.getValue().path("blocks").properties()
                .forEach(block -> nodeIds.add(block.getValue().path("nodeId").stringValue(null))));
        collectMaterialNodes(fixture.path("expectedCommand").path("exercise").path("content"), nodeIds, name);
    }

    private static void collectMaterialNodes(JsonNode node, Set<String> nodeIds, String name) {
        if (node.isObject() && "MATERIAL".equals(node.path("kind").stringValue(null))) {
            assertThat(nodeIds).as(name + " material node").contains(node.path("nodeId").stringValue(null));
        }
        node.forEach(child -> collectMaterialNodes(child, nodeIds, name));
    }

    private static void assertProbesAgreeWithTheKey(String name, String mechanic, JsonNode fixture) {
        JsonNode probes = fixture.path("expectedSelfEvaluation");
        assertThat(probes.isArray()).as(name).isTrue();
        JsonNode idMap = fixture.path("expectedIdMap");
        JsonNode key = fixture.path("expectedCommand").path("exercise").path("answerKey");
        if ("SELF_CHECK".equals(mechanic)) {
            assertThat(probes.size()).as(name + ": a self-check has no machine-checkable key").isZero();
            return;
        }
        assertThat(probes.size()).as(name).isGreaterThanOrEqualTo(2);
        boolean keyProbe = false;
        for (JsonNode probe : probes) {
            assertThat(List.of("CORRECT", "PARTIAL", "INCORRECT")).contains(probe.path("expectedResult").stringValue(null));
            if (!"KEY".equals(probe.path("probe").stringValue(null))) continue;
            keyProbe = true;
            assertThat(probe.path("expectedResult").stringValue(null)).as(name).isEqualTo("CORRECT");
            JsonNode response = probe.path("response");
            switch (mechanic) {
                case "CHOICE" -> assertThat(translateAll(response.path("optionIds"), idMap))
                        .containsExactlyInAnyOrderElementsOf(strings(key.path("correctOptionIds")));
                case "ORDER" -> assertThat(translateAll(response.path("sequence"), idMap)).isEqualTo(strings(key.path("sequence")));
                case "MATCH" -> assertThat(pairs(response.path("pairs"), "leftId", "rightId", idMap))
                        .isEqualTo(pairs(key.path("pairs"), "leftId", "rightId", null));
                case "CATEGORIZE" -> assertThat(pairs(response.path("assignments"), "itemId", "categoryId", idMap))
                        .isEqualTo(pairs(key.path("assignments"), "itemId", "categoryId", null));
                case "CLOZE" -> {
                    Map<String, String> answers = new LinkedHashMap<>();
                    key.path("blanks").forEach(blank -> answers.put(blank.path("blankId").stringValue(null),
                            blank.path("accepted").get(0).stringValue(null)));
                    Map<String, String> given = new LinkedHashMap<>();
                    response.path("blanks").forEach(blank -> given.put(translate(blank.path("blankId").stringValue(null), idMap),
                            blank.path("text").stringValue(null)));
                    assertThat(given).isEqualTo(answers);
                }
                default -> assertThat(response.path("kind").stringValue(null)).isEqualTo("TEXT");
            }
        }
        assertThat(keyProbe).as(name + " has a KEY probe").isTrue();
    }

    // ------------------------------------------------- cross-file consistency

    @Test
    void stateMachinesAreClosedAndOnlyNameOperationsAndCodesThatExist() throws IOException {
        JsonNode states = json(GENERATION.resolve("states.json"));
        JsonNode http = json(GENERATION.resolve("http.json"));
        JsonNode errors = json(GENERATION.resolve("errors.json")).path("codes");

        for (String machine : List.of("session", "artifact", "step", "turn", "mediaSlot")) {
            JsonNode definition = states.path(machine);
            Set<String> names = new TreeSet<>();
            Set<String> terminal = new TreeSet<>();
            definition.path("states").properties().forEach(state -> {
                names.add(state.getKey());
                if (state.getValue().path("terminal").booleanValue(false)) terminal.add(state.getKey());
            });
            for (JsonNode transition : definition.path("transitions")) {
                String from = transition.path("from").stringValue(null);
                String to = transition.path("to").stringValue(null);
                if (from != null) {
                    assertThat(names).as(machine + " from " + from).contains(from);
                    assertThat(terminal).as(machine + ": no transition leaves a terminal state: " + from).doesNotContain(from);
                }
                assertThat(names).as(machine + " to " + to).contains(to);
                assertThat(transition.path("trigger").stringValue("")).isNotBlank();
                assertThat(transition.path("actor").stringValue("")).isNotBlank();
            }
        }
        assertThat(states.path("session").path("states").size()).isEqualTo(7);
        assertThat(states.path("artifact").path("states").size()).isEqualTo(9);

        Map<String, JsonNode> operations = new LinkedHashMap<>();
        http.path("endpoints").forEach(endpoint -> assertThat(operations.put(endpoint.path("operationId").stringValue(null), endpoint)).isNull());
        for (String section : List.of("session", "artifact")) {
            states.path(section).path("allowedOperations").properties().forEach(entry -> {
                assertThat(states.path(section).path("states").has(entry.getKey())).isTrue();
                entry.getValue().forEach(operation -> assertThat(operations).containsKey(operation.stringValue(null)));
            });
        }
        Set<String> allOperations = new HashSet<>(operations.keySet());
        for (JsonNode endpoint : operations.values()) {
            for (JsonNode error : endpoint.path("errors")) {
                String code = error.path("code").stringValue(null);
                assertThat(errors.has(code)).as(endpoint.path("operationId").stringValue(null) + " names " + code).isTrue();
                assertThat(error.path("status").intValue()).as(code).isEqualTo(errors.path(code).path("status").intValue());
            }
        }
        // the AI operations of the handoff are all present
        assertThat(allOperations).contains("estimateGeneration", "createSession", "listSessions", "getSession", "cancelSession",
                "deleteSession", "listEvents", "getArtifact", "approveArtifact", "approveArtifacts", "rejectArtifact",
                "undoRejectArtifact", "handoffArtifact", "editArtifact", "revertArtifact");
        for (String code : List.of("USAGE_LIMIT_REACHED", "EDIT_IN_PROGRESS", "CAPABILITY_UNAVAILABLE", "SOURCE_UNAVAILABLE")) {
            assertThat(errors.has(code)).isTrue();
            assertThat(errors.path(code).path("type").stringValue(null)).startsWith("urn:mnema:problem:");
        }
        errors.properties().forEach(entry -> assertThat(entry.getValue().path("type").stringValue(null))
                .isEqualTo("urn:mnema:problem:" + entry.getKey().toLowerCase().replace('_', '-')));

        // every code lists exactly the operations that list it (generic codes are marked [all])
        Map<String, Set<String>> listedBy = new LinkedHashMap<>();
        operations.forEach((operation, endpoint) -> endpoint.path("errors").forEach(error ->
                listedBy.computeIfAbsent(error.path("code").stringValue(null), key -> new TreeSet<>()).add(operation)));
        errors.properties().forEach(entry -> {
            Set<String> declared = new TreeSet<>(strings(entry.getValue().path("endpoints")));
            if (declared.equals(Set.of("all"))) return;
            assertThat(listedBy.getOrDefault(entry.getKey(), Set.of())).as(entry.getKey() + " endpoints").isEqualTo(declared);
        });
        assertThat(operations.get("listActiveSessions").path("path").stringValue(null)).isEqualTo("/api/generation-sessions");

        // every non-initial state is reachable from an initial state
        for (String machine : List.of("session", "artifact", "step", "turn", "mediaSlot")) {
            JsonNode definition = states.path(machine);
            Set<String> reached = new HashSet<>();
            definition.path("initial").forEach(initial -> reached.add(initial.isString() ? initial.stringValue(null) : initial.path("state").stringValue(null)));
            for (JsonNode transition : definition.path("transitions")) {
                if (transition.path("from").isNull() || transition.path("from").isMissingNode()) reached.add(transition.path("to").stringValue(null));
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (JsonNode transition : definition.path("transitions")) {
                    String from = transition.path("from").stringValue(null);
                    if (from != null && reached.contains(from)) changed |= reached.add(transition.path("to").stringValue(null));
                }
            }
            Set<String> all = new TreeSet<>();
            definition.path("states").propertyNames().forEach(all::add);
            assertThat(reached).as(machine + " reachability").containsAll(all);
        }
    }

    @Test
    void eventsCarryOneExamplePerTypeWithIncreasingIds() throws IOException {
        JsonNode events = json(GENERATION.resolve("events.json"));
        Set<String> types = new TreeSet<>();
        long previous = 0;
        for (JsonNode event : events.path("envelope").path("example").path("events")) {
            long id = Long.parseLong(event.path("seq").stringValue(null));
            assertThat(event.has("eventId")).isFalse();
            assertThat(id).isGreaterThan(previous);
            previous = id;
            types.add(event.path("type").stringValue(null));
            assertThat(event.path("sessionId").isString()).isTrue();
        }
        assertThat(types).containsExactlyInAnyOrder("ARTIFACT_STATE", "BLOCKS_APPENDED", "MEDIA_SLOT_STATE", "USAGE_UPDATED", "SESSION_STATE");
        assertThat(events.path("envelope").path("example").path("cursor").stringValue(null)).isEqualTo(Long.toString(previous));
        JsonNode blocks = events.path("types").path("BLOCKS_APPENDED").path("example").path("payload").path("blocks");
        for (JsonNode block : blocks) {
            assertThat(block.path("type").isString()).isTrue();
        }
        assertThat(events.path("types").size()).isEqualTo(5);
        assertThat(events.path("description").stringValue(null)).contains("bigserial").contains("row lock");
    }

    @Test
    void theRateCardAndAllowancesMatchTheTablesInTheUsageReadme() throws IOException {
        JsonNode card = json(USAGE.resolve("rate-card-v1.json"));
        JsonNode allowances = json(USAGE.resolve("allowances-v1.json"));
        JsonNode usage = json(USAGE.resolve("usage.json"));
        assertThat(card.path("rateCardVersion").stringValue(null)).isEqualTo("rc-v1");
        assertThat(allowances.path("rateCardVersion").stringValue(null)).isEqualTo("rc-v1");
        assertThat(usage.path("usageResponse").path("rateCardVersion").stringValue(null)).isEqualTo("rc-v1");
        assertThat(usage.path("estimateResponse").path("rateCardVersion").stringValue(null)).isEqualTo("rc-v1");
        assertThat(card.path("revisionPolicy").stringValue(null)).contains("14 days").contains("never applies retroactively");

        String readme = Files.readString(USAGE.resolve("README.md"));
        Map<String, List<String>> rows = tableRows(readme, "| Operation |");
        Set<String> operations = new TreeSet<>();
        for (JsonNode operation : card.path("operations")) {
            String id = operation.path("id").stringValue(null);
            operations.add(id);
            assertThat(rows).as("README row for " + id).containsKey(id);
            List<String> cells = rows.get(id);
            JsonNode credits = operation.path("credits");
            String expected = "FAIR_USE".equals(operation.path("pricing").stringValue(null)) ? "fair-use"
                    : credits.isObject() ? credits.path("min").intValue() + "-" + credits.path("max").intValue()
                    : Integer.toString(credits.intValue());
            assertThat(cells.get(0)).as(id + " credits").isEqualTo(expected);
            assertThat(cells.get(1)).as(id + " cap").isEqualTo(operation.path("cap").isNull() ? "-" : operation.path("cap").stringValue(null));
            assertThat(cells.get(2)).as(id + " availability").isEqualTo(operation.path("availability").stringValue(null));
        }
        assertThat(rows.keySet()).as("README rows are exactly the rate card operations").isEqualTo(operations);
        Map<String, List<String>> plans = tableRows(readme, "| Plan |");
        assertThat(plans.keySet()).isEqualTo(Set.of("FREE", "PLUS", "PRO", "MAX"));
        allowances.path("plans").properties().forEach(entry -> {
            String plan = entry.getKey();
            JsonNode node = entry.getValue();
            List<String> cells = plans.get(plan);
            JsonNode stt = node.path("stt");
            String expectedStt = stt.path("minutesPerMonth").isNull()
                    ? "unlimited (velocity " + stt.path("velocityMinutesPerDay").intValue() + "/day)"
                    : stt.path("minutesPerMonth").intValue() + " (" + stt.path("minutesPerDay").intValue() + ")";
            JsonNode smart = node.path("smartPlan");
            String expectedSmart = smart.isNull() ? "none" : smart.has("perMonth") ? smart.path("perMonth").intValue() + " / month"
                    : smart.has("proPlansPerMonth") ? "weekly + " + smart.path("proPlansPerMonth").intValue() + " Pro" : "weekly";
            assertThat(cells).as(plan).containsExactly(Integer.toString(node.path("priceRubPerMonth").intValue()),
                    Integer.toString(node.path("monthlyCredits").intValue()), expectedStt,
                    node.path("assessment").path("answersPerMonth").intValue() + " (" + node.path("assessment").path("answersPerDay").intValue() + ")",
                    Integer.toString(node.path("caps").path("podcasts").intValue()), Integer.toString(node.path("caps").path("qualityImages").intValue()),
                    Integer.toString(node.path("caps").path("highFactcheck").intValue()), expectedSmart);
        });
        JsonNode free = allowances.path("plans").path("FREE").path("creditSchedule");
        int portions = 0;
        for (JsonNode portion : free.path("portions")) portions += portion.intValue();
        assertThat(portions).as("Free portions sum to the bar").isEqualTo(allowances.path("plans").path("FREE").path("monthlyCredits").intValue());
        assertThat(allowances.path("calendar").path("zone").stringValue(null)).isEqualTo("Europe/Moscow");
        assertThat(card.path("capBuckets").propertyNames()).contains("smartPlan", "stt", "assessment");
        card.path("operations").forEach(operation -> {
            if (!operation.path("cap").isNull()) assertThat(card.path("capBuckets").has(operation.path("cap").stringValue(null))).as(operation.path("id").stringValue(null)).isTrue();
        });

        // balance and percent arithmetic of the examples (holds included)
        for (String example : List.of("usageResponse", "usageResponseFree")) {
            JsonNode credits = usage.path(example).path("credits");
            int used = credits.path("used").intValue();
            int reserved = credits.path("reserved").intValue();
            assertThat(credits.path("remaining").intValue()).as(example).isEqualTo(credits.path("unlocked").intValue() - used - reserved);
            assertThat(credits.path("percentUsed").intValue()).as(example).isEqualTo((int) Math.round((used + reserved) * 100.0 / credits.path("total").intValue()));
        }
        for (String example : List.of("estimateResponse", "estimateResponseShortfall", "estimateResponseFreeBlocked")) {
            JsonNode estimate = usage.path(example);
            int total = 0;
            for (JsonNode line : estimate.path("breakdown")) total += line.path("credits").intValue();
            assertThat(estimate.path("credits").path("p95").intValue()).as(example).isEqualTo(total);
            assertThat(estimate.path("credits").path("p50").intValue()).as(example + " p50 = ceil(0.6 x weights)").isEqualTo((int) Math.ceil(total * 0.6 - 1e-9));
            assertThat(estimate.path("canStart").booleanValue()).as(example).isEqualTo(estimate.path("blockingBuckets").isEmpty());
        }
        JsonNode reservation = usage.path("reservation");
        assertThat(reservation.path("scopes").propertyNames()).containsExactlyInAnyOrder("SESSION", "TURN", "STEP");
        assertThat(reservation.path("states").propertyNames()).containsExactlyInAnyOrder("ACTIVE", "SETTLED", "RELEASED", "EXPIRED");
        assertThat(reservation.path("example").path("periodId").stringValue(null)).isEqualTo(usage.path("usageResponse").path("period").path("periodId").stringValue(null));
        assertThat(reservation.path("neverNegative").stringValue(null)).contains("available >= :hold");
        for (String example : List.of("example", "exampleFreeWeek", "exampleNotOffered")) {
            JsonNode problem = usage.path("errors").path("USAGE_LIMIT_REACHED").path(example);
            assertThat(problem.propertyNames()).contains("bucket", "window", "unit", "limit", "used", "required", "offered", "renewsAt", "fitsAfterRenewal");
        }
        assertThat(usage.path("usageResponseMaxFairUse").path("stt").path("limit").isNull()).isTrue();
    }

    @Test
    void theNotificationKindsMatchTheReadmeAndTheirExamples() throws IOException {
        JsonNode notifications = json(NOTIFICATIONS.resolve("notifications.json"));
        Map<String, List<String>> rows = tableRows(Files.readString(NOTIFICATIONS.resolve("README.md")), "| Kind |");
        Set<String> kinds = new TreeSet<>();
        notifications.path("kinds").properties().forEach(entry -> {
            String kind = entry.getKey();
            JsonNode definition = entry.getValue();
            kinds.add(kind);
            assertThat(rows).as("README row for " + kind).containsKey(kind);
            List<String> cells = rows.get(kind);
            assertThat(cells.get(0)).as(kind).isEqualTo(definition.path("severity").stringValue(null));
            assertThat(cells.get(1)).as(kind).isEqualTo(definition.path("route").stringValue(null));
            Set<String> declared = new TreeSet<>();
            definition.path("params").propertyNames().forEach(declared::add);
            Set<String> inReadme = new TreeSet<>();
            Matcher matcher = Pattern.compile("`([A-Za-z]+)`").matcher(cells.get(2));
            while (matcher.find()) inReadme.add(matcher.group(1));
            assertThat(inReadme).as(kind + " params").isEqualTo(declared);
            JsonNode example = definition.path("example");
            assertThat(example.path("kind").stringValue(null)).isEqualTo(kind);
            assertThat(example.path("severity").stringValue(null)).isEqualTo(definition.path("severity").stringValue(null));
            if ("DYNAMIC".equals(definition.path("route").stringValue(null))) {
                assertThat(List.of("WORKSHOP", "DECK", "NONE")).contains(example.path("route").stringValue(null));
            } else {
                assertThat(example.path("route").stringValue(null)).isEqualTo(definition.path("route").stringValue(null));
            }
            assertThat(Instant.parse(example.path("expiresAt").stringValue(null)))
                    .as(kind + " expiresAt = createdAt + 30 days").isEqualTo(Instant.parse(example.path("createdAt").stringValue(null)).plus(30, ChronoUnit.DAYS));
            Set<String> given = new TreeSet<>();
            example.path("params").propertyNames().forEach(given::add);
            assertThat(given).as(kind + " example params").isEqualTo(declared);
            assertThat(example.path("params").toString().getBytes(StandardCharsets.UTF_8).length).isLessThan(4096);
        });
        assertThat(kinds).containsExactlyInAnyOrder("GENERATION_PLAN_READY", "GENERATION_READY", "GENERATION_PARTIAL", "GENERATION_FAILED",
                "USAGE_LOW", "USAGE_EXHAUSTED", "GENERATION_SESSION_EXPIRING", "MEDIA_PROCESSING_FAILED");
        for (String list : List.of("listResponse", "listResponseAfter")) {
            long previous = list.equals("listResponse") ? Long.MAX_VALUE : 0;
            for (JsonNode item : notifications.path(list).path("items")) {
                long seq = Long.parseLong(item.path("seq").stringValue(null));
                assertThat(list.equals("listResponse") ? seq < previous : seq > previous).as(list + " order").isTrue();
                previous = seq;
                assertThat(Instant.parse(item.path("expiresAt").stringValue(null)))
                        .isEqualTo(Instant.parse(item.path("createdAt").stringValue(null)).plus(30, ChronoUnit.DAYS));
            }
        }
        assertThat(rows.keySet()).isEqualTo(kinds);
        assertThat(notifications.path("model").path("fields").path("params").stringValue(null)).contains("No server-rendered prose");
    }

    // --------------------------------------------------------------- prompts

    @Test
    void everyPromptSectionHasFrontMatterAndOnlyWellFormedPlaceholders() throws IOException {
        Path version = PROMPTS.resolve("v1");
        List<Path> sections = files(version, ".md");
        Set<String> names = new TreeSet<>();
        for (Path file : sections) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String name = version.relativize(file).toString();
            assertThat(text).as(name).startsWith("---\n");
            int end = text.indexOf("\n---\n", 4);
            assertThat(end).as(name + " front matter is closed").isPositive();
            Map<String, String> front = new LinkedHashMap<>();
            for (String line : text.substring(4, end).split("\n")) {
                int colon = line.indexOf(':');
                assertThat(colon).as(name + " front matter line").isPositive();
                front.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
            assertThat(front.keySet()).as(name).containsExactly("section", "prompt_version", "purpose");
            assertThat(front.get("prompt_version").replaceAll("\\s+#.*$", "")).as(name).isEqualTo("v1");
            assertThat(front.get("purpose")).as(name).isNotBlank();
            assertThat(names.add(front.get("section").replaceAll("\\s+#.*$", ""))).as(name + " section name is unique").isTrue();
            String body = text.substring(end + 5);
            assertThat(body).as(name).isNotBlank();

            boolean runtimeFilled = List.of("deck-brief.md", "material.md", "edit.md", "exercises.md", "assessment.md", "intent.md",
                    "exercise-edit.md", "plan.md", "research.md").contains(name);
            Matcher matcher = PLACEHOLDER.matcher(body);
            int count = 0;
            while (matcher.find()) {
                count++;
                assertThat(matcher.group(1)).as(name + " placeholder").matches(PLACEHOLDER_SYNTAX);
            }
            if (runtimeFilled) assertThat(count).as(name + " placeholders").isPositive();
            else assertThat(count).as(name + " is a byte-stable section without placeholders").isZero();
            assertThat(body).as(name).doesNotContain("::verify");
        }
        assertThat(names).containsExactlyInAnyOrder("system", "style", "skill-vocabulary", "skill-grammar", "skill-stem-concept",
                "skill-code", "skill-exam-summary", "deck-brief", "material", "edit", "exercises", "assessment", "intent", "exercise-edit", "plan", "research");
        assertThat(Files.readString(PROMPTS.resolve("README.md"))).contains("{{path.to.value}}");
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> sourceLines(String source) {
        List<String> lines = new ArrayList<>(List.of(source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        return lines;
    }

    /** Directive lines equal the slots, heading lines (plus the one a sources directive adds) equal the heading nodes. */
    private static void assertMbmStructure(String name, String source, JsonNode document, int slots) {
        Pattern media = Pattern.compile("^(\\[\\[[a-z][0-9]+]] )?::(audio|image|video)\\{.*");
        Pattern heading = Pattern.compile("^(\\[\\[[a-z][0-9]+]] )?#{1,3} .*");
        int mediaLines = 0;
        int headingLines = 0;
        boolean sources = false;
        for (String line : sourceLines(source)) {
            if (media.matcher(line).matches()) mediaLines++;
            if (heading.matcher(line).matches()) headingLines++;
            if (line.startsWith("::sources")) sources = true;
        }
        assertThat(mediaLines).as(name + " media directive lines vs slots").isEqualTo(slots);
        int[] headings = {0};
        countType(document.path("root"), "heading", headings);
        assertThat(headings[0]).as(name + " heading lines vs heading nodes").isEqualTo(headingLines + (sources ? 1 : 0));
    }

    private static void countType(JsonNode node, String type, int[] count) {
        if (type.equals(node.path("type").stringValue(null))) count[0]++;
        node.path("content").forEach(child -> countType(child, type, count));
    }

    private static void preOrder(JsonNode node, List<String> ids, List<String> assets, Set<String> types) {
        ids.add(node.path("id").stringValue(null));
        types.add(node.path("type").stringValue(null));
        if (node.path("attrs").has("assetId")) assets.add(node.path("attrs").path("assetId").stringValue(null));
        node.path("content").forEach(child -> preOrder(child, ids, assets, types));
    }

    private static String sequential(String variantGroup, int number) {
        return "00000000-0000-4000-" + variantGroup + "-" + String.format("%012x", number);
    }

    private static Map<String, List<String>> tableRows(String markdown, String headerPrefix) {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        boolean inTable = false;
        for (String line : markdown.split("\n")) {
            if (line.startsWith(headerPrefix)) {
                inTable = true;
                continue;
            }
            if (!inTable) continue;
            if (!line.startsWith("|")) break;
            if (line.startsWith("|---")) continue;
            String[] cells = line.substring(1).split("\\|", -1);
            String first = cells[0].trim();
            if (!first.startsWith("`")) continue;
            List<String> rest = new ArrayList<>();
            for (int index = 1; index < cells.length - 1; index++) rest.add(cells[index].trim());
            rows.put(first.replace("`", ""), rest);
        }
        return rows;
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(value -> result.add(value.stringValue(null)));
        return result;
    }

    private static String translate(String local, JsonNode idMap) {
        assertThat(idMap.has(local)).as("local id " + local).isTrue();
        return idMap.path(local).stringValue(null);
    }

    private static List<String> translateAll(JsonNode array, JsonNode idMap) {
        List<String> result = new ArrayList<>();
        array.forEach(value -> result.add(translate(value.stringValue(null), idMap)));
        return result;
    }

    private static Set<String> pairs(JsonNode array, String first, String second, JsonNode idMap) {
        Set<String> result = new TreeSet<>();
        array.forEach(pair -> {
            String a = pair.path(first).stringValue(null);
            String b = pair.path(second).stringValue(null);
            result.add((idMap == null ? a : translate(a, idMap)) + ">" + (idMap == null ? b : translate(b, idMap)));
        });
        return result;
    }

    /** Resolves every {@code $ref} object of one file; file part is relative to the file, empty means the same file. */
    private static int resolveReferences(Path file, JsonNode root) throws IOException {
        int[] count = {0};
        walkReferences(file, root, root, count);
        return count[0];
    }

    private static void walkReferences(Path file, JsonNode root, JsonNode node, int[] count) throws IOException {
        if (node.isObject() && node.path("$ref").isString()) {
            String reference = node.path("$ref").stringValue(null);
            int hash = reference.indexOf('#');
            String filePart = hash < 0 ? reference : reference.substring(0, hash);
            String pointer = hash < 0 ? "" : reference.substring(hash + 1);
            JsonNode target = filePart.isEmpty() ? root : json(file.getParent().resolve(filePart).normalize());
            assertThat(target.at(pointer).isMissingNode()).as(file.getFileName() + " $ref " + reference).isFalse();
            count[0]++;
        }
        for (JsonNode child : node) walkReferences(file, root, child, count);
    }

    /**
     * Reads through {@link ContentJsonReader} (duplicate keys rejected). The reader accepts object roots only, so an
     * array-rooted fixture (the {@code *.errors.json} lists) is read as the value of a wrapper member and unwrapped.
     */
    private static JsonNode json(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.stripLeading().startsWith("[")) {
                return JSON.read(("{\"value\":" + text + "}").getBytes(StandardCharsets.UTF_8)).path("value");
            }
            return JSON.read(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException(file.toString(), exception);
        } catch (RuntimeException exception) {
            throw new AssertionError("Invalid JSON file " + file, exception);
        }
    }

    private static List<Path> jsonFiles(Path... roots) throws IOException {
        List<Path> result = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(path -> path.toString().endsWith(".json")).sorted().forEach(result::add);
            }
        }
        return result;
    }

    private static List<Path> files(Path directory, String suffix) throws IOException {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(suffix)).sorted().toList();
        }
    }

    private static List<String> readAll(List<Path> files) throws IOException {
        List<String> result = new ArrayList<>();
        for (Path file : files) result.add(Files.readString(file, StandardCharsets.UTF_8));
        return result;
    }

    private static String baseName(Path file, String suffix) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - suffix.length());
    }

    private static Path repositoryRoot() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/generation/states.json"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot find repository root");
        return root;
    }

    // A strict subset of JSON Schema 2020-12: the keywords output.schema.json uses; unknown keywords are ignored by design
    // and the schema file stays within this subset (the same approach as StudyContractFixtureTest).
    private static final Set<String> KNOWN_KEYWORDS = Set.of("$schema", "$id", "$defs", "$ref", "title", "description", "type", "properties",
            "required", "additionalProperties", "items", "minItems", "maxItems", "uniqueItems", "minLength", "maxLength", "pattern",
            "minimum", "maximum", "enum", "const", "oneOf", "anyOf");

    /** Fails loudly on a keyword the mini-validator does not implement, instead of silently ignoring a constraint. */
    private static void assertKnownKeywords(JsonNode schema, String path) {
        if (!schema.isObject()) return;
        schema.propertyNames().forEach(keyword -> {
            if (!KNOWN_KEYWORDS.contains(keyword)) throw new IllegalStateException("Unsupported schema keyword " + keyword + " at " + path);
        });
        for (String container : List.of("properties", "$defs")) {
            schema.path(container).properties().forEach(entry -> assertKnownKeywords(entry.getValue(), path + "/" + container + "/" + entry.getKey()));
        }
        assertKnownKeywords(schema.path("items"), path + "/items");
        for (String combinator : List.of("oneOf", "anyOf")) {
            int index = 0;
            for (JsonNode branch : schema.path(combinator)) assertKnownKeywords(branch, path + "/" + combinator + "/" + index++);
        }
    }

    private static void validate(JsonNode value, JsonNode schema, JsonNode root, String path) {
        if (schema.has("$ref")) {
            validate(value, root.at(schema.path("$ref").stringValue(null).substring(1)), root, path);
            return;
        }
        if (schema.has("oneOf")) {
            int matches = 0;
            for (JsonNode candidate : schema.path("oneOf")) {
                try {
                    validate(value, candidate, root, path);
                    matches++;
                } catch (IllegalArgumentException ignored) {
                    // a oneOf branch is expected to reject non-matching shapes
                }
            }
            if (matches != 1) invalid(path, "must match exactly one schema");
            return;
        }
        if (schema.has("anyOf")) {
            for (JsonNode candidate : schema.path("anyOf")) {
                try {
                    validate(value, candidate, root, path);
                    return;
                } catch (IllegalArgumentException ignored) {
                    // try the next branch
                }
            }
            invalid(path, "must match at least one schema");
        }
        if (schema.has("const") && !value.equals(schema.path("const"))) invalid(path, "does not match const");
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode option : schema.path("enum")) found |= value.equals(option);
            if (!found) invalid(path, "is not in enum");
        }
        if (schema.has("type") && !matchesType(value, schema.path("type"))) invalid(path, "has wrong type");
        if (value.isString()) {
            String text = value.stringValue(null);
            if (schema.has("minLength") && text.length() < schema.path("minLength").intValue()) invalid(path, "too short");
            if (schema.has("maxLength") && text.length() > schema.path("maxLength").intValue()) invalid(path, "too long");
            if (schema.has("pattern") && !text.matches(schema.path("pattern").stringValue(null))) invalid(path, "does not match pattern");
        }
        if (value.isIntegralNumber()) {
            if (schema.has("minimum") && value.longValue() < schema.path("minimum").longValue()) invalid(path, "below minimum");
            if (schema.has("maximum") && value.longValue() > schema.path("maximum").longValue()) invalid(path, "above maximum");
        }
        if (value.isObject()) {
            for (JsonNode required : schema.path("required")) {
                if (!value.has(required.stringValue(null))) invalid(path, "missing required " + required.stringValue(null));
            }
            JsonNode properties = schema.path("properties");
            value.propertyNames().forEach(name -> {
                if (properties.has(name)) validate(value.path(name), properties.path(name), root, path + "." + name);
                else if (schema.path("additionalProperties").isBoolean() && !schema.path("additionalProperties").booleanValue(false)) {
                    invalid(path, "unknown property " + name);
                }
            });
        }
        if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.path("minItems").intValue()) invalid(path, "too few items");
            if (schema.has("maxItems") && value.size() > schema.path("maxItems").intValue()) invalid(path, "too many items");
            if (schema.path("uniqueItems").booleanValue(false)) {
                Set<JsonNode> seen = new HashSet<>();
                value.forEach(item -> {
                    if (!seen.add(item)) invalid(path, "duplicate items");
                });
            }
            if (schema.has("items")) {
                for (int index = 0; index < value.size(); index++) {
                    validate(value.get(index), schema.path("items"), root, path + "[" + index + "]");
                }
            }
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
}
