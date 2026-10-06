package app.mnema.learning.ai.eval;

import app.mnema.learning.ai.eval.GoldenCorpus.Fixture;
import app.mnema.learning.ai.eval.GoldenCorpus.Kind;
import app.mnema.learning.generation.exercise.ExerciseOutputSchema;
import app.mnema.learning.generation.mbm.MbmCompiler;
import app.mnema.learning.generation.mbm.MbmOptions;
import app.mnema.learning.generation.mbm.MbmResult;
import app.mnema.learning.generation.mbm.RandomIdAllocator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The offline contract of the golden eval corpus ({@code contracts/generation/eval}): every fixture file validates against
 * {@code fixture.schema.json}, the composition promised by issue #300 holds, ids are unique, held-out marks are spread, no fixture carries personal
 * data, every document and material compiles as MBM, and every expectation is anchored in its own input. Runs in CI: it needs no network and no key.
 */
class GoldenCorpusTest {
    private static final MbmCompiler COMPILER = new MbmCompiler();
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    private static final Pattern PHONE = Pattern.compile("\\+\\d[\\d\\s()-]{8,}");

    @Test
    void everyFileValidatesAgainstTheSchema() {
        ExerciseOutputSchema schema = ExerciseOutputSchema.of(GoldenCorpus.read("fixture.schema.json"));
        for (Kind kind : Kind.values()) {
            JsonNode file = GoldenCorpus.read("fixtures/" + kind.file());
            assertThat(schema.violations(file)).as(kind.file()).isEmpty();
            assertThat(file.path("kind").stringValue("")).isEqualTo(kind.wire());
            file.path("fixtures").forEach(fixture -> assertThat(fixture.path("kind").stringValue("")).as(fixture.path("id").stringValue("")).isEqualTo(kind.wire()));
        }
    }

    @Test
    void theSchemaRejectsABrokenFixture() {
        ExerciseOutputSchema schema = ExerciseOutputSchema.of(GoldenCorpus.read("fixture.schema.json"));
        JsonNode broken = GoldenCorpus.read("fixtures/notes.json").deepCopy();
        ((ObjectNode) broken.path("fixtures").get(0)).remove("expect");
        assertThat(schema.violations(broken)).isNotEmpty();
        JsonNode unknownMechanic = GoldenCorpus.read("fixtures/exercises.json").deepCopy();
        ((ObjectNode) unknownMechanic.path("fixtures").get(0).path("input")).putArray("mechanics").add("TELEPATHY");
        assertThat(schema.violations(unknownMechanic)).isNotEmpty();
    }

    @Test
    void theCompositionOfTheIssueHolds() {
        List<Fixture> all = GoldenCorpus.load();
        assertThat(all).hasSizeGreaterThanOrEqualTo(300);
        assertThat(all.stream().map(Fixture::id).collect(Collectors.toSet())).as("unique ids").hasSameSizeAs(all);

        List<Fixture> notes = of(all, Kind.MATERIAL_FROM_NOTES);
        assertThat(notes).hasSize(80);
        assertThat(count(notes, Fixture::language)).containsExactlyInAnyOrderEntriesOf(Map.of("ru", 35L, "en", 10L, "fr", 5L, "es", 5L, "ja", 10L, "zh", 8L, "ko", 7L));
        assertThat(notes.stream().filter(fixture -> fixture.tags().contains("injection")).count()).as("10 % of the notes carry an injection line").isEqualTo(8);

        List<Fixture> prompts = of(all, Kind.MATERIAL_FROM_PROMPT);
        assertThat(prompts).hasSize(80);
        Map<String, Set<String>> efforts = prompts.stream().collect(Collectors.groupingBy(fixture -> fixture.input().path("category").stringValue(""),
                TreeMap::new, Collectors.mapping(fixture -> fixture.input().path("effort").stringValue(""), Collectors.toSet())));
        assertThat(efforts.keySet()).containsExactlyInAnyOrder("vocabulary", "grammar", "stem", "code", "exam", "humanities");
        efforts.values().forEach(set -> assertThat(set).as("every category in three efforts").containsExactlyInAnyOrder("SHORT", "MEDIUM", "DETAILED"));

        List<Fixture> edits = of(all, Kind.EDIT);
        assertThat(edits).hasSize(60);
        assertThat(count(edits, fixture -> fixture.input().path("preset").stringValue("free"))).containsKeys("SIMPLER", "SHORTER", "EXAMPLE", "LONGER", "free");
        assertThat(edits.stream().filter(fixture -> fixture.input().has("instruction")).count()).as("free instructions").isGreaterThanOrEqualTo(15);

        List<Fixture> exercises = of(all, Kind.EXERCISE);
        assertThat(exercises).hasSize(80);
        Map<String, Long> mechanics = count(exercises, fixture -> fixture.input().path("mechanics").get(0).stringValue(""));
        assertThat(mechanics.keySet()).containsExactlyInAnyOrder("SELF_CHECK", "FREE_RESPONSE", "CLOZE", "CHOICE", "MATCH", "ORDER", "CATEGORIZE");
        mechanics.values().forEach(size -> assertThat(size).isGreaterThanOrEqualTo(8));
    }

    @Test
    void thirtyPercentOfEveryKindIsHeldOut() {
        for (Kind kind : Kind.values()) {
            List<Fixture> ofKind = of(GoldenCorpus.load(), kind);
            long held = ofKind.stream().filter(Fixture::heldOut).count();
            assertThat((double) held / ofKind.size()).as(kind.wire()).isBetween(0.28, 0.32);
        }
        // held-out fixtures are spread over languages, not one block of the file
        List<Fixture> notes = of(GoldenCorpus.load(), Kind.MATERIAL_FROM_NOTES);
        assertThat(notes.stream().filter(Fixture::heldOut).map(Fixture::language).collect(Collectors.toSet())).containsExactlyInAnyOrder("ru", "en", "fr", "es", "ja", "zh", "ko");
    }

    @Test
    void noFixtureCarriesPersonalData() {
        for (Fixture fixture : GoldenCorpus.load()) {
            String text = fixture.input().toString();
            if (!fixture.tags().contains("personal-data")) {
                assertThat(EMAIL.matcher(text).find()).as(fixture.id() + " has an e-mail address").isFalse();
                assertThat(PHONE.matcher(text).find()).as(fixture.id() + " has a telephone number").isFalse();
            } else {
                // the only addresses and numbers allowed are the reserved example domains and the 555 fiction range
                var emails = EMAIL.matcher(text);
                while (emails.find()) assertThat(emails.group()).matches(".*@example\\.(com|org)");
                var phones = PHONE.matcher(text);
                while (phones.find()) assertThat(phones.group()).contains("555");
            }
        }
    }

    @Test
    void everyDocumentAndMaterialCompiles() {
        for (Fixture fixture : GoldenCorpus.load()) {
            String source = switch (fixture.kind()) {
                case EDIT -> fixture.input().path("document").stringValue("");
                case EXERCISE -> fixture.input().path("material").stringValue("");
                default -> "";
            };
            if (source.isEmpty()) continue;
            assertThat(COMPILER.compile(source, MbmOptions.create(), new RandomIdAllocator())).as(fixture.id()).isInstanceOf(MbmResult.Success.class);
        }
        GoldenCorpus.exemplars().forEach((language, exemplar) ->
                assertThat(COMPILER.compile(exemplar, MbmOptions.create(), new RandomIdAllocator())).as("exemplar " + language).isInstanceOf(MbmResult.Success.class));
        assertThat(GoldenCorpus.exemplars().keySet()).containsExactlyInAnyOrder("ru", "en", "fr", "es", "ja", "zh", "ko");
    }

    @Test
    void everyExpectationIsAnchoredInItsInput() {
        List<String> problems = new java.util.ArrayList<>();
        for (Fixture fixture : GoldenCorpus.load()) {
            String lowerInput = fixture.input().toString().toLowerCase(Locale.ROOT);
            switch (fixture.kind()) {
                case MATERIAL_FROM_NOTES -> {
                    String note = fixture.input().path("note").stringValue("").toLowerCase(Locale.ROOT);
                    for (String term : fixture.expected("mustInclude")) if (!note.contains(term.toLowerCase(Locale.ROOT))) problems.add(fixture.id() + " term " + term);
                    // a leaked piece of personal data came from the note; an injection is checked by a canary or by a marker of the system prompt
                    if (fixture.tags().contains("personal-data")) {
                        for (String forbidden : fixture.expected("forbidden")) assertThat(note).as(fixture.id() + " forbidden " + forbidden).contains(forbidden.toLowerCase(Locale.ROOT));
                    }
                    assertThat(fixture.tags().contains("injection") || fixture.tags().contains("personal-data")).as(fixture.id() + " forbidden entries imply an adversarial tag")
                            .isEqualTo(!fixture.expected("forbidden").isEmpty());
                }
                case EDIT -> {
                    // the terms an edit must preserve are in the document, and the blocks it targets exist
                    String document = fixture.input().path("document").stringValue("").replace("*", "").toLowerCase(Locale.ROOT);
                    for (String term : fixture.expected("preserve")) if (!document.contains(term.toLowerCase(Locale.ROOT))) problems.add(fixture.id() + " preserve " + term);
                    assertThat(document).as(fixture.id() + " target").contains(fixture.input().path("target").path("startsWith").stringValue("").toLowerCase(Locale.ROOT));
                    assertThat(fixture.input().has("preset") || fixture.input().has("instruction")).isTrue();
                    for (String forbidden : fixture.expected("forbidden")) assertThat(lowerInput).as(fixture.id() + " forbidden " + forbidden).contains(forbidden.toLowerCase(Locale.ROOT));
                }
                default -> assertThat(fixture.expected("facts")).as(fixture.id() + " facts").isNotEmpty();
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void theAnswerChecksReferenceTheGoldenSetWithoutCopyingIt() throws IOException {
        JsonNode manifest = GoldenCorpus.answerChecks();
        Path golden = GoldenCorpus.root().resolve(manifest.path("source").stringValue(""));
        int answers = 0;
        Set<String> listed = new HashSet<>();
        for (JsonNode entry : manifest.path("files")) {
            Path file = golden.resolve(entry.path("file").stringValue(""));
            assertThat(file).exists();
            JsonNode exercise = GoldenCorpus.JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            assertThat(exercise.path("answers").size()).as(file.getFileName().toString()).isEqualTo(entry.path("answers").intValue());
            assertThat(exercise.path("exerciseId").stringValue("")).isEqualTo(entry.path("exerciseId").stringValue(""));
            answers += exercise.path("answers").size();
            listed.add(file.getFileName().toString());
        }
        assertThat(answers).isEqualTo(manifest.path("answers").intValue()).isGreaterThanOrEqualTo(120);
        try (Stream<Path> files = Files.list(golden)) {
            assertThat(files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".json")).collect(Collectors.toSet())).as("every golden file is listed").isEqualTo(listed);
        }
        assertThat(manifest.has("answersText")).as("the answers themselves are not duplicated").isFalse();
    }

    private static List<Fixture> of(List<Fixture> all, Kind kind) { return all.stream().filter(fixture -> fixture.kind() == kind).toList(); }

    private static Map<String, Long> count(List<Fixture> fixtures, Function<Fixture, String> key) {
        return fixtures.stream().collect(Collectors.groupingBy(key, TreeMap::new, Collectors.counting()));
    }
}
