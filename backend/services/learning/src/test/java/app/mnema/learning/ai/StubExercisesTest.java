package app.mnema.learning.ai;

import app.mnema.learning.ai.prompt.AssembledPrompt;
import app.mnema.learning.ai.prompt.PromptAssembler;
import app.mnema.learning.ai.prompt.PromptBlocks;
import app.mnema.learning.ai.prompt.PromptLibrary;
import app.mnema.learning.ai.prompt.PromptTask;
import app.mnema.learning.ai.prompt.PromptValues;
import app.mnema.learning.generation.exercise.ExerciseCode;
import app.mnema.learning.generation.exercise.ExerciseContext;
import app.mnema.learning.generation.exercise.ExerciseIds;
import app.mnema.learning.generation.exercise.ExerciseOutputSchema;
import app.mnema.learning.generation.exercise.ExerciseValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Stub's exercise answers run through the real exercise prompt and the real validation pipeline: whatever material the
 * request carries, the Stub's exercises are valid, in the mechanics the task allows, and the two markers break the first
 * exercise in the way the repair and the failure paths expect.
 */
class StubExercisesTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final PromptAssembler ASSEMBLER = new PromptAssembler(PromptLibrary.fromClasspath("v1"),
            new AiProperties.Prompt("v1", 32_000, 25_000));
    private static final ExerciseValidator VALIDATOR = new ExerciseValidator(ExerciseOutputSchema.load());
    private static final List<String> ALL = List.of("SELF_CHECK", "FREE_RESPONSE", "CLOZE", "CHOICE", "MATCH", "ORDER", "CATEGORIZE");

    private static List<ExerciseContext.Block> blocks(List<String> texts) {
        List<ExerciseContext.Block> blocks = new ArrayList<>();
        for (String text : texts) blocks.add(new ExerciseContext.Block(UUID.randomUUID(), text));
        return blocks;
    }

    /** The prompt of an exercise step for {@code texts} as blocks b1..bn, assembled by the real assembler. */
    private static String prompt(List<String> texts, int count, List<String> mechanics) {
        List<PromptBlocks.HandleLine> lines = new ArrayList<>();
        for (int index = 0; index < texts.size(); index++) lines.add(new PromptBlocks.HandleLine("b" + (index + 1), texts.get(index)));
        PromptValues values = PromptValues.create().block("schema", PromptBlocks.schema("{\"type\":\"object\"}"))
                .block("material_blocks", PromptBlocks.material("m1", lines)).block("objective_lines", PromptBlocks.empty())
                .block("existing_exercise_lines", PromptBlocks.empty()).block("neighbor_lines", PromptBlocks.empty())
                .number("task.count", count).text("task.mechanics", String.join(", ", mechanics) + ". Чередуй механики")
                .text("lang.output", "ru");
        AssembledPrompt assembled = ASSEMBLER.assemble(PromptTask.EXERCISES, values);
        return assembled.segments().stream().map(TextRequest.Segment::text).collect(Collectors.joining("\n"));
    }

    private static ExerciseContext context(List<String> texts, Set<String> allowed) {
        Map<String, ExerciseContext.Block> blocks = new LinkedHashMap<>();
        List<ExerciseContext.Block> list = blocks(texts);
        for (int index = 0; index < list.size(); index++) blocks.put("b" + (index + 1), list.get(index));
        return new ExerciseContext(UUID.fromString("018f1d98-5c10-4abc-8abc-0123456789c1"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                Map.of("m1", new ExerciseContext.Material(UUID.randomUUID(), UUID.randomUUID(), blocks)), Map.of(), allowed);
    }

    private static List<ExerciseValidator.Verdict> verdicts(String answer, List<String> texts, Set<String> allowed) throws Exception {
        JsonNode exercises = JSON.readTree(answer).path("exercises");
        List<ExerciseValidator.Verdict> verdicts = new ArrayList<>();
        ExerciseContext context = context(texts, allowed);
        for (int index = 0; index < exercises.size(); index++) {
            verdicts.add(VALIDATOR.validate(index, exercises.get(index), context, ExerciseIds.random()));
        }
        return verdicts;
    }

    private static final List<List<String>> MATERIALS = List.of(
            List.of("Планировщик выбирает план с наименьшей оценкой стоимости.", "Статистика таблицы обновляется командой ANALYZE."),
            List.of("memory", "forgetting"),
            List.of("Кошка"),
            List.of("The cat sat on the mat, and the dog looked at them: \"hello\" & <bye>."),
            List.of("这只猫坐在垫子上。", "狗在看着它们。"),
            List.of("Строка один\nстрока два\nстрока три", "- пункт один\n- пункт два"),
            List.of("слово ".repeat(200)),
            List.of("да нет"));

    @Test
    void everyMechanicOfEveryMaterialPassesTheWholePipeline() throws Exception {
        for (List<String> material : MATERIALS) {
            for (String mechanic : ALL) {
                String answer = StubExercises.answer(prompt(material, 4, List.of(mechanic)), false);
                List<ExerciseValidator.Verdict> verdicts = verdicts(answer, material, new LinkedHashSet<>(List.of(mechanic, "SELF_CHECK")));
                assertThat(verdicts).as(mechanic + " of " + material).hasSize(4)
                        .allSatisfy(verdict -> assertThat(verdict).isInstanceOf(ExerciseValidator.Valid.class));
            }
        }
    }

    @Test
    void anAutoBatchOfThreeOrMoreCyclesThroughAtLeastThreeMechanics() throws Exception {
        List<String> material = MATERIALS.getFirst();
        for (int count = 3; count <= 10; count++) {
            String answer = StubExercises.answer(prompt(material, count, ALL), false);
            Set<String> used = new LinkedHashSet<>();
            JSON.readTree(answer).path("exercises").forEach(exercise -> used.add(exercise.path("mechanic").stringValue(null)));
            assertThat(JSON.readTree(answer).path("exercises")).hasSize(count);
            assertThat(used).hasSizeGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void theTaskLineAndAMaterialWithoutTextDecideWhatTheStubAnswers() throws Exception {
        assertThat(StubExercises.isExerciseRequest(prompt(MATERIALS.getFirst(), 2, ALL))).isTrue();
        assertThat(StubExercises.isExerciseRequest("# Заголовок")).isFalse();
        // only the mechanics of the task are used
        String answer = StubExercises.answer(prompt(MATERIALS.getFirst(), 3, List.of("CHOICE", "MATCH")), false);
        Set<String> used = new LinkedHashSet<>();
        JSON.readTree(answer).path("exercises").forEach(exercise -> used.add(exercise.path("mechanic").stringValue(null)));
        assertThat(used).containsExactlyInAnyOrder("CHOICE", "MATCH");
        // a repair asks for as many as the repair message says
        String repair = prompt(MATERIALS.getFirst(), 5, ALL) + "\n<repair>\nВерни json {\"exercises\": […]} ровно из 2 упражнений: только замены\n</repair>";
        assertThat(JSON.readTree(StubExercises.answer(repair, true)).path("exercises")).hasSize(2);
        // no text in the material, no exercise
        String empty = prompt(MATERIALS.getFirst(), 2, ALL).replaceAll("(?s)<material id=\"m1\">.*</material>", "<material id=\"m1\">\n\n</material>");
        assertThat(JSON.readTree(StubExercises.answer(empty, false)).path("exercises")).isEmpty();
    }

    @Test
    void theBrokenKeyMarkerBreaksOnlyTheFirstExerciseOfTheFirstAnswer() throws Exception {
        List<String> material = List.of("Планировщик выбирает план [[stub:broken-key]]", "Статистика таблицы обновляется командой ANALYZE.");
        List<ExerciseValidator.Verdict> first = verdicts(StubExercises.answer(prompt(material, 3, ALL), false), material, Set.copyOf(ALL));
        assertThat(first.getFirst()).isInstanceOf(ExerciseValidator.Invalid.class);
        assertThat(((ExerciseValidator.Invalid) first.getFirst()).findings()).extracting(finding -> finding.code())
                .containsExactly(ExerciseCode.CHOICE_CORRECT_COUNT);
        assertThat(first.subList(1, 3)).allSatisfy(verdict -> assertThat(verdict).isInstanceOf(ExerciseValidator.Valid.class));
        // the repair answer is valid
        String repair = prompt(material, 3, ALL) + "\n<repair>\nВерни json ровно из 1 упражнений\n</repair>";
        assertThat(verdicts(StubExercises.answer(repair, true), material, Set.copyOf(ALL)))
                .hasSize(1).allSatisfy(verdict -> assertThat(verdict).isInstanceOf(ExerciseValidator.Valid.class));
    }

    @Test
    void theAlwaysBrokenMarkerBreaksTheRepairToo() throws Exception {
        List<String> material = List.of("Планировщик выбирает план [[stub:broken-key-always]]");
        String repair = prompt(material, 3, ALL) + "\n<repair>\nВерни json ровно из 1 упражнений\n</repair>";
        List<ExerciseValidator.Verdict> verdicts = verdicts(StubExercises.answer(repair, true), material, Set.copyOf(ALL));
        assertThat(verdicts).hasSize(1);
        assertThat(((ExerciseValidator.Invalid) verdicts.getFirst()).findings()).extracting(finding -> finding.code())
                .containsExactly(ExerciseCode.CHOICE_CORRECT_COUNT);
    }

    @Test
    void theAdapterAnswersAnExerciseRequestAsJsonAndKeepsItsOtherJsonAnswer() {
        StubTextAdapter stub = new StubTextAdapter();
        String prompt = prompt(MATERIALS.getFirst(), 2, ALL);
        TextRequest exercises = new TextRequest(AiRoute.TEXT_FAST, List.of(TextRequest.Segment.user(prompt, false)), OutputContract.JSON, 2_000,
                0.4, Duration.ofSeconds(5), AiTestSupport.KEY, null, null, 1);
        String text = ((AiResult.Ok<TextResponse>) stub.attempt("stub", exercises, Duration.ofSeconds(1))).value().text();
        assertThat(text).startsWith("{\"exercises\":[");
        TextRequest other = new TextRequest(AiRoute.TEXT_FAST, List.of(TextRequest.Segment.user("тема", false)), OutputContract.JSON, 500, 0.4,
                Duration.ofSeconds(5), AiTestSupport.KEY, null, null, 1);
        assertThat(((AiResult.Ok<TextResponse>) stub.attempt("stub", other, Duration.ofSeconds(1))).value().text()).startsWith("{\"stub\":true");
    }
}
