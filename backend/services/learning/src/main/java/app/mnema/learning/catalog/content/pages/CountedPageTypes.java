package app.mnema.learning.catalog.content.pages;

import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.ObjectRef;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class CountedPageTypes {
    private CountedPageTypes() { }

    /**
     * Role/limits are domain contracts; the native decoder does not consume deck profiles. {@code bulkBuild} says whether
     * {@link CountedPages#build} may run: it re-issues every page id, so it is false for the manifests of published revisions.
     * There is no shorter constructor: a profile states its choice.
     */
    public record Profile(String role, int leafRank, int maxEntries, boolean emptyRoot, boolean keyed, boolean bulkBuild) {
        public Profile {
            if (role == null || !role.matches("[a-z][a-z0-9_]{0,31}") || leafRank < 1 || leafRank > 32 || maxEntries < 1) {
                throw new IllegalArgumentException("Invalid counted page profile");
            }
            if (leafRank + maximumHeight(maxEntries) > 32) throw new IllegalArgumentException("Counted page profile exceeds DAG rank");
        }
        public int maximumHeight() { return maximumHeight(maxEntries); }
        private static int maximumHeight(int entries) {
            long minimum = 32; int height = 0;
            while (minimum <= entries) { height++; minimum *= 16; }
            return height;
        }
        public static Profile nativeNodes() { return new Profile("nodes", 4, 10_000, false, true, true); }
        public static Profile members(int maximum) { return new Profile("members", 10, maximum, true, true, false); }
        public static Profile exercises(int maximum) { return new Profile("exercises", 10, maximum, true, true, false); }
    }

    public record Entry(UUID key, ObjectRef target) {
        public Entry {
            Objects.requireNonNull(target);
            if (key != null) UuidPolicy.requireEntityId(key, "entryKey");
        }
    }

    /** Count and height are checked against the actual root page, not trusted as cached authority. */
    public record TreeRoot(ObjectRef ref, int height, int count) {
        public TreeRoot {
            Objects.requireNonNull(ref);
            if (height < 0 || count < 0) throw new IllegalArgumentException("Invalid counted root");
        }
    }

    public record PageEdit(TreeRoot root, List<NewObject> additions) {
        public PageEdit { Objects.requireNonNull(root); additions = List.copyOf(additions); }
    }

    /** Must return the exact immutable object in the requested authorized scope. */
    @FunctionalInterface
    public interface ObjectSource { NewObject read(ObjectRef ref); }

    /** Many objects of one authorized scope at once: the exact immutable objects, every requested id present (a missing one fails). */
    @FunctionalInterface
    public interface BatchSource { Map<UUID, NewObject> readAll(UUID scope, Collection<UUID> ids); }

    /**
     * One member (or exercise) whose entry differs between two trees. {@code before == null}: ADDED; {@code after == null}: REMOVED;
     * both set and different: CHANGED. An ordinal is -1 on the side where the key is absent.
     */
    public record Change(UUID key, ObjectRef before, ObjectRef after, int beforeOrdinal, int afterOrdinal) {
        public Change {
            Objects.requireNonNull(key);
            if ((before == null) != (beforeOrdinal < 0) || (after == null) != (afterOrdinal < 0) || (before == null && after == null)) {
                throw new IllegalArgumentException("Invalid counted page change");
            }
        }
    }

    /** {@code movedKeys}: keys on both sides whose relative order changed, the complement of a longest chain, sorted by after-ordinal. */
    public record OrderChange(boolean changed, List<UUID> movedKeys) {
        public OrderChange { movedKeys = List.copyOf(movedKeys); }
    }

    /** {@code pagesRead}: distinct pages fetched from the source; {@code levels}: batched source calls (one per tree level at most). */
    public record DiffStats(int pagesRead, int levels) { }

    /** Changes: by after-ordinal (added and changed), then the removed ones by before-ordinal. */
    public record Result(List<Change> changes, OrderChange order, DiffStats stats) {
        public Result { changes = List.copyOf(changes); Objects.requireNonNull(order); Objects.requireNonNull(stats); }
        /** The answer for two references to the same root: nothing changed and no page was read. */
        public static Result unchanged() { return new Result(List.of(), new OrderChange(false, List.of()), new DiffStats(0, 0)); }
    }
}
