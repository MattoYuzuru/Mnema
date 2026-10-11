package app.mnema.learning.catalog.content.pages;

import app.mnema.learning.catalog.content.pages.CountedPageFixture.Tree;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static app.mnema.learning.catalog.content.pages.CountedPageTypes.Result;

import static app.mnema.learning.catalog.content.pages.CountedPageFixture.assertMatches;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seeded random trees (0..5 000 entries) edited by 1..200 real insert/delete/move/replace operations, compared with a full comparison of
 * the two entry lists. Each case diffs a base against two siblings derived from it by independent edits, and the siblings against each
 * other: that last pair shares only the pages neither side touched. The failing seed is in the error. No jqwik: a plain {@link Random}.
 */
class CountedPageDiffPropertyTest {
    private static final int CASES = 320;
    private static final long BASE_SEED = 20_261_011L;

    @Test
    void diffEqualsAFullComparisonForRandomTreesAndEdits() {
        long total = 0;
        for (int i = 0; i < CASES; i++) {
            long seed = BASE_SEED + i;
            try {
                total += runCase(seed);
            } catch (Throwable failure) {
                System.err.println("CountedPageDiffPropertyTest failing seed=" + seed);
                throw new AssertionError("failing seed=" + seed + ": " + failure, failure);
            }
        }
        System.out.println("CountedPageDiff property: " + CASES + " cases, " + total + " compared pairs, order changed in " + orderChanged
                + ", added " + added + ", removed " + removed + ", changed " + retargeted + ", height differs in " + heightDiffers);
        assertThat(total).isEqualTo(CASES * 3L);
        // The generator must actually reach every kind of difference.
        assertThat(orderChanged).isGreaterThan(CASES / 4);
        assertThat(added).isGreaterThan(CASES / 4);
        assertThat(removed).isGreaterThan(CASES / 4);
        assertThat(retargeted).isGreaterThan(CASES / 4);
        assertThat(heightDiffers).isPositive();
    }

    private long orderChanged;
    private long added;
    private long removed;
    private long retargeted;
    private long heightDiffers;

    private void tally(Result result, Tree before, Tree after) {
        if (result.order().changed()) orderChanged++;
        if (result.changes().stream().anyMatch(change -> change.before() == null)) added++;
        if (result.changes().stream().anyMatch(change -> change.after() == null)) removed++;
        if (result.changes().stream().anyMatch(change -> change.before() != null && change.after() != null)) retargeted++;
        if (before.root.height() != after.root.height()) heightDiffers++;
    }

    private int runCase(long seed) {
        Random random = new Random(seed);
        CountedPageFixture fx = new CountedPageFixture();
        // Mostly small and mid sizes (height 0..2), some near the 5 000 limit, some empty.
        int size = switch (random.nextInt(6)) {
            case 0 -> random.nextInt(40);
            case 1 -> random.nextInt(300);
            case 2, 3 -> random.nextInt(2000);
            default -> random.nextInt(5001);
        };
        Tree base = fx.new Tree(random.nextBoolean() ? fx.distinctEntries(size) : fx.sharedEntries(size));
        Tree left = base.copy();
        Tree right = base.copy();
        edit(left, random);
        edit(right, random);
        for (Tree tree : new Tree[] {base, left, right}) tree.assertReadsAsModel();
        for (Tree[] pair : new Tree[][] {{base, left}, {base, right}, {left, right}}) {
            Result result = fx.diff(pair[0].root, pair[1].root);
            assertMatches(result, pair[0].model, pair[1].model);
            // Exactly the pages of one tree and not the other: shared subtrees are never read, changed paths always are.
            assertThat(result.stats().pagesRead()).isEqualTo(fx.unsharedPages(pair[0].root, pair[1].root));
            tally(result, pair[0], pair[1]);
        }
        return 3;
    }

    private static void edit(Tree tree, Random random) {
        int edits = 1 + random.nextInt(200);
        boolean clustered = random.nextInt(3) == 0;
        for (int i = 0; i < edits; i++) {
            if (clustered && !tree.model.isEmpty() && random.nextBoolean()) {
                // Edits near one spot keep their paths together, and moves between nearby leaves keep shared pages between them.
                int at = Math.min(tree.model.size() - 1, 40 + random.nextInt(40));
                if (random.nextBoolean()) tree.move(at, Math.min(tree.model.size() - 1, at + random.nextInt(100)));
                else tree.insert(at, tree.entry());
            } else {
                tree.randomEdit(random);
            }
        }
    }
}
