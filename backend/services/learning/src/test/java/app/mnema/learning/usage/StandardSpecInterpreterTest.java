package app.mnema.learning.usage;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a spec implies: shape validation, operations and counts, and the limits that are refused instead of clamped. */
class StandardSpecInterpreterTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String NOTE = "20700000-0000-4000-8000-0000000000%02d";
    private final StandardSpecInterpreter interpreter = new StandardSpecInterpreter(new GenerationLimits(20, 20, 20, 10, 60));

    private GenerationSpecInterpreter.Interpretation interpret(String json) {
        return interpret(json, 100);
    }

    private GenerationSpecInterpreter.Interpretation interpret(String json, int remaining) {
        return interpreter.interpret(UUID.randomUUID(), UUID.randomUUID(), parse(json), remaining);
    }

    private static JsonNode parse(String json) {
        return JSON.readTree(json);
    }

    private static String note(int n) {
        return "{\"role\":\"SOURCE\",\"type\":\"NOTE\",\"noteId\":\"" + NOTE.formatted(n) + "\",\"noteRowVersion\":\"3\"}";
    }

    private static String materials(String sources, String settings) {
        return "{\"kind\":\"MATERIALS\",\"prompt\":\"p\",\"sources\":[" + sources + "],\"settings\":" + settings + "}";
    }

    private static String targets(int count) {
        return String.join(",", IntStream.rangeClosed(1, count).mapToObj(i -> "{\"memberKey\":\""
                + "44444444-4444-4444-8444-4444444444%02d".formatted(i) + "\",\"itemRevisionId\":\""
                + "55555555-5555-4555-8555-555555555555\"}").toList());
    }

    private static String exercises(int targets, String settings) {
        return "{\"kind\":\"EXERCISES\",\"targets\":[" + targets(targets) + "],\"settings\":" + settings + "}";
    }

    private static List<String> lines(GenerationSpecInterpreter.Interpretation interpretation) {
        return interpretation.lines().stream().map(line -> line.operation() + "x" + line.count()).toList();
    }

    @Test
    void theContractsMaterialsExampleIsOneMediumMaterialWithAudioAndImageSearch() {
        JsonNode example = ContractExamples.generation("specMaterials");
        var result = interpreter.interpret(UUID.randomUUID(), UUID.randomUUID(), example, 308);
        assertThat(lines(result)).containsExactly("MATERIAL_MEDIUMx1", "TTS_CLIP_30Sx1", "IMAGE_SEARCHx1");
        assertThat(result.budgetPercent()).isNull();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void theContractsExercisesExampleIsThreeExercises() {
        var result = interpreter.interpret(UUID.randomUUID(), UUID.randomUUID(), ContractExamples.generation("specExercises"), 308);
        assertThat(lines(result)).containsExactly("EXERCISES_PER_MATERIALx3");
    }

    @Test
    void oneArtifactPerNoteUnlessMergedAndEffortPicksTheMaterialWeightWithAutoPricedAsTheWorstCase() {
        String two = note(1) + "," + note(2);
        assertThat(lines(interpret(materials(two, "{\"effort\":\"DETAILED\"}")))).containsExactly("MATERIAL_DETAILEDx2");
        assertThat(lines(interpret(materials(two, "{\"effort\":\"SHORT\",\"notesMode\":\"MERGE_INTO_ONE\"}"))))
                .containsExactly("MATERIAL_SHORTx1");
        assertThat(lines(interpret(materials(two, "{\"effort\":\"AUTO\"}")))).containsExactly("MATERIAL_MEDIUMx2");
        assertThat(lines(interpret("{\"kind\":\"MATERIALS\",\"prompt\":\"Explain\"}"))).containsExactly("MATERIAL_MEDIUMx1");
        // Item sources and style examples make no artifact of their own.
        String item = "{\"role\":\"SOURCE\",\"type\":\"ITEM\",\"memberKey\":\"44444444-4444-4444-8444-444444444444\","
                + "\"itemRevisionId\":\"55555555-5555-4555-8555-555555555555\"}";
        assertThat(lines(interpret(materials(item, "{}")))).containsExactly("MATERIAL_MEDIUMx1");
    }

    @Test
    void mediaAndFactCheckAreOnePerArtifactAndShortEffortDoesNoResearch() {
        String two = note(1) + "," + note(2);
        String settings = "{\"effort\":\"MEDIUM\",\"factCheck\":true,\"media\":{\"audio\":{\"enabled\":true,\"lang\":\"ja\","
                + "\"voice\":null},\"imageSearch\":true}}";
        assertThat(lines(interpret(materials(two, settings)))).containsExactly("MATERIAL_MEDIUMx2", "TTS_CLIP_30Sx2",
                "IMAGE_SEARCHx2", "FACTCHECK_LOWx2");
        assertThat(lines(interpret(materials(two, settings.replace("MEDIUM", "SHORT"))))).containsExactly("MATERIAL_SHORTx2",
                "TTS_CLIP_30Sx2", "IMAGE_SEARCHx2");
        assertThat(lines(interpret(materials(note(1), "{\"media\":{\"audio\":{\"enabled\":false},\"imageSearch\":false}}"))))
                .containsExactly("MATERIAL_MEDIUMx1");
    }

    @Test
    void budgetPercentIsCarriedAndPlanFirstAndSimilarToDeckCostNothingOnceThePlannerExists() {
        var withPlanner = new StandardSpecInterpreter(new GenerationLimits(20, 20, 20, 10, 60), null, true);
        var result = withPlanner.interpret(UUID.randomUUID(), UUID.randomUUID(),
                parse(materials(note(1), "{\"budgetPercent\":40,\"planFirst\":true,\"similarToDeck\":true}")), 100);
        assertThat(result.budgetPercent()).isEqualTo(40);
        assertThat(lines(result)).containsExactly("MATERIAL_MEDIUMx1");
        assertThat(interpret(materials(note(1), "{\"budgetPercent\":null}")).budgetPercent()).isNull();
    }

    @Test
    void planFirstIsNotSupportedUntilThePlannerIsEnabled() {
        assertThatThrownBy(() -> interpret(materials(note(1), "{\"planFirst\":true}")))
                .isInstanceOfSatisfying(SpecNotSupportedException.class, failure ->
                        assertThat(failure.extension().members()).containsEntry("kind", "MATERIALS"));
        // planFirst false is the plain run
        assertThat(lines(interpret(materials(note(1), "{\"planFirst\":false}")))).containsExactly("MATERIAL_MEDIUMx1");
    }

    @Test
    void aMaterialsSpecWithNothingToWorkFromOrABadShapeIsInvalid() {
        invalid("{\"kind\":\"MATERIALS\"}");
        invalid("{\"kind\":\"MATERIALS\",\"prompt\":\"   \"}");
        invalid("{\"kind\":\"MATERIALS\",\"prompt\":\"" + "x".repeat(2001) + "\"}");
        invalid("{\"kind\":\"MATERIALS\",\"prompt\":\"p\",\"extra\":1}");
        invalid("{\"kind\":\"MATERIALS\",\"prompt\":null}");
        invalid("{\"kind\":\"MATERIALS\",\"prompt\":\"p\",\"outputLanguage\":\"not a tag\"}");
        invalid("{\"kind\":\"MATERIALS\",\"prompt\":\"p\",\"sources\":{}}");
        invalid(materials(note(1).replace("\"NOTE\"", "\"FILE\""), "{}"));
        invalid(materials(note(1).replace("SOURCE", "ORIGIN"), "{}"));
        invalid(materials(note(1).replace("\"noteRowVersion\":\"3\"", "\"noteRowVersion\":\"007\""), "{}"));
        invalid(materials(note(1).replace("\"noteRowVersion\":\"3\"", "\"noteRowVersion\":3"), "{}"));
        invalid(materials(note(1).replace("}", ",\"extra\":1}"), "{}"));
        invalid(materials(note(1).replace(NOTE.formatted(1), "20700000-0000-4000-8000-00000000000A"), "{}"));
        invalid(materials(note(1).replace(NOTE.formatted(1), "not-a-uuid"), "{}"));
        invalid(materials(note(1).replace(NOTE.formatted(1), "00000000-0000-0000-0000-000000000000"), "{}"));
        invalid(materials(note(1).replace("SOURCE", "STYLE_EXAMPLE"), "{}"));
        String style = "{\"role\":\"STYLE_EXAMPLE\",\"type\":\"ITEM\",\"memberKey\":\"44444444-4444-4444-8444-444444444444\","
                + "\"itemRevisionId\":\"55555555-5555-4555-8555-555555555555\"}";
        invalid(materials(style + "," + style + "," + style + "," + note(1), "{}"));
        invalid(materials(note(1), "{\"effort\":\"HUGE\"}"));
        invalid(materials(note(1), "{\"notesMode\":\"SPLIT\"}"));
        invalid(materials(note(1), "{\"unknown\":true}"));
        invalid(materials(note(1), "{\"factCheck\":\"yes\"}"));
        invalid(materials(note(1), "{\"budgetPercent\":0}"));
        invalid(materials(note(1), "{\"budgetPercent\":101}"));
        invalid(materials(note(1), "{\"budgetPercent\":1.5}"));
        invalid(materials(note(1), "{\"media\":{\"video\":true}}"));
        invalid(materials(note(1), "{\"media\":{\"audio\":{}}}"));
        invalid(materials(note(1), "{\"media\":{\"audio\":{\"enabled\":true,\"lang\":\"bad lang\"}}}"));
        invalid(materials(note(1), "{\"media\":{\"audio\":{\"enabled\":true,\"voice\":\"robot\"}}}"));
        invalid("{\"kind\":\"MATERIALS\",\"prompt\":\"p\",\"settings\":[]}");
        invalid("{\"kind\":\"NOPE\"}");
        invalid("{\"kind\":3}");
        assertThatThrownBy(() -> interpreter.interpret(UUID.randomUUID(), UUID.randomUUID(), JSON.readTree("[]"), 1))
                .isInstanceOf(InvalidRequestException.class);
    }

    private void invalid(String json) {
        assertThatThrownBy(() -> interpret(json)).as(json).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void tooManySourcesOrArtifactsIsAResourceLimitNotAClamp() {
        String twentyOne = String.join(",", IntStream.rangeClosed(1, 21).mapToObj(UsageSpecs::noteSource).toList());
        assertLimit(() -> interpret(materials(twentyOne, "{}")), "SOURCES", Map.of("maxSources", 20));
        var tight = new StandardSpecInterpreter(new GenerationLimits(20, 2, 20, 10, 60));
        assertThatThrownBy(() -> tight.interpret(UUID.randomUUID(), UUID.randomUUID(),
                parse(materials(note(1) + "," + note(2) + "," + note(3), "{}")), 10))
                .isInstanceOfSatisfying(ResourceLimitExceededException.class, failure -> assertThat(failure.extension().members())
                        .containsEntry("limit", "ARTIFACTS_PER_SESSION").containsEntry("limits", Map.of("maxArtifactsPerSession", 2)));
        // Merged into one, the same sources fit.
        assertThat(lines(tight.interpret(UUID.randomUUID(), UUID.randomUUID(),
                parse(materials(note(1) + "," + note(2) + "," + note(3), "{\"notesMode\":\"MERGE_INTO_ONE\"}")), 10)))
                .containsExactly("MATERIAL_MEDIUMx1");
    }

    private void assertLimit(Runnable action, String limit, Map<String, Object> limits) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResourceLimitExceededException.class,
                failure -> assertThat(failure.extension().members()).containsEntry("limit", limit).containsEntry("limits", limits));
    }

    @Test
    void exactQuantityIsTargetsTimesPerTargetAndAutoIsFiveOrWhatTheSessionLimitAllows() {
        assertThat(lines(interpret(exercises(4, "{\"quantity\":{\"mode\":\"EXACT\",\"perTarget\":3}}"))))
                .containsExactly("EXERCISES_PER_MATERIALx12");
        assertThat(lines(interpret(exercises(1, "{}")))).containsExactly("EXERCISES_PER_MATERIALx5");
        assertThat(lines(interpret(exercises(3, "{\"quantity\":{\"mode\":\"AUTO\"},\"mechanics\":\"AUTO\"}"))))
                .containsExactly("EXERCISES_PER_MATERIALx15");
        assertThat(lines(interpret(exercises(13, "{}")))).containsExactly("EXERCISES_PER_MATERIALx52");
        assertThat(lines(interpret(exercises(20, "{}")))).containsExactly("EXERCISES_PER_MATERIALx60");
        assertThat(lines(interpret("{\"kind\":\"EXERCISES\",\"targets\":[" + targets(2) + "]}")))
                .containsExactly("EXERCISES_PER_MATERIALx10");
    }

    @Test
    void budgetPercentBuysWhatThatShareOfTheRemainingBudgetAffords() {
        String quantity = "{\"quantity\":{\"mode\":\"BUDGET_PERCENT\",\"percent\":10}}";
        // 10% of 100 credits is 10; eight credits buy five exercises, so six whole ones fit.
        assertThat(lines(interpret(exercises(2, quantity), 100))).containsExactly("EXERCISES_PER_MATERIALx6");
        // Never fewer than one per target, never more than ten per target or sixty in all.
        assertThat(lines(interpret(exercises(3, quantity), 0))).containsExactly("EXERCISES_PER_MATERIALx3");
        assertThat(lines(interpret(exercises(2, quantity.replace("10", "100")), 5_000))).containsExactly("EXERCISES_PER_MATERIALx20");
        assertThat(lines(interpret(exercises(20, quantity.replace("10", "100")), 5_000))).containsExactly("EXERCISES_PER_MATERIALx60");
        assertThat(interpret(exercises(2, "{\"budgetPercent\":25}"), 100).budgetPercent()).isEqualTo(25);
    }

    @Test
    void limitsAboveTheOwnerDecisionsAreRefusedWithTheLimitAndTheLimits() {
        Map<String, Object> all = Map.of("maxExerciseTargets", 20, "maxExercisesPerTarget", 10, "maxExercisesPerSession", 60);
        assertLimit(() -> interpret(exercises(21, "{}")), "EXERCISE_TARGETS", all);
        assertLimit(() -> interpret(exercises(2, "{\"quantity\":{\"mode\":\"EXACT\",\"perTarget\":11}}")), "EXERCISES_PER_TARGET", all);
        assertLimit(() -> interpret(exercises(7, "{\"quantity\":{\"mode\":\"EXACT\",\"perTarget\":10}}")), "EXERCISES_PER_SESSION", all);
        // At the limits exactly it is accepted.
        assertThat(lines(interpret(exercises(6, "{\"quantity\":{\"mode\":\"EXACT\",\"perTarget\":10}}"))))
                .containsExactly("EXERCISES_PER_MATERIALx60");
        var small = new StandardSpecInterpreter(new GenerationLimits(20, 20, 70, 10, 60));
        assertThatThrownBy(() -> small.interpret(UUID.randomUUID(), UUID.randomUUID(), parse(exercises(61, "{}")), 10))
                .isInstanceOfSatisfying(ResourceLimitExceededException.class, failure -> assertThat(failure.extension().members())
                        .containsEntry("limit", "EXERCISES_PER_SESSION"));
    }

    @Test
    void anExercisesSpecWithABadShapeIsInvalid() {
        invalid("{\"kind\":\"EXERCISES\"}");
        invalid("{\"kind\":\"EXERCISES\",\"targets\":[]}");
        invalid("{\"kind\":\"EXERCISES\",\"targets\":{}}");
        invalid("{\"kind\":\"EXERCISES\",\"targets\":[{\"memberKey\":\"44444444-4444-4444-8444-444444444444\"}]}");
        invalid("{\"kind\":\"EXERCISES\",\"targets\":[" + targets(1) + "," + targets(1) + "]}");
        invalid(exercises(1, "{\"quantity\":{\"mode\":\"EXACT\",\"perTarget\":0}}"));
        invalid(exercises(1, "{\"quantity\":{\"mode\":\"EXACT\"}}"));
        invalid(exercises(1, "{\"quantity\":{\"mode\":\"EXACT\",\"perTarget\":2,\"percent\":3}}"));
        invalid(exercises(1, "{\"quantity\":{\"mode\":\"AUTO\",\"perTarget\":2}}"));
        invalid(exercises(1, "{\"quantity\":{\"mode\":\"BUDGET_PERCENT\",\"percent\":0}}"));
        invalid(exercises(1, "{\"quantity\":{\"mode\":\"BUDGET_PERCENT\",\"percent\":101}}"));
        invalid(exercises(1, "{\"quantity\":{\"mode\":\"WHATEVER\"}}"));
        invalid(exercises(1, "{\"quantity\":{\"mode\":\"EXACT\",\"perTarget\":2.5}}"));
        invalid(exercises(1, "{\"mechanics\":[]}"));
        invalid(exercises(1, "{\"mechanics\":\"SOME\"}"));
        invalid(exercises(1, "{\"mechanics\":[\"CLOZE\",\"CLOZE\"]}"));
        invalid(exercises(1, "{\"mechanics\":[\"DRAWING\"]}"));
        invalid(exercises(1, "{\"mechanics\":[3]}"));
        invalid(exercises(1, "{\"priority\":\"RANDOM\"}"));
        invalid(exercises(1, "{\"extra\":1}"));
        assertThat(lines(interpret(exercises(1, "{\"mechanics\":[\"CLOZE\",\"ORDER\"],\"priority\":\"BALANCED\",\"planFirst\":true}"))))
                .containsExactly("EXERCISES_PER_MATERIALx5");
    }

    @Test
    void reviseSpecsAreNotSupportedYet() {
        for (String kind : List.of("REVISE_ITEM", "REVISE_EXERCISE")) {
            assertThatThrownBy(() -> interpret("{\"kind\":\"" + kind + "\",\"target\":{}}"))
                    .isInstanceOfSatisfying(SpecNotSupportedException.class,
                            failure -> assertThat(failure.extension().members()).containsEntry("kind", kind));
        }
    }

    private static String noteWith(int n, String overrides) {
        String note = note(n);
        return note.substring(0, note.length() - 1) + ",\"overrides\":" + overrides + "}";
    }

    @Test
    void perNoteOverridesPriceEachMaterialAtItsEffectiveSettings() {
        String sources = noteWith(1, "{\"effort\":\"DETAILED\",\"media\":{\"audio\":{\"enabled\":true,\"lang\":\"ko\",\"voice\":null},"
                + "\"imageSearch\":false}}") + "," + note(2) + "," + noteWith(3, "{\"effort\":\"SHORT\"}");
        var result = interpret(materials(sources, "{\"effort\":\"MEDIUM\",\"media\":{\"imageSearch\":true}}"));
        // note 1: detailed + audio, no image; note 2: session values (medium + image); note 3: short + image
        assertThat(lines(result)).containsExactlyInAnyOrder("MATERIAL_DETAILEDx1", "MATERIAL_MEDIUMx1", "MATERIAL_SHORTx1",
                "TTS_CLIP_30Sx1", "IMAGE_SEARCHx2");
        // without overrides the lines are the old session-wide ones
        assertThat(lines(interpret(materials(note(1) + "," + note(2), "{\"effort\":\"MEDIUM\"}")))).containsExactly("MATERIAL_MEDIUMx2");
        // an override that equals the session value prices the same as none
        assertThat(lines(interpret(materials(noteWith(1, "{\"effort\":\"MEDIUM\"}") + "," + note(2), "{\"effort\":\"MEDIUM\"}"))))
                .containsExactly("MATERIAL_MEDIUMx2");
    }

    @Test
    void overridesAreStrictSparseAndOnlyForNotesWrittenOnePerNote() {
        String settings = "{\"effort\":\"MEDIUM\"}";
        String merge = "{\"effort\":\"MEDIUM\",\"notesMode\":\"MERGE_INTO_ONE\"}";
        for (String bad : List.of("{}", "[]", "\"DETAILED\"", "null", "{\"effort\":\"HUGE\"}", "{\"effort\":null}", "{\"factCheck\":true}",
                "{\"effort\":\"SHORT\",\"extra\":1}", "{\"media\":{}}", "{\"media\":{\"video\":true}}", "{\"media\":{\"audio\":{}}}",
                "{\"media\":{\"audio\":{\"enabled\":\"yes\"}}}", "{\"media\":{\"audio\":{\"enabled\":true,\"voice\":\"robot\"}}}",
                "{\"media\":{\"audio\":{\"enabled\":true,\"lang\":\"not a language\"}}}", "{\"media\":{\"imageSearch\":1}}")) {
            assertThatThrownBy(() -> interpret(materials(noteWith(1, bad), settings))).as(bad).isInstanceOf(InvalidRequestException.class);
        }
        // merged notes: one material, nothing to override
        assertThatThrownBy(() -> interpret(materials(noteWith(1, "{\"effort\":\"SHORT\"}") + "," + note(2), merge)))
                .isInstanceOf(InvalidRequestException.class);
        // an override is only for a note, only for a SOURCE
        String item = "{\"role\":\"SOURCE\",\"type\":\"ITEM\",\"memberKey\":\"44444444-4444-4444-8444-444444444444\","
                + "\"itemRevisionId\":\"55555555-5555-4555-8555-555555555555\",\"overrides\":{\"effort\":\"SHORT\"}}";
        assertThatThrownBy(() -> interpret(materials(item, settings))).isInstanceOf(InvalidRequestException.class);
        String style = noteWith(1, "{\"effort\":\"SHORT\"}").replace("\"SOURCE\"", "\"STYLE_EXAMPLE\"");
        assertThatThrownBy(() -> interpret(materials(style, settings))).isInstanceOf(InvalidRequestException.class);
        // a spec without notes has nothing to override either
        assertThat(lines(interpret(materials(note(1), settings)))).containsExactly("MATERIAL_MEDIUMx1");
    }

    @Test
    void theContractsOverridesExamplePricesEachNoteAtItsOwnSettings() {
        var result = interpreter.interpret(UUID.randomUUID(), UUID.randomUUID(), ContractExamples.generation("specMaterialsOverrides"), 308);
        assertThat(lines(result)).containsExactlyInAnyOrder("MATERIAL_DETAILEDx1", "MATERIAL_SHORTx1", "TTS_CLIP_30Sx1", "IMAGE_SEARCHx1");
    }
}
