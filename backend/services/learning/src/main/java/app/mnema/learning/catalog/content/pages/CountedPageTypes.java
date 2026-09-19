package app.mnema.learning.catalog.content.pages;

import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.ObjectRef;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class CountedPageTypes {
    private CountedPageTypes() { }

    /** Role/limits are domain contracts; the native decoder does not consume deck profiles. */
    public record Profile(String role, int leafRank, int maxEntries, boolean emptyRoot, boolean keyed) {
        public Profile {
            if (role == null || !role.matches("[a-z][a-z0-9_]{0,31}") || leafRank < 1 || leafRank > 32 || maxEntries < 1) {
                throw new IllegalArgumentException("Invalid counted page profile");
            }
            long minimum = 32;
            int height = 0;
            while (minimum <= maxEntries) { height++; minimum *= 16; }
            if (leafRank + height > 32) throw new IllegalArgumentException("Counted page profile exceeds DAG rank");
        }
        public static Profile nativeNodes() { return new Profile("nodes", 4, 10_000, false, true); }
        public static Profile members(int maximum) { return new Profile("members", 10, maximum, true, true); }
        public static Profile exercises(int maximum) { return new Profile("exercises", 10, maximum, true, true); }
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
}
