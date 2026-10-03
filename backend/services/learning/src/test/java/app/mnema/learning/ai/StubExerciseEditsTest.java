package app.mnema.learning.ai;

import app.mnema.learning.ai.prompt.AssembledPrompt;
import app.mnema.learning.ai.prompt.PromptAssembler;
import app.mnema.learning.ai.prompt.PromptBlocks;
import app.mnema.learning.ai.prompt.PromptLibrary;
import app.mnema.learning.ai.prompt.PromptTask;
import app.mnema.learning.ai.prompt.PromptValues;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The Stub's exercise revisions run through the real exercise-edit prompt (#294). */
class StubExerciseEditsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final PromptAssembler ASSEMBLER = new PromptAssembler(PromptLibrary.fromClasspath("v1"),
            new AiProperties.Prompt("v1", 32_000, 25_000));

    private static String prompt(String exercise, String instruction) {
        PromptValues values = PromptValues.create().block("schema", PromptBlocks.schema("{\"type\":\"object\"}"))
                .block("material_blocks", PromptBlocks.material("m1", List.of(new PromptBlocks.HandleLine("b1", "Планировщик выбирает план"))))
                .block("objective_lines", PromptBlocks.lines(List.of("t1 · Цель")))
                .block("current_exercise_blocks", PromptBlocks.currentExercise(exercise))
                .text("instruction", instruction).text("lang.output", "ru");
        AssembledPrompt assembled = ASSEMBLER.assemble(PromptTask.EXERCISE_EDIT, values);
        return assembled.segments().stream().map(TextRequest.Segment::text).collect(Collectors.joining("\n"));
    }

    private static JsonNode first(String answer) throws Exception {
        return JSON.readTree(answer).path("exercises").get(0);
    }

    @Test
    void theFirstTextBlockOfThePromptGetsOneMoreSentenceAndNothingElseChanges() throws Exception {
        String exercise = "{\"mechanic\":\"CHOICE\",\"subject\":\"m1\",\"objective\":{\"ref\":\"t1\"},\"prompt\":[{\"kind\":\"MATERIAL\",\"ref\":\"m1:b1\"},"
                + "{\"kind\":\"TEXT\",\"text\":\"Что делает планировщик & кто?\"}],\"options\":[{\"id\":\"o1\",\"text\":\"<план>\",\"correct\":true}]}";

        JsonNode revised = first(StubExerciseEdits.answer(prompt(exercise, "проще"), false));

        // the entities of the prompt layer are undone: what the model was shown is what comes back
        assertThat(revised.path("prompt").get(1).path("text").stringValue(null)).isEqualTo("Что делает планировщик & кто? " + StubExerciseEdits.SENTENCE);
        assertThat(revised.path("prompt").get(0)).isEqualTo(JSON.readTree(exercise).path("prompt").get(0));
        assertThat(revised.path("options")).isEqualTo(JSON.readTree(exercise).path("options"));
        assertThat(revised.path("mechanic").stringValue(null)).isEqualTo("CHOICE");
    }

    @Test
    void aPromptOfAQuoteOnlyGetsALeadingTextAndBrokenAnswersAreProducedOnRequest() throws Exception {
        String exercise = "{\"mechanic\":\"SELF_CHECK\",\"subject\":\"m1\",\"objective\":{\"ref\":\"t1\"},\"prompt\":[{\"kind\":\"MATERIAL\",\"ref\":\"m1:b1\"}],"
                + "\"reference\":[{\"kind\":\"TEXT\",\"text\":\"ответ\"}]}";
        JsonNode revised = first(StubExerciseEdits.answer(prompt(exercise, "проще"), false));
        assertThat(revised.path("prompt")).hasSize(2);
        assertThat(revised.path("prompt").get(0).path("text").stringValue(null)).isEqualTo(StubExerciseEdits.SENTENCE);
        assertThat(revised.path("prompt").get(1).path("kind").stringValue(null)).isEqualTo("MATERIAL");

        String broken = prompt(exercise, "[[stub:broken-key]] проще");
        assertThat(StubExerciseEdits.answer(broken, false)).contains("NOT_A_MECHANIC");
        assertThat(StubExerciseEdits.answer(broken, true)).contains("SELF_CHECK");
        assertThat(StubExerciseEdits.answer(prompt(exercise, "[[stub:broken-key-always]] проще"), true)).contains("NOT_A_MECHANIC");
        // no exercise in the prompt, or one that is not JSON: an answer with nothing in it
        assertThat(StubExerciseEdits.answer("no exercise here", false)).isEqualTo("{\"exercises\":[]}");
        assertThat(StubExerciseEdits.answer("<current_exercise>\nnot json\n</current_exercise>", false)).isEqualTo("{\"exercises\":[]}");
        assertThat(StubExerciseEdits.answer("<current_exercise>\n[1]\n</current_exercise>", false)).isEqualTo("{\"exercises\":[]}");
        assertThat(StubExerciseEdits.isExerciseEditRequest(prompt(exercise, "x"))).isTrue();
        assertThat(StubExerciseEdits.isExerciseEditRequest("<task kind=\"exercises\">")).isFalse();
    }
}
