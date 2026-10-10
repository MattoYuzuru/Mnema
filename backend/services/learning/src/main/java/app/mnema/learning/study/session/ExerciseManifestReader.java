package app.mnema.learning.study.session;

import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Profile;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.TreeRoot;
import app.mnema.learning.catalog.content.pages.CountedPages;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.ObjectRef;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads the pinned exercises manifest of a Deck revision (Share/5, #427): the immutable counted tree whose leaves are keyed by
 * the exercise id and point at the descriptor of the revision the Deck revision published for it. This is what a candidate
 * generation scans, by ordinal, instead of asking "which revision was current at the author's {@code deck_sequence}": a copy
 * has its own numbering and no definitions of its own, but it pins the same root.
 *
 * <p>The caller holds the Deck revision's durable pin on the root for the whole read; each call touches only the pages that
 * intersect the requested range.
 */
final class ExerciseManifestReader {
    private static final int PAGE = 100;
    private final ImmutableStorage storage;

    ExerciseManifestReader(ImmutableStorage storage) { this.storage = storage; }

    /** The entries {@code [start, start+limit)} of the manifest of {@code count} exercises, in manifest order. */
    List<StudySessionRepository.ManifestEntry> entries(UUID scope, UUID root, int count, int start, int limit) {
        int end = Math.min(count, start + limit);
        if (start < 0 || start >= end) return List.of();
        CountedPages pages = new CountedPages(Profile.exercises(ExerciseService.MAX_EXERCISES),
                ref -> storage.readBatch(ref.reuseScopeId(), List.of(ref.objectId())).getFirst().value());
        TreeRoot tree = new TreeRoot(new ObjectRef(scope, root), height(scope, root), count);
        List<StudySessionRepository.ManifestEntry> result = new ArrayList<>(end - start);
        for (int at = start; at < end; at += PAGE) {
            for (Entry entry : pages.read(tree, at, Math.min(PAGE, end - at))) {
                result.add(new StudySessionRepository.ManifestEntry(entry.key(), entry.target().objectId()));
            }
        }
        return List.copyOf(result);
    }

    private int height(UUID scope, UUID root) {
        NewObject page = storage.readBatch(scope, List.of(root)).getFirst().value();
        if (!page.payload().path("role").isString() || !"exercises".equals(page.payload().path("role").stringValue(null))
                || !page.payload().path("treeHeight").canConvertToInt()) {
            throw new IllegalStateException("Invalid exercise root");
        }
        return page.payload().path("treeHeight").intValue();
    }
}
