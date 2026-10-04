package app.mnema.learning.generation;

import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.Rows.Source;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.usage.AdmissionPricing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The plan of a plan-first session ({@code contracts/generation/README.md}, decision 17): what the model proposes, what the owner approves,
 * how both are validated and priced, and the wire shape of {@code getSession.plan}. Pure apart from the price list: no database, no model.
 *
 * <p>Two kinds of input, two policies. <b>The model's answer</b> ({@link #fromModel}) is data: an unknown handle, a count outside the limits,
 * a mechanic that is not allowed or a malformed field is a finding that sends the answer back once (then to the strong route, then the
 * plan fails); an over-long {@code why} or title is cut, and a plan that costs more than the batch hold is trimmed from the end with a note.
 * <b>The owner's plan</b> ({@link #fromOwner}) is user input: nothing is clamped. A shape the contract does not have, an unknown target or
 * note, a mechanic outside the allowed set is {@code 400}; a count above the per-target or the per-session limit is {@code 422
 * RESOURCE_LIMIT_EXCEEDED}; a cost above the hold is the approval's business ({@code SessionService}: it extends the hold or refuses).
 *
 * <p>The identifiers of the wire plan are the owner's own ({@code memberKey} of a target, {@code noteId} of a source); the model's handles
 * ({@code m1}, {@code n2}) exist only in its prompt and answer.
 */
@Component
class Plans {
    static final String EXERCISES = "EXERCISES";
    static final String MATERIALS = "MATERIALS";
    static final int MAX_TITLE = 160;
    static final int MAX_WHY = 200;
    private static final List<String> EFFORTS = List.of("SHORT", "MEDIUM", "DETAILED");
    private static final int MAX_FINDINGS = 12;

    private final AdmissionPricing pricing;
    private final GenerationRepository repository;
    private final int maxPerTarget;
    private final int maxPerSession;
    private final int maxArtifacts;

    Plans(AdmissionPricing pricing, GenerationRepository repository, @Value("${learning.generation.max-exercises-per-target:10}") int maxPerTarget,
          @Value("${learning.generation.max-exercises-per-session:60}") int maxPerSession,
          @Value("${learning.generation.max-artifacts-per-session:20}") int maxArtifacts) {
        this.pricing = pricing;
        this.repository = repository;
        this.maxPerTarget = maxPerTarget;
        this.maxPerSession = maxPerSession;
        this.maxArtifacts = maxArtifacts;
    }

    // ------------------------------------------------------------------------------ types

    /** One planned run of exercises over one target; {@code why} is the model's reason (null for an item the owner added). */
    record ExerciseItem(UUID memberKey, List<String> mechanics, int count, String why) { }

    /** One planned material; {@code noteId} is null for a prompt-only or a merged session. */
    record MaterialItem(UUID noteId, String title, String effort, String why) { }

    /** A validated plan; {@code notes} are the codes of what the server did to it (a trim). */
    record Draft(String kind, List<ExerciseItem> exercises, List<MaterialItem> materials, List<String> notes) {
        Draft {
            exercises = List.copyOf(exercises);
            materials = List.copyOf(materials);
            notes = List.copyOf(notes);
        }

        int size() {
            return kind.equals(EXERCISES) ? exercises.size() : materials.size();
        }

        /** The number of artifacts the plan creates: one per exercise, one per material. */
        int artifacts() {
            return kind.equals(EXERCISES) ? exercises.stream().mapToInt(ExerciseItem::count).sum() : materials.size();
        }
    }

    /** What the model's answer was: a plan, or the findings that send it back. */
    sealed interface Parsed {
        record Ok(Draft draft) implements Parsed { }

        record Invalid(String findings) implements Parsed { }
    }

    /**
     * The session as the plan sees it: the targets or the notes in spec order (the model's handles {@code m1..} and {@code n1..} are their
     * positions), the mechanics the spec allows and the effort a fixed spec imposes. Built from the immutable spec alone.
     */
    record Basis(String kind, ExercisesSpec exercises, MaterialsSpec materials, List<Source> targets, List<Source> notes,
                 List<String> allowedMechanics) {
        static Basis of(Session session) {
            if (session.kind().equals(EXERCISES)) {
                ExercisesSpec spec = ExercisesSpec.read(session.spec());
                return new Basis(EXERCISES, spec, null, spec.targets(), List.of(), spec.allowedMechanics());
            }
            MaterialsSpec spec = MaterialsSpec.read(session.spec());
            return new Basis(MATERIALS, null, spec, List.of(), spec.notes(), List.of());
        }

        /** One material per note (the planner may split a note into several), else one source-less material list. */
        boolean perNote() {
            return kind.equals(MATERIALS) && !materials.mergeNotes() && !notes.isEmpty();
        }

        Optional<Source> note(UUID noteId) {
            return notes.stream().filter(note -> note.noteId().equals(noteId)).findFirst();
        }

        boolean hasTarget(UUID memberKey) {
            return targets.stream().anyMatch(target -> target.memberKey().equals(memberKey));
        }

        /** The effort a material is written at when the spec fixes one ({@code AUTO} leaves it to the plan), else null. */
        String fixedEffort() {
            return kind.equals(MATERIALS) && !materials.effort().equals("AUTO") ? materials.effort() : null;
        }
    }

    Basis basis(Session session) {
        return Basis.of(session);
    }

    // ------------------------------------------------------------------------------ pricing

    /** What the plan costs as a batch (the plan's own price is separate): the credits the batch hold must cover. */
    int cost(Basis basis, Draft draft) {
        if (basis.kind().equals(EXERCISES)) return pricing.exerciseCredits(draft.artifacts());
        int total = 0;
        for (MaterialItem item : draft.materials()) total += materialCredits(basis, item.noteId(), item.effort());
        return total;
    }

    /**
     * What one material costs at {@code effort}: the material itself and, from the effective settings of its note (the session's with the
     * note's overrides), the media and the research the estimate prices for it, exactly as the unplanned estimate does.
     */
    int materialCredits(Basis basis, UUID noteId, String effort) {
        MaterialsSpec.Effective settings = settings(basis, noteId).withEffort(effort);
        int credits = pricing.credits(AdmissionPricing.materialOperation(settings.workingEffort()));
        if (settings.audio()) credits += pricing.credits("TTS_CLIP_30S");
        if (settings.imageSearch()) credits += pricing.credits("IMAGE_SEARCH");
        if (settings.research()) credits += pricing.credits("FACTCHECK_LOW");
        return credits;
    }

    /** The effective settings of a planned material: the spec's, with the overrides of its note. */
    MaterialsSpec.Effective settings(Basis basis, UUID noteId) {
        if (noteId == null) return basis.materials().defaults();
        return basis.materials().forArtifact(Json.array().add(SessionService.noteRef(basis.note(noteId).orElseThrow())));
    }

    // ------------------------------------------------------------------------- the model's plan

    /**
     * Reads the model's answer. A violation of the answer's own shape is a finding (the caller sends the answer back); what is merely
     * too much (a total above the limits, a cost above {@code holdCredits}) is trimmed from the end and noted.
     */
    Parsed fromModel(JsonNode answer, Basis basis, int holdCredits) {
        JsonNode items = answer == null ? null : answer.path("items");
        if (items == null || !items.isArray() || items.isEmpty()) return new Parsed.Invalid("items: SCHEMA_INVALID");
        List<String> findings = new ArrayList<>();
        Draft draft = basis.kind().equals(EXERCISES) ? modelExercises(items, basis, findings) : modelMaterials(items, basis, findings);
        if (!findings.isEmpty()) return new Parsed.Invalid(String.join("; ", findings.subList(0, Math.min(findings.size(), MAX_FINDINGS))));
        Draft trimmed = trim(basis, draft, holdCredits);
        if (trimmed.size() == 0) return new Parsed.Invalid("items: NOTHING_FITS_THE_BUDGET");
        return new Parsed.Ok(trimmed);
    }

    private Draft modelExercises(JsonNode items, Basis basis, List<String> findings) {
        List<ExerciseItem> planned = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (int index = 0; index < items.size() && findings.size() < MAX_FINDINGS; index++) {
            JsonNode item = items.get(index);
            String at = "items[" + index + "]";
            if (!item.isObject()) {
                findings.add(at + ": NOT_AN_OBJECT");
                continue;
            }
            int target = handle(item.path("target").stringValue(null), "m", basis.targets().size());
            if (target < 0) findings.add(at + ".target: UNKNOWN_TARGET");
            else if (!seen.add(target)) findings.add(at + ".target: DUPLICATE_TARGET");
            List<String> mechanics = modelMechanics(item.path("mechanics"), basis.allowedMechanics());
            if (mechanics == null) findings.add(at + ".mechanics: NOT_ALLOWED");
            JsonNode count = item.path("count");
            if (!count.isIntegralNumber() || !count.canConvertToInt() || count.intValue() < 1 || count.intValue() > maxPerTarget) {
                findings.add(at + ".count: OUT_OF_RANGE");
            }
            if (target >= 0 && mechanics != null && count.isIntegralNumber() && count.canConvertToInt() && count.intValue() >= 1
                    && count.intValue() <= maxPerTarget) {
                planned.add(new ExerciseItem(basis.targets().get(target).memberKey(), mechanics, count.intValue(), why(item)));
            }
        }
        return new Draft(EXERCISES, planned, List.of(), List.of());
    }

    /** The mechanics of an item: absent or {@code "AUTO"} is the allowed set; a list is deduplicated and must stay within it (null: it does not). */
    private static List<String> modelMechanics(JsonNode node, List<String> allowed) {
        if (node.isMissingNode() || node.isNull() || node.isString() && node.stringValue().equals("AUTO")) return allowed;
        if (!node.isArray() || node.isEmpty()) return null;
        Set<String> chosen = new LinkedHashSet<>();
        for (JsonNode mechanic : node) {
            if (!mechanic.isString() || !allowed.contains(mechanic.stringValue())) return null;
            chosen.add(mechanic.stringValue());
        }
        // the registry order of the contract, so the same set is always shown the same way
        List<String> ordered = new ArrayList<>(allowed);
        ordered.retainAll(chosen);
        return List.copyOf(ordered);
    }

    private Draft modelMaterials(JsonNode items, Basis basis, List<String> findings) {
        List<MaterialItem> planned = new ArrayList<>();
        for (int index = 0; index < items.size() && findings.size() < MAX_FINDINGS; index++) {
            JsonNode item = items.get(index);
            String at = "items[" + index + "]";
            if (!item.isObject()) {
                findings.add(at + ": NOT_AN_OBJECT");
                continue;
            }
            UUID note = null;
            if (basis.perNote()) {
                int source = handle(item.path("source").stringValue(null), "n", basis.notes().size());
                if (source < 0) findings.add(at + ".source: UNKNOWN_SOURCE");
                else note = basis.notes().get(source).noteId();
            }
            String title = item.path("title").isString() ? cut(item.path("title").stringValue().strip(), MAX_TITLE) : "";
            if (title.isBlank()) findings.add(at + ".title: MISSING");
            String effort = modelEffort(item.path("effort"), basis);
            if (effort == null) findings.add(at + ".effort: NOT_ALLOWED");
            if ((note != null || !basis.perNote()) && !title.isBlank() && effort != null) planned.add(new MaterialItem(note, title, effort, why(item)));
        }
        return new Draft(MATERIALS, List.of(), planned, List.of());
    }

    /** A fixed spec effort wins over the model's; {@code AUTO} or nothing is medium; any other word is a finding (null). */
    private static String modelEffort(JsonNode node, Basis basis) {
        String fixed = basis.fixedEffort();
        if (fixed != null) return fixed.equals("AUTO") ? "MEDIUM" : fixed;
        if (node.isMissingNode() || node.isNull() || node.isString() && node.stringValue().equals("AUTO")) return "MEDIUM";
        return node.isString() && EFFORTS.contains(node.stringValue()) ? node.stringValue() : null;
    }

    /** Takes items off the end until the plan fits the limits and the hold; notes it when it did. */
    private Draft trim(Basis basis, Draft draft, int holdCredits) {
        List<ExerciseItem> exercises = new ArrayList<>(draft.exercises());
        List<MaterialItem> materials = new ArrayList<>(draft.materials());
        boolean trimmed = false;
        while (true) {
            Draft current = new Draft(draft.kind(), exercises, materials, List.of());
            boolean tooMany = basis.kind().equals(EXERCISES) ? current.artifacts() > maxPerSession : materials.size() > maxArtifacts;
            if (current.size() == 0 || !tooMany && cost(basis, current) <= holdCredits) break;
            trimmed = true;
            if (basis.kind().equals(EXERCISES)) {
                ExerciseItem last = exercises.removeLast();
                if (last.count() > 1) exercises.add(new ExerciseItem(last.memberKey(), last.mechanics(), last.count() - 1, last.why()));
            } else {
                materials.removeLast();
            }
        }
        return new Draft(draft.kind(), exercises, materials, trimmed ? List.of("TRIMMED_TO_BUDGET") : List.of());
    }

    /** The 1-based {@code prefix + n} handle as a 0-based index, or -1 for anything else. */
    private static int handle(String value, String prefix, int size) {
        if (value == null || !value.matches(prefix + "[1-9][0-9]{0,3}")) return -1;
        int index = Integer.parseInt(value.substring(prefix.length())) - 1;
        return index < size ? index : -1;
    }

    private static String why(JsonNode item) {
        return item.path("why").isString() ? cut(item.path("why").stringValue().strip(), MAX_WHY) : "";
    }

    private static String cut(String text, int codePoints) {
        return text.codePointCount(0, text.length()) <= codePoints ? text : text.substring(0, text.offsetByCodePoints(0, codePoints));
    }

    // -------------------------------------------------------------------------- the owner's plan

    /**
     * Reads the plan of a plan-approval request: strictly, and without clamping anything.
     *
     * @throws InvalidRequestException a shape the contract does not have, an unknown or repeated target or note, a mechanic that is not allowed
     */
    Draft fromOwner(JsonNode plan, Basis basis, Map<UUID, String> keptWhy) {
        Commands.fields(plan, Set.of("items"), Set.of());
        JsonNode items = plan.get("items");
        if (!items.isArray() || items.isEmpty()) throw new InvalidRequestException();
        return basis.kind().equals(EXERCISES) ? ownerExercises(items, basis, keptWhy) : ownerMaterials(items, basis);
    }

    /**
     * The limits of a plan the owner edited, after the shape: no silent clamp.
     *
     * @throws ResourceLimitExceededException a count above the per-target limit, a total above the per-session limit, too many materials
     */
    void requireWithinLimits(Draft draft) {
        if (draft.kind().equals(EXERCISES)) {
            for (ExerciseItem item : draft.exercises()) {
                if (item.count() > maxPerTarget) throw exceeded("EXERCISES_PER_TARGET");
            }
            if (draft.artifacts() > maxPerSession) throw exceeded("EXERCISES_PER_SESSION");
        } else if (draft.materials().size() > maxArtifacts) {
            throw exceeded("ARTIFACTS_PER_SESSION");
        }
    }

    private Draft ownerExercises(JsonNode items, Basis basis, Map<UUID, String> keptWhy) {
        List<ExerciseItem> planned = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (JsonNode item : items) {
            Commands.fields(item, Set.of("memberKey", "mechanics", "count"), Set.of());
            UUID member = Commands.entity(item, "memberKey");
            if (!basis.hasTarget(member) || !seen.add(member)) throw new InvalidRequestException();
            JsonNode mechanics = item.get("mechanics");
            if (!mechanics.isArray() || mechanics.isEmpty()) throw new InvalidRequestException();
            Set<String> chosen = new LinkedHashSet<>();
            for (JsonNode mechanic : mechanics) {
                if (!mechanic.isString() || !basis.allowedMechanics().contains(mechanic.stringValue()) || !chosen.add(mechanic.stringValue())) {
                    throw new InvalidRequestException();
                }
            }
            JsonNode count = item.get("count");
            if (!count.isIntegralNumber() || !count.canConvertToInt() || count.intValue() < 1) throw new InvalidRequestException();
            List<String> ordered = new ArrayList<>(basis.allowedMechanics());
            ordered.retainAll(chosen);
            planned.add(new ExerciseItem(member, List.copyOf(ordered), count.intValue(), keptWhy.get(member)));
        }
        return new Draft(EXERCISES, planned, List.of(), List.of());
    }

    private Draft ownerMaterials(JsonNode items, Basis basis) {
        List<MaterialItem> planned = new ArrayList<>();
        for (JsonNode item : items) {
            Commands.fields(item, Set.of("source", "title", "effort"), Set.of());
            JsonNode source = item.get("source");
            UUID note = null;
            if (basis.perNote()) {
                if (!source.isString()) throw new InvalidRequestException();
                note = Commands.entity(item, "source");
                if (basis.note(note).isEmpty()) throw new InvalidRequestException();
            } else if (!source.isNull()) {
                throw new InvalidRequestException();
            }
            JsonNode title = item.get("title");
            String text = title.isString() ? title.stringValue().strip() : "";
            if (text.isEmpty() || text.codePointCount(0, text.length()) > MAX_TITLE) throw new InvalidRequestException();
            JsonNode effort = item.get("effort");
            if (!effort.isString() || !EFFORTS.contains(effort.stringValue())) throw new InvalidRequestException();
            planned.add(new MaterialItem(note, text, effort.stringValue(), null));
        }
        return new Draft(MATERIALS, List.of(), planned, List.of());
    }

    private ResourceLimitExceededException exceeded(String limit) {
        Map<String, Object> limits = new LinkedHashMap<>();
        switch (limit) {
            case "ARTIFACTS_PER_SESSION" -> limits.put("maxArtifactsPerSession", maxArtifacts);
            default -> {
                limits.put("maxExercisesPerTarget", maxPerTarget);
                limits.put("maxExercisesPerSession", maxPerSession);
            }
        }
        return new ResourceLimitExceededException(ProblemExtension.builder().put("limit", limit).put("limits", limits).build());
    }

    // ------------------------------------------------------------------------------- wire

    /**
     * The wire shape of {@code getSession.plan}.
     *
     * @param targets  EXERCISES: {@code [{memberKey, title, exercises}]}, every target of the spec (the rows a plan may keep, drop or take back)
     * @param sources  MATERIALS: {@code [{noteId, label}]}, every note of the spec
     * @param hold     the credits the batch hold covers
     * @param plan     what the plan itself cost
     * @param bar      the credit bar of the period (the percentages are a share of it)
     */
    ObjectNode wire(Basis basis, Draft draft, JsonNode targets, JsonNode sources, int plan, int hold, int bar, boolean approved) {
        ObjectNode node = Json.object();
        node.put("kind", draft.kind());
        node.put("approved", approved);
        ArrayNode list = node.putArray("items");
        Map<UUID, String> titles = new LinkedHashMap<>();
        targets.forEach(target -> titles.put(UUID.fromString(target.path("memberKey").stringValue("")), target.path("title").stringValue("")));
        if (draft.kind().equals(EXERCISES)) {
            for (ExerciseItem item : draft.exercises()) {
                ObjectNode row = list.addObject().put("memberKey", item.memberKey().toString())
                        .put("title", titles.getOrDefault(item.memberKey(), ""));
                ArrayNode mechanics = row.putArray("mechanics");
                item.mechanics().forEach(mechanics::add);
                row.put("count", item.count());
                row.put("why", item.why() == null ? "" : item.why());
            }
            node.set("targets", targets.deepCopy());
        } else {
            for (MaterialItem item : draft.materials()) {
                ObjectNode row = list.addObject();
                row.put("source", item.noteId() == null ? null : item.noteId().toString());
                row.put("title", item.title()).put("effort", item.effort());
                row.put("why", item.why() == null ? "" : item.why());
                ObjectNode credits = row.putObject("creditsByEffort");
                for (String effort : EFFORTS) credits.put(effort, materialCredits(basis, item.noteId(), effort));
            }
            node.set("sources", sources.deepCopy());
        }
        ObjectNode totals = node.putObject("totals");
        totals.put("items", draft.size());
        totals.put("artifacts", draft.artifacts());
        ObjectNode cost = node.putObject("cost");
        cost.put("planCredits", plan);
        cost.put("batchCredits", cost(basis, draft));
        cost.put("holdCredits", hold);
        cost.put("barCredits", bar);
        ObjectNode rates = node.putObject("rates");
        if (draft.kind().equals(EXERCISES)) rates.put("exercisesPerFive", pricing.exerciseCredits(5));
        else rates.put("note", "creditsByEffort of each item");
        ObjectNode limits = node.putObject("limits");
        if (draft.kind().equals(EXERCISES)) {
            limits.put("maxExercisesPerTarget", maxPerTarget).put("maxExercisesPerSession", maxPerSession);
            ArrayNode allowed = node.putArray("allowedMechanics");
            basis.allowedMechanics().forEach(allowed::add);
        } else {
            limits.put("maxArtifactsPerSession", maxArtifacts);
        }
        ArrayNode notes = node.putArray("notes");
        for (String code : draft.notes()) notes.addObject().put("code", code).put("text", NOTE_TEXTS.getOrDefault(code, code));
        return node;
    }

    private static final Map<String, String> NOTE_TEXTS = Map.of("TRIMMED_TO_BUDGET",
            "План обрезан по бюджету: последние пункты не поместились.");

    /**
     * The planned material an artifact of an approved plan is written from: the item of the approved plan at the artifact's ordinal (the approval
     * creates the artifacts in the order of the plan). Empty for a session without a plan, a plan not yet approved and any other kind.
     */
    Optional<MaterialItem> plannedMaterial(Session session, Rows.Artifact artifact) {
        if (!session.kind().equals(MATERIALS)) return Optional.empty();
        return repository.plan(session.sessionId()).filter(stored -> stored.path("approved").asBoolean(false))
                .map(this::read).filter(draft -> artifact.ordinal() < draft.materials().size()).map(draft -> draft.materials().get(artifact.ordinal()));
    }

    /** The kept {@code why} of the items of a stored plan by target, so an approval that keeps a row keeps its reason. */
    static Map<UUID, String> whyOf(JsonNode stored) {
        Map<UUID, String> why = new LinkedHashMap<>();
        for (JsonNode item : stored.path("items")) {
            String member = item.path("memberKey").stringValue(null);
            if (member != null) why.put(UUID.fromString(member), item.path("why").stringValue(""));
        }
        return why;
    }

    /** The stored plan as items, for the artifacts of an approved plan: {@code {kind, items}} read back into a draft. */
    Draft read(JsonNode stored) {
        String kind = stored.path("kind").stringValue("");
        List<ExerciseItem> exercises = new ArrayList<>();
        List<MaterialItem> materials = new ArrayList<>();
        for (JsonNode item : stored.path("items")) {
            if (kind.equals(EXERCISES)) {
                List<String> mechanics = new ArrayList<>();
                item.path("mechanics").forEach(mechanic -> mechanics.add(mechanic.stringValue("")));
                exercises.add(new ExerciseItem(UUID.fromString(item.path("memberKey").stringValue("")), mechanics,
                        item.path("count").intValue(), item.path("why").stringValue("")));
            } else {
                String note = item.path("source").stringValue(null);
                materials.add(new MaterialItem(note == null ? null : UUID.fromString(note), item.path("title").stringValue(""),
                        item.path("effort").stringValue("MEDIUM"), item.path("why").stringValue("")));
            }
        }
        List<String> notes = new ArrayList<>();
        stored.path("notes").forEach(note -> notes.add(note.path("code").stringValue("")));
        return new Draft(kind, exercises, materials, notes);
    }
}
