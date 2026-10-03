package app.mnema.learning.study.attempt;

import app.mnema.learning.capability.SemanticAssessmentProvider.CriterionGrade;
import app.mnema.learning.capability.SemanticAssessmentProvider.Flag;
import app.mnema.learning.capability.SemanticAssessmentProvider.Run;
import app.mnema.learning.capability.SemanticAssessmentProvider.Verdict;
import app.mnema.learning.catalog.exercise.Rubric;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The golden set of the AI assessment ({@code contracts/study/assessment-golden}, status {@code proposed}: labelled by the agent, the
 * owner reviews it as an input to the AI-17 gate): 12 exercises times 12 answers, each with the verdicts a careful grader gives and the
 * expected judgement at S1, S2 and S3. This is the policy layer, run in CI: the server's aggregation of the recorded verdicts must give
 * the labelled judgement at every strictness. The model layer is {@code SemanticEvalRunner} (opt-in, live).
 */
class AssessmentGoldenPolicyTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> KINDS = List.of("complete", "partial", "off-topic", "bag-of-terms", "misconception", "injection",
            "asr-noise", "other-language", "terse-correct", "verbose-wrong", "injection-with-content", "asr-gradable");

    static Path directory() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("contracts/study/assessment-golden"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot find the golden set");
        return root.resolve("contracts/study/assessment-golden");
    }

    static List<JsonNode> exercises() throws IOException {
        List<JsonNode> exercises = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory())) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                exercises.add(JSON.readTree(Files.readString(file)));
            }
        }
        return exercises;
    }

    /** The rubric of a golden exercise as the publication command carries it (fresh UUIDs, in order), parsed by the strict reader. */
    static Rubric rubric(JsonNode exercise) {
        ObjectNode rubric = JSON.createObjectNode().put("referenceAnswer", exercise.path("rubric").path("referenceAnswer").stringValue(null));
        ArrayNode criteria = rubric.putArray("criteria");
        for (JsonNode criterion : exercise.path("rubric").path("criteria")) {
            criteria.addObject().put("criterionId", UUID.randomUUID().toString()).put("description", criterion.path("description").stringValue(null))
                    .put("tier", criterion.path("tier").stringValue(null)).put("weight", criterion.path("weight").intValue());
        }
        rubric.set("misconceptions", exercise.path("rubric").path("misconceptions").deepCopy());
        rubric.set("acceptableTerms", exercise.path("rubric").path("acceptableTerms").deepCopy());
        return Rubric.read(rubric);
    }

    static Run run(Rubric rubric, JsonNode output) {
        List<CriterionGrade> grades = new ArrayList<>();
        JsonNode criteria = output.path("criteria");
        for (int index = 0; index < rubric.criteria().size(); index++) {
            JsonNode grade = criteria.get(index);
            assertThat(grade.path("id").stringValue(null)).isEqualTo("c" + (index + 1));
            Verdict verdict = Verdict.valueOf(grade.path("verdict").stringValue(null));
            boolean quoted = verdict == Verdict.MET || verdict == Verdict.PARTLY;
            grades.add(new CriterionGrade(rubric.criteria().get(index).criterionId(), verdict,
                    quoted ? grade.path("quote").stringValue(null) : null, grade.path("note").stringValue(null)));
        }
        Set<Flag> flags = EnumSet.noneOf(Flag.class);
        output.path("flags").forEach(flag -> flags.add(Flag.valueOf(flag.stringValue(null))));
        return new Run(grades, flags);
    }

    /** The label the server's aggregation gives: the judgement, or {@code SELF_CHECK} for provider uncertainty. */
    static String label(Rubric rubric, SemanticStrictness strictness, Run recorded, JsonNode answer) {
        return label(rubric, strictness, recorded, answer.path("answerSource").stringValue(null).equals("SPEECH"));
    }

    static String label(Rubric rubric, SemanticStrictness strictness, Run recorded, boolean speech) {
        List<Run> runs = new ArrayList<>();
        for (int index = 0; index < strictness.runs(); index++) runs.add(recorded);
        SemanticPolicy.Outcome outcome = SemanticPolicy.aggregate(rubric, strictness, runs, speech);
        return outcome instanceof SemanticPolicy.Graded graded ? graded.judgement().name() : "SELF_CHECK";
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    @Test
    void theSetHasTwelveExercisesOfTwelveAnswerKindsInTwoLanguagesAndFourDomains() throws IOException {
        List<JsonNode> exercises = exercises();
        assertThat(exercises).hasSize(12);
        Set<String> languages = new TreeSet<>();
        Set<String> domains = new TreeSet<>();
        Set<String> ids = new TreeSet<>();
        for (JsonNode exercise : exercises) {
            assertThat(exercise.path("status").stringValue(null)).as("labelled by the agent, owner review pending").isEqualTo("proposed");
            languages.add(exercise.path("language").stringValue(null));
            domains.add(exercise.path("domain").stringValue(null));
            ids.add(exercise.path("exerciseId").stringValue(null));
            assertThat(exercise.path("answers")).hasSize(12);
            assertThat(exercise.path("answers")).extracting(answer -> answer.path("kind").stringValue(null)).containsExactlyElementsOf(KINDS);
            exercise.path("answers").forEach(answer -> assertThat(answer.path("status").stringValue(null)).isEqualTo("proposed"));
            rubric(exercise); // tier counts, weights, lengths: the same strict reader as publication
        }
        assertThat(languages).containsExactly("en", "ru");
        assertThat(domains).containsExactly("code", "humanities", "language", "stem");
        assertThat(ids).hasSize(12).contains("pg-optimizer");
        JsonNode optimizer = exercises.stream().filter(exercise -> exercise.path("exerciseId").stringValue(null).equals("pg-optimizer")).findFirst().orElseThrow();
        assertThat(optimizer.path("prompt").stringValue(null)).isEqualTo("Как работает оптимизатор запросов PostgreSQL?");
        assertThat(exercises.stream().mapToInt(exercise -> exercise.path("answers").size()).sum()).isEqualTo(144);
    }

    @Test
    void everyRecordedQuoteIsAVerbatimFragmentOfItsAnswerAndOnlyMetAndPartlyNeedOne() throws IOException {
        for (JsonNode exercise : exercises()) {
            for (JsonNode answer : exercise.path("answers")) {
                String text = normalize(answer.path("text").stringValue(null));
                assertThat(answer.path("text").stringValue(null).length()).isLessThanOrEqualTo(4_096);
                for (JsonNode grade : answer.path("graderOutput").path("criteria")) {
                    String verdict = grade.path("verdict").stringValue(null);
                    String quote = grade.path("quote").stringValue(null);
                    if (verdict.equals("MET") || verdict.equals("PARTLY")) {
                        assertThat(quote).as(answer.path("id").stringValue(null)).isNotBlank();
                    }
                    if (!quote.isEmpty()) {
                        assertThat(text).as(answer.path("id").stringValue(null) + " " + quote).contains(normalize(quote));
                        assertThat(quote.split("\\s+").length).as("a quote is at most 15 words").isLessThanOrEqualTo(15);
                    }
                }
            }
        }
    }

    @Test
    void theServerAggregationOfTheRecordedVerdictsGivesTheLabelAtEveryStrictness() throws IOException {
        int checked = 0;
        for (JsonNode exercise : exercises()) {
            Rubric rubric = rubric(exercise);
            for (JsonNode answer : exercise.path("answers")) {
                Run recorded = run(rubric, answer.path("graderOutput"));
                for (SemanticStrictness strictness : SemanticStrictness.values()) {
                    assertThat(label(rubric, strictness, recorded, answer)).as(answer.path("id").stringValue(null) + " at " + strictness)
                            .isEqualTo(answer.path("expected").path(strictness.name()).stringValue(null));
                    checked++;
                }
            }
        }
        assertThat(checked).isEqualTo(432);
    }

    @Test
    void thePancakeRecipeBagsOfTermsMisconceptionsInjectionsAndVerboseNonsenseAreInsufficientAtEveryLevel() throws IOException {
        for (JsonNode exercise : exercises()) {
            Rubric rubric = rubric(exercise);
            for (JsonNode answer : exercise.path("answers")) {
                String kind = answer.path("kind").stringValue(null);
                if (!Set.of("off-topic", "bag-of-terms", "misconception", "injection", "verbose-wrong").contains(kind)) continue;
                Run recorded = run(rubric, answer.path("graderOutput"));
                for (SemanticStrictness strictness : SemanticStrictness.values()) {
                    assertThat(label(rubric, strictness, recorded, answer)).as(answer.path("id").stringValue(null) + " at " + strictness)
                            .isEqualTo("INSUFFICIENT");
                }
                if (kind.equals("off-topic")) {
                    assertThat(answer.path("text").stringValue(null)).as("the same recipe for every exercise").containsAnyOf("муку", "flour");
                    assertThat(recorded.flags()).contains(Flag.OFF_TOPIC);
                }
                if (kind.equals("injection")) assertThat(recorded.flags()).contains(Flag.INJECTION);
            }
        }
    }

    @Test
    void garbledSpeechIsSelfCheckNotAResultAndAShallowCorrectAnswerGrowsStricter() throws IOException {
        int shallow = 0;
        for (JsonNode exercise : exercises()) {
            Rubric rubric = rubric(exercise);
            for (JsonNode answer : exercise.path("answers")) {
                String kind = answer.path("kind").stringValue(null);
                Run recorded = run(rubric, answer.path("graderOutput"));
                if (kind.equals("asr-noise")) {
                    assertThat(answer.path("answerSource").stringValue(null)).isEqualTo("SPEECH");
                    for (SemanticStrictness strictness : SemanticStrictness.values()) {
                        assertThat(label(rubric, strictness, recorded, answer)).isEqualTo("SELF_CHECK");
                    }
                }
                if (kind.equals("terse-correct")) {
                    // the lenient level accepts it with LOW evidence, the strict ones ask for completeness
                    assertThat(label(rubric, SemanticStrictness.S1, recorded, answer)).isEqualTo("COMPLETE");
                    assertThat(label(rubric, SemanticStrictness.S2, recorded, answer)).isEqualTo("PARTIAL");
                    assertThat(label(rubric, SemanticStrictness.S3, recorded, answer)).isEqualTo("PARTIAL");
                    shallow++;
                }
            }
        }
        assertThat(shallow).isEqualTo(12);
    }

    @Test
    void anInjectionThatCarriesRealContentIsGradedOnTheContentAndAGradableTranscriptIsNotSelfCheck() throws IOException {
        int injected = 0;
        int transcripts = 0;
        for (JsonNode exercise : exercises()) {
            Rubric rubric = rubric(exercise);
            for (JsonNode answer : exercise.path("answers")) {
                String kind = answer.path("kind").stringValue(null);
                Run recorded = run(rubric, answer.path("graderOutput"));
                if (kind.equals("injection-with-content")) {
                    injected++;
                    assertThat(recorded.flags()).containsExactly(Flag.INJECTION);
                    SemanticPolicy.Graded graded = (SemanticPolicy.Graded) SemanticPolicy.aggregate(rubric, SemanticStrictness.S1, List.of(recorded), false);
                    assertThat(graded.injection()).as("the reason code INJECTION is recorded").isTrue();
                    assertThat(graded.judgement()).as("the content, not the request, decides").isEqualTo(SemanticPolicy.Judgement.COMPLETE);
                    assertThat(label(rubric, SemanticStrictness.S2, recorded, answer)).isEqualTo("PARTIAL");
                }
                if (kind.equals("asr-gradable")) {
                    transcripts++;
                    assertThat(answer.path("answerSource").stringValue(null)).isEqualTo("SPEECH");
                    assertThat(recorded.flags()).doesNotContain(Flag.ASR_GARBLED);
                    for (SemanticStrictness strictness : SemanticStrictness.values()) {
                        assertThat(label(rubric, strictness, recorded, answer)).isEqualTo("COMPLETE");
                    }
                }
            }
        }
        assertThat(injected).isEqualTo(12);
        assertThat(transcripts).isEqualTo(12);
    }
}
