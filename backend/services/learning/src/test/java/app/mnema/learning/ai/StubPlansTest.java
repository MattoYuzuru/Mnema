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

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The Stub's plan answers run through the real plan prompt (#295): one item per target or note, and the two markers of the repair. */
class StubPlansTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final PromptAssembler ASSEMBLER = new PromptAssembler(PromptLibrary.fromClasspath("v1"),
            new AiProperties.Prompt("v1", 32_000, 25_000));
    private final StubTextAdapter stub = new StubTextAdapter();

    private static PromptValues common(String kind, String hint) {
        return PromptValues.create().text("deck.title", "Колода").text("deck.description", "не указано").number("counts.items", 3)
                .number("counts.exercises", 4).text("lang.output", "ru").text("task.kind", kind).text("task.hint", hint)
                .text("task.budget", "не больше 15 кредитов").block("limit_lines", PromptBlocks.lines(List.of("материалов в плане: не больше 20")));
    }

    private static String exercises(String hint, String... titles) {
        List<String> lines = new java.util.ArrayList<>();
        for (int index = 0; index < titles.length; index++) lines.add("m" + (index + 1) + " · " + titles[index] + " · exercises: 0");
        AssembledPrompt prompt = ASSEMBLER.assemble(PromptTask.PLAN, common("EXERCISES", hint).block("target_lines", PromptBlocks.lines(lines))
                .block("note_blocks", PromptBlocks.empty()).block("outline.lines", PromptBlocks.empty()));
        return prompt.segments().stream().map(TextRequest.Segment::text).collect(Collectors.joining("\n"));
    }

    private static String materials(String request, String... notes) {
        List<PromptBlocks.OutlineEntry> none = List.of();
        List<app.mnema.learning.ai.prompt.PromptBlock> blocks = new java.util.ArrayList<>();
        for (int index = 0; index < notes.length; index++) blocks.add(PromptBlocks.note("n" + (index + 1), notes[index]));
        PromptValues values = common("MATERIALS", "Заметок нет.").block("target_lines", PromptBlocks.empty())
                .block("note_blocks", PromptBlocks.join(blocks)).block("outline.lines", PromptBlocks.outline(none));
        if (request != null) values.text("request", request);
        return ASSEMBLER.assemble(PromptTask.PLAN, values).segments().stream().map(TextRequest.Segment::text).collect(Collectors.joining("\n"));
    }

    private JsonNode answer(String prompt, boolean repair) throws Exception {
        List<TextRequest.Segment> segments = new java.util.ArrayList<>(List.of(TextRequest.Segment.user(prompt, false)));
        TextRequest call = new TextRequest(AiRoute.PLAN, segments, OutputContract.JSON, 1_000, 0.3, Duration.ofSeconds(5), AiTestSupport.KEY, null, null, 1);
        if (repair) call = call.withRepair("x");
        return JSON.readTree(((AiResult.Ok<TextResponse>) stub.attempt("stub", call, Duration.ofSeconds(5))).value().text());
    }

    @Test
    void anExercisesPlanHasOneItemPerTargetWithTheAllowedMechanicsRoundRobinAndTheRequestedCount() throws Exception {
        JsonNode plan = answer(exercises("Механики: CLOZE, CHOICE, ORDER. На материал: 4.", "Один", "Два", "Три", "Четыре"), false);
        assertThat(plan.path("items")).hasSize(4);
        assertThat(plan.path("items").get(0).path("target").stringValue(null)).isEqualTo("m1");
        assertThat(plan.path("items").get(3).path("target").stringValue(null)).isEqualTo("m4");
        assertThat(plan.path("items").get(0).path("mechanics")).extracting(JsonNode::stringValue).containsExactly("CLOZE", "CHOICE");
        assertThat(plan.path("items").get(2).path("mechanics")).extracting(JsonNode::stringValue).containsExactly("ORDER", "CLOZE");
        assertThat(plan.path("items").get(0).path("count").intValue()).isEqualTo(4);
        assertThat(plan.path("items").get(0).path("why").stringValue(null)).isNotBlank();
        // no count requested: three; a single allowed mechanic: just that one
        JsonNode defaults = answer(exercises("Механики: CLOZE. На материал: не указано.", "Один"), false);
        assertThat(defaults.path("items").get(0).path("count").intValue()).isEqualTo(3);
        assertThat(defaults.path("items").get(0).path("mechanics")).extracting(JsonNode::stringValue).containsExactly("CLOZE");
    }

    @Test
    void aMaterialsPlanHasOneItemPerNoteOrThreeWithoutSourcesAndDecodesTheEscapedNote() throws Exception {
        JsonNode plan = answer(materials(null, "Шахматы & дебюты\nвторая строка", "Глаголы"), false);
        assertThat(plan.path("items")).hasSize(2);
        assertThat(plan.path("items").get(0).path("source").stringValue(null)).isEqualTo("n1");
        assertThat(plan.path("items").get(0).path("title").stringValue(null)).isEqualTo("Шахматы & дебюты");
        assertThat(plan.path("items").get(0).path("effort").stringValue(null)).isEqualTo("MEDIUM");
        JsonNode none = answer(materials("темы по сетям"), false);
        assertThat(none.path("items")).hasSize(3);
        assertThat(none.path("items").get(0).path("source").isNull()).isTrue();
        // the notes of a long text are cut to a title
        assertThat(answer(materials(null, "а".repeat(200)), false).path("items").get(0).path("title").stringValue(null)).hasSize(48);
    }

    @Test
    void theMarkersBreakTheFirstAnswerOrEveryOneAndOnlyAPlanRequestIsAnswered() throws Exception {
        String once = exercises("Механики: CLOZE. На материал: 2.", "Тема [[stub:plan-invalid]]");
        assertThat(answer(once, false).path("items").isString()).isTrue();
        assertThat(answer(once, true).path("items").isArray()).isTrue();
        String always = exercises("Механики: CLOZE. На материал: 2.", "Тема [[stub:plan-invalid-always]]");
        assertThat(answer(always, false).path("items").isString()).isTrue();
        assertThat(answer(always, true).path("items").isString()).isTrue();
        assertThat(StubPlans.isPlanRequest("<task kind=\"exercises\">")).isFalse();
    }
}
