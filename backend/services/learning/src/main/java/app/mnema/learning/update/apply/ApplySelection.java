package app.mnema.learning.update.apply;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.update.plan.PlanUnit;
import app.mnema.learning.update.plan.Reason;
import app.mnema.learning.update.plan.UnitClass;
import app.mnema.learning.update.plan.UnitKind;
import app.mnema.learning.update.plan.UpdatePlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What an apply command does with each unit of the recomputed plan (Updates/4, #443): taken (ACCEPT), declined (DECLINE: the owner said no), or left for later (PENDING). Pure: it
 * only reads the plan and the command.
 *
 * <p><b>Choices.</b> Each named unit must be in the plan and the choice must be one its class takes ({@link #allowed}); anything else is a 400 that names the unit. An exercise
 * that needs a material the copy deleted cannot take the author's version (the combined publication would refuse a binding to a missing material), so ACCEPT of it is refused
 * early and by name.
 *
 * <p><b>«Принять всё безопасное».</b> {@code acceptSafe} takes every NEW and SAFE unit the choices do not name, and nothing else: never a CONFLICT, a unit the author removed, or a
 * question asked again.
 *
 * <p><b>The engine's invariants, modelled here so that none surfaces as a bare 400.</b> (1) An objective may move only together with the exercises that evidence it (the objective
 * head must be the newest revision the head exercises pin): an objective taken by name takes every exercise of the plan that lists it in {@code dependsOn} (counted in
 * {@link #also()}), or is {@code dependency_declined} if one of them was declined by name; an objective taken only by «accept all safe» waits unless every such exercise is taken
 * (or one of them was taken by name, which brings the objective anyway). (2) A material the author removed cannot be removed while a head exercise of the copy is about it: taking
 * the removal takes the removal of those exercises when the plan lists them as removals, else it is {@code material_still_used} naming the material (and the exercise when the plan
 * lists it).
 *
 * <p><b>Dependencies.</b> Taking a unit takes its {@code dependsOn} units ({@link #also} lists what was taken only for that reason). A unit taken by name whose dependency the owner
 * declined by name is a 400 naming both. A unit taken only by {@code acceptSafe} whose dependency was declined by name is not taken (it stays pending), so «accept all safe, but not
 * this material» works without the owner also naming every exercise of that material.
 */
final class ApplySelection {
    /** What happens to a unit. */
    enum Fate { ACCEPT, DECLINE, PENDING }

    private final Map<String, PlanUnit> units = new LinkedHashMap<>();
    private final Map<String, Fate> fates = new HashMap<>();
    private final Set<String> also = new LinkedHashSet<>();

    private ApplySelection() { }

    /**
     * @param bound for each member key of a material the plan removes (the author removed it), the exercises of the copy's HEAD that are about it (the engine refuses to remove a
     *              material a head exercise still quotes, so the exercise has to go with it)
     * @throws InvalidRequestException naming the unit (and the dependency) of a choice that cannot be honoured
     */
    static ApplySelection of(UpdatePlan plan, ApplyCommand command, Map<UUID, Set<UUID>> bound) {
        ApplySelection selection = new ApplySelection();
        plan.units().forEach(unit -> selection.units.put(unit.unitId(), unit));
        Map<String, List<String>> dependents = new HashMap<>();
        for (PlanUnit unit : plan.units()) {
            for (String dependency : unit.dependsOn()) dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(unit.unitId());
        }
        Set<String> explicitAccept = new LinkedHashSet<>();
        Set<String> explicitKeep = new LinkedHashSet<>();
        for (ApplyCommand.Pick pick : command.choices()) {
            PlanUnit unit = selection.units.get(pick.unitId());
            if (unit == null) throw invalid("unknown_unit", pick.unitId());
            if (!allowed(unit.unitClass(), pick.choice())) throw invalid("choice_not_allowed", pick.unitId());
            if (pick.choice().takesAuthor()) {
                if (unit.reason() == Reason.MATERIAL_DELETED) throw invalid("exercise_needs_material", pick.unitId());
                explicitAccept.add(pick.unitId());
            } else {
                explicitKeep.add(pick.unitId());
            }
        }
        Set<String> accepted = new LinkedHashSet<>(explicitAccept);
        if (command.acceptSafe()) {
            for (PlanUnit unit : plan.units()) {
                boolean safe = unit.unitClass() == UnitClass.NEW || unit.unitClass() == UnitClass.SAFE;
                if (safe && !explicitAccept.contains(unit.unitId()) && !explicitKeep.contains(unit.unitId())) accepted.add(unit.unitId());
            }
        }
        Set<String> soft = new HashSet<>(accepted);
        soft.removeAll(explicitAccept);
        Set<String> refused = new HashSet<>(explicitKeep);
        boolean changed;
        do {
            changed = false;
            for (String id : List.copyOf(accepted)) {
                if (!accepted.contains(id)) continue;
                PlanUnit unit = selection.units.get(id);
                boolean named = explicitAccept.contains(id);
                // the units this one needs to be taken with it
                for (String dependency : unit.dependsOn()) {
                    if (!selection.units.containsKey(dependency)) continue;
                    if (refused.contains(dependency)) {
                        if (named) throw invalid("dependency_declined", id, dependency);
                        accepted.remove(id);
                        refused.add(id);
                        changed = true;
                        break;
                    }
                    if (accepted.add(dependency)) changed = true;
                }
                if (!accepted.contains(id)) continue;
                if (unit.kind() == UnitKind.OBJECTIVE) {
                    List<String> exercises = dependents.getOrDefault(id, List.of());
                    if (named) {
                        // the objective head must be the newest one the head exercises pin: the exercises that evidence it come with it
                        for (String exercise : exercises) {
                            if (refused.contains(exercise)) throw invalid("dependency_declined", id, exercise);
                            if (selection.units.get(exercise).reason() == Reason.MATERIAL_DELETED) throw invalid("exercise_needs_material", exercise);
                            if (accepted.add(exercise)) changed = true;
                        }
                    } else if (soft.contains(id) && !exercises.stream().allMatch(accepted::contains) && exercises.stream().noneMatch(explicitAccept::contains)) {
                        // taken only by «accept all safe» and some exercise that evidences it waits: the objective waits with it
                        accepted.remove(id);
                        refused.add(id);
                        changed = true;
                    }
                } else if (unit.kind() == UnitKind.MATERIAL && unit.authorRevisionId() == null) {
                    // the engine refuses to remove a material that a head exercise is still about: that exercise has to be removed by the same command
                    for (UUID exerciseKey : bound.getOrDefault(unit.key(), Set.of())) {
                        String exercise = UnitKind.EXERCISE.unitId(exerciseKey);
                        PlanUnit removal = selection.units.get(exercise);
                        if (removal == null || removal.authorRevisionId() != null || refused.contains(exercise)) {
                            throw removal == null ? invalid("material_still_used", id) : invalid("material_still_used", id, exercise);
                        }
                        if (accepted.add(exercise)) changed = true;
                    }
                }
            }
        } while (changed);
        for (String id : accepted) {
            if (!explicitAccept.contains(id) && !soft.contains(id)) selection.also.add(id);
        }
        for (String id : selection.units.keySet()) {
            selection.fates.put(id, accepted.contains(id) ? Fate.ACCEPT : explicitKeep.contains(id) ? Fate.DECLINE : Fate.PENDING);
        }
        return selection;
    }

    /** The choices a class takes (the contract: NEW/SAFE ACCEPT|DECLINE, CONFLICT/ASK_AGAIN ACCEPT|KEEP_MINE, DELETED DELETE|KEEP). */
    static boolean allowed(UnitClass unitClass, ApplyChoice choice) {
        return switch (unitClass) {
            case NEW, SAFE -> choice == ApplyChoice.ACCEPT || choice == ApplyChoice.DECLINE;
            case CONFLICT, ASK_AGAIN -> choice == ApplyChoice.ACCEPT || choice == ApplyChoice.KEEP_MINE;
            case DELETED -> choice == ApplyChoice.DELETE || choice == ApplyChoice.KEEP;
        };
    }

    /** The units of the plan in its order. */
    Iterable<PlanUnit> units() { return units.values(); }

    Fate fate(PlanUnit unit) { return fates.get(unit.unitId()); }

    int count(Fate fate) { return (int) fates.values().stream().filter(found -> found == fate).count(); }

    /** The units taken only because another taken unit depends on them. */
    int also() { return also.size(); }

    private static InvalidRequestException invalid(String reason, String unitId) {
        return new InvalidRequestException(ProblemExtension.builder().put("reason", reason).put("unitId", unitId).build());
    }

    private static InvalidRequestException invalid(String reason, String unitId, String dependency) {
        return new InvalidRequestException(ProblemExtension.builder().put("reason", reason).put("unitId", unitId).put("dependency", dependency).build());
    }
}
