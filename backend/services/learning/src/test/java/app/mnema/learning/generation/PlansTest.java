package app.mnema.learning.generation;

import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.usage.AdmissionPricing;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The plan of a plan-first session without a database or a model (#295, decision 17): what the model's answer may say and how the server reads it
 * (findings that send it back, a trim to the hold, silent cuts of text), how the owner's plan is read (strict, never clamped) and the wire shape.
 */
class PlansTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID DECK = UUID.randomUUID();

    private final AdmissionPricing pricing = mock(AdmissionPricing.class);
    private final GenerationRepository repository = mock(GenerationRepository.class);
    private final Plans plans = new Plans(pricing, repository, 10, 60, 20);

    PlansTest() {
        when(pricing.exerciseCredits(anyInt())).thenAnswer(call -> (8 * (int) call.getArgument(0) + 4) / 5);
        when(pricing.credits(anyString())).thenAnswer(call -> switch ((String) call.getArgument(0)) {
            case "MATERIAL_SHORT" -> 4;
            case "MATERIAL_MEDIUM" -> 10;
            case "MATERIAL_DETAILED" -> 22;
            case "TTS_CLIP_30S" -> 10;
            case "IMAGE_SEARCH" -> 1;
            case "FACTCHECK_LOW" -> 15;
            default -> throw new IllegalArgumentException();
        });
    }

    // ------------------------------------------------------------------------ fixtures

    private final UUID[] members = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
    private final UUID[] notes = {UUID.randomUUID(), UUID.randomUUID()};

    private Plans.Basis exercises(String settings) throws Exception {
        ObjectNode spec = JSON.createObjectNode().put("kind", "EXERCISES");
        ArrayNode targets = spec.putArray("targets");
        for (UUID member : members) targets.addObject().put("memberKey", member.toString()).put("itemRevisionId", UUID.randomUUID().toString());
        spec.set("settings", JSON.readTree(settings));
        return Plans.Basis.of(session("EXERCISES", spec));
    }

    private Plans.Basis materials(String settings, boolean withNotes) throws Exception {
        ObjectNode spec = JSON.createObjectNode().put("kind", "MATERIALS").put("prompt", "тема");
        if (withNotes) {
            ArrayNode sources = spec.putArray("sources");
            for (UUID note : notes) {
                sources.addObject().put("role", "SOURCE").put("type", "NOTE").put("noteId", note.toString()).put("noteRowVersion", "0");
            }
        }
        spec.set("settings", JSON.readTree(settings));
        return Plans.Basis.of(session("MATERIALS", spec));
    }

    private static Session session(String kind, JsonNode spec) {
        return new Session(UUID.randomUUID(), OWNER, DECK, kind, "PLAN_READY", null, spec, UUID.randomUUID(), 3, 0, null, null, null);
    }

    private Plans.Draft ok(Plans.Parsed parsed) {
        assertThat(parsed).isInstanceOf(Plans.Parsed.Ok.class);
        return ((Plans.Parsed.Ok) parsed).draft();
    }

    private String invalid(Plans.Parsed parsed) {
        assertThat(parsed).isInstanceOf(Plans.Parsed.Invalid.class);
        return ((Plans.Parsed.Invalid) parsed).findings();
    }

    private JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    // ----------------------------------------------------------------- the model: exercises

    @Test
    void aValidExercisesPlanIsReadWithTheIdentifiersOfTheSpecAndTheMechanicsInRegistryOrder() throws Exception {
        Plans.Basis basis = exercises("{\"mechanics\":[\"CHOICE\",\"CLOZE\",\"ORDER\"]}");
        Plans.Draft draft = ok(plans.fromModel(json("{\"items\":[{\"target\":\"m3\",\"mechanics\":[\"ORDER\",\"CLOZE\",\"ORDER\"],\"count\":2,"
                + "\"why\":\"" + "я".repeat(300) + "\"},{\"target\":\"m1\",\"mechanics\":\"AUTO\",\"count\":1}]}"), basis, 100));
        assertThat(draft.exercises()).extracting(Plans.ExerciseItem::memberKey).containsExactly(members[2], members[0]);
        // a duplicate is dropped and the registry order wins, so the same set is always shown the same way
        assertThat(draft.exercises().get(0).mechanics()).containsExactly("CLOZE", "ORDER");
        assertThat(draft.exercises().get(1).mechanics()).containsExactly("CLOZE", "CHOICE", "ORDER");
        // a reason that is too long is cut, not refused
        assertThat(draft.exercises().get(0).why()).hasSize(Plans.MAX_WHY);
        assertThat(draft.exercises().get(1).why()).isEmpty();
        assertThat(draft.notes()).isEmpty();
        assertThat(plans.cost(basis, draft)).isEqualTo(5);
        assertThat(draft.artifacts()).isEqualTo(3);
        assertThat(draft.size()).isEqualTo(2);
    }

    @Test
    void aShapeThatIsNotAPlanIsFoundAndSentBack() throws Exception {
        Plans.Basis basis = exercises("{\"mechanics\":[\"CLOZE\",\"CHOICE\"]}");
        assertThat(invalid(plans.fromModel(null, basis, 100))).contains("SCHEMA_INVALID");
        assertThat(invalid(plans.fromModel(json("{\"items\":\"нет\"}"), basis, 100))).contains("SCHEMA_INVALID");
        assertThat(invalid(plans.fromModel(json("{\"items\":[]}"), basis, 100))).contains("SCHEMA_INVALID");
        String findings = invalid(plans.fromModel(json("{\"items\":[5,{\"target\":\"m9\",\"count\":1},{\"target\":\"m1\",\"count\":1},"
                + "{\"target\":\"m1\",\"count\":1},{\"target\":\"m2\",\"count\":0},{\"target\":\"m2\",\"count\":11},"
                + "{\"target\":\"m2\",\"count\":\"2\"},{\"target\":\"m3\",\"count\":1,\"mechanics\":[\"MATCH\"]},"
                + "{\"target\":\"m3\",\"count\":1,\"mechanics\":[]},{\"target\":\"m3\",\"count\":1,\"mechanics\":{}}]}"), basis, 100));
        assertThat(findings).contains("items[0]: NOT_AN_OBJECT", "items[1].target: UNKNOWN_TARGET", "items[3].target: DUPLICATE_TARGET",
                "items[4].count: OUT_OF_RANGE", "items[5].count: OUT_OF_RANGE", "items[6].count: OUT_OF_RANGE", "items[7].mechanics: NOT_ALLOWED",
                "items[8].mechanics: NOT_ALLOWED");
    }

    @Test
    void aPlanDearerThanTheHoldOrAboveTheSessionLimitIsTrimmedFromTheEndAndNoted() throws Exception {
        Plans.Basis basis = exercises("{}");
        // 4 + 4 + 4 = 12 exercises cost 20; the hold of 10 buys 6: the last counts go first, whole items after
        Plans.Draft trimmed = ok(plans.fromModel(json("{\"items\":[{\"target\":\"m1\",\"count\":4},{\"target\":\"m2\",\"count\":4},{\"target\":\"m3\",\"count\":4}]}"),
                basis, 10));
        assertThat(trimmed.exercises()).extracting(Plans.ExerciseItem::count).containsExactly(4, 2);
        assertThat(trimmed.notes()).containsExactly("TRIMMED_TO_BUDGET");
        assertThat(plans.cost(basis, trimmed)).isEqualTo(10);
        // a hold that buys nothing leaves nothing to propose
        assertThat(invalid(plans.fromModel(json("{\"items\":[{\"target\":\"m1\",\"count\":4}]}"), basis, 0))).contains("NOTHING_FITS_THE_BUDGET");
        // the session limit trims as well, even when the hold is generous (a small limit makes the case without 7 targets)
        Plans small = new Plans(pricing, repository, 10, 5, 20);
        Plans.Draft limited = ok(small.fromModel(json("{\"items\":[{\"target\":\"m1\",\"count\":3},{\"target\":\"m2\",\"count\":3}]}"), basis, 1_000));
        assertThat(limited.artifacts()).isEqualTo(5);
        assertThat(limited.notes()).containsExactly("TRIMMED_TO_BUDGET");
    }

    // ----------------------------------------------------------------- the model: materials

    @Test
    void aMaterialsPlanFollowsTheNotesTheTitleIsCutAndAFixedEffortWins() throws Exception {
        Plans.Basis auto = materials("{\"effort\":\"AUTO\"}", true);
        Plans.Draft draft = ok(plans.fromModel(json("{\"items\":[{\"source\":\"n2\",\"title\":\"  " + "т".repeat(200) + "  \",\"effort\":\"DETAILED\",\"why\":\"почему\"},"
                + "{\"source\":\"n1\",\"title\":\"Вторая\"},{\"source\":\"n1\",\"title\":\"Третья\",\"effort\":\"AUTO\"}]}"), auto, 1_000));
        assertThat(draft.materials()).extracting(Plans.MaterialItem::noteId).containsExactly(notes[1], notes[0], notes[0]);
        assertThat(draft.materials().get(0).title()).hasSize(Plans.MAX_TITLE);
        assertThat(draft.materials()).extracting(Plans.MaterialItem::effort).containsExactly("DETAILED", "MEDIUM", "MEDIUM");
        assertThat(plans.cost(auto, draft)).isEqualTo(22 + 10 + 10);

        // a spec that fixes the effort wins over the model's
        Plans.Basis fixed = materials("{\"effort\":\"SHORT\"}", true);
        assertThat(ok(plans.fromModel(json("{\"items\":[{\"source\":\"n1\",\"title\":\"Т\",\"effort\":\"DETAILED\"}]}"), fixed, 100)).materials().getFirst().effort())
                .isEqualTo("SHORT");
        // a note the spec does not have, no title and a word that is no effort are findings
        String findings = invalid(plans.fromModel(json("{\"items\":[{\"source\":\"n3\",\"title\":\"Т\"},{\"source\":\"n1\",\"title\":\"  \"},"
                + "{\"source\":\"n1\",\"title\":\"Т\",\"effort\":\"HUGE\"},{\"title\":\"Т\"}]}"), auto, 100));
        assertThat(findings).contains("items[0].source: UNKNOWN_SOURCE", "items[1].title: MISSING", "items[2].effort: NOT_ALLOWED", "items[3].source: UNKNOWN_SOURCE");
        // too much for the hold: items leave from the end
        Plans.Draft trimmed = ok(plans.fromModel(json("{\"items\":[{\"source\":\"n1\",\"title\":\"А\"},{\"source\":\"n2\",\"title\":\"Б\"}]}"), auto, 10));
        assertThat(trimmed.materials()).hasSize(1);
        assertThat(trimmed.notes()).containsExactly("TRIMMED_TO_BUDGET");
    }

    @Test
    void aPromptOnlyOrMergedPlanHasNoSourceWhateverTheModelSays() throws Exception {
        for (Plans.Basis basis : List.of(materials("{}", false), materials("{\"notesMode\":\"MERGE_INTO_ONE\"}", true))) {
            assertThat(basis.perNote()).isFalse();
            Plans.Draft draft = ok(plans.fromModel(json("{\"items\":[{\"source\":\"n1\",\"title\":\"А\"},{\"title\":\"Б\",\"effort\":\"SHORT\"}]}"), basis, 1_000));
            assertThat(draft.materials()).allSatisfy(item -> assertThat(item.noteId()).isNull());
        }
    }

    @Test
    void aMaterialIsPricedWithTheMediaAndTheResearchOfItsOwnSettings() throws Exception {
        Plans.Basis basis = materials("{\"effort\":\"MEDIUM\",\"factCheck\":true,\"media\":{\"audio\":{\"enabled\":true},\"imageSearch\":true}}", true);
        assertThat(plans.materialCredits(basis, notes[0], "MEDIUM")).isEqualTo(10 + 10 + 1 + 15);
        // no research for the short effort (architecture section 14)
        assertThat(plans.materialCredits(basis, notes[0], "SHORT")).isEqualTo(4 + 10 + 1);
        assertThat(plans.materialCredits(materials("{}", false), null, "DETAILED")).isEqualTo(22);
    }

    // ------------------------------------------------------------------------ the owner

    @Test
    void theOwnersPlanIsReadStrictlyWithoutClampingAnything() throws Exception {
        Plans.Basis basis = exercises("{\"mechanics\":[\"CLOZE\",\"CHOICE\"]}");
        String first = "{\"memberKey\":\"" + members[0] + "\",\"mechanics\":[\"CHOICE\"],\"count\":3}";
        Plans.Draft draft = plans.fromOwner(json("{\"items\":[" + first + ",{\"memberKey\":\"" + members[2]
                + "\",\"mechanics\":[\"CHOICE\",\"CLOZE\"],\"count\":1}]}"), basis, Map.of(members[0], "почему"));
        assertThat(draft.exercises()).extracting(Plans.ExerciseItem::memberKey).containsExactly(members[0], members[2]);
        assertThat(draft.exercises().get(0).why()).isEqualTo("почему");
        assertThat(draft.exercises().get(1).why()).isNull();
        assertThat(draft.exercises().get(1).mechanics()).containsExactly("CLOZE", "CHOICE");

        for (String bad : List.of("{}", "{\"items\":[]}", "{\"items\":{}}", "{\"items\":[" + first + "],\"x\":1}", "{\"items\":[5]}",
                "{\"items\":[{\"memberKey\":\"" + UUID.randomUUID() + "\",\"mechanics\":[\"CLOZE\"],\"count\":1}]}",
                "{\"items\":[" + first + "," + first + "]}",
                "{\"items\":[{\"memberKey\":\"" + members[0] + "\",\"mechanics\":[],\"count\":1}]}",
                "{\"items\":[{\"memberKey\":\"" + members[0] + "\",\"mechanics\":[\"MATCH\"],\"count\":1}]}",
                "{\"items\":[{\"memberKey\":\"" + members[0] + "\",\"mechanics\":[\"CLOZE\",\"CLOZE\"],\"count\":1}]}",
                "{\"items\":[{\"memberKey\":\"" + members[0] + "\",\"mechanics\":[\"CLOZE\"],\"count\":0}]}",
                "{\"items\":[{\"memberKey\":\"" + members[0] + "\",\"mechanics\":[\"CLOZE\"],\"count\":1.5}]}",
                "{\"items\":[{\"memberKey\":\"" + members[0] + "\",\"mechanics\":[\"CLOZE\"],\"count\":1,\"why\":\"x\"}]}")) {
            assertThatThrownBy(() -> plans.fromOwner(json(bad), basis, Map.of())).as(bad).isInstanceOf(InvalidRequestException.class);
        }

        // a count above the limits is not clamped: it is a refusal that names the limit
        Plans.Draft big = plans.fromOwner(json("{\"items\":[{\"memberKey\":\"" + members[0] + "\",\"mechanics\":[\"CLOZE\"],\"count\":11}]}"), basis, Map.of());
        assertThatThrownBy(() -> plans.requireWithinLimits(big)).isInstanceOf(ResourceLimitExceededException.class)
                .satisfies(refusal -> assertThat(((ResourceLimitExceededException) refusal).extension().members()).containsEntry("limit", "EXERCISES_PER_TARGET"));
        Plans small = new Plans(pricing, repository, 10, 5, 2);
        Plans.Draft total = plans.fromOwner(json("{\"items\":[{\"memberKey\":\"" + members[0] + "\",\"mechanics\":[\"CLOZE\"],\"count\":3},{\"memberKey\":\""
                + members[1] + "\",\"mechanics\":[\"CLOZE\"],\"count\":3}]}"), basis, Map.of());
        assertThatThrownBy(() -> small.requireWithinLimits(total)).isInstanceOf(ResourceLimitExceededException.class)
                .satisfies(refusal -> assertThat(((ResourceLimitExceededException) refusal).extension().members()).containsEntry("limit", "EXERCISES_PER_SESSION"));
        plans.requireWithinLimits(total);
    }

    @Test
    void theOwnersMaterialsPlanNamesItsNotesAndHasValidTitlesAndEfforts() throws Exception {
        Plans.Basis perNote = materials("{}", true);
        Plans.Draft draft = plans.fromOwner(json("{\"items\":[{\"source\":\"" + notes[1] + "\",\"title\":\" Тема \",\"effort\":\"SHORT\"},"
                + "{\"source\":\"" + notes[1] + "\",\"title\":\"Ещё\",\"effort\":\"DETAILED\"}]}"), perNote, Map.of());
        assertThat(draft.materials()).extracting(Plans.MaterialItem::title).containsExactly("Тема", "Ещё");
        assertThat(draft.materials()).extracting(Plans.MaterialItem::noteId).containsExactly(notes[1], notes[1]);
        for (String bad : List.of("{\"items\":[{\"source\":null,\"title\":\"Т\",\"effort\":\"SHORT\"}]}",
                "{\"items\":[{\"source\":\"" + UUID.randomUUID() + "\",\"title\":\"Т\",\"effort\":\"SHORT\"}]}",
                "{\"items\":[{\"source\":\"" + notes[0] + "\",\"title\":\" \",\"effort\":\"SHORT\"}]}",
                "{\"items\":[{\"source\":\"" + notes[0] + "\",\"title\":\"" + "т".repeat(161) + "\",\"effort\":\"SHORT\"}]}",
                "{\"items\":[{\"source\":\"" + notes[0] + "\",\"title\":\"Т\",\"effort\":\"AUTO\"}]}",
                "{\"items\":[{\"source\":\"" + notes[0] + "\",\"title\":\"Т\"}]}")) {
            assertThatThrownBy(() -> plans.fromOwner(json(bad), perNote, Map.of())).as(bad).isInstanceOf(InvalidRequestException.class);
        }
        Plans.Basis promptOnly = materials("{}", false);
        assertThat(plans.fromOwner(json("{\"items\":[{\"source\":null,\"title\":\"Т\",\"effort\":\"SHORT\"}]}"), promptOnly, Map.of()).materials()).hasSize(1);
        assertThatThrownBy(() -> plans.fromOwner(json("{\"items\":[{\"source\":\"" + notes[0] + "\",\"title\":\"Т\",\"effort\":\"SHORT\"}]}"), promptOnly, Map.of()))
                .isInstanceOf(InvalidRequestException.class);
        StringBuilder many = new StringBuilder("{\"items\":[");
        for (int index = 0; index < 3; index++) many.append(index == 0 ? "" : ",").append("{\"source\":null,\"title\":\"Т\",\"effort\":\"SHORT\"}");
        Plans.Draft three = plans.fromOwner(json(many + "]}"), promptOnly, Map.of());
        assertThatThrownBy(() -> new Plans(pricing, repository, 10, 60, 2).requireWithinLimits(three)).isInstanceOf(ResourceLimitExceededException.class)
                .satisfies(refusal -> assertThat(((ResourceLimitExceededException) refusal).extension().members()).containsEntry("limit", "ARTIFACTS_PER_SESSION"));
    }

    // -------------------------------------------------------------------- wire and storage

    @Test
    void theWirePlanHasTheShapeOfTheContractAndReadsBackAsTheSamePlan() throws Exception {
        Plans.Basis basis = exercises("{\"mechanics\":[\"CLOZE\",\"CHOICE\"]}");
        Plans.Draft draft = ok(plans.fromModel(json("{\"items\":[{\"target\":\"m2\",\"mechanics\":[\"CLOZE\"],\"count\":5,\"why\":\"мало\"}]}"), basis, 100));
        ArrayNode targets = JSON.createArrayNode();
        for (int index = 0; index < members.length; index++) {
            targets.addObject().put("memberKey", members[index].toString()).put("title", "Материал " + index).put("exercises", index);
        }
        ObjectNode wire = plans.wire(basis, draft, targets, JSON.createArrayNode(), 20, 8, 360, false);
        assertThat(wire.path("kind").stringValue(null)).isEqualTo("EXERCISES");
        assertThat(wire.path("approved").booleanValue()).isFalse();
        assertThat(wire.path("items").get(0).path("title").stringValue(null)).isEqualTo("Материал 1");
        assertThat(wire.path("items").get(0).path("why").stringValue(null)).isEqualTo("мало");
        assertThat(wire.path("totals").path("artifacts").intValue()).isEqualTo(5);
        assertThat(wire.path("cost").toString()).isEqualTo("{\"planCredits\":20,\"batchCredits\":8,\"holdCredits\":8,\"barCredits\":360}");
        assertThat(wire.path("rates").path("exercisesPerFive").intValue()).isEqualTo(8);
        assertThat(wire.path("allowedMechanics")).extracting(JsonNode::stringValue).containsExactly("CLOZE", "CHOICE");
        assertThat(wire.path("targets")).hasSize(3);
        Plans.Draft back = plans.read(wire);
        assertThat(back.exercises()).isEqualTo(draft.exercises());
        assertThat(Plans.whyOf(wire)).containsEntry(members[1], "мало");

        Plans.Basis perNote = materials("{}", true);
        Plans.Draft materialsDraft = ok(plans.fromModel(json("{\"items\":[{\"source\":\"n1\",\"title\":\"Т\",\"effort\":\"SHORT\"}]}"), perNote, 100));
        ObjectNode materialsWire = plans.wire(perNote, materialsDraft, JSON.createArrayNode(), JSON.createArrayNode(), 20, 100, 360, true);
        assertThat(materialsWire.path("approved").booleanValue()).isTrue();
        assertThat(materialsWire.path("items").get(0).path("creditsByEffort").toString()).isEqualTo("{\"SHORT\":4,\"MEDIUM\":10,\"DETAILED\":22}");
        assertThat(materialsWire.path("limits").path("maxArtifactsPerSession").intValue()).isEqualTo(20);
        assertThat(plans.read(materialsWire).materials()).isEqualTo(materialsDraft.materials());
        // a trimmed plan says so in words
        Plans.Draft trimmed = new Plans.Draft("MATERIALS", List.of(), materialsDraft.materials(), List.of("TRIMMED_TO_BUDGET"));
        assertThat(plans.wire(perNote, trimmed, JSON.createArrayNode(), JSON.createArrayNode(), 20, 4, 360, false).path("notes").get(0).path("text").stringValue(null))
                .contains("обрезан");
        assertThat(plans.read(plans.wire(perNote, trimmed, JSON.createArrayNode(), JSON.createArrayNode(), 20, 4, 360, false)).notes())
                .containsExactly("TRIMMED_TO_BUDGET");
    }

    @Test
    void aPlannedMaterialIsTheItemOfTheApprovedPlanAtTheOrdinalOfItsArtifact() throws Exception {
        Plans.Basis basis = materials("{}", true);
        Session session = session("MATERIALS", JSON.createObjectNode().put("kind", "MATERIALS"));
        Plans.Draft draft = new Plans.Draft("MATERIALS", List.of(), List.of(new Plans.MaterialItem(notes[0], "Первая", "SHORT", ""),
                new Plans.MaterialItem(null, "Вторая", "DETAILED", "")), List.of());
        ObjectNode wire = plans.wire(basis, draft, JSON.createArrayNode(), JSON.createArrayNode(), 20, 26, 360, true);
        when(repository.plan(session.sessionId())).thenReturn(Optional.<JsonNode>of(wire));
        assertThat(plans.plannedMaterial(session, artifact(1))).hasValueSatisfying(item -> {
            assertThat(item.title()).isEqualTo("Вторая");
            assertThat(item.effort()).isEqualTo("DETAILED");
        });
        assertThat(plans.plannedMaterial(session, artifact(2))).isEmpty();
        // a plan that is not approved yet is not a work order, and a session of another kind has none
        wire.put("approved", false);
        assertThat(plans.plannedMaterial(session, artifact(0))).isEmpty();
        assertThat(plans.plannedMaterial(session("EXERCISES", JSON.createObjectNode()), artifact(0))).isEmpty();
    }

    private static Artifact artifact(int ordinal) {
        return new Artifact(UUID.randomUUID(), UUID.randomUUID(), OWNER, "ITEM", ordinal, "QUEUED", null, null, "", null, 0, 0,
                JSON.createArrayNode(), null, 0);
    }
}
