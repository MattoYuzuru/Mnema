package app.mnema.learning.update.apply;

import app.mnema.learning.catalog.content.pages.CountedPageTypes.Change;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.DiffStats;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.OrderChange;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Result;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import app.mnema.learning.update.CombinedUnits;
import app.mnema.learning.update.CombinedUnits.ItemOp;
import app.mnema.learning.update.apply.ApplyOps.Built;
import app.mnema.learning.update.apply.ApplyOps.Lists;
import app.mnema.learning.update.apply.ApplyOps.Shape;
import app.mnema.learning.update.apply.ApplyOps.Text;
import app.mnema.learning.update.plan.Choice;
import app.mnema.learning.update.plan.PlanUnit;
import app.mnema.learning.update.plan.UnitClass;
import app.mnema.learning.update.plan.UnitKind;
import app.mnema.learning.update.plan.UpdatePlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Updates/4 (#443), the construction of the operations of the combined publication from the units an apply command takes, against plain lists: for any copy and any published
 * order, any set of units taken, the operations are valid one after the other (every ordinal holds the key the operation names, in the manifest as the previous operations left
 * it), the result holds exactly the members it should, an untouched copy ends in exactly the order of P, and the ORDER unit brings the order of P with as few moves as possible.
 */
class ApplyOpsTest {
    private static final UUID SCOPE = UUID.randomUUID();
    private static final Text TEXT = new Text("title", "description");

    /** A copy H and a published order P of the same lineage as lists of member keys, and what differs between them. */
    private record World(List<UUID> head, List<UUID> published, Set<UUID> changed, Map<UUID, UUID> authorRevision, Map<UUID, UUID> mineRevision) {
        Result diff() {
            Map<UUID, Integer> inHead = index(head);
            Map<UUID, Integer> inPublished = index(published);
            List<Change> changes = new ArrayList<>();
            for (UUID key : published) {
                if (!inHead.containsKey(key)) changes.add(new Change(key, null, ref(), -1, inPublished.get(key)));
                else if (changed.contains(key)) changes.add(new Change(key, ref(), ref(), inHead.get(key), inPublished.get(key)));
            }
            for (UUID key : head) if (!inPublished.containsKey(key)) changes.add(new Change(key, ref(), null, inHead.get(key), -1));
            return new Result(changes, new OrderChange(false, List.of()), new DiffStats(0, 0));
        }
    }

    static Choice defaultFor(UnitClass unitClass) {
        return switch (unitClass) {
            case NEW, SAFE -> Choice.ACCEPT;
            case CONFLICT, ASK_AGAIN -> Choice.KEEP_MINE;
            case DELETED -> Choice.KEEP;
        };
    }

    private static ObjectRef ref() { return new ObjectRef(SCOPE, UUID.randomUUID()); }

    private static Map<UUID, Integer> index(List<UUID> list) {
        Map<UUID, Integer> result = new HashMap<>();
        for (int at = 0; at < list.size(); at++) result.put(list.get(at), at);
        return result;
    }

    private static World world(List<UUID> head, List<UUID> published, Set<UUID> changed) {
        Map<UUID, UUID> author = new HashMap<>();
        Map<UUID, UUID> mine = new HashMap<>();
        published.forEach(key -> author.put(key, UUID.randomUUID()));
        head.forEach(key -> mine.put(key, UUID.randomUUID()));
        return new World(head, published, changed, author, mine);
    }

    /** The plan of the world: one MATERIAL unit per difference (+ the ORDER unit when asked), the command taking exactly {@code taken} and (optionally) the order. */
    private static ApplySelection selection(World world, Set<UUID> taken, boolean order) {
        List<PlanUnit> units = new ArrayList<>();
        List<ApplyCommand.Pick> picks = new ArrayList<>();
        UUID deck = UUID.randomUUID();
        for (Change change : world.diff().changes()) {
            UUID author = change.after() == null ? null : world.authorRevision().get(change.key());
            UUID mine = change.before() == null ? null : world.mineRevision().get(change.key());
            UnitClass unitClass = author == null ? UnitClass.DELETED : mine == null ? UnitClass.NEW : UnitClass.SAFE;
            units.add(new PlanUnit(UnitKind.MATERIAL, change.key(), unitClass, null, author, mine, mine, List.of(), defaultFor(unitClass)));
            if (taken.contains(change.key())) picks.add(new ApplyCommand.Pick(UnitKind.MATERIAL, change.key(), author == null ? ApplyChoice.DELETE : ApplyChoice.ACCEPT));
        }
        if (order) {
            units.add(new PlanUnit(UnitKind.ORDER, deck, UnitClass.SAFE, null, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of(), defaultFor(UnitClass.SAFE)));
            picks.add(new ApplyCommand.Pick(UnitKind.ORDER, deck, ApplyChoice.ACCEPT));
        }
        UpdatePlan plan = new UpdatePlan(UpdatePlan.State.READY, deck, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "sha256:" + "a".repeat(64), null,
                new UpdatePlan.Summary(0, 0, 0, 0, 0, 0), units);
        return ApplySelection.of(plan, new ApplyCommand(UUID.randomUUID(), plan.planVersion(), false, picks), Map.of());
    }

    private static Built build(World world, ApplySelection selection) {
        return ApplyOps.build(selection, new Shape(world.diff(), world.head().size()), new Shape(new Result(List.of(), new OrderChange(false, List.of()), new DiffStats(0, 0)), 0),
                TEXT, () -> new Lists(world.head(), world.published()));
    }

    /** Replays the item operations on a plain list exactly as the engine reads them: every ordinal valid in the list as it stands, and the key found there. */
    private static List<UUID> replay(List<UUID> head, List<ItemOp> ops) {
        List<UUID> list = new ArrayList<>(head);
        Set<UUID> primary = new HashSet<>();
        Set<UUID> moved = new HashSet<>();
        for (ItemOp op : ops) {
            switch (op) {
                case ItemOp.AdoptReplace replace -> {
                    assertThat(list.get(replace.ordinal())).isEqualTo(replace.memberKey());
                    assertThat(primary.add(replace.memberKey())).isTrue();
                }
                case ItemOp.AdoptInsert insert -> {
                    assertThat(insert.ordinal()).isBetween(0, list.size());
                    list.add(insert.ordinal(), insert.memberKey());
                    assertThat(primary.add(insert.memberKey())).isTrue();
                }
                case ItemOp.Remove remove -> {
                    assertThat(list.get(remove.ordinal())).isEqualTo(remove.memberKey());
                    list.remove(remove.ordinal());
                    assertThat(primary.add(remove.memberKey())).isTrue();
                }
                case ItemOp.Move move -> {
                    assertThat(list.get(move.fromOrdinal())).isEqualTo(move.memberKey());
                    assertThat(move.fromOrdinal()).isNotEqualTo(move.toOrdinal());
                    list.remove(move.fromOrdinal());
                    assertThat(move.toOrdinal()).isBetween(0, list.size());
                    list.add(move.toOrdinal(), move.memberKey());
                    assertThat(moved.add(move.memberKey())).isTrue();
                }
            }
        }
        return list;
    }

    private static List<UUID> keys(int count) {
        List<UUID> keys = new ArrayList<>();
        for (int index = 0; index < count; index++) keys.add(UUID.randomUUID());
        return keys;
    }

    @Test
    void anUntouchedCopyThatTakesEverythingEndsInExactlyTheOrderOfThePublishedRevision() {
        Random random = new Random(443);
        for (int round = 0; round < 400; round++) {
            // P: the author's order. H: the copy at the base, i.e. P without what the author added, and with what the author removed (placed anywhere)
            List<UUID> published = keys(random.nextInt(40));
            List<UUID> head = new ArrayList<>();
            Set<UUID> changed = new HashSet<>();
            Set<UUID> taken = new HashSet<>();
            for (UUID key : published) {
                int dice = random.nextInt(10);
                if (dice < 3) {
                    taken.add(key);                                  // the author added it: the copy has not got it
                } else {
                    head.add(key);
                    if (dice < 5) { changed.add(key); taken.add(key); }
                }
            }
            for (int removed = random.nextInt(5); removed > 0; removed--) {
                UUID key = UUID.randomUUID();
                head.add(random.nextInt(head.size() + 1), key);      // the author removed it
                taken.add(key);
            }
            World world = world(head, published, changed);
            Built built = build(world, selection(world, taken, false));
            assertThat(replay(head, built.items())).as("round " + round).isEqualTo(published);
        }
    }

    @Test
    void aMemberThePublishedRevisionHasAndTheCopyDoesNotTakeLeavesAPlaceOnlyForTheOnesItTakes() {
        Random random = new Random(7);
        for (int round = 0; round < 300; round++) {
            List<UUID> published = keys(1 + random.nextInt(30));
            List<UUID> head = new ArrayList<>();
            Set<UUID> taken = new HashSet<>();
            List<UUID> declined = new ArrayList<>();
            for (UUID key : published) {
                if (random.nextInt(3) == 0) {
                    if (random.nextBoolean()) taken.add(key); else declined.add(key);
                } else {
                    head.add(key);
                }
            }
            World world = world(head, published, Set.of());
            Built built = build(world, selection(world, taken, false));
            List<UUID> expected = new ArrayList<>(published);
            expected.removeAll(declined);
            assertThat(replay(head, built.items())).as("round " + round).isEqualTo(expected);
        }
    }

    @Test
    void anyCopyAndAnyChoiceGivesOperationsTheEngineCanReadOneAfterTheOther() {
        Random random = new Random(11);
        for (int round = 0; round < 600; round++) {
            List<UUID> all = keys(random.nextInt(35));
            List<UUID> head = new ArrayList<>();
            List<UUID> published = new ArrayList<>();
            Set<UUID> changed = new HashSet<>();
            for (UUID key : all) {
                switch (random.nextInt(5)) {
                    case 0 -> head.add(key);                                          // the author removed it, the copy has it
                    case 1 -> published.add(key);                                     // the author added it (or the copy deleted it)
                    default -> { head.add(key); published.add(key); if (random.nextBoolean()) changed.add(key); }
                }
            }
            Collections.shuffle(head, random);
            if (random.nextBoolean()) Collections.shuffle(published, random);
            World world = world(head, published, changed);
            Set<UUID> taken = new HashSet<>();
            for (Change change : world.diff().changes()) if (random.nextBoolean()) taken.add(change.key());
            boolean order = random.nextBoolean();
            Built built = build(world, selection(world, taken, order));
            List<UUID> result = replay(head, built.items());

            Set<UUID> expected = new HashSet<>(head);
            for (UUID key : taken) {
                if (!published.contains(key)) expected.remove(key); else expected.add(key);
            }
            assertThat(new HashSet<>(result)).as("round " + round).isEqualTo(expected);
            assertThat(result).doesNotHaveDuplicates();
            if (order) {
                List<UUID> inPublishedOrder = published.stream().filter(result::contains).toList();
                assertThat(result.stream().filter(published::contains).toList()).as("the order of P, round " + round).isEqualTo(inPublishedOrder);
            }
        }
    }

    @Test
    void theOrderUnitUsesAsFewMovesAsTheLongestCommonChainAllows() {
        Random random = new Random(21);
        for (int round = 0; round < 300; round++) {
            List<UUID> published = keys(1 + random.nextInt(25));
            List<UUID> head = new ArrayList<>(published);
            Collections.shuffle(head, random);
            World world = world(head, published, Set.of());
            Built built = build(world, selection(world, Set.of(), true));
            long moves = built.items().stream().filter(ItemOp.Move.class::isInstance).count();
            assertThat(replay(head, built.items())).isEqualTo(published);
            assertThat(moves).as("round " + round).isEqualTo(head.size() - longestIncreasing(head, published));
        }
    }

    /** O(n^2) dynamic programme, independent of the implementation: the longest chain of {@code head} that is also in the order of {@code published}. */
    private static int longestIncreasing(List<UUID> head, List<UUID> published) {
        Map<UUID, Integer> place = index(published);
        int[] best = new int[head.size()];
        int longest = 0;
        for (int at = 0; at < head.size(); at++) {
            best[at] = 1;
            for (int before = 0; before < at; before++) {
                if (place.get(head.get(before)) < place.get(head.get(at))) best[at] = Math.max(best[at], best[before] + 1);
            }
            longest = Math.max(longest, best[at]);
        }
        return longest;
    }

    @Test
    void aMemberOfTheCopyThatPublishedDoesNotHaveStaysBehindTheMemberItFollowed() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), own = UUID.randomUUID(), early = UUID.randomUUID();
        // the copy: early (its own), c, own (its own, after c), a, b ; the author's order: a, b, c
        World world = world(List.of(early, c, own, a, b), List.of(a, b, c), Set.of());
        Built built = build(world, selection(world, Set.of(), true));
        assertThat(replay(world.head(), built.items())).isEqualTo(List.of(early, a, b, c, own));
    }

    @Test
    void theMetadataObjectivesAndExercisesAreBuiltFromTheAcceptedUnits() {
        UUID deck = UUID.randomUUID();
        UUID objective = UUID.randomUUID();
        UUID objectiveRevision = UUID.randomUUID();
        UUID exercise = UUID.randomUUID();
        UUID added = UUID.randomUUID();
        UUID revised = UUID.randomUUID();
        UUID removed = UUID.randomUUID();
        UUID authorAdded = UUID.randomUUID();
        UUID authorRevised = UUID.randomUUID();
        List<PlanUnit> units = List.of(
                new PlanUnit(UnitKind.METADATA, deck, UnitClass.SAFE, null, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of(), defaultFor(UnitClass.SAFE)),
                new PlanUnit(UnitKind.OBJECTIVE, objective, UnitClass.SAFE, null, objectiveRevision, null, null, List.of(), defaultFor(UnitClass.SAFE)),
                new PlanUnit(UnitKind.EXERCISE, added, UnitClass.NEW, null, authorAdded, null, null, List.of("OBJECTIVE:" + objective), defaultFor(UnitClass.NEW)),
                new PlanUnit(UnitKind.EXERCISE, revised, UnitClass.SAFE, null, authorRevised, UUID.randomUUID(), UUID.randomUUID(), List.of(), defaultFor(UnitClass.SAFE)),
                new PlanUnit(UnitKind.EXERCISE, removed, UnitClass.DELETED, null, null, UUID.randomUUID(), UUID.randomUUID(), List.of(), defaultFor(UnitClass.DELETED)));
        UpdatePlan plan = new UpdatePlan(UpdatePlan.State.READY, deck, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "sha256:" + "b".repeat(64), null,
                new UpdatePlan.Summary(0, 0, 0, 0, 0, 0), units);
        // "accept all safe" and the exercise the author removed
        ApplySelection selection = ApplySelection.of(plan, new ApplyCommand(UUID.randomUUID(), plan.planVersion(), true,
                List.of(new ApplyCommand.Pick(UnitKind.EXERCISE, removed, ApplyChoice.DELETE))), Map.of());
        // the exercises manifest of H: [x, revised, y, removed]; P: [x, revised, added', y]
        UUID x = UUID.randomUUID(), y = UUID.randomUUID();
        Result diff = new Result(List.of(new Change(added, null, ref(), -1, 2), new Change(revised, ref(), ref(), 1, 1), new Change(removed, ref(), null, 3, -1)),
                new OrderChange(false, List.of()), new DiffStats(0, 0));
        Built built = ApplyOps.build(selection, new Shape(new Result(List.of(), new OrderChange(false, List.of()), new DiffStats(0, 0)), 0), new Shape(diff, 4), TEXT,
                () -> { throw new AssertionError("the order was not asked for"); });
        assertThat(built.metadata()).isEqualTo(new CombinedUnits.Metadata("title", "description"));
        assertThat(built.objectives()).containsExactly(new CombinedUnits.ObjectiveOp.Adopt(objective, objectiveRevision));
        assertThat(built.exercises()).containsExactly(new CombinedUnits.ExerciseOp.AdoptReplace(revised, 1, authorRevised),
                new CombinedUnits.ExerciseOp.Remove(removed, 3), new CombinedUnits.ExerciseOp.AdoptInsert(added, 2, authorAdded));
        assertThat(x).isNotEqualTo(y);
    }

    @Test
    void aPlanThatTheManifestsDoNotDescribeIsRefused() {
        UUID key = UUID.randomUUID();
        World world = world(List.of(key), List.of(key), Set.of());
        // the plan names a replacement the diff does not show
        UUID deck = UUID.randomUUID();
        UpdatePlan plan = new UpdatePlan(UpdatePlan.State.READY, deck, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "sha256:" + "c".repeat(64), null,
                new UpdatePlan.Summary(0, 0, 0, 0, 0, 0),
                List.of(new PlanUnit(UnitKind.MATERIAL, key, UnitClass.SAFE, null, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of(), defaultFor(UnitClass.SAFE))));
        ApplySelection selection = ApplySelection.of(plan, new ApplyCommand(UUID.randomUUID(), plan.planVersion(), true, List.of()), Map.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> build(world, selection)).isInstanceOf(IllegalStateException.class);
    }
}
