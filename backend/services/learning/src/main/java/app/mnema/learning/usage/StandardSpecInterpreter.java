package app.mnema.learning.usage;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The default {@link GenerationSpecInterpreter}: validates the shape of a spec strictly (unknown fields are
 * {@code INVALID_REQUEST}) and prices the forms the contract defines, MATERIALS and EXERCISES. REVISE_ITEM and
 * REVISE_EXERCISE are {@code SPEC_NOT_SUPPORTED} until AI-16 (#294).
 *
 * <p>Interpretation of the declarative parts, all worst-case ("what a reservation holds"):
 * <ul>
 *   <li>MATERIALS: one artifact per NOTE source (one when notes are merged or there are none), priced by effort
 *       ({@code AUTO} is priced as medium: the planner chooses later); declared media are one audio clip and one image
 *       search per artifact; a fact check is one low-effort check per artifact and is not run for the short effort
 *       (architecture section 14: short does no research); {@code planFirst} is priced by the planner (AI-14).</li>
 *   <li>EXERCISES: {@code EXACT} is targets x perTarget; {@code AUTO} is five per target, fewer when targets x five
 *       would exceed the session limit; {@code BUDGET_PERCENT} is what that share of the remaining budget buys, at
 *       least one per target and within the limits. Anything the user states above a limit is refused, never clamped.</li>
 * </ul>
 * The estimate does not check that sources exist or belong to the owner: that is the generation module's boundary.
 */
@Component
final class StandardSpecInterpreter implements GenerationSpecInterpreter {
    private static final int MAX_PROMPT = 2_000;
    private static final int MAX_STYLE_EXAMPLES = 2;
    private static final int AUTO_PER_TARGET = 5;
    private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,3}");
    private static final Pattern DECIMAL = Pattern.compile("0|[1-9][0-9]{0,17}");
    private static final Set<String> EFFORTS = Set.of("AUTO", "SHORT", "MEDIUM", "DETAILED");
    private static final Set<String> NOTES_MODES = Set.of("ONE_PER_NOTE", "MERGE_INTO_ONE");
    private static final Set<String> MECHANICS = Set.of("SELF_CHECK", "FREE_RESPONSE", "CLOZE", "CHOICE", "MATCH", "ORDER",
            "CATEGORIZE");
    private static final Set<String> PRIORITIES = Set.of("UNCOVERED_FIRST", "BALANCED");

    private final GenerationLimits limits;

    StandardSpecInterpreter(GenerationLimits limits) {
        this.limits = limits;
    }

    @Override
    public Interpretation interpret(UUID owner, UUID deckId, JsonNode spec, int remainingCredits) {
        if (!spec.isObject()) throw invalid();
        String kind = text(spec, "kind");
        return switch (kind) {
            case "MATERIALS" -> materials(spec);
            case "EXERCISES" -> exercises(spec, remainingCredits);
            case "REVISE_ITEM", "REVISE_EXERCISE" -> throw new SpecNotSupportedException(kind);
            default -> throw invalid();
        };
    }

    // ---------------------------------------------------------------- MATERIALS

    private Interpretation materials(JsonNode spec) {
        keys(spec, Set.of("kind"), Set.of("outputLanguage", "prompt", "sources", "settings"));
        language(spec);
        String prompt = spec.has("prompt") ? text(spec, "prompt") : "";
        if (prompt.length() > MAX_PROMPT) throw invalid();

        int sourceRoles = 0;
        int notes = 0;
        int styleExamples = 0;
        if (spec.has("sources")) {
            JsonNode sources = array(spec, "sources");
            if (sources.size() > limits.maxSources) throw limits.exceeded("SOURCES");
            for (JsonNode source : sources) {
                String role = text(source, "role");
                String type = text(source, "type");
                if (type.equals("NOTE")) {
                    keys(source, Set.of("role", "type", "noteId", "noteRowVersion"), Set.of());
                    uuid(source, "noteId");
                    if (!DECIMAL.matcher(text(source, "noteRowVersion")).matches()) throw invalid();
                } else if (type.equals("ITEM")) {
                    keys(source, Set.of("role", "type", "memberKey", "itemRevisionId"), Set.of());
                    uuid(source, "memberKey");
                    uuid(source, "itemRevisionId");
                } else {
                    throw invalid();
                }
                switch (role) {
                    case "SOURCE" -> {
                        sourceRoles++;
                        if (type.equals("NOTE")) notes++;
                    }
                    case "STYLE_EXAMPLE" -> {
                        // Style examples are items only, at most two, and never the material itself.
                        if (!type.equals("ITEM") || ++styleExamples > MAX_STYLE_EXAMPLES) throw invalid();
                    }
                    default -> throw invalid();
                }
            }
        }
        if (sourceRoles == 0 && prompt.isBlank()) throw invalid();

        String effort = "AUTO";
        String notesMode = "ONE_PER_NOTE";
        boolean audio = false;
        boolean imageSearch = false;
        boolean factCheck = false;
        Integer budget = null;
        if (spec.has("settings")) {
            JsonNode settings = object(spec, "settings");
            keys(settings, Set.of(), Set.of("effort", "notesMode", "media", "factCheck", "similarToDeck", "planFirst",
                    "budgetPercent"));
            if (settings.has("effort")) effort = oneOf(settings, "effort", EFFORTS);
            if (settings.has("notesMode")) notesMode = oneOf(settings, "notesMode", NOTES_MODES);
            if (settings.has("media")) {
                JsonNode media = object(settings, "media");
                keys(media, Set.of(), Set.of("audio", "imageSearch"));
                if (media.has("audio")) {
                    JsonNode clip = object(media, "audio");
                    keys(clip, Set.of("enabled"), Set.of("lang", "voice"));
                    audio = flag(clip, "enabled");
                    if (clip.has("lang") && !LANGUAGE.matcher(text(clip, "lang")).matches()) throw invalid();
                    if (clip.has("voice") && !clip.get("voice").isNull()) oneOf(clip, "voice", Set.of("female", "male"));
                }
                if (media.has("imageSearch")) imageSearch = flag(media, "imageSearch");
            }
            if (settings.has("factCheck")) factCheck = flag(settings, "factCheck");
            if (settings.has("similarToDeck")) flag(settings, "similarToDeck");
            if (settings.has("planFirst")) flag(settings, "planFirst");
            budget = budget(settings);
        }

        int artifacts = notesMode.equals("MERGE_INTO_ONE") || notes == 0 ? 1 : notes;
        if (artifacts > limits.maxArtifactsPerSession) throw limits.exceeded("ARTIFACTS_PER_SESSION");
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(switch (effort) {
            case "SHORT" -> "MATERIAL_SHORT";
            case "DETAILED" -> "MATERIAL_DETAILED";
            default -> "MATERIAL_MEDIUM";
        }, artifacts));
        if (audio) lines.add(new Line("TTS_CLIP_30S", artifacts));
        if (imageSearch) lines.add(new Line("IMAGE_SEARCH", artifacts));
        if (factCheck && !effort.equals("SHORT")) lines.add(new Line("FACTCHECK_LOW", artifacts));
        return new Interpretation(lines, budget, List.of());
    }

    // ---------------------------------------------------------------- EXERCISES

    private Interpretation exercises(JsonNode spec, int remainingCredits) {
        keys(spec, Set.of("kind", "targets"), Set.of("outputLanguage", "settings"));
        language(spec);
        JsonNode targets = array(spec, "targets");
        if (targets.isEmpty()) throw invalid();
        if (targets.size() > limits.maxExerciseTargets) throw limits.exceeded("EXERCISE_TARGETS");
        Set<UUID> members = new HashSet<>();
        for (JsonNode target : targets) {
            keys(target, Set.of("memberKey", "itemRevisionId"), Set.of());
            uuid(target, "itemRevisionId");
            if (!members.add(uuid(target, "memberKey"))) throw invalid();
        }
        int count = targets.size();

        String quantity = "AUTO";
        int perTarget = 0;
        int percent = 0;
        Integer budget = null;
        if (spec.has("settings")) {
            JsonNode settings = object(spec, "settings");
            keys(settings, Set.of(), Set.of("mechanics", "priority", "quantity", "planFirst", "budgetPercent"));
            if (settings.has("mechanics")) mechanics(settings.get("mechanics"));
            if (settings.has("priority")) oneOf(settings, "priority", PRIORITIES);
            if (settings.has("planFirst")) flag(settings, "planFirst");
            budget = budget(settings);
            if (settings.has("quantity")) {
                JsonNode node = object(settings, "quantity");
                quantity = oneOf(node, "mode", Set.of("AUTO", "EXACT", "BUDGET_PERCENT"));
                switch (quantity) {
                    case "AUTO" -> keys(node, Set.of("mode"), Set.of());
                    case "EXACT" -> {
                        keys(node, Set.of("mode", "perTarget"), Set.of());
                        perTarget = integer(node, "perTarget");
                        if (perTarget < 1) throw invalid();
                        if (perTarget > limits.maxExercisesPerTarget) throw limits.exceeded("EXERCISES_PER_TARGET");
                    }
                    default -> {
                        keys(node, Set.of("mode", "percent"), Set.of());
                        percent = integer(node, "percent");
                        if (percent < 1 || percent > 100) throw invalid();
                    }
                }
            }
        }

        long total = switch (quantity) {
            case "EXACT" -> (long) count * perTarget;
            case "BUDGET_PERCENT" -> {
                long cap = BigDecimal.valueOf((long) percent * remainingCredits).divide(BigDecimal.valueOf(100), 0,
                        RoundingMode.FLOOR).longValueExact();
                // Eight credits buy five exercises; every target gets at least one and at most the per-target limit.
                long affordable = cap * 5 / 8;
                yield Math.max(count, Math.min(affordable, Math.min((long) count * limits.maxExercisesPerTarget,
                        limits.maxExercisesPerSession)));
            }
            default -> (long) count * Math.clamp(limits.maxExercisesPerSession / count, 1, AUTO_PER_TARGET);
        };
        if (total > limits.maxExercisesPerSession) throw limits.exceeded("EXERCISES_PER_SESSION");
        return new Interpretation(List.of(new Line(RateCard.EXERCISES, (int) total)), budget, List.of());
    }

    private void mechanics(JsonNode node) {
        if (node.isString()) {
            if (!node.stringValue().equals("AUTO")) throw invalid();
            return;
        }
        if (!node.isArray() || node.isEmpty()) throw invalid();
        Set<String> seen = new HashSet<>();
        for (JsonNode mechanic : node) {
            if (!mechanic.isString() || !MECHANICS.contains(mechanic.stringValue()) || !seen.add(mechanic.stringValue())) {
                throw invalid();
            }
        }
    }

    // ------------------------------------------------------------------ parsing

    private static InvalidRequestException invalid() {
        return new InvalidRequestException();
    }

    /** Exactly the required keys plus any of the optional ones; an unknown or missing key is a 400. */
    private static void keys(JsonNode node, Set<String> required, Set<String> optional) {
        if (!node.isObject()) throw invalid();
        for (String name : node.propertyNames()) {
            if (!required.contains(name) && !optional.contains(name)) throw invalid();
        }
        for (String name : required) {
            if (!node.has(name)) throw invalid();
        }
    }

    private static String text(JsonNode node, String name) {
        if (!node.isObject() || !node.has(name) || !node.get(name).isString()) throw invalid();
        return node.get(name).stringValue();
    }

    private static String oneOf(JsonNode node, String name, Set<String> allowed) {
        String value = text(node, name);
        if (!allowed.contains(value)) throw invalid();
        return value;
    }

    private static boolean flag(JsonNode node, String name) {
        if (!node.has(name) || !node.get(name).isBoolean()) throw invalid();
        return node.get(name).booleanValue();
    }

    private static int integer(JsonNode node, String name) {
        if (!node.has(name) || !node.get(name).isIntegralNumber() || !node.get(name).canConvertToInt()) throw invalid();
        return node.get(name).intValue();
    }

    private static JsonNode object(JsonNode node, String name) {
        if (!node.has(name) || !node.get(name).isObject()) throw invalid();
        return node.get(name);
    }

    private static JsonNode array(JsonNode node, String name) {
        if (!node.has(name) || !node.get(name).isArray()) throw invalid();
        return node.get(name);
    }

    private static UUID uuid(JsonNode node, String name) {
        String value = text(node, name);
        try {
            UUID id = UuidPolicy.requireEntityId(UUID.fromString(value), name);
            // Canonical lowercase form only: the same identifier must have one spelling.
            if (!id.toString().equals(value)) throw invalid();
            return id;
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static void language(JsonNode spec) {
        if (spec.has("outputLanguage") && !LANGUAGE.matcher(text(spec, "outputLanguage")).matches()) throw invalid();
    }

    /** {@code budgetPercent}: absent or null (no budget) or 1..100. */
    private static Integer budget(JsonNode settings) {
        if (!settings.has("budgetPercent") || settings.get("budgetPercent").isNull()) return null;
        int percent = integer(settings, "budgetPercent");
        if (percent < 1 || percent > 100) throw invalid();
        return percent;
    }
}
