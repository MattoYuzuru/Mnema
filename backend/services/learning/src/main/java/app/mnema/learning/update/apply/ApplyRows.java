package app.mnema.learning.update.apply;

import app.mnema.learning.update.plan.PlanUnit;
import app.mnema.learning.update.plan.UnitKind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The writes to {@code fork_unit_decision} that an apply command asks of the final transaction of its publication (Updates/4, #443, migration V61), one {@link Row} per unit of the
 * plan that needs one:
 * <ul>
 *   <li>a unit the command took needs no row; if it had a recorded decision ({@link Op#REMOVE}) the row goes, the copy now holds what the author published;</li>
 *   <li>a unit the owner declined by name gets the row that remembers the author's state it was declined at: {@link Op#DECLINE_NEW} (an addition), {@link Op#KEEP_DELETED} (a change
 *       to a unit the owner had deleted), {@link Op#KEEP_MINE} (everything else, also «the author removed it and I keep it», whose author state is "absent");</li>
 *   <li>a unit left for later gets {@link Op#PENDING} with its OWN old base and the author's state offered in THIS plan, so the base can move to the published revision at once
 *       (the base rule of the architecture; the invariant of the Updates/3 review: the pair is always rewritten to what this plan offered).</li>
 * </ul>
 * Every PENDING row the copy had is deleted by the writer first, so a pending unit that is no longer on offer leaves no row behind.
 */
final class ApplyRows {
    /** "Absent": the unit does not exist on that side (the author removed it; it did not exist at the old base). */
    static final String ABSENT = "-";
    /** A column the row does not use. */
    static final String UNUSED = "";

    /** What the final writer does with the unit's row: remove it, or write it with this decision (the constants other than {@code REMOVE} are the {@code decision} values of V61). */
    enum Op { REMOVE, KEEP_MINE, DECLINE_NEW, KEEP_DELETED, PENDING }

    record Row(Op op, UnitKind kind, UUID key, String resolved, String base) {
        ArrayNode toJson() {
            return JsonNodeFactory.instance.arrayNode().add(op.name()).add(kind.name()).add(key.toString()).add(resolved).add(base);
        }

        static Row fromJson(JsonNode node) {
            return new Row(Op.valueOf(node.get(0).stringValue(null)), UnitKind.valueOf(node.get(1).stringValue(null)), UUID.fromString(node.get(2).stringValue(null)),
                    node.get(3).stringValue(null), node.get(4).stringValue(null));
        }
    }

    private ApplyRows() { }

    /** @param recorded the unit ids that have a recorded decision that is not PENDING */
    static List<Row> build(ApplySelection selection, Set<String> recorded) {
        List<Row> rows = new ArrayList<>();
        for (PlanUnit unit : selection.units()) {
            switch (selection.fate(unit)) {
                case ACCEPT -> {
                    if (recorded.contains(unit.unitId())) rows.add(new Row(Op.REMOVE, unit.kind(), unit.key(), UNUSED, UNUSED));
                }
                case DECLINE -> rows.add(declined(unit));
                case PENDING -> rows.add(new Row(Op.PENDING, unit.kind(), unit.key(), state(unit.authorRevisionId()), state(unit.baseRevisionId())));
            }
        }
        return rows;
    }

    private static Row declined(PlanUnit unit) {
        UUID author = unit.authorRevisionId();
        boolean unitOfTheCopy = unit.kind() == UnitKind.MATERIAL || unit.kind() == UnitKind.EXERCISE;
        if (unitOfTheCopy && author == null) return new Row(Op.KEEP_MINE, unit.kind(), unit.key(), ABSENT, UNUSED);
        if (unitOfTheCopy && unit.mineRevisionId() == null) {
            // the copy does not have the unit: the author added it (the owner declines an addition) or the owner had deleted it (and keeps it deleted)
            return new Row(unit.baseRevisionId() == null ? Op.DECLINE_NEW : Op.KEEP_DELETED, unit.kind(), unit.key(), author.toString(), UNUSED);
        }
        return new Row(Op.KEEP_MINE, unit.kind(), unit.key(), author.toString(), UNUSED);
    }

    private static String state(UUID revision) { return revision == null ? ABSENT : revision.toString(); }
}
