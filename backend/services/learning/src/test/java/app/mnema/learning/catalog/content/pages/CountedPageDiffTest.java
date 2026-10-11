package app.mnema.learning.catalog.content.pages;

import app.mnema.learning.catalog.content.pages.CountedPageFixture.Tree;
import app.mnema.learning.storage.StorageTypes.NewEdge;
import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static app.mnema.learning.catalog.content.pages.CountedPageFixture.assertMatches;
import static app.mnema.learning.catalog.content.pages.CountedPageTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CountedPageDiffTest {
    private final CountedPageFixture fx = new CountedPageFixture();

    /** Every pair is checked against a full comparison of the entry lists, in both directions. */
    private Result check(Tree before, Tree after) {
        Result forward = fx.diff(before.root, after.root);
        assertMatches(forward, before.model, after.model);
        assertMatches(fx.diff(after.root, before.root), after.model, before.model);
        assertThat(forward.stats().levels()).isLessThanOrEqualTo(Math.max(before.root.height(), after.root.height()) + 1);
        return forward;
    }

    private static int bound(Tree... trees) {
        int height = 0;
        for (Tree tree : trees) height = Math.max(height, tree.maxHeight);
        return 6 * (height + 2);
    }

    @Test
    void identicalRootsReadNothing() {
        Tree tree = fx.new Tree(fx.sharedEntries(3000));
        fx.calls = 0;
        Result result = fx.diff(tree.root, tree.root);
        assertThat(result.changes()).isEmpty();
        assertThat(result.order().changed()).isFalse();
        assertThat(result.stats()).isEqualTo(new DiffStats(0, 0));
        assertThat(fx.calls).isZero();
        assertThatThrownBy(() -> fx.diff(tree.root, new TreeRoot(tree.root.ref(), tree.root.height(), tree.root.count() - 1)))
                .isInstanceOfSatisfying(CountedPageFailure.class, failure -> assertThat(failure.code()).isEqualTo(CountedPageFailure.Code.INVALID_PAGE));
    }

    @Test
    void sameContentUnderNewPageIdsHasNoChangesButCostsTheWholeTree() {
        List<Entry> entries = fx.distinctEntries(2000);
        Tree one = fx.new Tree(entries);
        Tree rebuilt = fx.new Tree(entries);
        Result result = check(one, rebuilt);
        assertThat(result.changes()).isEmpty();
        assertThat(result.order().changed()).isFalse();
        // Why a published manifest is never rebuilt: nothing is shared, so the diff reads every page of both trees.
        assertThat(result.stats().pagesRead()).isGreaterThan(2 * 2000 / 32);
    }

    @Test
    void emptyToManyManyToEmptyAndEmptyToEmpty() {
        Tree empty = fx.new Tree(List.of());
        Tree otherEmpty = fx.new Tree(List.of());
        Tree some = fx.new Tree(fx.distinctEntries(1500));
        Tree small = fx.new Tree(fx.distinctEntries(3));
        assertThat(check(empty, otherEmpty).changes()).isEmpty();
        assertThat(check(empty, some).changes()).hasSize(1500).allSatisfy(change -> assertThat(change.before()).isNull());
        assertThat(check(small, empty).changes()).hasSize(3).allSatisfy(change -> assertThat(change.after()).isNull());
        assertThat(fx.diff(some.root, empty.root).changes()).hasSize(1500).allSatisfy(change -> assertThat(change.beforeOrdinal()).isNotNegative());
    }

    @Test
    void singleInsertAtStartMiddleAndEndWithAndWithoutSplit() {
        // 1024 entries are 32 full leaves: any insert splits a leaf (and the next one, the root).
        for (int size : List.of(1024, 1000, 5000)) {
            Tree base = fx.new Tree(fx.sharedEntries(size));
            for (int at : List.of(0, size / 2, size)) {
                Tree edited = base.copy();
                Entry added = fx.entry();
                edited.insert(at, added);
                Result result = check(base, edited);
                assertThat(result.changes()).containsExactly(new Change(added.key(), null, added.target(), -1, at));
                assertThat(result.order().changed()).isFalse();
                assertThat(result.stats().pagesRead()).isLessThanOrEqualTo(bound(base, edited));
            }
        }
    }

    @Test
    void deletesThatBorrowMergeAndCollapseTheRoot() {
        Tree base = fx.new Tree(fx.sharedEntries(1024));
        Tree edited = base.copy();
        // Drain one leaf: borrow from a neighbour first, then merges.
        for (int i = 0; i < 40; i++) edited.delete(160);
        Result result = check(base, edited);
        assertThat(result.changes()).hasSize(40).allSatisfy(change -> assertThat(change.after()).isNull());
        assertThat(result.order().changed()).isFalse();
        Tree last = base.copy();
        for (int i = 0; i < 5; i++) last.delete(last.model.size() - 1);
        assertThat(check(base, last).changes()).hasSize(5);

        Tree small = fx.new Tree(fx.sharedEntries(40));
        assertThat(small.root.height()).isOne();
        Tree collapsed = small.copy();
        for (int i = 0; i < 28; i++) collapsed.delete(0);
        assertThat(collapsed.root.height()).isZero();
        assertThat(check(small, collapsed).changes()).hasSize(28);
        Tree emptied = small.copy();
        while (!emptied.model.isEmpty()) emptied.delete(0);
        assertThat(check(small, emptied).changes()).hasSize(40);
    }

    @Test
    void moveWithinOneLeaf() {
        Tree base = fx.new Tree(fx.sharedEntries(1024));
        Tree edited = base.copy();
        UUID moved = base.model.get(70).key();
        edited.move(70, 75);
        Result result = check(base, edited);
        assertThat(result.changes()).isEmpty();
        assertThat(result.order().changed()).isTrue();
        // A short step: either the key itself or the five keys it passed; the chain keeps the larger frame.
        assertThat(result.order().movedKeys()).containsExactly(moved);
        assertThat(result.stats().pagesRead()).isLessThanOrEqualTo(bound(base, edited));
    }

    @Test
    void keyThatLeavesTheEndOfOneLeafAndLandsInANonAdjacentLeafCrossedSharedPages() {
        Tree base = fx.new Tree(fx.sharedEntries(1024));
        assertThat(base.root.height()).isOne();
        // Entry 31 is the last of leaf 0; after its removal leaf 3 starts at ordinal 95, so 95 is the first slot of leaf 3.
        // Leaves 1 and 2 stay untouched between the two edited leaves: the only evidence of the move is the shared pages the key crossed.
        Tree forward = base.copy();
        UUID key = base.model.get(31).key();
        forward.move(31, 95);
        Result result = check(base, forward);
        assertThat(result.changes()).isEmpty();
        assertThat(result.order().changed()).isTrue();
        assertThat(result.order().movedKeys()).containsExactly(key);
        assertThat(result.stats().pagesRead()).isLessThanOrEqualTo(bound(base, forward));
        // The way back and a long jump across many untouched leaves.
        Tree back = forward.copy();
        back.move(95, 31);
        assertThat(check(forward, back).order().movedKeys()).containsExactly(key);
        Tree far = base.copy();
        far.move(31, 1000);
        assertThat(check(base, far).order().movedKeys()).containsExactly(key);
        Tree toFront = base.copy();
        toFront.move(990, 0);
        assertThat(check(base, toFront).order().movedKeys()).containsExactly(base.model.get(990).key());
    }

    @Test
    void replaceReportsOneChangedTargetAndKeepsTheOrder() {
        Tree base = fx.new Tree(fx.distinctEntries(2500));
        Tree edited = base.copy();
        edited.replace(1234);
        Result result = check(base, edited);
        Entry was = base.model.get(1234);
        Entry now = edited.model.get(1234);
        assertThat(result.changes()).containsExactly(new Change(was.key(), was.target(), now.target(), 1234, 1234));
        assertThat(result.order()).isEqualTo(new OrderChange(false, List.of()));
        assertThat(result.stats().pagesRead()).isLessThanOrEqualTo(bound(base, edited));
    }

    @Test
    void heightGrowsAndShrinks() {
        Tree base = fx.new Tree(fx.sharedEntries(1024));
        Tree grown = base.copy();
        grown.insert(500, fx.entry());
        assertThat(base.root.height()).isOne();
        assertThat(grown.root.height()).isEqualTo(2);
        assertThat(check(base, grown).changes()).hasSize(1);
        Tree leaf = fx.new Tree(fx.sharedEntries(32));
        Tree split = leaf.copy();
        split.insert(5, fx.entry());
        assertThat(leaf.root.height()).isZero();
        assertThat(split.root.height()).isOne();
        assertThat(check(leaf, split).changes()).hasSize(1);
        // A subtree becomes the root of the other side: its whole subtree is shared and read once, as the root.
        Tree trimmed = grown.copy();
        trimmed.delete(500);
        assertMatches(fx.diff(grown.root, trimmed.root), grown.model, trimmed.model);
    }

    @Test
    void differentScopesAndTamperedRootsAreRejected() {
        Tree tree = fx.new Tree(fx.sharedEntries(100));
        Tree other = fx.new Tree(fx.sharedEntries(100));
        ObjectRef foreign = new ObjectRef(UUID.randomUUID(), other.root.ref().objectId());
        assertRejected(() -> fx.diff(tree.root, new TreeRoot(foreign, other.root.height(), other.root.count())));
        assertRejected(() -> fx.diff(tree.root, new TreeRoot(other.root.ref(), other.root.height(), other.root.count() + 1)));
        assertRejected(() -> fx.diff(tree.root, new TreeRoot(other.root.ref(), other.root.height() + 1, other.root.count())));
        assertRejected(() -> fx.diff(tree.root, new TreeRoot(other.root.ref(), 31, other.root.count())));
        assertRejected(() -> new CountedPageDiff(Profile.exercises(100_000), fx.source()).diff(tree.root, other.root));
        assertThatThrownBy(() -> new CountedPageDiff(new Profile("fragments", 1, 100, false, false, true), fx.source()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingWrongAndMalformedObjectsFail() {
        Tree tree = fx.new Tree(fx.sharedEntries(500));
        Tree other = fx.new Tree(fx.sharedEntries(500));
        assertRejected(() -> new CountedPageDiff(CountedPageFixture.PRODUCTION, (scope, ids) -> Map.of()).diff(tree.root, other.root));
        assertRejected(() -> new CountedPageDiff(CountedPageFixture.PRODUCTION, (scope, ids) -> Map.of(tree.root.ref().objectId(),
                fx.objects.get(other.root.ref().objectId()), other.root.ref().objectId(), fx.objects.get(other.root.ref().objectId())))
                .diff(tree.root, other.root));
        assertRejected(() -> new CountedPageDiff(CountedPageFixture.PRODUCTION, (scope, ids) -> null).diff(tree.root, other.root));
        // Dropping any page that is really needed fails; a source that omits one of the two leaf pages of the second round.
        int[] round = {0};
        assertRejected(() -> new CountedPageDiff(CountedPageFixture.PRODUCTION, (scope, ids) -> {
            Map<UUID, NewObject> found = new java.util.HashMap<>();
            ids.forEach(id -> found.put(id, fx.objects.get(id)));
            if (++round[0] == 2) found.remove(ids.iterator().next());
            return found;
        }).diff(tree.root, other.root));
        // A key twice in one tree.
        Entry twice = fx.entry();
        List<Entry> doubled = new java.util.ArrayList<>(fx.distinctEntries(20));
        doubled.add(twice);
        doubled.add(twice);
        Tree duplicated = fx.new Tree(doubled);
        assertRejected(() -> fx.diff(fx.new Tree(fx.distinctEntries(20)).root, duplicated.root));
    }

    @Test
    void sharedPagesThatChangedPlacesCannotBeFromOneLineage() {
        Tree tree = fx.new Tree(fx.sharedEntries(64));
        NewObject root = fx.objects.get(tree.root.ref().objectId());
        assertThat(root.edges()).hasSize(2);
        NewObject swapped = new NewObject(UUID.randomUUID(), root.kind(), root.encodingVersion(), root.dagRank(), root.payload(),
                List.of(new NewEdge(0, null, root.edges().get(1).child()), new NewEdge(1, null, root.edges().get(0).child())));
        fx.objects.put(swapped.objectId(), swapped);
        assertRejected(() -> fx.diff(tree.root, new TreeRoot(new ObjectRef(fx.scope, swapped.objectId()), 1, 64)));
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CountedPageFailure.class,
                failure -> assertThat(failure.code()).isEqualTo(CountedPageFailure.Code.INVALID_PAGE));
    }
}
