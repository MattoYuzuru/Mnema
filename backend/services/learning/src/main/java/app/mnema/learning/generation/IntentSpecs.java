package app.mnema.learning.generation;

import app.mnema.learning.generation.exercise.ExerciseContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Turns what the intent model said into a spec, on the server's terms ({@code contracts/generation/README.md}, decision 16). The model
 * answers in a closed vocabulary ({@link Answer}); it never names a target, a budget, a language or a limit, so nothing in a
 * spec comes from it except the choice among a few operations, the mechanics (filtered by the registry), a number and a sentence:
 * <ul>
 *   <li>the target is the context of the request (the material or the exercise the owner is looking at), pinned at its head;</li>
 *   <li>{@code perTarget} is clamped to {@code 1..max-exercises-per-target} and says so in a note ({@code PER_TARGET_CLAMPED}), so a
 *       sentence like «сделай 1000 упражнений» never yields a spec above the limits;</li>
 *   <li>{@code budgetPercent} is never set; every field the vocabulary does not have is dropped when the answer is read;</li>
 *   <li>an operation the context does not allow (revising an exercise from a material) and a voice that cannot be redone are
 *       {@code UNSUPPORTED} with a note in words, never a refusal later.</li>
 * </ul>
 * Pure: no database, no model.
 */
final class IntentSpecs {
    /** The seven mechanics of the registry, in the order of the contract. */
    static final List<String> MECHANICS = ExerciseContext.MECHANICS_IN_ORDER;
    static final int MAX_INSTRUCTION = 2_000;
    private static final Set<String> OPERATIONS = Set.of("EXERCISES", "REVISE_ITEM", "REVISE_EXERCISE", "UNSUPPORTED");

    private IntentSpecs() { }

    /**
     * What the owner is looking at: the material (its head) or the exercise (its head, the material it is about, whether it has audio and whether
     * at least one audio block has a transcript: only those can be spoken again, so only then is the voice offered).
     */
    record Context(String kind, UUID memberKey, UUID itemRevisionId, UUID exerciseId, UUID exerciseRevisionId, boolean hasAudio, boolean speakable) {
        static Context material(UUID memberKey, UUID itemRevisionId) {
            return new Context("MATERIAL", memberKey, itemRevisionId, null, null, false, false);
        }

        /** @param memberKey the subject material and {@code itemRevisionId} its head, the target of new exercises */
        static Context exercise(UUID exerciseId, UUID exerciseRevisionId, UUID memberKey, UUID itemRevisionId, boolean hasAudio, boolean speakable) {
            return new Context("EXERCISE", memberKey, itemRevisionId, exerciseId, exerciseRevisionId, hasAudio, speakable);
        }

        boolean isMaterial() {
            return kind.equals("MATERIAL");
        }

        /** The operations the context allows (the first one the model's, listed to it in its prompt). */
        List<String> operations() {
            return isMaterial() ? List.of("EXERCISES", "REVISE_ITEM", "UNSUPPORTED") : List.of("EXERCISES", "REVISE_EXERCISE", "UNSUPPORTED");
        }
    }

    /**
     * The model's answer as the vocabulary has it.
     *
     * @param mechanics null for {@code AUTO}
     * @param perTarget null when no number was named; any integer the model wrote (it is clamped later)
     * @param voice {@code female}, {@code male} or null (no media action)
     */
    record Answer(String operation, List<String> mechanics, BigInteger perTarget, String instruction, String voice) { }

    /** What the endpoint answers: {@code spec} is null for {@code UNSUPPORTED}. */
    record Built(String operation, ObjectNode spec, ArrayNode chips, ArrayNode notes) { }

    // ------------------------------------------------------------------------ reading

    /**
     * The answer of the model read against the vocabulary; unknown members are dropped.
     *
     * @return empty when it is not an answer of the vocabulary (the caller repairs once, then gives up as {@code UNSUPPORTED})
     */
    static Optional<Answer> read(JsonNode node) {
        if (node == null || !node.isObject()) return Optional.empty();
        String operation = node.path("operation").stringValue(null);
        if (operation == null || !OPERATIONS.contains(operation)) return Optional.empty();

        List<String> mechanics = null;
        JsonNode listed = node.path("mechanics");
        if (listed.isArray()) {
            Set<String> chosen = new LinkedHashSet<>();
            for (JsonNode mechanic : listed) {
                if (mechanic.isString() && MECHANICS.contains(mechanic.stringValue())) chosen.add(mechanic.stringValue());
            }
            // a list with none of the registry's names says nothing: not an answer
            if (chosen.isEmpty() && !listed.isEmpty()) return Optional.empty();
            mechanics = chosen.isEmpty() ? null : List.copyOf(chosen);
        } else if (!(listed.isMissingNode() || listed.isNull() || listed.isString() && listed.stringValue().equals("AUTO"))) {
            return Optional.empty();
        }

        BigInteger perTarget = null;
        JsonNode number = node.path("perTarget");
        if (!(number.isMissingNode() || number.isNull())) {
            if (!number.isIntegralNumber()) return Optional.empty();
            perTarget = number.bigIntegerValue();
        }

        JsonNode text = node.path("instruction");
        if (!(text.isMissingNode() || text.isNull() || text.isString())) return Optional.empty();
        String instruction = text.isString() ? text.stringValue().strip() : "";

        String voice = null;
        JsonNode media = node.path("media");
        if (!(media.isMissingNode() || media.isNull())) {
            if (!media.isObject() || !"AUDIO_REGENERATE".equals(media.path("action").stringValue(null))) return Optional.empty();
            voice = media.path("voice").stringValue(null);
            if (!"female".equals(voice) && !"male".equals(voice)) return Optional.empty();
        }
        return Optional.of(new Answer(operation, mechanics, perTarget, instruction, voice));
    }

    // ------------------------------------------------------------------------ building

    /**
     * The spec, chips and notes for {@code answer} in {@code context}.
     *
     * @param maxPerTarget the limit of exercises per material ({@code learning.generation.max-exercises-per-target})
     * @param voiceRevision whether the redo of an exercise's audio can run now (the capability gate)
     */
    static Built build(Context context, Answer answer, int maxPerTarget, boolean voiceRevision) {
        List<Note> notes = new ArrayList<>();
        String operation = answer.operation();
        if (operation.equals("UNSUPPORTED")) return unsupported(Note.UNSUPPORTED);
        if (!context.operations().contains(operation)) return unsupported(context.isMaterial() ? Note.NEEDS_EXERCISE : Note.NEEDS_MATERIAL);
        return switch (operation) {
            case "EXERCISES" -> exercises(context, answer, maxPerTarget, notes);
            case "REVISE_ITEM" -> reviseItem(context, answer, notes);
            default -> reviseExercise(context, answer, voiceRevision, notes);
        };
    }

    private static Built exercises(Context context, Answer answer, int maxPerTarget, List<Note> notes) {
        ObjectNode spec = Json.object().put("kind", "EXERCISES");
        spec.putArray("targets").addObject().put("memberKey", context.memberKey().toString()).put("itemRevisionId", context.itemRevisionId().toString());
        ObjectNode settings = spec.putObject("settings");
        if (answer.mechanics() == null) {
            settings.put("mechanics", "AUTO");
        } else {
            ArrayNode list = settings.putArray("mechanics");
            answer.mechanics().forEach(list::add);
        }
        settings.put("priority", "UNCOVERED_FIRST");
        Integer perTarget = null;
        if (answer.perTarget() == null) {
            settings.putObject("quantity").put("mode", "AUTO");
        } else {
            BigInteger wanted = answer.perTarget();
            int clamped = wanted.compareTo(BigInteger.ONE) < 0 ? 1 : wanted.compareTo(BigInteger.valueOf(maxPerTarget)) > 0 ? maxPerTarget : wanted.intValueExact();
            if (!wanted.equals(BigInteger.valueOf(clamped))) notes.add(clamped == 1 ? Note.PER_TARGET_MIN.at(1) : Note.PER_TARGET_MAX.at(maxPerTarget));
            perTarget = clamped;
            settings.putObject("quantity").put("mode", "EXACT").put("perTarget", clamped);
        }
        ArrayNode chips = Json.array();
        chips.add(chip("OPERATION", "EXERCISES"));
        ObjectNode mechanics = chip("MECHANICS", answer.mechanics() == null ? Json.NODES.stringNode("AUTO") : array(answer.mechanics()));
        ArrayNode options = mechanics.putArray("options");
        MECHANICS.forEach(options::add);
        chips.add(mechanics);
        ObjectNode count = Json.object().put("kind", "PER_TARGET");
        if (perTarget == null) count.putNull("value");
        else count.put("value", perTarget);
        count.put("min", 1).put("max", maxPerTarget);
        chips.add(count);
        return new Built("EXERCISES", spec, chips, notes(notes));
    }

    private static Built reviseItem(Context context, Answer answer, List<Note> notes) {
        String instruction = trimmed(answer.instruction(), notes);
        if (instruction.isBlank()) return unsupported(Note.NO_INSTRUCTION);
        ObjectNode spec = Json.object().put("kind", "REVISE_ITEM");
        spec.putObject("target").put("memberKey", context.memberKey().toString()).put("itemRevisionId", context.itemRevisionId().toString());
        spec.put("instruction", instruction);
        ArrayNode chips = Json.array();
        chips.add(chip("OPERATION", "REVISE_ITEM"));
        chips.add(instructionChip(instruction));
        return new Built("REVISE_ITEM", spec, chips, notes(notes));
    }

    private static Built reviseExercise(Context context, Answer answer, boolean voiceRevision, List<Note> notes) {
        String instruction = trimmed(answer.instruction(), notes);
        String voice = answer.voice();
        if (voice != null && !context.hasAudio()) {
            notes.add(Note.NO_AUDIO);
            voice = null;
        } else if (voice != null && !context.speakable()) {
            notes.add(Note.NO_TRANSCRIPT);
            voice = null;
        } else if (voice != null && !voiceRevision) {
            notes.add(Note.MEDIA_UNAVAILABLE);
            voice = null;
        }
        if (instruction.isBlank() && voice == null) return unsupported(notes.isEmpty() ? Note.NO_INSTRUCTION : notes.getLast());
        ObjectNode spec = Json.object().put("kind", "REVISE_EXERCISE");
        spec.putObject("target").put("exerciseId", context.exerciseId().toString()).put("exerciseRevisionId", context.exerciseRevisionId().toString());
        ArrayNode chips = Json.array();
        chips.add(chip("OPERATION", "REVISE_EXERCISE"));
        if (!instruction.isBlank()) {
            spec.put("instruction", instruction);
            chips.add(instructionChip(instruction));
        }
        if (voice != null) {
            spec.putObject("media").put("action", "AUDIO_REGENERATE").put("voice", voice);
            ObjectNode chip = chip("VOICE", voice);
            chip.putArray("options").add("female").add("male");
            chips.add(chip);
        }
        return new Built("REVISE_EXERCISE", spec, chips, notes(notes));
    }

    private static Built unsupported(Note note) {
        return new Built("UNSUPPORTED", null, Json.array(), notes(List.of(note)));
    }

    // ------------------------------------------------------------------------ helpers

    private static String trimmed(String instruction, List<Note> notes) {
        if (instruction.codePointCount(0, instruction.length()) <= MAX_INSTRUCTION) return instruction;
        notes.add(Note.INSTRUCTION_TRIMMED);
        return instruction.substring(0, instruction.offsetByCodePoints(0, MAX_INSTRUCTION)).strip();
    }

    private static ObjectNode chip(String kind, String value) {
        return Json.object().put("kind", kind).put("value", value);
    }

    private static ObjectNode chip(String kind, JsonNode value) {
        ObjectNode chip = Json.object().put("kind", kind);
        chip.set("value", value);
        return chip;
    }

    private static ObjectNode instructionChip(String instruction) {
        return chip("INSTRUCTION", instruction).put("maxLength", MAX_INSTRUCTION);
    }

    private static ArrayNode array(List<String> values) {
        ArrayNode array = Json.array();
        values.forEach(array::add);
        return array;
    }

    private static ArrayNode notes(List<Note> notes) {
        ArrayNode array = Json.array();
        for (Note note : notes) {
            ObjectNode entry = array.addObject().put("code", note.code).put("text", note.text);
            if (note.limit != null) entry.put("limit", note.limit);
        }
        return array;
    }

    /** The notes a client shows next to the chips: a stable {@code code} and its text in words (never the model's own text). */
    private static final class Note {
        static final Note UNSUPPORTED = new Note("UNSUPPORTED", "Это я пока не умею: могу сделать упражнения или поправить текст.", null);
        static final Note NEEDS_EXERCISE = new Note("NEEDS_EXERCISE", "Чтобы править упражнение, откройте его.", null);
        static final Note NEEDS_MATERIAL = new Note("NEEDS_MATERIAL", "Чтобы править текст материала, откройте материал.", null);
        static final Note NO_INSTRUCTION = new Note("NO_INSTRUCTION", "Не поняла, что именно изменить. Опишите правку подробнее.", null);
        static final Note NO_AUDIO = new Note("NO_AUDIO", "В этом упражнении нет озвучки.", null);
        static final Note NO_TRANSCRIPT = new Note("NO_TRANSCRIPT", "Озвучить заново можно только аудио с текстом: добавьте расшифровку к записи в упражнении.", null);
        static final Note MEDIA_UNAVAILABLE = new Note("MEDIA_UNAVAILABLE", "Озвучка пока недоступна.", null);
        static final Note INSTRUCTION_TRIMMED = new Note("INSTRUCTION_TRIMMED", "Запрос сокращён до 2000 знаков.", null);
        static final Note PER_TARGET_MAX = new Note("PER_TARGET_CLAMPED", "Не больше %d на материал", 0);
        static final Note PER_TARGET_MIN = new Note("PER_TARGET_CLAMPED", "Не меньше %d на материал", 0);

        final String code;
        final String text;
        final Integer limit;

        private Note(String code, String text, Integer limit) {
            this.code = code;
            this.text = text;
            this.limit = limit;
        }

        /** The clamp note for a limit of {@code value}. */
        Note at(int value) {
            return new Note(code, String.format(text, value), value);
        }
    }
}
