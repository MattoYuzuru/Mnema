package app.mnema.learning.generation;

import app.mnema.learning.ai.ResearchSettings;
import app.mnema.learning.generation.Rows.Source;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The parts of a validated {@code MATERIALS} spec that the session reads. The spec was already validated strictly by the
 * usage module's interpreter (shape, enums, limits), so this reader only picks values and applies the contract's
 * defaults; it never decides what is valid.
 *
 * @param effort the effort as sent; {@code AUTO} runs as {@code MEDIUM} until the planner exists (AI-14)
 * @param audioLanguage the clip language, null when audio is off or the spec names none
 * @param noteOverrides the sparse per-note {@code overrides} by note id (only with one artifact per note, #290)
 */
record MaterialsSpec(String prompt, String outputLanguage, List<Source> sources, String effort, boolean mergeNotes,
                     boolean audio, String audioLanguage, String audioVoice, boolean imageSearch, boolean factCheck,
                     boolean similarToDeck, Map<UUID, JsonNode> noteOverrides) {
    static final String DEFAULT_LANGUAGE = "ru";

    static MaterialsSpec read(JsonNode spec) {
        List<Source> sources = new ArrayList<>();
        Map<UUID, JsonNode> overrides = new LinkedHashMap<>();
        int ordinal = 0;
        for (JsonNode source : spec.path("sources")) {
            String role = source.path("role").stringValue("SOURCE");
            if (source.path("type").stringValue("").equals("NOTE")) {
                UUID noteId = UUID.fromString(source.path("noteId").stringValue(""));
                sources.add(new Source(ordinal++, role, "NOTE", noteId,
                        Long.parseLong(source.path("noteRowVersion").stringValue("0")), null, null));
                if (source.path("overrides").isObject()) overrides.putIfAbsent(noteId, source.get("overrides"));
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
                settings.path("similarToDeck").asBoolean(false), Map.copyOf(overrides));
    }

    /**
     * The settings that apply to one material: the session's, with the sparse overrides of the note it is written from
     * (members that are absent keep the session value; an {@code audio} override replaces the whole clip setting).
     */
    record Effective(String effort, boolean audio, String audioLanguage, String audioVoice, boolean imageSearch,
                     boolean factCheck) {
        /** The same settings at another effort: what a planned material uses (the plan decides the effort of each one). */
        Effective withEffort(String other) {
            return new Effective(other, audio, audioLanguage, audioVoice, imageSearch, factCheck);
        }

        /** The effort a material is written and charged at: {@code AUTO} is {@code MEDIUM} without a planner. */
        String workingEffort() {
            return effort.equals("AUTO") ? "MEDIUM" : effort;
        }

        /** Media directives the compiler allows for this material: exactly the declared kinds (the estimate prices only those). */
        int maxMedia() {
            return (audio ? 1 : 0) + (imageSearch ? 1 : 0);
        }

        /** Whether the material needs web research: a fact check on an effort above short. */
        boolean research() {
            return factCheck && !workingEffort().equals("SHORT");
        }

        /**
         * How many search requests the research of this material may make: 0 without research, else the effort's cap ({@code MEDIUM} 2, {@code DETAILED}
         * 6, {@code AUTO} 3) bounded by {@code max}. The effort here is the declared one: an {@code AUTO} material that no plan fixed is capped as {@code AUTO}.
         */
        int researchCap(int max) {
            return research() ? ResearchSettings.cap(effort, max) : 0;
        }
    }

    /** The session's own settings, for a material without overrides (merged notes, prompt only, items only). */
    Effective defaults() {
        return new Effective(effort, audio, audioLanguage, audioVoice, imageSearch, factCheck);
    }

    /** The settings of the material whose pins are {@code sourceRefs}: the overrides of its NOTE source, when it has any. */
    Effective forArtifact(JsonNode sourceRefs) {
        Effective base = defaults();
        if (mergeNotes || noteOverrides.isEmpty()) return base;
        for (JsonNode ref : sourceRefs) {
            if (!ref.path("type").stringValue("").equals("NOTE")) continue;
            JsonNode overrides = noteOverrides.get(UUID.fromString(ref.path("noteId").stringValue("")));
            return overrides == null ? base : apply(base, overrides);
        }
        return base;
    }

    private static Effective apply(Effective base, JsonNode overrides) {
        String effort = overrides.path("effort").stringValue(base.effort());
        JsonNode media = overrides.path("media");
        boolean audio = base.audio();
        String language = base.audioLanguage();
        String voice = base.audioVoice();
        if (media.path("audio").isObject()) {
            JsonNode clip = media.get("audio");
            audio = clip.path("enabled").asBoolean(false);
            language = clip.path("lang").stringValue(null);
            voice = clip.path("voice").stringValue(null);
        }
        boolean image = media.has("imageSearch") ? media.path("imageSearch").asBoolean(false) : base.imageSearch();
        return new Effective(effort, audio, language, voice, image, base.factCheck());
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
}
