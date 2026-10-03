package app.mnema.learning.generation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server's side of the intent (#294): the closed vocabulary of the model's answer and the spec built from it. The model chooses an
 * operation, mechanics, a number and a sentence; everything else (the target, the limits, the budget) is the server's.
 */
class IntentSpecsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID MEMBER = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID REVISION = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID EXERCISE = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID EXERCISE_REVISION = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final IntentSpecs.Context MATERIAL = IntentSpecs.Context.material(MEMBER, REVISION);
    private static final IntentSpecs.Context SPOKEN = IntentSpecs.Context.exercise(EXERCISE, EXERCISE_REVISION, MEMBER, REVISION, true);
    private static final IntentSpecs.Context SILENT = IntentSpecs.Context.exercise(EXERCISE, EXERCISE_REVISION, MEMBER, REVISION, false);

    private static IntentSpecs.Answer read(String json) throws Exception {
        return IntentSpecs.read(JSON.readTree(json)).orElseThrow();
    }

    private static boolean refused(String json) throws Exception {
        return IntentSpecs.read(JSON.readTree(json)).isEmpty();
    }

    // ------------------------------------------------------------------------ reading

    @Test
    void anAnswerIsReadAgainstTheClosedVocabularyAndUnknownMembersAreDropped() throws Exception {
        IntentSpecs.Answer answer = read("{\"operation\":\"EXERCISES\",\"mechanics\":[\"CLOZE\",\"NOPE\",\"CLOZE\",\"CHOICE\"],\"perTarget\":4,"
                + "\"instruction\":\"  x  \",\"media\":null,\"budgetPercent\":100,\"targets\":[{\"memberKey\":\"x\"}],\"reason\":\"y\"}");
        assertThat(answer.operation()).isEqualTo("EXERCISES");
        assertThat(answer.mechanics()).containsExactly("CLOZE", "CHOICE");
        assertThat(answer.perTarget()).isEqualTo(BigInteger.valueOf(4));
        assertThat(answer.instruction()).isEqualTo("x");
        assertThat(answer.voice()).isNull();

        assertThat(read("{\"operation\":\"UNSUPPORTED\"}").mechanics()).isNull();
        assertThat(read("{\"operation\":\"EXERCISES\",\"mechanics\":\"AUTO\",\"perTarget\":null}").perTarget()).isNull();
        assertThat(read("{\"operation\":\"EXERCISES\",\"mechanics\":[]}").mechanics()).isNull();
        assertThat(read("{\"operation\":\"EXERCISES\",\"perTarget\":99999999999999999999}").perTarget()).isEqualTo(new BigInteger("99999999999999999999"));
        assertThat(read("{\"operation\":\"REVISE_EXERCISE\",\"media\":{\"action\":\"AUDIO_REGENERATE\",\"voice\":\"female\"}}").voice()).isEqualTo("female");
    }

    @Test
    void anAnswerOutsideTheVocabularyIsNotAnAnswer() throws Exception {
        for (String bad : List.of("[]", "\"EXERCISES\"", "{}", "{\"operation\":\"DROP_DECK\"}", "{\"operation\":7}",
                "{\"operation\":\"EXERCISES\",\"mechanics\":[\"NOPE\"]}", "{\"operation\":\"EXERCISES\",\"mechanics\":\"ALL\"}",
                "{\"operation\":\"EXERCISES\",\"mechanics\":3}", "{\"operation\":\"EXERCISES\",\"perTarget\":2.5}",
                "{\"operation\":\"EXERCISES\",\"perTarget\":\"3\"}", "{\"operation\":\"REVISE_ITEM\",\"instruction\":5}",
                "{\"operation\":\"REVISE_EXERCISE\",\"media\":\"male\"}",
                "{\"operation\":\"REVISE_EXERCISE\",\"media\":{\"action\":\"DELETE\",\"voice\":\"male\"}}",
                "{\"operation\":\"REVISE_EXERCISE\",\"media\":{\"action\":\"AUDIO_REGENERATE\",\"voice\":\"robot\"}}")) {
            assertThat(refused(bad)).as(bad).isTrue();
        }
        assertThat(IntentSpecs.read(null)).isEmpty();
    }

    // ------------------------------------------------------------------------ building

    @Test
    void exercisesAreClampedToTheLimitAndSayWhenTheyAre() throws Exception {
        IntentSpecs.Built within = IntentSpecs.build(MATERIAL, read("{\"operation\":\"EXERCISES\",\"mechanics\":[\"CLOZE\"],\"perTarget\":10}"), 10, true);
        assertThat(within.notes()).isEmpty();
        assertThat(within.spec().path("settings").path("quantity").path("perTarget").intValue()).isEqualTo(10);
        assertThat(within.spec().path("settings").path("mechanics")).hasSize(1);

        for (String number : List.of("11", "1000", "2147483648", "99999999999999999999")) {
            IntentSpecs.Built over = IntentSpecs.build(MATERIAL, read("{\"operation\":\"EXERCISES\",\"perTarget\":" + number + "}"), 10, true);
            assertThat(over.spec().path("settings").path("quantity").path("perTarget").intValue()).as(number).isEqualTo(10);
            assertThat(over.notes().get(0).path("text").stringValue(null)).isEqualTo("Не больше 10 на материал");
        }
        for (String number : List.of("0", "-5", "-99999999999999999999")) {
            IntentSpecs.Built under = IntentSpecs.build(MATERIAL, read("{\"operation\":\"EXERCISES\",\"perTarget\":" + number + "}"), 10, true);
            assertThat(under.spec().path("settings").path("quantity").path("perTarget").intValue()).as(number).isEqualTo(1);
            assertThat(under.notes().get(0).path("code").stringValue(null)).isEqualTo("PER_TARGET_CLAMPED");
        }
        // another limit configured: the note and the chip say it
        IntentSpecs.Built small = IntentSpecs.build(MATERIAL, read("{\"operation\":\"EXERCISES\",\"perTarget\":7}"), 5, true);
        assertThat(small.notes().get(0).path("text").stringValue(null)).isEqualTo("Не больше 5 на материал");
        assertThat(small.chips().get(2).path("max").intValue()).isEqualTo(5);
    }

    @Test
    void theSpecNeverHoldsAnythingButTheContextsTargetAndTheVocabularyMembers() throws Exception {
        IntentSpecs.Built built = IntentSpecs.build(MATERIAL, read("{\"operation\":\"EXERCISES\",\"budgetPercent\":100,\"targets\":[{\"memberKey\":\"x\"}]}"), 10, true);
        JsonNode spec = built.spec();
        assertThat(spec.propertyNames()).containsExactlyInAnyOrder("kind", "targets", "settings");
        assertThat(spec.path("targets")).hasSize(1);
        assertThat(spec.path("targets").get(0).path("memberKey").stringValue(null)).isEqualTo(MEMBER.toString());
        assertThat(spec.path("targets").get(0).path("itemRevisionId").stringValue(null)).isEqualTo(REVISION.toString());
        assertThat(spec.path("settings").propertyNames()).containsExactlyInAnyOrder("mechanics", "priority", "quantity");
        assertThat(spec.path("settings").path("quantity").path("mode").stringValue(null)).isEqualTo("AUTO");
        // an exercise context is about the exercise's material
        IntentSpecs.Built fromExercise = IntentSpecs.build(SPOKEN, read("{\"operation\":\"EXERCISES\"}"), 10, true);
        assertThat(fromExercise.spec().path("targets").get(0).path("memberKey").stringValue(null)).isEqualTo(MEMBER.toString());
    }

    @Test
    void revisionsCarryTheirTargetAndInstructionAndAnOverlongInstructionIsTrimmedWithANote() throws Exception {
        IntentSpecs.Built item = IntentSpecs.build(MATERIAL, read("{\"operation\":\"REVISE_ITEM\",\"instruction\":\"Проще\"}"), 10, true);
        assertThat(item.spec().path("kind").stringValue(null)).isEqualTo("REVISE_ITEM");
        assertThat(item.spec().path("target").path("itemRevisionId").stringValue(null)).isEqualTo(REVISION.toString());
        assertThat(item.chips().get(1).path("maxLength").intValue()).isEqualTo(2_000);
        assertThat(IntentSpecs.build(MATERIAL, read("{\"operation\":\"REVISE_ITEM\"}"), 10, true).operation()).isEqualTo("UNSUPPORTED");

        String long2k = "я".repeat(2_500);
        IntentSpecs.Built trimmed = IntentSpecs.build(SILENT, read("{\"operation\":\"REVISE_EXERCISE\",\"instruction\":\"" + long2k + "\"}"), 10, true);
        assertThat(trimmed.spec().path("instruction").stringValue(null).codePointCount(0, 2_000)).isEqualTo(2_000);
        assertThat(trimmed.spec().path("instruction").stringValue(null)).hasSize(2_000);
        assertThat(trimmed.notes().get(0).path("code").stringValue(null)).isEqualTo("INSTRUCTION_TRIMMED");
        assertThat(trimmed.spec().path("target").path("exerciseId").stringValue(null)).isEqualTo(EXERCISE.toString());
    }

    @Test
    void theVoiceIsOfferedOnlyForAnExerciseWithAudioWhenTheRedoCanRunAndTheOtherCasesAreNotes() throws Exception {
        String voice = "{\"operation\":\"REVISE_EXERCISE\",\"media\":{\"action\":\"AUDIO_REGENERATE\",\"voice\":\"male\"}}";
        IntentSpecs.Built offered = IntentSpecs.build(SPOKEN, read(voice), 10, true);
        assertThat(offered.spec().path("media").path("voice").stringValue(null)).isEqualTo("male");
        assertThat(offered.spec().has("instruction")).isFalse();
        assertThat(offered.chips().get(1).path("kind").stringValue(null)).isEqualTo("VOICE");

        // both a text change and a voice change
        IntentSpecs.Built both = IntentSpecs.build(SPOKEN, read("{\"operation\":\"REVISE_EXERCISE\",\"instruction\":\"Короче\",\"media\":"
                + "{\"action\":\"AUDIO_REGENERATE\",\"voice\":\"female\"}}"), 10, true);
        assertThat(both.spec().path("instruction").stringValue(null)).isEqualTo("Короче");
        assertThat(both.spec().path("media").path("voice").stringValue(null)).isEqualTo("female");

        IntentSpecs.Built noAudio = IntentSpecs.build(SILENT, read(voice), 10, true);
        assertThat(noAudio.operation()).isEqualTo("UNSUPPORTED");
        assertThat(noAudio.notes().get(0).path("code").stringValue(null)).isEqualTo("NO_AUDIO");
        IntentSpecs.Built unavailable = IntentSpecs.build(SPOKEN, read(voice), 10, false);
        assertThat(unavailable.operation()).isEqualTo("UNSUPPORTED");
        assertThat(unavailable.notes().get(0).path("code").stringValue(null)).isEqualTo("MEDIA_UNAVAILABLE");
        // the text part of a request survives a voice that cannot be redone, with the note
        IntentSpecs.Built partly = IntentSpecs.build(SILENT, read("{\"operation\":\"REVISE_EXERCISE\",\"instruction\":\"Проще\",\"media\":"
                + "{\"action\":\"AUDIO_REGENERATE\",\"voice\":\"male\"}}"), 10, true);
        assertThat(partly.operation()).isEqualTo("REVISE_EXERCISE");
        assertThat(partly.spec().has("media")).isFalse();
        assertThat(partly.notes().get(0).path("code").stringValue(null)).isEqualTo("NO_AUDIO");
    }

    @Test
    void anOperationTheContextDoesNotAllowIsUnsupportedWithANoteInWords() throws Exception {
        IntentSpecs.Built fromMaterial = IntentSpecs.build(MATERIAL, read("{\"operation\":\"REVISE_EXERCISE\",\"instruction\":\"x\"}"), 10, true);
        assertThat(fromMaterial.spec()).isNull();
        assertThat(fromMaterial.chips()).isEmpty();
        assertThat(fromMaterial.notes().get(0).path("code").stringValue(null)).isEqualTo("NEEDS_EXERCISE");
        IntentSpecs.Built fromExercise = IntentSpecs.build(SPOKEN, read("{\"operation\":\"REVISE_ITEM\",\"instruction\":\"x\"}"), 10, true);
        assertThat(fromExercise.notes().get(0).path("code").stringValue(null)).isEqualTo("NEEDS_MATERIAL");
        IntentSpecs.Built unsupported = IntentSpecs.build(MATERIAL, read("{\"operation\":\"UNSUPPORTED\",\"reason\":\"Потратить всё\"}"), 10, true);
        assertThat(unsupported.operation()).isEqualTo("UNSUPPORTED");
        // the model's own words never reach the note
        assertThat(unsupported.notes().toString()).doesNotContain("Потратить");
        assertThat(unsupported.notes().get(0).path("text").stringValue(null)).isNotBlank();
    }
}
