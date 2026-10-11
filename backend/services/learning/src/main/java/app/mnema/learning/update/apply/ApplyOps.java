package app.mnema.learning.update.apply;

import app.mnema.learning.catalog.content.pages.CountedPageTypes.Change;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Result;
import app.mnema.learning.update.CombinedUnits.ExerciseOp;
import app.mnema.learning.update.CombinedUnits.ItemOp;
import app.mnema.learning.update.CombinedUnits.Metadata;
import app.mnema.learning.update.CombinedUnits.ObjectiveOp;
import app.mnema.learning.update.apply.ApplySelection.Fate;
import app.mnema.learning.update.plan.PlanUnit;
import app.mnema.learning.update.plan.UnitKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The operations of the combined publication that take the units an {@link ApplySelection} accepted (Updates/4, #443). Pure: the places come from the frontier diff of the copy's head
 * H against the published revision P ({@code ManifestDiff}, O(changes)), never from the client, and the operations are built so that every ordinal is valid in the manifest as the
 * PREVIOUS operations left it (the engine's rule), which a test replays against a plain list.
 *
 * <p><b>Order of the operations</b> (materials, then exercises; both with the same three steps):
 * <ol>
 *   <li><b>replacements</b> by place in H, ascending: a replacement moves nothing, so every place is H's;</li>
 *   <li><b>removals</b> by place in H, DESCENDING: removing the last first leaves the places of the others untouched;</li>
 *   <li><b>insertions</b> by place in P, ascending. The place of an insertion is its place in P minus the members of P the copy does not take (they are absent from the copy and
 *       precede it in P), then at most the size of the manifest at that moment. That is exactly the place in P when the copy follows P's order (an untouched copy, with its own
 *       additions after the author's members) and a close place otherwise;</li>
 *   <li><b>moves</b> (materials only, only when the ORDER unit is taken): the result is brought to the order of P. A key of P the result holds stays in the order of P, a member
 *       P does not have (the owner's own addition, or one the owner kept although the author removed it) stays behind the P-member it followed; the keys outside a longest
 *       common chain are moved, each right behind its predecessor in the target, so there are as few moves as the chain allows.</li>
 * </ol>
 * Objectives are adopted at the author's revision, and the deck's title and description at those of P.
 */
final class ApplyOps {
    /** The most members of one manifest the ORDER unit reads in full (a scan of both manifests, 100 entries per read). */
    static final int ORDER_SCAN_LIMIT = 20_000;

    /** The members of the copy's head and of P, in order; read only when the ORDER unit is taken. */
    record Lists(List<UUID> head, List<UUID> published) { }

    /** The title and description of P. */
    record Text(String title, String description) { }

    /** The diff of H against P of one manifest and the size of H's. */
    record Shape(Result diff, int headCount) { }

    /** The operations, ready for a {@code CombinedUnits}. */
    record Built(Metadata metadata, List<ItemOp> items, List<ExerciseOp> exercises, List<ObjectiveOp> objectives) { }

    /** One operation on a manifest: the key, the revision to adopt (null for a removal) and the place. */
    record Step(UUID key, UUID revision, int ordinal) { }

    private ApplyOps() { }

    static Built build(ApplySelection selection, Shape members, Shape exercises, Text published, Supplier<Lists> lists) {
        Metadata metadata = null;
        List<ObjectiveOp> objectives = new ArrayList<>();
        boolean order = false;
        for (PlanUnit unit : selection.units()) {
            if (selection.fate(unit) != Fate.ACCEPT) continue;
            switch (unit.kind()) {
                case METADATA -> metadata = new Metadata(published.title(), published.description());
                case ORDER -> order = true;
                case OBJECTIVE -> objectives.add(new ObjectiveOp.Adopt(unit.key(), unit.authorRevisionId()));
                case MATERIAL, EXERCISE -> { }
            }
        }
        Steps materialSteps = steps(UnitKind.MATERIAL, selection, members);
        List<ItemOp> items = new ArrayList<>();
        materialSteps.replace().forEach(step -> items.add(new ItemOp.AdoptReplace(step.key(), step.ordinal(), step.revision())));
        materialSteps.remove().forEach(step -> items.add(new ItemOp.Remove(step.key(), step.ordinal())));
        materialSteps.insert().forEach(step -> items.add(new ItemOp.AdoptInsert(step.key(), step.ordinal(), step.revision())));
        if (order) items.addAll(moves(materialSteps, members, lists.get()));

        Steps exerciseSteps = steps(UnitKind.EXERCISE, selection, exercises);
        List<ExerciseOp> exerciseOps = new ArrayList<>();
        exerciseSteps.replace().forEach(step -> exerciseOps.add(new ExerciseOp.AdoptReplace(step.key(), step.ordinal(), step.revision())));
        exerciseSteps.remove().forEach(step -> exerciseOps.add(new ExerciseOp.Remove(step.key(), step.ordinal())));
        exerciseSteps.insert().forEach(step -> exerciseOps.add(new ExerciseOp.AdoptInsert(step.key(), step.ordinal(), step.revision())));
        return new Built(metadata, items, exerciseOps, objectives);
    }

    /** Replacements, removals and insertions of one kind, each list in the order the operations run in. */
    record Steps(List<Step> replace, List<Step> remove, List<Step> insert) { }

    static Steps steps(UnitKind kind, ApplySelection selection, Shape shape) {
        Map<UUID, Change> changes = new HashMap<>();
        List<Integer> added = new ArrayList<>();
        for (Change change : shape.diff().changes()) {
            changes.put(change.key(), change);
            if (change.before() == null) added.add(change.afterOrdinal());
        }
        Collections.sort(added);
        List<Step> replace = new ArrayList<>();
        List<Step> remove = new ArrayList<>();
        List<Step> insert = new ArrayList<>();
        for (PlanUnit unit : selection.units()) {
            if (unit.kind() != kind || selection.fate(unit) != Fate.ACCEPT) continue;
            UUID author = unit.authorRevisionId();
            UUID mine = unit.mineRevisionId();
            Change change = changes.get(unit.key());
            if (change == null) throw disagree();
            if (author != null && mine != null) {
                if (change.before() == null || change.after() == null) throw disagree();
                replace.add(new Step(unit.key(), author, change.beforeOrdinal()));
            } else if (author != null) {
                if (change.before() != null) throw disagree();
                insert.add(new Step(unit.key(), author, change.afterOrdinal()));
            } else if (mine != null) {
                if (change.after() != null) throw disagree();
                remove.add(new Step(unit.key(), null, change.beforeOrdinal()));
            } else {
                throw disagree();
            }
        }
        replace.sort(Comparator.comparingInt(Step::ordinal));
        remove.sort(Comparator.comparingInt(Step::ordinal).reversed());
        insert.sort(Comparator.comparingInt(Step::ordinal));
        List<Step> placed = new ArrayList<>(insert.size());
        int size = shape.headCount() - remove.size();
        for (int index = 0; index < insert.size(); index++) {
            int inP = insert.get(index).ordinal();
            // the members of P before this one that the copy does not take: they are not in the copy, so they take no place
            int absent = lessThan(added, inP) - index;
            int place = Math.max(0, Math.min(inP - absent, size + index));
            placed.add(new Step(insert.get(index).key(), insert.get(index).revision(), place));
        }
        return new Steps(List.copyOf(replace), List.copyOf(remove), List.copyOf(placed));
    }

    private static int lessThan(List<Integer> sorted, int value) {
        int low = 0;
        int high = sorted.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (sorted.get(middle) < value) low = middle + 1; else high = middle;
        }
        return low;
    }

    private static IllegalStateException disagree() {
        return new IllegalStateException("The update plan and the manifests disagree");
    }

    // ---- the order of materials ----

    /** The moves that bring the members the operations above leave to the order of P (see the class comment). */
    static List<ItemOp> moves(Steps steps, Shape members, Lists lists) {
        List<UUID> current = new ArrayList<>(lists.head());
        if (current.size() != members.headCount()) throw disagree();
        for (Step step : steps.replace()) {
            if (!current.get(step.ordinal()).equals(step.key())) throw disagree();
        }
        for (Step step : steps.remove()) {
            if (!current.get(step.ordinal()).equals(step.key())) throw disagree();
            current.remove(step.ordinal());
        }
        for (Step step : steps.insert()) current.add(step.ordinal(), step.key());
        List<UUID> target = target(current, lists.published());
        Set<UUID> stay = longestChain(current, target);
        List<UUID> work = new ArrayList<>(current);
        List<ItemOp> moves = new ArrayList<>();
        for (int index = 0; index < target.size(); index++) {
            UUID key = target.get(index);
            if (stay.contains(key)) continue;
            int from = work.indexOf(key);
            work.remove(from);
            int to = index == 0 ? 0 : work.indexOf(target.get(index - 1)) + 1;
            work.add(to, key);
            if (from != to) moves.add(new ItemOp.Move(key, from, to));
        }
        if (!work.equals(target)) throw new IllegalStateException("The moves of the order unit do not reach the target order");
        return moves;
    }

    /** The order of P for the members P has; a member P does not have follows the P-member in front of it (or stays in front of all of them). */
    static List<UUID> target(List<UUID> current, List<UUID> published) {
        Set<UUID> inCurrent = new HashSet<>(current);
        Set<UUID> inPublished = new HashSet<>(published);
        List<UUID> front = new ArrayList<>();
        Map<UUID, List<UUID>> followers = new HashMap<>();
        UUID anchor = null;
        for (UUID key : current) {
            if (inPublished.contains(key)) anchor = key;
            else if (anchor == null) front.add(key);
            else followers.computeIfAbsent(anchor, ignored -> new ArrayList<>()).add(key);
        }
        List<UUID> target = new ArrayList<>(current.size());
        target.addAll(front);
        for (UUID key : published) {
            if (!inCurrent.contains(key)) continue;
            target.add(key);
            target.addAll(followers.getOrDefault(key, List.of()));
        }
        return target;
    }

    /** The keys of a longest increasing chain of the places {@code target} has in {@code current}: the ones that do not have to move. */
    static Set<UUID> longestChain(List<UUID> current, List<UUID> target) {
        Map<UUID, Integer> place = new HashMap<>();
        for (int index = 0; index < current.size(); index++) place.put(current.get(index), index);
        int[] sequence = new int[target.size()];
        for (int index = 0; index < sequence.length; index++) sequence[index] = place.get(target.get(index));
        int[] tails = new int[sequence.length];
        int[] tailAt = new int[sequence.length];
        int[] previous = new int[sequence.length];
        int length = 0;
        for (int index = 0; index < sequence.length; index++) {
            int at = Arrays.binarySearch(tails, 0, length, sequence[index]);
            if (at < 0) at = -at - 1;
            tails[at] = sequence[index];
            tailAt[at] = index;
            previous[index] = at == 0 ? -1 : tailAt[at - 1];
            if (at == length) length++;
        }
        Set<UUID> stay = new HashSet<>();
        int index = length == 0 ? -1 : tailAt[length - 1];
        while (index >= 0) {
            stay.add(target.get(index));
            index = previous[index];
        }
        return stay;
    }
}
