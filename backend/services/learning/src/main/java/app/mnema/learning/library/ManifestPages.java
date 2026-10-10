package app.mnema.learning.library;

import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Profile;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.TreeRoot;
import app.mnema.learning.catalog.content.pages.CountedPages;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Pages of the members and exercises manifests of a published revision: the counted-page reader of the owner's lists pointed at the immutable roots the
 * revision pinned. A page read costs O(log N + P) object reads (bounded by the reader's own budget); nothing is written.
 */
@Component
final class ManifestPages {
    static final int MAX_ENTRIES = 100_000;
    static final Profile MEMBERS = Profile.members(MAX_ENTRIES);
    static final Profile EXERCISES = Profile.exercises(MAX_ENTRIES);

    private final ImmutableStorage storage;

    ManifestPages(ImmutableStorage storage) { this.storage = storage; }

    List<Entry> members(UUID scope, UUID root, int count, int start, int limit) {
        return read(MEMBERS, "members", scope, root, count, start, limit);
    }

    List<Entry> exercises(UUID scope, UUID root, int count, int start, int limit) {
        return read(EXERCISES, "exercises", scope, root, count, start, limit);
    }

    private List<Entry> read(Profile profile, String role, UUID scope, UUID root, int count, int start, int limit) {
        if (count == 0 || start >= count) return List.of();
        NewObject page = storage.readBatch(scope, List.of(root)).getFirst().value();
        if (!page.payload().path("role").isString() || !role.equals(page.payload().path("role").stringValue(null))
                || !page.payload().path("treeHeight").canConvertToInt()) {
            throw new IllegalStateException("Invalid published manifest root");
        }
        TreeRoot tree = new TreeRoot(new ObjectRef(scope, root), page.payload().path("treeHeight").intValue(), count);
        return new CountedPages(profile, ref -> storage.readBatch(ref.reuseScopeId(), List.of(ref.objectId())).getFirst().value())
                .read(tree, start, Math.min(limit, count - start));
    }
}
