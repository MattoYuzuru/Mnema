package app.mnema.learning.generation;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * The parts of a validated {@code REVISE_ITEM} or {@code REVISE_EXERCISE} spec that a session reads (the usage module's interpreter has
 * already refused every malformed spec).
 *
 * @param kind {@code REVISE_ITEM} or {@code REVISE_EXERCISE}
 * @param memberKey the material (REVISE_ITEM), else null
 * @param itemRevisionId the head of that material the owner saw
 * @param exerciseId the exercise (REVISE_EXERCISE), else null
 * @param exerciseRevisionId the head of that exercise the owner saw
 * @param instruction what to change; blank only for a REVISE_EXERCISE that redoes the audio and nothing else
 * @param voice {@code female} or {@code male} when the spec redoes the audio of the exercise, else null
 * @param outputLanguage the language of the exercise's text
 */
record ReviseSpec(String kind, UUID memberKey, UUID itemRevisionId, UUID exerciseId, UUID exerciseRevisionId, String instruction,
                  String voice, String outputLanguage) {
    static final String ITEM = "REVISE_ITEM";
    static final String EXERCISE = "REVISE_EXERCISE";

    static ReviseSpec read(JsonNode spec) {
        String kind = spec.path("kind").stringValue("");
        JsonNode target = spec.path("target");
        String instruction = spec.path("instruction").stringValue("");
        String language = spec.path("outputLanguage").stringValue(MaterialsSpec.DEFAULT_LANGUAGE);
        if (kind.equals(ITEM)) {
            return new ReviseSpec(kind, UUID.fromString(target.path("memberKey").stringValue("")),
                    UUID.fromString(target.path("itemRevisionId").stringValue("")), null, null, instruction, null, language);
        }
        JsonNode media = spec.path("media");
        return new ReviseSpec(kind, null, null, UUID.fromString(target.path("exerciseId").stringValue("")),
                UUID.fromString(target.path("exerciseRevisionId").stringValue("")), instruction,
                media.isObject() ? media.path("voice").stringValue(null) : null, language);
    }

    boolean hasInstruction() {
        return !instruction.isBlank();
    }

    boolean hasMedia() {
        return voice != null;
    }
}
