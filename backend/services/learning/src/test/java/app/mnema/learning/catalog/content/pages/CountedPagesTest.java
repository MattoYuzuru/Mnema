package app.mnema.learning.catalog.content.pages;

import app.mnema.learning.storage.StorageTypes.*;
import tools.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

import static app.mnema.learning.catalog.content.pages.CountedPageTypes.*;
import static org.assertj.core.api.Assertions.*;

class CountedPagesTest {
    private final UUID scope = UUID.randomUUID();
    private final Map<UUID, NewObject> objects = new HashMap<>();
    private int reads;
    /** The members role and rank, with the bulk flag only a test may set: production manifests refuse {@code build}. */
    private static final Profile BULK_MEMBERS = new Profile("members", 10, 100_001, true, true, true);
    private final CountedPages pages = new CountedPages(BULK_MEMBERS, ref -> { reads++; return objects.get(ref.objectId()); });

    @Test
    void exactEmptyProfilesAndRankContract() {
        for (String role : List.of("members", "exercises")) {
            var editor = new CountedPages(new Profile(role, 10, 100_000, true, true, true), ref -> null);
            var result = editor.build(scope, List.of());
            assertThat(result.root().height()).isZero();
            assertThat(result.root().count()).isZero();
            assertThat(result.additions()).hasSize(1);
            var page = result.additions().getFirst();
            assertThat(page.dagRank()).isEqualTo((short) 10);
            assertThat(page.edges()).isEmpty();
            assertThat(page.payload().toString()).isEqualTo("{\"codec\":1,\"role\":\"" + role + "\",\"treeHeight\":0,\"counts\":[]}");
        }
        assertThatThrownBy(() -> new Profile("bad role", 10, 1, true, true, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Profile("members", 32, 1000, true, true, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CountedPages(Profile.nativeNodes(), ref -> null).build(scope, List.of())).isInstanceOf(CountedPageFailure.class);
    }

    @Test
    void manifestProfilesCannotBeBulkBuiltAndOnlyDerivedStructuresMay() {
        assertThat(Profile.members(10).bulkBuild()).isFalse();
        assertThat(Profile.exercises(10).bulkBuild()).isFalse();
        assertThat(Profile.nativeNodes().bulkBuild()).isTrue();
        // Fail closed: the flag is part of the only constructor, so no profile can forget to state it.
        assertThat(Profile.class.getConstructors()).hasSize(1);
        assertThat(new Profile("fragments", 1, 10, false, false, true).bulkBuild()).isTrue();
        Entry value = entry();
        for (Profile profile : List.of(Profile.members(10), Profile.exercises(10))) {
            var editor = new CountedPages(profile, ref -> objects.get(ref.objectId()));
            assertThatThrownBy(() -> editor.build(scope, List.of())).isInstanceOfSatisfying(CountedPageFailure.class,
                    failure -> assertThat(failure.code()).isEqualTo(CountedPageFailure.Code.BULK_BUILD_FORBIDDEN));
            assertThatThrownBy(() -> editor.build(scope, List.of(value))).isInstanceOfSatisfying(CountedPageFailure.class,
                    failure -> assertThat(failure.code()).isEqualTo(CountedPageFailure.Code.BULK_BUILD_FORBIDDEN));
        }
        // Derived structures (native nodes: leaf rank 4) still bulk-build.
        NewObject node = new NewObject(UUID.randomUUID(), ObjectKind.PAGE, (short) 1, (short) 3,
                JsonNodeFactory.instance.objectNode().put("node", true), List.of());
        objects.put(node.objectId(), node);
        var natives = new CountedPages(Profile.nativeNodes(), ref -> objects.get(ref.objectId()));
        assertThat(natives.build(scope, List.of(new Entry(UUID.randomUUID(), new ObjectRef(scope, node.objectId())))).root().count()).isOne();
        // Local edits stay available for the production profile.
        var production = new CountedPages(Profile.members(10), ref -> objects.get(ref.objectId()));
        TreeRoot root = keep(pages.build(scope, List.of(value)));
        assertThat(production.insert(root, 1, entry()).root().count()).isEqualTo(2);
    }

    @Test
    void repeatedSplitsBorrowMergeAndRootCollapseRetainEveryHistoricalRoot() {
        var model = new ArrayList<Entry>();
        TreeRoot root = keep(pages.build(scope, model));
        for (int i = 0; i < 1100; i++) {
            Entry entry = entry();
            int at = i % 2 == 0 ? 0 : model.size();
            root = keep(pages.insert(root, at, entry)); model.add(at, entry);
            validate(root, model);
        }
        TreeRoot history = root;
        List<Entry> historical = List.copyOf(model);
        for (int i = 0; !model.isEmpty(); i++) {
            int at = i % 3 == 0 ? 0 : i % 3 == 1 ? model.size() - 1 : model.size() / 2;
            root = keep(pages.delete(root, at, model.remove(at).key()));
            validate(root, model);
        }
        assertThat(root.height()).isZero();
        validate(history, historical);
    }

    @Test
    void seededOperationsAgreeWithIndependentListAndOnlyReturnReachablePostorder() {
        Random random = new Random(740187);
        var model = new ArrayList<Entry>();
        for (int i = 0; i < 1200; i++) model.add(entry());
        TreeRoot root = keep(pages.build(scope, model));
        for (int i = 0; i < 1500; i++) {
            int at = random.nextInt(model.size());
            PageEdit change;
            switch (random.nextInt(4)) {
                case 0 -> { Entry value = entry(); model.add(at, value); change = pages.insert(root, at, value); }
                case 1 -> change = pages.delete(root, at, model.remove(at).key());
                case 2 -> { Entry value = model.remove(at); int to = random.nextInt(model.size() + 1); model.add(to, value); change = pages.move(root, at, to, value.key()); }
                default -> { Entry old = model.get(at); Entry value = new Entry(old.key(), entry().target()); model.set(at, value); change = pages.replace(root, at, old.key(), value); }
            }
            assertThat(change.additions().size()).isLessThanOrEqualTo(6 * (root.height() + 2));
            root = keep(change);
            validate(root, model);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {10_000, 50_000, 100_000})
    void acceptedSyntheticMembershipSizesHaveBoundedReadsAndLocalWrites(int size) {
        Entry descriptor = entry();
        var model = new ArrayList<Entry>();
        for (int i = 0; i < size; i++) model.add(new Entry(UUID.randomUUID(), descriptor.target()));
        TreeRoot old = keep(pages.build(scope, model));
        reads = 0;
        assertThat(pages.read(old, size - 100, 100)).containsExactlyElementsOf(model.subList(size - 100, size));
        int windowReads = reads;
        assertThat(windowReads).isLessThanOrEqualTo(12);
        reads = 0;
        var edit = pages.insert(old, size / 2, entry());
        int insertionReads = reads;
        assertThat(insertionReads).isLessThanOrEqualTo(12);
        assertThat(edit.additions().size()).isLessThanOrEqualTo(2 * (old.height() + 1) + 1);
        reads = 0;
        var moved = pages.move(old, 0, size - 1, model.getFirst().key());
        assertThat(reads).isLessThanOrEqualTo(12);
        assertThat(moved.additions().size()).isLessThanOrEqualTo(4 * (old.height() + 1) + 2);
        reads = 0;
        var deleted = pages.delete(old, 0, model.getFirst().key());
        assertThat(reads).isLessThanOrEqualTo(12);
        assertThat(deleted.additions().size()).isLessThanOrEqualTo(3 * (old.height() + 1) + 2);
        var next = keep(edit);
        assertThat(next.height()).isLessThanOrEqualTo(3);
        validate(old, model);
        System.out.println("K3 synthetic entries=" + size + " height=" + old.height() + " first100Reads=" + windowReads
                + " insertReads=" + insertionReads + " newPages=" + edit.additions().size());
    }

    @Test
    void expectedKeysRangesBudgetsTargetsAndNoopsFailClosed() {
        Entry value = entry();
        TreeRoot root = keep(pages.build(scope, List.of(value)));
        assertThat(pages.move(root, 0, 0, value.key()).additions()).isEmpty();
        assertThat(pages.replace(root, 0, value.key(), value).additions()).isEmpty();
        assertThat(pages.read(root, 1, 100)).isEmpty();
        assertThatThrownBy(() -> pages.delete(root, 0, UUID.randomUUID())).isInstanceOf(CountedPageFailure.class);
        assertThatThrownBy(() -> pages.move(root, 0, 1, value.key())).isInstanceOf(CountedPageFailure.class);
        assertThatThrownBy(() -> pages.insert(root, -1, value)).isInstanceOf(CountedPageFailure.class);
        assertThatThrownBy(() -> pages.read(root, 0, 101)).isInstanceOf(CountedPageFailure.class);
        assertThatThrownBy(() -> pages.replace(root, 0, value.key(), entry())).isInstanceOf(CountedPageFailure.class);
        assertThatThrownBy(() -> pages.insert(root, 0, new Entry(value.key(), new ObjectRef(UUID.randomUUID(), value.target().objectId())))).isInstanceOf(CountedPageFailure.class);
        assertThatThrownBy(() -> pages.insert(root, 0, new Entry(value.key(), root.ref()))).isInstanceOf(CountedPageFailure.class);
        var limited = new CountedPages(Profile.members(1), ref -> objects.get(ref.objectId()));
        assertThatThrownBy(() -> limited.insert(root, 0, value)).isInstanceOf(CountedPageFailure.class);
        assertThatThrownBy(() -> pages.read(new TreeRoot(root.ref(), 1, 1), 0, 1)).isInstanceOf(CountedPageFailure.class);
    }

    @Test
    void malformedVisitedPagesAndCountsAreRejectedWithoutWholeTreeScan() {
        var entries = new ArrayList<Entry>();
        for (int i = 0; i < 40; i++) entries.add(entry());
        TreeRoot root = keep(pages.build(scope, entries));
        NewObject original = objects.get(root.ref().objectId());
        var payload = original.payload().deepCopy();
        ((tools.jackson.databind.node.ArrayNode) payload.path("counts")).set(0, JsonNodeFactory.instance.numberNode(19));
        ((tools.jackson.databind.node.ArrayNode) payload.path("counts")).set(1, JsonNodeFactory.instance.numberNode(21));
        objects.put(original.objectId(), new NewObject(original.objectId(), original.kind(), original.encodingVersion(), original.dagRank(), payload, original.edges()));
        assertThatThrownBy(() -> pages.read(root, 0, 1)).isInstanceOf(CountedPageFailure.class);
        objects.put(original.objectId(), new NewObject(original.objectId(), original.kind(), original.encodingVersion(), (short) 12, original.payload(), original.edges()));
        assertThatThrownBy(() -> pages.read(root, 0, 1)).isInstanceOf(CountedPageFailure.class);
        objects.remove(original.objectId());
        assertThatThrownBy(() -> pages.read(root, 0, 1)).isInstanceOf(CountedPageFailure.class);
    }

    private Entry entry() {
        NewObject value = new NewObject(UUID.randomUUID(), ObjectKind.PAGE, (short) 1, (short) 9,
                JsonNodeFactory.instance.objectNode().put("descriptor", true), List.of());
        objects.put(value.objectId(), value);
        return new Entry(UUID.randomUUID(), new ObjectRef(scope, value.objectId()));
    }

    private TreeRoot keep(PageEdit edit) {
        Set<UUID> newIds = new HashSet<>();
        edit.additions().forEach(value -> newIds.add(value.objectId()));
        Set<UUID> seen = new HashSet<>();
        for (NewObject value : edit.additions()) {
            for (NewEdge edge : value.edges()) if (newIds.contains(edge.child().objectId())) assertThat(seen).contains(edge.child().objectId());
            seen.add(value.objectId()); objects.put(value.objectId(), value);
        }
        Set<UUID> reachable = new HashSet<>();
        collect(edit.root().ref(), reachable);
        assertThat(reachable).containsAll(newIds);
        return edit.root();
    }

    private void collect(ObjectRef ref, Set<UUID> result) {
        if (!result.add(ref.objectId())) return;
        objects.get(ref.objectId()).edges().forEach(edge -> collect(edge.child(), result));
    }

    private void validate(TreeRoot root, List<Entry> expected) {
        List<Entry> actual = new ArrayList<>();
        assertThat(walk(root.ref(), root.height(), true, actual)).isEqualTo(root.count());
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    private int walk(ObjectRef ref, int height, boolean root, List<Entry> output) {
        NewObject page = objects.get(ref.objectId());
        assertThat(page.dagRank()).isEqualTo((short) (10 + height));
        assertThat(page.edges().size()).isBetween(root ? height == 0 ? 0 : 2 : 16, 32);
        int count = 0;
        for (int i = 0; i < page.edges().size(); i++) {
            NewEdge edge = page.edges().get(i);
            int childCount;
            if (height == 0) { output.add(new Entry(edge.logicalKey(), edge.child())); childCount = 1; }
            else childCount = walk(edge.child(), height - 1, false, output);
            assertThat(page.payload().path("counts").get(i).intValue()).isEqualTo(childCount);
            count += childCount;
        }
        return count;
    }
}
