package app.mnema.learning.update;

import app.mnema.learning.catalog.content.pages.CountedPageFailure;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.PageEdit;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Profile;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.TreeRoot;
import app.mnema.learning.catalog.content.pages.CountedPages;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import app.mnema.learning.storage.StorageTypes.StageBatch;
import app.mnema.learning.storage.StorageTypes.StagedRoot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The two manifests of a deck being edited in memory: the members root and the exercises root, each a counted page tree. Every operation is one path-copy
 * ({@code CountedPages}, O(log N) pages) whose new pages are kept here and NOT written; only {@link #flush} writes, and only the pages that are still reachable from the
 * final roots, in dependency order, with one staging pin per root. So a command of a thousand operations writes the pages of its result, not of every intermediate tree.
 * Pages already stored are read once (they are immutable). The editor holds no database state of its own: it lives for one transaction.
 *
 * <p>An operation names the key AND the ordinal at which the caller expects it; the page code verifies the key, and a mismatch (or an ordinal outside the manifest) means the caller's
 * plan does not describe this manifest: {@link VersionConflictException}.
 */
final class ManifestEditor {
    /** The bound of both manifests of a deck. */
    static final int MAX_ENTRIES = 100_000;
    /** New pages per storage batch: a batch is bounded by 64 objects and 1 MiB. */
    private static final int BATCH = 32;

    private final ImmutableStorage storage;
    private final UUID scope;
    private final UUID actor;
    private final Map<UUID, NewObject> pending = new LinkedHashMap<>();
    private final Map<UUID, NewObject> stored = new HashMap<>();
    private final CountedPages memberPages;
    private final CountedPages exercisePages;
    private TreeRoot members;
    private TreeRoot exercises;

    ManifestEditor(ImmutableStorage storage, UUID scope, UUID actor, TreeRoot members, TreeRoot exercises) {
        this.storage = storage;
        this.scope = scope;
        this.actor = actor;
        this.members = members;
        this.exercises = exercises;
        this.memberPages = new CountedPages(Profile.members(MAX_ENTRIES), this::read);
        this.exercisePages = new CountedPages(Profile.exercises(MAX_ENTRIES), this::read);
    }

    /** The root of a stored manifest with its tree height read from the root page (the page is validated by every later operation). */
    static TreeRoot root(ImmutableStorage storage, UUID scope, UUID root, int count, String role) {
        NewObject page = storage.readBatch(scope, List.of(root)).getFirst().value();
        if (!role.equals(page.payload().path("role").stringValue(null)) || !page.payload().path("treeHeight").canConvertToInt()) {
            throw new IllegalStateException("Invalid " + role + " root");
        }
        return new TreeRoot(new ObjectRef(scope, root), page.payload().path("treeHeight").intValue(), count);
    }

    TreeRoot members() { return members; }

    TreeRoot exercises() { return exercises; }

    // ---- the members manifest ----

    void replaceMember(int ordinal, UUID member, UUID descriptor) {
        members = apply(() -> memberPages.replace(members, ordinal, member, new Entry(member, new ObjectRef(scope, descriptor))));
    }

    void insertMember(int ordinal, UUID member, UUID descriptor) {
        members = apply(() -> memberPages.insert(members, ordinal, new Entry(member, new ObjectRef(scope, descriptor))));
    }

    void deleteMember(int ordinal, UUID member) {
        members = apply(() -> memberPages.delete(members, ordinal, member));
    }

    void moveMember(int from, int to, UUID member) {
        members = apply(() -> memberPages.move(members, from, to, member));
    }

    // ---- the exercises manifest ----

    void replaceExercise(int ordinal, UUID exercise, UUID descriptor) {
        exercises = apply(() -> exercisePages.replace(exercises, ordinal, exercise, new Entry(exercise, new ObjectRef(scope, descriptor))));
    }

    void insertExercise(int ordinal, UUID exercise, UUID descriptor) {
        exercises = apply(() -> exercisePages.insert(exercises, ordinal, new Entry(exercise, new ObjectRef(scope, descriptor))));
    }

    void deleteExercise(int ordinal, UUID exercise) {
        exercises = apply(() -> exercisePages.delete(exercises, ordinal, exercise));
    }

    private TreeRoot apply(java.util.function.Supplier<PageEdit> operation) {
        try {
            PageEdit edit = operation.get();
            edit.additions().forEach(page -> pending.put(page.objectId(), page));
            return edit.root();
        } catch (CountedPageFailure failure) {
            throw switch (failure.code()) {
                case KEY_MISMATCH, INVALID_RANGE -> new VersionConflictException();
                case BUDGET_EXCEEDED -> new InvalidRequestException();
                case INVALID_PAGE -> failure;
            };
        }
    }

    private NewObject read(ObjectRef ref) {
        NewObject page = pending.get(ref.objectId());
        if (page != null) return page;
        return stored.computeIfAbsent(ref.objectId(), id -> storage.readBatch(ref.reuseScopeId(), List.of(id)).getFirst().value());
    }

    /**
     * Writes the pages that are reachable from the two roots and not stored yet (children before parents), then pins both roots with one staging pin each, and releases the
     * pins the batches needed. Must run inside the transaction that will use or roll back the pages. After it the editor holds the stored pages in its read cache.
     *
     * @return the staging pins of the members root and of the exercises root, in that order
     */
    List<StagedRoot> flush(Duration lease) {
        List<NewObject> reachable = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        postorder(members.ref().objectId(), visited, reachable);
        postorder(exercises.ref().objectId(), visited, reachable);
        List<StagedRoot> batchPins = new ArrayList<>();
        for (int at = 0; at < reachable.size(); at += BATCH) {
            List<NewObject> batch = reachable.subList(at, Math.min(reachable.size(), at + BATCH));
            batchPins.addAll(storage.stageBatch(new StageBatch(scope, actor, batch, List.of(batch.getLast().objectId())), lease));
        }
        List<StagedRoot> roots = storage.stageBatch(new StageBatch(scope, actor, List.of(),
                List.of(members.ref().objectId(), exercises.ref().objectId())), lease);
        batchPins.forEach(pin -> storage.release(scope, pin.stagingPinId()));
        reachable.forEach(page -> stored.put(page.objectId(), page));
        pending.clear();
        return roots;
    }

    private void postorder(UUID id, Set<UUID> visited, List<NewObject> result) {
        NewObject page = pending.get(id);
        if (page == null || !visited.add(id)) return;
        page.edges().forEach(edge -> postorder(edge.child().objectId(), visited, result));
        result.add(page);
    }
}
