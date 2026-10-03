package app.mnema.learning.generation;

import app.mnema.learning.generation.Rows.Source;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The parts of a validated {@code MATERIALS} spec that the session reads. The spec was already validated strictly by the
 * usage module's interpreter (shape, enums, limits), so this reader only picks values and applies the contract's
 * defaults; it never decides what is valid.
 *
 * @param effort the effort as sent; {@code AUTO} runs as {@code MEDIUM} until the planner exists (AI-14)
 * @param audioLanguage the clip language, null when audio is off or the spec names none
 */
record MaterialsSpec(String prompt, String outputLanguage, List<Source> sources, String effort, boolean mergeNotes,
                     boolean audio, String audioLanguage, String audioVoice, boolean imageSearch, boolean factCheck,
                     boolean similarToDeck) {
    static final String DEFAULT_LANGUAGE = "ru";

    static MaterialsSpec read(JsonNode spec) {
        List<Source> sources = new ArrayList<>();
        int ordinal = 0;
        for (JsonNode source : spec.path("sources")) {
            String role = source.path("role").stringValue("SOURCE");
            if (source.path("type").stringValue("").equals("NOTE")) {
                sources.add(new Source(ordinal++, role, "NOTE", UUID.fromString(source.path("noteId").stringValue("")),
                        Long.parseLong(source.path("noteRowVersion").stringValue("0")), null, null));
            } else {
                sources.add(new Source(ordinal++, role, "ITEM", null, null,
                        UUID.fromString(source.path("memberKey").stringValue("")),
                        UUID.fromString(source.path("itemRevisionId").stringValue(""))));
            }
        }
        JsonNode settings = spec.path("settings");
        JsonNode audio = settings.path("media").path("audio");
        return new MaterialsSpec(spec.path("prompt").stringValue(""), spec.path("outputLanguage").stringValue(DEFAULT_LANGUAGE),
                List.copyOf(sources), settings.path("effort").stringValue("AUTO"),
                settings.path("notesMode").stringValue("ONE_PER_NOTE").equals("MERGE_INTO_ONE"), audio.path("enabled").asBoolean(false),
                audio.path("lang").stringValue(null), audio.path("voice").stringValue(null),
                settings.path("media").path("imageSearch").asBoolean(false), settings.path("factCheck").asBoolean(false),
                settings.path("similarToDeck").asBoolean(false));
    }

    /** The effort a material is written and charged at: {@code AUTO} is {@code MEDIUM} without a planner. */
    String workingEffort() {
        return effort.equals("AUTO") ? "MEDIUM" : effort;
    }

    List<Source> notes() {
        return sources.stream().filter(source -> source.type().equals("NOTE") && source.role().equals("SOURCE")).toList();
    }

    /** Material sources the model reads as context (items with role SOURCE). */
    List<Source> items() {
        return sources.stream().filter(source -> source.type().equals("ITEM") && source.role().equals("SOURCE")).toList();
    }

    List<Source> styleExamples() {
        return sources.stream().filter(source -> source.role().equals("STYLE_EXAMPLE")).toList();
    }

    /** How many artifacts the spec creates: one per note, or one when notes are merged or absent. */
    int artifactCount() {
        int notes = notes().size();
        return mergeNotes || notes == 0 ? 1 : notes;
    }

    /** Media directives the compiler allows per material: exactly the declared kinds (the estimate prices only those). */
    int maxMedia() {
        return (audio ? 1 : 0) + (imageSearch ? 1 : 0);
    }
}
