package app.mnema.learning.ai;

import app.mnema.learning.ai.prompt.AssembledPrompt;
import app.mnema.learning.ai.prompt.PromptAssembler;
import app.mnema.learning.ai.prompt.PromptBlocks;
import app.mnema.learning.ai.prompt.PromptLibrary;
import app.mnema.learning.ai.prompt.PromptTask;
import app.mnema.learning.ai.prompt.PromptValues;
import app.mnema.learning.generation.mbm.MbmCompiler;
import app.mnema.learning.generation.mbm.MbmOptions;
import app.mnema.learning.generation.mbm.MbmResult;
import app.mnema.learning.generation.mbm.RandomIdAllocator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The Stub's edit answers run through the real edit prompt and the real MBM edit compiler. */
class StubEditsTest {
    private static final PromptAssembler ASSEMBLER = new PromptAssembler(PromptLibrary.fromClasspath("v1"),
            new AiProperties.Prompt("v1", 32_000, 25_000));
    private final StubTextAdapter stub = new StubTextAdapter();

    /** The prompt of an EDIT step: the target text between its neighbours, the preset and the instruction. */
    private static String prompt(String target, String preset, String instruction) {
        PromptValues values = PromptValues.create().text("deck.title", "Колода").text("deck.description", "Описание")
                .text("lang.output", "ru").text("lang.target", "не указан").number("counts.items", 0).number("counts.exercises", 0)
                .text("deck_terms", "нет").number("style_card.words", 0).number("style_card.headings", 0).number("style_card.lists", 0)
                .number("style_card.tables", 0).number("style_card.examples", 0).number("style_card.audio", 0)
                .block("exemplar_blocks", PromptBlocks.empty()).text("recent_material", "нет").number("outline.total", 0)
                .number("outline.shown", 0).block("outline.lines", PromptBlocks.empty())
                .block("document", PromptBlocks.document("# Заголовок", target, "Конец."))
                .text("history", "нет предыдущих правок").text("instruction", instruction);
        if (preset != null) values.text("preset", preset);
        AssembledPrompt assembled = ASSEMBLER.assemble(PromptTask.EDIT, values);
        return assembled.segments().stream().map(TextRequest.Segment::text).collect(Collectors.joining("\n"));
    }

    private static TextRequest request(String prompt) {
        return new TextRequest(AiRoute.TEXT_FAST, List.of(TextRequest.Segment.user(prompt, false)), OutputContract.MBM_TEXT, 500, 0.7,
                Duration.ofSeconds(5), AiTestSupport.KEY, null, null, 1);
    }

    @Test
    void theTargetBlocksComeBackWithTheirHandlesAndEachPlainParagraphGainsOneSentenceNamingThePreset() {
        String target = "[[b2]] Первый абзац\nвторая строка.\n\n[[b3]] ## Подзаголовок\n\n[[b4]] - пункт\n- ещё\n\n[[b5]]\n\n[[b6]] ```sql\nselect 1;\n```";
        String answer = StubEdits.answer(prompt(target, "Проще", "без указаний"));

        assertThat(answer).isEqualTo("[[b2]] Первый абзац\nвторая строка. Переписано: Проще.\n\n[[b3]] ## Подзаголовок\n\n[[b4]] - пункт\n- ещё\n\n[[b5]]"
                + "\n\n[[b6]] ```sql\nselect 1;\n```\n");
        // and it is exactly what the compiler wants for those handles: every handle used, the types kept, so every node id is kept
        Map<String, MbmOptions.Handle> handles = Map.of("b2", new MbmOptions.Handle(UUID.randomUUID(), "paragraph"),
                "b3", new MbmOptions.Handle(UUID.randomUUID(), "heading"), "b4", new MbmOptions.Handle(UUID.randomUUID(), "bullet_list"),
                "b5", new MbmOptions.Handle(UUID.randomUUID(), "paragraph"), "b6", new MbmOptions.Handle(UUID.randomUUID(), "code_block"));
        MbmResult result = new MbmCompiler().compile(answer, MbmOptions.edit(handles), new RandomIdAllocator());
        assertThat(result).isInstanceOf(MbmResult.Success.class);
        JsonNode content = ((MbmResult.Success) result).document().path("root").path("content");
        assertThat(content).hasSize(5);
        for (int index = 0; index < 5; index++) {
            String handle = "b" + (index + 2);
            assertThat(content.get(index).path("id").stringValue(null)).isEqualTo(handles.get(handle).nodeId().toString());
        }
    }

    @Test
    void theAdapterAnswersAnEditRequestAndTheFailureMarkersOfTheInstructionStillApply() {
        String prompt = prompt("[[b2]] Текст.", null, "Сделай короче");
        TextResponse answer = ((AiResult.Ok<TextResponse>) stub.attempt("stub", request(prompt), Duration.ofSeconds(1))).value();
        assertThat(answer.text()).isEqualTo("[[b2]] Текст. Переписано: нет.\n");
        assertThat(answer.finishReason()).isEqualTo(TextResponse.FinishReason.STOP);

        AiResult<TextResponse> refused = stub.attempt("stub", request(prompt("[[b2]] Текст.", "Короче", "[[stub:refusal]] правь")), Duration.ofSeconds(1));
        assertThat(((AiResult.Failed<TextResponse>) refused).failure()).isInstanceOf(AiFailure.Refusal.class);
        // a draft request is not an edit request
        assertThat(StubEdits.isEditRequest("<task kind=\"material\">")).isFalse();
        assertThat(StubEdits.answer("нет такого блока")).isEmpty();
    }
}
