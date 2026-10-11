package app.mnema.learning.update.plan;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One unit of an update plan. The revision ids are the unit's lineage revisions (the author's in the published revision, the copy's head, the base); for METADATA and ORDER they
 * are deck revisions of the source (author, base) and of the copy (mine). {@code null} is "the unit does not exist on that side".
 */
public record PlanUnit(UnitKind kind, UUID key, UnitClass unitClass, Reason reason, UUID authorRevisionId, UUID mineRevisionId, UUID baseRevisionId,
                       List<String> dependsOn, Choice defaultChoice) {
    public PlanUnit {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(key);
        Objects.requireNonNull(unitClass);
        Objects.requireNonNull(defaultChoice);
        dependsOn = List.copyOf(dependsOn);
    }

    public String unitId() { return kind.unitId(key); }

    PlanUnit with(UnitClass newClass, Reason newReason, List<String> newDependencies) {
        return new PlanUnit(kind, key, newClass, newReason, authorRevisionId, mineRevisionId, baseRevisionId, newDependencies, defaultFor(newClass));
    }

    static Choice defaultFor(UnitClass unitClass) {
        return switch (unitClass) {
            case NEW, SAFE -> Choice.ACCEPT;
            case CONFLICT, ASK_AGAIN -> Choice.KEEP_MINE;
            case DELETED -> Choice.KEEP;
        };
    }
}
