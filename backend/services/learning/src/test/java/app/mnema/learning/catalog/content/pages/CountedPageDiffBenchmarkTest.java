package app.mnema.learning.catalog.content.pages;

import app.mnema.learning.catalog.content.pages.CountedPageFixture.Tree;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static app.mnema.learning.catalog.content.pages.CountedPageFixture.assertMatches;
import static app.mnema.learning.catalog.content.pages.CountedPageTypes.Result;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cost claim of the frontier diff, measured: pages read for N entries and k random edits. One nested sequence of 1 000 edits per N, so the
 * trees for k = 1, 10, 100, 1 000 are prefixes of one history. Every result is also checked against a full comparison.
 *
 * <p>Bounds (all deterministic, seeded). Pages read are exactly the pages of either tree absent from the other. One edit creates at most
 * {@code 6 (H + 2)} pages (asserted by {@code CountedPagesTest}) and destroys at most as many, so k edits cost at most {@code 12 k (H + 2)}
 * where H is the tallest tree seen: the bound has no N in it, only the logarithmic height. The measured values are far below it because the
 * upper levels of the k paths coincide. Across N the pages for fixed small k change by about the height ratio, not by the size ratio.
 */
class CountedPageDiffBenchmarkTest {
    private static final int[] SIZES = {1_000, 10_000, 100_000};
    private static final int[] EDITS = {1, 10, 100, 1_000};
    private static final long SEED = 440_440L;

    private record Cell(int size, int edits, int height, int pages, int levels, double millis, int totalPages) { }

    @Test
    void pagesReadDependOnTheChangesAndTheHeightNotOnTheSize() {
        Map<String, Cell> cells = new HashMap<>();
        List<String> table = new ArrayList<>();
        for (int size : SIZES) {
            CountedPageFixture fx = new CountedPageFixture();
            Random random = new Random(SEED + size);
            Tree base = fx.new Tree(fx.sharedEntries(size));
            int totalPages = fx.reachablePages(base.root).size();
            Tree edited = base.copy();
            int done = 0;
            for (int k : EDITS) {
                while (done < k) { edited.randomEdit(random); done++; }
                CountedPageDiff differ = fx.differ();
                Result result = differ.diff(base.root, edited.root);
                assertMatches(result, base.model, edited.model);
                assertThat(result.stats().pagesRead()).isEqualTo(fx.unsharedPages(base.root, edited.root));
                double best = Double.MAX_VALUE;
                for (int run = 0; run < 5; run++) {
                    long started = System.nanoTime();
                    differ.diff(base.root, edited.root);
                    best = Math.min(best, (System.nanoTime() - started) / 1e6);
                }
                int height = edited.maxHeight;
                Cell cell = new Cell(size, k, height, result.stats().pagesRead(), result.stats().levels(), best, totalPages);
                cells.put(size + "/" + k, cell);
                table.add(String.format("| %,d | %,d | %d | %,d | %,d | %.2f |", size, k, height, cell.pages(), totalPages, cell.millis()));
                // The bound has no N in it.
                assertThat(cell.pages()).as("N=%d k=%d", size, k).isLessThanOrEqualTo(12 * k * (height + 2));
                assertThat(cell.levels()).isLessThanOrEqualTo(height + 1);
                // Far below a walk of the tree for small k.
                if (k <= 10 && size >= 100_000) assertThat(cell.pages()).isLessThan(totalPages / 20);
            }
        }
        System.out.println("CountedPageDiff benchmark (pages read; best of 5 wall ms, in-memory source)");
        System.out.println("| N | edits k | tallest H | pages read | pages in tree | ms |");
        System.out.println("|---|---|---|---|---|---|");
        table.forEach(System.out::println);
        for (int k : EDITS) {
            for (int size : SIZES) {
                Cell cell = cells.get(size + "/" + k);
                // Each edit rewrites a leaf nobody else shares: the cost grows with k, about linearly while the paths stay disjoint.
                if (size >= 100_000 && k <= 100) assertThat(cell.pages()).isGreaterThanOrEqualTo(k);
            }
        }
        for (int size : SIZES) {
            for (int k : new int[] {1, 10}) {
                // Ten times the edits cost at most ten times the pages (plus one path), and never fewer.
                Cell few = cells.get(size + "/" + k);
                Cell many = cells.get(size + "/" + (k * 10));
                assertThat(many.pages()).isLessThanOrEqualTo(10 * few.pages() + 12 * (many.height() + 2));
                assertThat(many.pages()).isGreaterThanOrEqualTo(few.pages());
            }
        }
        // 100 times the entries, the same edits: the pages differ by the height, not by 100.
        for (int k : new int[] {1, 10}) {
            Cell small = cells.get("1000/" + k);
            Cell large = cells.get("100000/" + k);
            assertThat(large.pages()).isLessThanOrEqualTo(3 * small.pages() * (large.height() + 2) / (small.height() + 2));
        }
    }
}
