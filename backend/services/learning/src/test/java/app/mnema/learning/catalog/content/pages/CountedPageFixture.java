package app.mnema.learning.catalog.content.pages;

import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.ObjectKind;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static app.mnema.learning.catalog.content.pages.CountedPageTypes.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * In-memory world for the diff tests: real {@link CountedPages} edits over a map of objects, a counting {@link BatchSource} and an oracle
 * that compares a diff with a full comparison of the two trees' entry lists. The bulk profile (same role and rank, so the stored pages are
 * identical to production ones) exists only here: production manifests refuse {@code build}.
 */
final class CountedPageFixture {
    /** Headroom above the production limit of 100 000 so a fixture of exactly that size can still grow by edits. */
    static final int MAX_ENTRIES = 200_000;
    static final Profile BULK = new Profile("members", 10, MAX_ENTRIES, true, true, true);
    static final Profile PRODUCTION = Profile.members(MAX_ENTRIES);

    final UUID scope = UUID.randomUUID();
    final Map<UUID, NewObject> objects = new HashMap<>();
    final CountedPages pages = new CountedPages(BULK, ref -> objects.get(ref.objectId()));
    int calls;
    int pagesServed;
    private final ObjectRef descriptor;

    CountedPageFixture() { descriptor = descriptorRef(); }

    BatchSource source() {
        return (reuse, ids) -> {
            calls++;
            Map<UUID, NewObject> found = new HashMap<>();
            for (UUID id : ids) {
                NewObject object = objects.get(id);
                if (object != null) found.put(id, object);
                pagesServed++;
            }
            return found;
        };
    }

    CountedPageDiff differ() { return new CountedPageDiff(PRODUCTION, source()); }

    Result diff(TreeRoot before, TreeRoot after) { return differ().diff(before, after); }

    /** A new descriptor object (rank 9) behind a new random key. */
    Entry entry() { return new Entry(UUID.randomUUID(), descriptorRef()); }

    /** A new descriptor behind the given key (a re-targeted member). */
    Entry entry(UUID key) { return new Entry(key, descriptorRef()); }

    /** A key whose descriptor is the one shared by all {@link #sharedEntries} (cheap big trees). */
    List<Entry> sharedEntries(int count) {
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) entries.add(new Entry(UUID.randomUUID(), descriptor));
        return entries;
    }

    List<Entry> distinctEntries(int count) {
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) entries.add(entry());
        return entries;
    }

    private ObjectRef descriptorRef() {
        NewObject value = new NewObject(UUID.randomUUID(), ObjectKind.PAGE, (short) 1, (short) 9,
                JsonNodeFactory.instance.objectNode().put("descriptor", true).put("n", objects.size()), List.of());
        objects.put(value.objectId(), value);
        return new ObjectRef(scope, value.objectId());
    }

    /** Ids of the pages (not the descriptors) reachable from a root. */
    Set<UUID> reachablePages(TreeRoot root) {
        Set<UUID> seen = new HashSet<>();
        java.util.ArrayDeque<UUID> pending = new java.util.ArrayDeque<>();
        pending.add(root.ref().objectId());
        while (!pending.isEmpty()) {
            UUID id = pending.poll();
            NewObject page = objects.get(id);
            if (page.dagRank() < 10 || !seen.add(id)) continue;
            page.edges().forEach(edge -> pending.add(edge.child().objectId()));
        }
        return seen;
    }

    /** The pages of exactly one of the two trees: what a frontier diff must read, no more and no fewer. */
    int unsharedPages(TreeRoot before, TreeRoot after) {
        Set<UUID> left = reachablePages(before);
        Set<UUID> right = reachablePages(after);
        int count = 0;
        for (UUID id : left) if (!right.contains(id)) count++;
        for (UUID id : right) if (!left.contains(id)) count++;
        return count;
    }

    TreeRoot keep(PageEdit edit) {
        for (NewObject value : edit.additions()) objects.put(value.objectId(), value);
        return edit.root();
    }

    /** A tree and the entry list it must hold, edited in place by real {@link CountedPages} operations. */
    final class Tree {
        TreeRoot root;
        final List<Entry> model;
        int maxHeight;

        Tree(List<Entry> entries) {
            model = new ArrayList<>(entries);
            root = keep(pages.build(scope, entries));
            maxHeight = root.height();
        }

        Tree(TreeRoot root, List<Entry> model) { this.root = root; this.model = new ArrayList<>(model); maxHeight = root.height(); }

        Tree copy() { return new Tree(root, model); }

        Entry entry() { return CountedPageFixture.this.entry(); }

        void insert(int at, Entry entry) { root = update(pages.insert(root, at, entry)); model.add(at, entry); }

        void delete(int at) { root = update(pages.delete(root, at, model.get(at).key())); model.remove(at); }

        void move(int from, int to) {
            Entry value = model.remove(from);
            model.add(to, value);
            root = update(pages.move(root, from, to, value.key()));
        }

        void replace(int at) {
            Entry value = CountedPageFixture.this.entry(model.get(at).key());
            root = update(pages.replace(root, at, value.key(), value));
            model.set(at, value);
        }

        private TreeRoot update(PageEdit edit) {
            TreeRoot next = keep(edit);
            maxHeight = Math.max(maxHeight, next.height());
            return next;
        }

        /** One random edit. */
        void randomEdit(Random random) { randomEdit(random, Integer.MAX_VALUE); }

        /** One random edit that never grows the tree beyond {@code maxSize} entries. */
        void randomEdit(Random random, int maxSize) {
            int size = model.size();
            int op = size == 0 ? 0 : size >= maxSize ? 1 + random.nextInt(3) : random.nextInt(4);
            switch (op) {
                case 0 -> insert(random.nextInt(size + 1), entry());
                case 1 -> delete(random.nextInt(size));
                case 2 -> move(random.nextInt(size), random.nextInt(size));
                default -> replace(random.nextInt(size));
            }
        }

        /** The whole tree through the public reader, checked against the model. */
        void assertReadsAsModel() {
            List<Entry> read = new ArrayList<>();
            for (int start = 0; start < root.count(); start += 100) read.addAll(pages.read(root, start, 100));
            assertThat(read).containsExactlyElementsOf(model);
        }
    }

    // ---- the oracle: a full comparison of two entry lists ------------------------------------------------------------------

    static List<Change> expectedChanges(List<Entry> before, List<Entry> after) {
        Map<UUID, Integer> beforeOrdinal = new HashMap<>();
        for (int i = 0; i < before.size(); i++) assertThat(beforeOrdinal.put(before.get(i).key(), i)).as("unique keys").isNull();
        Set<UUID> afterKeys = new HashSet<>();
        List<Change> changes = new ArrayList<>();
        for (int i = 0; i < after.size(); i++) {
            Entry now = after.get(i);
            assertThat(afterKeys.add(now.key())).as("unique keys").isTrue();
            Integer was = beforeOrdinal.get(now.key());
            if (was == null) changes.add(new Change(now.key(), null, now.target(), -1, i));
            else if (!before.get(was).target().equals(now.target())) changes.add(new Change(now.key(), before.get(was).target(), now.target(), was, i));
        }
        for (int i = 0; i < before.size(); i++) {
            if (!afterKeys.contains(before.get(i).key())) changes.add(new Change(before.get(i).key(), before.get(i).target(), null, i, -1));
        }
        List<Change> sorted = new ArrayList<>(changes.stream().filter(change -> change.after() != null)
                .sorted(java.util.Comparator.comparingInt(Change::afterOrdinal)).toList());
        sorted.addAll(changes.stream().filter(change -> change.after() == null)
                .sorted(java.util.Comparator.comparingInt(Change::beforeOrdinal)).toList());
        return sorted;
    }

    static List<UUID> commonOrder(List<Entry> side, List<Entry> other) {
        Set<UUID> keys = new HashSet<>();
        other.forEach(entry -> keys.add(entry.key()));
        return side.stream().map(Entry::key).filter(keys::contains).toList();
    }

    /** The diff must equal the full comparison, and applying {@code movedKeys} must turn the before order into the after order. */
    static void assertMatches(Result result, List<Entry> before, List<Entry> after) {
        assertThat(result.changes()).containsExactlyElementsOf(expectedChanges(before, after));
        List<UUID> commonBefore = commonOrder(before, after);
        List<UUID> commonAfter = commonOrder(after, before);
        assertThat(result.order().changed()).isEqualTo(!commonBefore.equals(commonAfter));
        List<UUID> moved = result.order().movedKeys();
        assertThat(moved.isEmpty()).isEqualTo(!result.order().changed());
        Set<UUID> movedSet = new HashSet<>(moved);
        assertThat(movedSet).hasSameSizeAs(moved);
        Map<UUID, Integer> afterPosition = new HashMap<>();
        for (int i = 0; i < commonAfter.size(); i++) afterPosition.put(commonAfter.get(i), i);
        assertThat(moved).allSatisfy(key -> assertThat(afterPosition).containsKey(key));
        assertThat(moved).isSortedAccordingTo(java.util.Comparator.comparingInt(afterPosition::get));
        // Remove the moved keys from the before order and put them at their after positions: the after order must come out.
        List<UUID> kept = new ArrayList<>(commonBefore.stream().filter(key -> !movedSet.contains(key)).toList());
        UUID[] rebuilt = new UUID[commonAfter.size()];
        for (UUID key : moved) rebuilt[afterPosition.get(key)] = key;
        int next = 0;
        for (int i = 0; i < rebuilt.length; i++) if (rebuilt[i] == null) rebuilt[i] = kept.get(next++);
        assertThat(next).isEqualTo(kept.size());
        assertThat(List.of(rebuilt)).containsExactlyElementsOf(commonAfter);
    }
}
