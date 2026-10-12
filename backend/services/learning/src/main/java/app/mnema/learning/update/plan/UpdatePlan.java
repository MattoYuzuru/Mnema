package app.mnema.learning.update.plan;

import java.util.List;
import java.util.UUID;

/**
 * The three-way plan of a copy: its base (what both started from), the author's published revision and the copy's head, by units, with the pins that make it a plan of exactly
 * this state ({@code planVersion} is a digest of the head, the published revision, the base and the decisions). {@code units} is the whole plan in its deterministic order; the
 * HTTP route cuts pages of it, and it, the {@code summary} and the unit routes are all the owner ever sees. {@code implicit} (Updates/4) are the units that are not listed but are
 * brought by the units that need them (the objective a NEW exercise introduces, which the copy has no head for): the apply command adopts them with those units and never alone. States other than READY and UP_TO_DATE carry no pins and no units (and nothing about the source).
 */
public record UpdatePlan(State state, UUID forkDeckId, UUID forkHeadRevisionId, UUID upstreamRevisionId, UUID baseRevisionId, String planVersion, String releaseNote,
                         Summary summary, List<PlanUnit> units, List<PlanUnit> implicit) {
    public UpdatePlan {
        units = List.copyOf(units);
        implicit = List.copyOf(implicit);
    }

    public UpdatePlan(State state, UUID forkDeckId, UUID forkHeadRevisionId, UUID upstreamRevisionId, UUID baseRevisionId, String planVersion, String releaseNote,
                      Summary summary, List<PlanUnit> units) {
        this(state, forkDeckId, forkHeadRevisionId, upstreamRevisionId, baseRevisionId, planVersion, releaseNote, summary, units, List.of());
    }

    public enum State { READY, UP_TO_DATE, SOURCE_UNAVAILABLE, DETACHED, PREPARING }

    /** Counts of the WHOLE plan by class (an objective counts like any other unit). */
    public record Summary(int newMaterials, int newExercises, int fixes, int conflicts, int deletedByAuthor, int askAgain) {
        static final Summary EMPTY = new Summary(0, 0, 0, 0, 0, 0);

        static Summary of(List<PlanUnit> units) {
            int newMaterials = 0, newExercises = 0, fixes = 0, conflicts = 0, deleted = 0, again = 0;
            for (PlanUnit unit : units) {
                switch (unit.unitClass()) {
                    case NEW -> {
                        // only materials and exercises are ever NEW
                        if (unit.kind() == UnitKind.MATERIAL) newMaterials++;
                        else if (unit.kind() == UnitKind.EXERCISE) newExercises++;
                    }
                    case SAFE -> fixes++;
                    case CONFLICT -> conflicts++;
                    case DELETED -> deleted++;
                    case ASK_AGAIN -> again++;
                }
            }
            return new Summary(newMaterials, newExercises, fixes, conflicts, deleted, again);
        }
    }

    static UpdatePlan closed(State state, UUID forkDeckId) {
        return new UpdatePlan(state, forkDeckId, null, null, null, null, null, Summary.EMPTY, List.of(), List.of());
    }

    public boolean hasPins() { return state == State.READY || state == State.UP_TO_DATE; }

    /** The listed units and the implicit ones: everything an apply command may adopt. */
    public List<PlanUnit> allUnits() {
        if (implicit.isEmpty()) return units;
        List<PlanUnit> all = new java.util.ArrayList<>(units);
        all.addAll(implicit);
        return all;
    }

    /** The unit with this kind and key, if the plan lists it. */
    public java.util.Optional<PlanUnit> unit(UnitKind kind, UUID key) {
        return units.stream().filter(unit -> unit.kind() == kind && unit.key().equals(key)).findFirst();
    }
}
