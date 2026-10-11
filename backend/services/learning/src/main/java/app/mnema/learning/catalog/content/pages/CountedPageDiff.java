package app.mnema.learning.catalog.content.pages;

import app.mnema.learning.catalog.content.pages.CountedPageCodec.Link;
import app.mnema.learning.catalog.content.pages.CountedPageCodec.Node;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.BatchSource;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Change;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.DiffStats;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.OrderChange;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Profile;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Result;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.TreeRoot;
import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.ObjectRef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static app.mnema.learning.catalog.content.pages.CountedPageCodec.check;
import static app.mnema.learning.catalog.content.pages.CountedPageFailure.Code.INVALID_PAGE;

/**
 * Frontier diff of two counted trees of one reuse scope: which keys were added, removed or re-targeted, and whether the order of the keys
 * both sides keep changed. Pure and read-only; it never rebuilds or writes a page.
 *
 * <p><b>Why page ids are enough.</b> A page is immutable and its id is random and issued once, so an id that appears in both trees is the very
 * same page and therefore the very same subtree (every descendant id, key and target). Path-copy edits keep the id of every page they do not
 * touch, so two revisions of one lineage share every untouched subtree and differ only along the edited paths. Ordinals are never compared:
 * an insert shifts them past the edit, which is why the walk is over ids, with each page carrying only the ordinal of its first entry
 * (parent start plus the counts of the preceding siblings, known from the parent without reading the page).
 *
 * <p><b>Level-synchronous walk.</b> Each side keeps a frontier of unexpanded pages grouped by height. The loop always takes the highest non-empty
 * level of either side. A page of height {@code h} can only equal a page of height {@code h} (the height is part of the immutable page), and the
 * trees are height-balanced, so a page of height {@code h} is produced only by expanding a page of height {@code h + 1} or is a root. Every
 * higher page was expanded in an earlier round, therefore when level {@code h} is processed it holds <em>all</em> pages of that height reachable
 * through unshared ancestors on both sides. Ids present in both level maps are shared: they are dropped unread (an order token remembers their
 * two start ordinals) and nothing below them is visited. Each remaining page is read, validated by the same {@link CountedPageCodec} as
 * {@link CountedPages} (role, rank, encoding, size rules, its height and count against what the parent or the {@link TreeRoot} recorded) and
 * expanded; all pages of a level are fetched with one {@link BatchSource#readAll} call. Pages read are exactly the pages of either tree not
 * contained in the other (an ancestor of an unshared page is unshared), which is O(changes * height); identical roots read nothing.
 *
 * <p><b>Entries.</b> Leaf entries of expanded leaves are collected per side as {@code key -> (target, ordinal)}. A key only on the before side is
 * REMOVED, only on the after side ADDED, on both with different targets CHANGED, on both with the same target unchanged (it only takes part in
 * the order). A key inside a shared page is in that same page on the other side, so it is never reported. Keys are unique per tree: a duplicate
 * on one side, an id reachable twice, a missing object, a wrong role, height or count, a scope mismatch and a count identity that does not
 * balance ({@code before - removed + added == after}) all fail with {@link CountedPageFailure}. Shared pages are trusted (their parent recorded
 * their height and count and they were validated when the revision was published); each tree must be a valid tree of this profile.
 *
 * <p><b>Order.</b> Per side the sequence of tokens is: the expanded keys present on both sides plus one token per shared page, sorted by
 * ordinal (a shared page is one token at its first ordinal; its entries are contiguous and identical on both sides, so expanding the tokens
 * back gives exactly the sequence of all common keys). The relative order changed iff the two token sequences differ. Shared pages are the
 * stable frame: path-copy edits never reorder untouched pages, so their relative order is the same in both trees (a violation fails as
 * INVALID_PAGE), and the moved keys are the complement of a <em>weighted</em> longest increasing subsequence of after-positions in
 * before-order, in which a shared token weighs more than all keys together so it always stays in the chain and only keys are reported as
 * moved. This is what catches a key that left the end of one leaf and re-appeared at the start of a non-adjacent leaf, across a shared page:
 * the shared page is a token the key crossed. Applying the moved keys at their after-positions to the before order of the common keys gives
 * the after order of the common keys.
 */
public final class CountedPageDiff {
    private final Profile profile;
    private final BatchSource source;

    public CountedPageDiff(Profile profile, BatchSource source) {
        this.profile = Objects.requireNonNull(profile);
        this.source = Objects.requireNonNull(source);
        if (!profile.keyed()) throw new IllegalArgumentException("A diff needs keyed entries");
    }

    /** Both roots must be trees of the same reuse scope; the caller authorized both revisions. */
    public Result diff(TreeRoot before, TreeRoot after) {
        UUID scope = before.ref().reuseScopeId();
        check(scope.equals(after.ref().reuseScopeId()), INVALID_PAGE);
        check(Math.max(before.height(), after.height()) <= 32 - profile.leafRank(), INVALID_PAGE);
        if (before.ref().equals(after.ref())) {
            // One page cannot have two heights or counts.
            check(before.equals(after), INVALID_PAGE);
            return Result.unchanged();
        }
        return new Run(scope).run(before, after);
    }

    private record Slot(int height, int count, int start, boolean root) { }
    private record Leaf(ObjectRef target, int ordinal) { }
    private record Shared(UUID page, int startBefore, int startAfter) { }

    /** One side's frontier by height and the leaf entries met so far. */
    private static final class Side {
        private final List<Map<UUID, Slot>> levels = new ArrayList<>();
        final Map<UUID, Leaf> entries = new HashMap<>();

        Side() { for (int i = 0; i <= 32; i++) levels.add(new LinkedHashMap<>()); }
        Map<UUID, Slot> level(int height) { return levels.get(height); }
        int top() {
            for (int i = 32; i >= 0; i--) if (!levels.get(i).isEmpty()) return i;
            return -1;
        }
    }

    private static final class Token {
        final UUID key;
        final int positionBefore;
        final int positionAfter;
        int rank;

        Token(UUID key, int positionBefore, int positionAfter) {
            this.key = key;
            this.positionBefore = positionBefore;
            this.positionAfter = positionAfter;
        }
        boolean shared() { return key == null; }
    }

    private final class Run {
        private final UUID scope;
        private final Side before = new Side();
        private final Side after = new Side();
        private final List<Shared> shared = new ArrayList<>();
        private int pagesRead;
        private int levels;

        Run(UUID scope) { this.scope = scope; }

        Result run(TreeRoot beforeRoot, TreeRoot afterRoot) {
            before.level(beforeRoot.height()).put(beforeRoot.ref().objectId(), new Slot(beforeRoot.height(), beforeRoot.count(), 0, true));
            after.level(afterRoot.height()).put(afterRoot.ref().objectId(), new Slot(afterRoot.height(), afterRoot.count(), 0, true));
            for (int height = Math.max(before.top(), after.top()); height >= 0; height = Math.max(before.top(), after.top())) {
                Map<UUID, Slot> left = before.level(height);
                Map<UUID, Slot> right = after.level(height);
                for (var iterator = left.entrySet().iterator(); iterator.hasNext(); ) {
                    var entry = iterator.next();
                    Slot twin = right.get(entry.getKey());
                    if (twin == null) continue;
                    check(twin.count() == entry.getValue().count(), INVALID_PAGE);
                    shared.add(new Shared(entry.getKey(), entry.getValue().start(), twin.start()));
                    iterator.remove();
                    right.remove(entry.getKey());
                }
                Set<UUID> ids = new LinkedHashSet<>(left.keySet());
                ids.addAll(right.keySet());
                if (ids.isEmpty()) continue;
                Map<UUID, NewObject> objects = source.readAll(scope, List.copyOf(ids));
                check(objects != null, INVALID_PAGE);
                levels++;
                pagesRead += ids.size();
                expand(before, left, height, objects);
                expand(after, right, height, objects);
                left.clear();
                right.clear();
            }
            return result(beforeRoot.count(), afterRoot.count());
        }

        private void expand(Side side, Map<UUID, Slot> level, int height, Map<UUID, NewObject> objects) {
            for (var entry : level.entrySet()) {
                UUID id = entry.getKey();
                Slot slot = entry.getValue();
                NewObject object = objects.get(id);
                check(object != null && object.objectId().equals(id), INVALID_PAGE);
                Node node = CountedPageCodec.parse(profile, scope, new ObjectRef(scope, id), object, slot.root());
                check(node.height() == slot.height() && node.count() == slot.count(), INVALID_PAGE);
                int start = slot.start();
                for (Link link : node.links()) {
                    if (height == 0) {
                        check(side.entries.put(link.key(), new Leaf(link.target(), start)) == null, INVALID_PAGE);
                    } else {
                        // The same page twice in one tree would mean duplicate keys.
                        check(side.level(height - 1).put(link.target().objectId(), new Slot(height - 1, link.count(), start, false)) == null, INVALID_PAGE);
                    }
                    start += link.count();
                }
            }
        }

        private Result result(int countBefore, int countAfter) {
            List<Change> changed = new ArrayList<>();
            List<Change> removed = new ArrayList<>();
            for (var entry : after.entries.entrySet()) {
                Leaf now = entry.getValue();
                Leaf then = before.entries.get(entry.getKey());
                if (then == null) changed.add(new Change(entry.getKey(), null, now.target(), -1, now.ordinal()));
                else if (!then.target().equals(now.target())) changed.add(new Change(entry.getKey(), then.target(), now.target(), then.ordinal(), now.ordinal()));
            }
            int added = (int) changed.stream().filter(change -> change.before() == null).count();
            for (var entry : before.entries.entrySet()) {
                if (!after.entries.containsKey(entry.getKey())) {
                    removed.add(new Change(entry.getKey(), entry.getValue().target(), null, entry.getValue().ordinal(), -1));
                }
            }
            check((long) countBefore - removed.size() + added == countAfter, INVALID_PAGE);
            changed.sort(Comparator.comparingInt(Change::afterOrdinal));
            removed.sort(Comparator.comparingInt(Change::beforeOrdinal));
            List<Change> all = new ArrayList<>(changed.size() + removed.size());
            all.addAll(changed);
            all.addAll(removed);
            return new Result(all, order(), new DiffStats(pagesRead, levels));
        }

        private OrderChange order() {
            List<Token> tokens = new ArrayList<>();
            for (var entry : before.entries.entrySet()) {
                Leaf now = after.entries.get(entry.getKey());
                if (now != null) tokens.add(new Token(entry.getKey(), entry.getValue().ordinal(), now.ordinal()));
            }
            for (Shared page : shared) tokens.add(new Token(null, page.startBefore(), page.startAfter()));
            int size = tokens.size();
            Token[] byBefore = tokens.toArray(new Token[0]);
            Arrays.sort(byBefore, Comparator.comparingInt(token -> token.positionBefore));
            Token[] byAfter = byBefore.clone();
            Arrays.sort(byAfter, Comparator.comparingInt(token -> token.positionAfter));
            for (int i = 0; i < size; i++) byAfter[i].rank = i;
            boolean changed = false;
            int lastShared = -1;
            for (int i = 0; i < size; i++) {
                changed |= byBefore[i].rank != i;
                if (byBefore[i].shared()) {
                    // Untouched pages never trade places in a path-copied tree.
                    check(byBefore[i].rank > lastShared, INVALID_PAGE);
                    lastShared = byBefore[i].rank;
                }
            }
            if (!changed) return new OrderChange(false, List.of());
            boolean[] kept = longestChain(byBefore);
            List<Token> moved = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                if (kept[i]) continue;
                check(!byBefore[i].shared(), INVALID_PAGE);
                moved.add(byBefore[i]);
            }
            moved.sort(Comparator.comparingInt(token -> token.positionAfter));
            return new OrderChange(true, moved.stream().map(token -> token.key).toList());
        }

        /** Maximum-weight increasing subsequence of the ranks (Fenwick tree of prefix maxima); a shared token outweighs all keys together. */
        private boolean[] longestChain(Token[] byBefore) {
            int size = byBefore.length;
            long heavy = size + 1L;
            long[] best = new long[size];
            int[] previous = new int[size];
            long[] treeValue = new long[size + 1];
            int[] treeIndex = new int[size + 1];
            Arrays.fill(treeIndex, -1);
            int last = -1;
            for (int i = 0; i < size; i++) {
                long prefix = 0;
                int from = -1;
                for (int at = byBefore[i].rank; at > 0; at -= at & -at) {
                    if (treeValue[at] > prefix) { prefix = treeValue[at]; from = treeIndex[at]; }
                }
                best[i] = prefix + (byBefore[i].shared() ? heavy : 1);
                previous[i] = from;
                for (int at = byBefore[i].rank + 1; at <= size; at += at & -at) {
                    if (best[i] > treeValue[at]) { treeValue[at] = best[i]; treeIndex[at] = i; }
                }
                if (last < 0 || best[i] > best[last]) last = i;
            }
            boolean[] kept = new boolean[size];
            for (int at = last; at >= 0; at = previous[at]) kept[at] = true;
            return kept;
        }
    }
}
