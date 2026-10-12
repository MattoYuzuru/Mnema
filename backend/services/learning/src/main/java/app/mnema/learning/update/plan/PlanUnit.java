package app.mnema.learning.update.plan;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One unit of an update plan. {@code implied} holds the ids of units the plan does NOT list but that this unit always brings with it (Updates/4: the objective an author's NEW exercise
 * introduces, which the copy has no head for, is part of that exercise and not a thing to review); it is not on the wire and not in {@code dependsOn}. The revision ids are the unit's lineage revisions (the author's in the published revision, the copy's head, the base); for METADATA and ORDER they
 * are deck revisions of the source (author, base) and of the copy (mine). {@code null} is "the unit does not exist on that side".
 */
public record PlanUnit(UnitKind kind, UUID key, UnitClass unitClass, Reason reason, UUID authorRevisionId, UUID mineRevisionId, UUID baseRevisionId,
                       List<String> dependsOn, Choice defaultChoice, List<String> implied) {
    public PlanUnit(UnitKind kind, UUID key, UnitClass unitClass, Reason reason, UUID authorRevisionId, UUID mineRevisionId, UUID baseRevisionId, List<String> dependsOn,
                    Choice defaultChoice) {
        this(kind, key, unitClass, reason, authorRevisionId, mineRevisionId, baseRevisionId, dependsOn, defaultChoice, List.of());
    }

    public PlanUnit {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(key);
        Objects.requireNonNull(unitClass);
        Objects.requireNonNull(defaultChoice);
        dependsOn = List.copyOf(dependsOn);
        implied = List.copyOf(implied);
    }

    public String unitId() { return kind.unitId(key); }

    PlanUnit with(UnitClass newClass, Reason newReason, List<String> newDependencies) {
        return with(newClass, newReason, newDependencies, implied);
    }

    PlanUnit with(UnitClass newClass, Reason newReason, List<String> newDependencies, List<String> newImplied) {
        return new PlanUnit(kind, key, newClass, newReason, authorRevisionId, mineRevisionId, baseRevisionId, newDependencies, defaultFor(newClass), newImplied);
    }

    static Choice defaultFor(UnitClass unitClass) {
        return switch (unitClass) {
            case NEW, SAFE -> Choice.ACCEPT;
            case CONFLICT, ASK_AGAIN -> Choice.KEEP_MINE;
            case DELETED -> Choice.KEEP;
        };
    }
}
