package app.mnema.learning.generation.exercise;

import app.mnema.learning.generation.exercise.ExerciseFixtures.Fixture;
import app.mnema.learning.generation.exercise.ExerciseFixtures.GoldenIds;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inverse of the compiler for REVISE_EXERCISE (#294): every committed valid fixture is turned back into the output form that
 * validates against the schema and compiles to the very exercise it came from, with the identifiers the model keeps; what the output form
 * cannot express is not decompiled (the owner edits it by hand), and audio blocks of the prompt are set aside and put back.
 */
class ExerciseDecompilerTest {
    private static final ExerciseOutputSchema SCHEMA = ExerciseOutputSchema.load();

    private static JsonNode stored(Fixture fixture) {
        return fixture.json().path("expectedCommand").path("exercise");
    }

    @Test
    void everyValidFixtureRoundTripsToTheSameExerciseAndTheDecompiledFormIsValid() {
        int seen = 0;
        for (Fixture fixture : ExerciseFixtures.all()) {
            if (!fixture.valid()) continue;
            seen++;
            ExerciseContext context = fixture.context();
            Optional<ExerciseDecompiler.Decompiled> decompiled = ExerciseDecompiler.decompile(stored(fixture), context);
            assertThat(decompiled).as(fixture.name()).isPresent();
            ObjectNode model = decompiled.get().model();
            assertThat(SCHEMA.exercise(model)).as(fixture.name() + " is valid output").isEmpty();
            assertThat(model.path("subject").stringValue(null)).isEqualTo("m1");
            assertThat(model.path("objective").path("ref").stringValue(null)).isEqualTo("t1");

            // the model keeps every local id: the compiled exercise has the identifiers of the stored one, none is allocated
            ExerciseCompiler.Compiled again = ExerciseCompiler.compile(model, context, ids -> {
                throw new AssertionError("an identifier was allocated for a kept local id");
            }, decompiled.get().ids());
            assertThat(again.command().path("exercise").path("content")).as(fixture.name() + " content")
                    .isEqualTo(stored(fixture).path("content"));
            assertThat(again.command().path("exercise").path("answerKey")).as(fixture.name() + " key").isEqualTo(stored(fixture).path("answerKey"));
        }
        assertThat(seen).isGreaterThanOrEqualTo(8);
    }

    @Test
    void aLocalIdTheModelAddsGetsANewIdentifierAndTheOnesItKeepsKeepTheirs() {
        Fixture fixture = ExerciseFixtures.all().stream().filter(candidate -> candidate.name().equals("choice-single.json")).findFirst().orElseThrow();
        ExerciseContext context = fixture.context();
        ExerciseDecompiler.Decompiled decompiled = ExerciseDecompiler.decompile(stored(fixture), context).orElseThrow();
        ObjectNode revised = decompiled.model().deepCopy();
        ArrayNode options = (ArrayNode) revised.path("options");
        options.addObject().put("id", "o9").put("text", "Ещё один вариант").put("correct", false).put("whyWrong", "Не подходит.");
        UUID fresh = UUID.fromString("99999999-9999-4999-8999-999999999999");

        ExerciseCompiler.Compiled compiled = ExerciseCompiler.compile(revised, context, kind -> fresh, decompiled.ids());

        ArrayNode compiledOptions = (ArrayNode) compiled.command().path("exercise").path("content").path("options");
        assertThat(compiledOptions).hasSize(options.size());
        assertThat(compiledOptions.get(options.size() - 1).path("optionId").stringValue(null)).isEqualTo(fresh.toString());
        assertThat(compiledOptions.get(0).path("optionId")).isEqualTo(stored(fixture).path("content").path("options").get(0).path("optionId"));
    }

    @Test
    void anExerciseTheOutputFormHasNoRoomForIsNotDecompiled() {
        Fixture fixture = ExerciseFixtures.all().stream().filter(candidate -> candidate.name().equals("free-response-alternatives.json")).findFirst().orElseThrow();
        ExerciseContext context = fixture.context();
        ObjectNode exercise = (ObjectNode) stored(fixture).deepCopy();
        assertThat(ExerciseDecompiler.decompile(exercise, context)).isPresent();

        // an image in the prompt, a quote of another material, a speech answer and a rubric are all outside the form
        ObjectNode withImage = exercise.deepCopy();
        ((ArrayNode) withImage.path("content").path("prompt")).addObject().put("kind", "IMAGE").put("assetId", UUID.randomUUID().toString()).put("alt", "схема");
        assertThat(ExerciseDecompiler.decompile(withImage, context)).isEmpty();

        ObjectNode foreign = exercise.deepCopy();
        ((ArrayNode) foreign.path("content").path("prompt")).addObject().put("kind", "MATERIAL").put("memberKey", UUID.randomUUID().toString())
                .put("itemRevisionId", UUID.randomUUID().toString()).put("nodeId", UUID.randomUUID().toString());
        assertThat(ExerciseDecompiler.decompile(foreign, context)).isEmpty();

        ObjectNode speech = exercise.deepCopy();
        ((ObjectNode) speech.path("content")).put("responseInput", "TEXT_OR_SPEECH");
        assertThat(ExerciseDecompiler.decompile(speech, context)).isEmpty();

        ObjectNode semantic = exercise.deepCopy();
        ((ObjectNode) semantic.path("evaluatorPolicy")).put("id", "ai-semantic");
        assertThat(ExerciseDecompiler.decompile(semantic, context)).isEmpty();

        ObjectNode unknown = exercise.deepCopy();
        unknown.put("type", "SPEECH_REPEAT");
        assertThat(ExerciseDecompiler.decompile(unknown, context)).isEmpty();

        // a prompt of audio only has nothing to show the model
        ObjectNode silent = exercise.deepCopy();
        ((ObjectNode) silent.path("content")).putArray("prompt").addObject().put("kind", "AUDIO").put("assetId", UUID.randomUUID().toString())
                .put("title", "звук");
        assertThat(ExerciseDecompiler.decompile(silent, context)).isEmpty();
    }

    @Test
    void anAudioBlockOfThePromptIsSetAsideAndPutBackWhereItStood() {
        Fixture fixture = ExerciseFixtures.all().stream().filter(candidate -> candidate.name().equals("choice-single.json")).findFirst().orElseThrow();
        ExerciseContext context = fixture.context();
        ObjectNode exercise = (ObjectNode) stored(fixture).deepCopy();
        ArrayNode prompt = (ArrayNode) exercise.path("content").path("prompt");
        ObjectNode audio = JSON().createObjectNode().put("kind", "AUDIO").put("assetId", UUID.randomUUID().toString()).put("title", "Произношение");
        prompt.insert(0, audio);

        ExerciseDecompiler.Decompiled decompiled = ExerciseDecompiler.decompile(exercise, context).orElseThrow();

        // the model never sees the audio; the compiled exercise gets it back at index 0
        assertThat(decompiled.model().path("prompt")).hasSize(prompt.size() - 1);
        assertThat(decompiled.model().toString()).doesNotContain(audio.path("assetId").stringValue(null));
        assertThat(decompiled.media()).hasSize(1);
        ObjectNode compiled = (ObjectNode) ExerciseCompiler.compile(decompiled.model(), context, new GoldenIds(), decompiled.ids()).command().path("exercise");
        ObjectNode restored = decompiled.withMedia(compiled);
        assertThat(restored.path("content").path("prompt").get(0)).isEqualTo(audio);
        assertThat(restored.path("content").path("prompt")).hasSize(prompt.size());
        // a revised prompt that got shorter puts it at the end rather than losing it
        ObjectNode shorter = compiled.deepCopy();
        ((ObjectNode) shorter.path("content")).putArray("prompt").addObject().put("kind", "TEXT").put("text", "Вопрос");
        List<JsonNode> blocks = new java.util.ArrayList<>();
        decompiled.withMedia(shorter).path("content").path("prompt").forEach(blocks::add);
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).path("kind").stringValue(null)).isEqualTo("AUDIO");
    }

    private static tools.jackson.databind.json.JsonMapper JSON() {
        return ExerciseFixtures.JSON;
    }
}
