package app.mnema.learning.ai.prompt;

import app.mnema.learning.ai.AiProperties;
import app.mnema.learning.ai.TextRequest;
import app.mnema.learning.ai.UserKeys;
import app.mnema.learning.ai.AiRoute;
import app.mnema.learning.ai.OutputContract;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptAssemblerTest {
    private static final PromptLibrary LIBRARY = PromptLibrary.fromClasspath("v1");
    private static final AiProperties.Prompt LIMITS = new AiProperties.Prompt("v1", 32_000, 25_000);
    private final PromptAssembler assembler = new PromptAssembler(LIBRARY, LIMITS);
    private static final List<String> PREFIX = List.of("system", "style", "skills/vocabulary", "skills/grammar", "skills/stem-concept",
            "skills/code", "skills/exam-summary");

    /** The body of a prompt file read straight from the classpath, independent of the loader under test. */
    private static String fileBody(String path) throws IOException {
        try (var in = PromptAssemblerTest.class.getResourceAsStream("/ai/prompts/v1/" + path + ".md")) {
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return raw.substring(raw.indexOf("\n---\n", 3) + 5).replaceAll("\n+$", "");
        }
    }

    @Test
    void theStablePrefixIsByteIdenticalForEveryUserAndEqualsTheFilesOnDisk() throws IOException {
        AssembledPrompt first = assembler.assemble(PromptTask.MATERIAL, PromptFixtures.material("Японский", List.of("заметка один")));
        AssembledPrompt other = assembler.assemble(PromptTask.MATERIAL,
                PromptFixtures.material("Совсем другая колода", List.of("другая заметка", "и ещё одна")));

        for (int index = 0; index < PREFIX.size(); index++) {
            TextRequest.Segment segment = first.segments().get(index);
            assertThat(segment.text()).as(PREFIX.get(index)).isEqualTo(fileBody(PREFIX.get(index)));
            assertThat(segment.text()).isEqualTo(other.segments().get(index).text());
            assertThat(segment.role()).isEqualTo(TextRequest.Role.SYSTEM);
            assertThat(segment.cacheable()).isTrue();
        }
        // the per-deck brief differs between decks, the task section differs per call
        assertThat(first.segments().get(7).text()).isNotEqualTo(other.segments().get(7).text());
    }

    @Test
    void layersAreOrderedStableFirstAndFormAValidRequest() {
        AssembledPrompt prompt = assembler.assemble(PromptTask.MATERIAL, PromptFixtures.material("Японский", List.of("заметка")));
        List<TextRequest.Segment> segments = prompt.segments();

        assertThat(segments).hasSize(9);
        assertThat(segments.get(7).role()).isEqualTo(TextRequest.Role.USER);
        assertThat(segments.get(7).cacheable()).isTrue();
        assertThat(segments.get(7).text()).contains("<title>Японский</title>");
        assertThat(segments.get(8).cacheable()).isFalse();
        assertThat(segments.get(8).text()).contains("<task kind=\"material\">").contains("Объясни глагол");
        assertThat(prompt.promptVersion()).isEqualTo("v1");
        // the cacheable segments are a leading run, which TextRequest enforces
        new TextRequest(AiRoute.TEXT_FAST, segments, OutputContract.MBM_TEXT, 4_000, 0.8, Duration.ofMinutes(6),
                UserKeys.withSecret("0123456789abcdef0123456789abcdef", "k1").opaque(java.util.UUID.randomUUID()), null, null, 1);
        assertThat(prompt.sectionTokens()).containsKeys("system", "style", "deck-brief", "material");
        assertThat(prompt.estimatedTokens()).isEqualTo(prompt.sectionTokens().values().stream().mapToInt(Integer::intValue).sum());
        assertThat(prompt.overWorkingTarget()).isFalse();
    }

    @Test
    void editExercisesAndAssessmentHaveTheirOwnShape() {
        AssembledPrompt edit = assembler.assemble(PromptTask.EDIT, PromptFixtures.edit());
        assertThat(edit.segments()).hasSize(9);
        assertThat(edit.segments().get(8).text()).contains("<task kind=\"edit\">");

        AssembledPrompt exercises = assembler.assemble(PromptTask.EXERCISES, PromptFixtures.exercises());
        // the data policy of the core (stable, cacheable), then the exercise section (volatile)
        assertThat(exercises.segments()).hasSize(2);
        assertThat(exercises.segments().get(0).cacheable()).isTrue();
        assertThat(exercises.segments().get(0).role()).isEqualTo(TextRequest.Role.SYSTEM);
        assertThat(exercises.segments().get(0).text()).startsWith("<data_policy>").endsWith("</data_policy>")
                .contains("<material>").contains("<objectives>").contains("<existing_exercises>").contains("<neighbors>");
        assertThat(exercises.segments().get(1).cacheable()).isFalse();
        assertThat(exercises.segments().get(1).text()).contains("<task kind=\"exercises\">");
        assertThat(exercises.sectionTokens()).containsKeys("data-policy", "exercises");

        AssembledPrompt assessment = assembler.assemble(PromptTask.ASSESSMENT, PromptFixtures.assessment());
        // the grader rules and the exercise are a cacheable head, the answer source and the learner answer the volatile tail
        assertThat(assessment.segments()).hasSize(2);
        assertThat(assessment.segments().get(0).cacheable()).isTrue();
        assertThat(assessment.segments().get(0).text()).contains("<grader>").contains("<criteria>").doesNotContain("<learner_answer>\"");
        assertThat(assessment.segments().get(1).cacheable()).isFalse();
        assertThat(assessment.segments().get(1).text()).startsWith("<answer_source>TYPED</answer_source>").contains("<learner_answer>");
    }

    @Test
    void theTaskSkillMustBeOneOfTheDocumentedValues() {
        for (String skill : List.of("vocabulary", "grammar", "concept", "code", "exam_notes", "free")) {
            assembler.assemble(PromptTask.MATERIAL, PromptFixtures.material("Т", List.of()).text("task.skill", skill));
        }
        assertThatThrownBy(() -> assembler.assemble(PromptTask.MATERIAL, PromptFixtures.material("Т", List.of()).text("task.skill", "poetry")))
                .isInstanceOf(PromptException.class);
    }

    @Test
    void anOversizedSectionOrInputIsRefusedSoTheCallerTrimsItsData() {
        String large = "слово ".repeat(10_000);
        assertThatThrownBy(() -> assembler.assemble(PromptTask.MATERIAL, PromptFixtures.material("Т", List.of(large, large, large, large))))
                .isInstanceOf(PromptException.class).hasMessageContaining("material");
        var tight = new PromptAssembler(LIBRARY, new AiProperties.Prompt("v1", 3_500, 3_000));
        assertThatThrownBy(() -> tight.assemble(PromptTask.MATERIAL, PromptFixtures.material("Т", List.of())))
                .isInstanceOf(PromptException.class).hasMessageContaining("ceiling");
    }

    @Test
    void inputAboveTheWorkingSizeIsFlaggedNotRefused() {
        var low = new PromptAssembler(LIBRARY, new AiProperties.Prompt("v1", 32_000, 3_000));
        AssembledPrompt prompt = low.assemble(PromptTask.MATERIAL, PromptFixtures.material("Т", List.of()));
        assertThat(prompt.estimatedTokens()).isGreaterThan(3_000);
        assertThat(prompt.overWorkingTarget()).isTrue();
    }

    @Test
    void theStaticPrefixFitsItsBudgetWithRoomToGrow() {
        AssembledPrompt prompt = assembler.assemble(PromptTask.MATERIAL, PromptFixtures.material("Т", List.of()));
        int prefix = PREFIX.stream().mapToInt(name -> prompt.sectionTokens().get(name.replace("skills/", "skill-")))
                .sum();
        // research sizing: 1.6-2.1k core, 1.0-1.3k style, five skills of 0.9-1.2k; the estimate is conservative, so allow 2x
        assertThat(prefix).isBetween(1_000, 12_000);
    }

    /** The learning goal of #301 tunes client copy only: no assembled prompt, and no prompt file, names a goal value. */
    @Test
    void noLearningGoalValueAppearsInAnyAssembledPrompt() {
        java.util.regex.Pattern goal = java.util.regex.Pattern.compile("\\b(EXAMS|INTERVIEW|LANGUAGE|WORK|SELF)\\b");
        for (AssembledPrompt prompt : List.of(
                assembler.assemble(PromptTask.MATERIAL, PromptFixtures.material("Японский", List.of("заметка"))),
                assembler.assemble(PromptTask.EDIT, PromptFixtures.edit()),
                assembler.assemble(PromptTask.EXERCISES, PromptFixtures.exercises()),
                assembler.assemble(PromptTask.ASSESSMENT, PromptFixtures.assessment()))) {
            for (TextRequest.Segment segment : prompt.segments()) {
                assertThat(goal.matcher(segment.text()).find()).as(segment.text()).isFalse();
            }
        }
    }
}
