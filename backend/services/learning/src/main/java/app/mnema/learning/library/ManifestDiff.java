package app.mnema.learning.library;

import app.mnema.learning.catalog.content.pages.CountedPageDiff;
import app.mnema.learning.catalog.content.pages.CountedPageFailure;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.BatchSource;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Profile;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Result;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.TreeRoot;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import app.mnema.learning.storage.StorageTypes.StoredObject;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What changed between two published manifests of one reuse scope (a copy's base revision, its head or the source's published revision all
 * share the scope): the frontier diff of {@link CountedPageDiff} over the immutable roots the revisions pinned. It reads only the pages along
 * the changed paths, every tree level in {@link ImmutableStorage#readMany} calls of at most {@link ImmutableStorage#MAX_READ_MANY} pages
 * (two data statements each), and writes nothing. The caller has already authorized both revisions; a physical id is never access.
 */
@Component
public final class ManifestDiff {
    private final ImmutableStorage storage;

    ManifestDiff(ImmutableStorage storage) { this.storage = storage; }

    public Result members(UUID scope, UUID rootBefore, int countBefore, UUID rootAfter, int countAfter) {
        return diff(ManifestPages.MEMBERS, "members", scope, rootBefore, countBefore, rootAfter, countAfter);
    }

    public Result exercises(UUID scope, UUID rootBefore, int countBefore, UUID rootAfter, int countAfter) {
        return diff(ManifestPages.EXERCISES, "exercises", scope, rootBefore, countBefore, rootAfter, countAfter);
    }

    private Result diff(Profile profile, String role, UUID scope, UUID rootBefore, int countBefore, UUID rootAfter, int countAfter) {
        if (rootBefore.equals(rootAfter)) {
            // One page cannot have two counts: the same failure as the diff itself.
            if (countBefore != countAfter) throw new CountedPageFailure(CountedPageFailure.Code.INVALID_PAGE);
            return Result.unchanged();
        }
        // One call for both roots: it gives their heights like ManifestPages.read and seeds the first level of the walk.
        Map<UUID, NewObject> roots = new HashMap<>();
        for (StoredObject stored : storage.readMany(scope, List.of(rootBefore, rootAfter))) roots.put(stored.ref().objectId(), stored.value());
        TreeRoot before = new TreeRoot(new ObjectRef(scope, rootBefore), height(roots.get(rootBefore), role), countBefore);
        TreeRoot after = new TreeRoot(new ObjectRef(scope, rootAfter), height(roots.get(rootAfter), role), countAfter);
        BatchSource source = (reuse, ids) -> {
            Map<UUID, NewObject> found = new HashMap<>();
            List<UUID> missing = new ArrayList<>();
            for (UUID id : ids) {
                NewObject known = roots.remove(id);
                if (known == null) missing.add(id); else found.put(id, known);
            }
            for (int from = 0; from < missing.size(); from += ImmutableStorage.MAX_READ_MANY) {
                List<UUID> chunk = missing.subList(from, Math.min(from + ImmutableStorage.MAX_READ_MANY, missing.size()));
                for (StoredObject stored : storage.readMany(reuse, chunk)) found.put(stored.ref().objectId(), stored.value());
            }
            return found;
        };
        return new CountedPageDiff(profile, source).diff(before, after);
    }

    private static int height(NewObject page, String role) {
        if (!page.payload().path("role").isString() || !role.equals(page.payload().path("role").stringValue(null))
                || !page.payload().path("treeHeight").canConvertToInt()) {
            throw new IllegalStateException("Invalid published manifest root");
        }
        return page.payload().path("treeHeight").intValue();
    }
}
