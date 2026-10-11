package app.mnema.learning.catalog.content.pages;

import app.mnema.learning.catalog.content.pages.CountedPageTypes.Profile;
import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.ObjectKind;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static app.mnema.learning.catalog.content.pages.CountedPageFailure.Code.INVALID_PAGE;

/**
 * The one reader of a stored counted page: shape, role, rank, counts and the MIN/MAX rules. {@link CountedPages} and
 * {@link CountedPageDiff} both go through it, so the two can never disagree about what a valid page is.
 */
final class CountedPageCodec {
    static final int MIN = 16;
    static final int MAX = 32;

    private CountedPageCodec() { }

    record Link(UUID key, ObjectRef target, int count) { }
    record Node(ObjectRef ref, int height, List<Link> links, int count) { }

    /** Validates one stored page against the profile; {@code root} selects the root's (looser) entry-count rule. */
    static Node parse(Profile profile, UUID scope, ObjectRef ref, NewObject object, boolean root) {
        JsonNode data = object.payload();
        check(object.kind() == ObjectKind.PAGE && object.encodingVersion() == 1 && data.size() == 4
                && data.has("codec") && data.has("role") && data.has("treeHeight") && data.has("counts")
                && number(data.path("codec"), 1, 1) == 1 && data.path("role").isString()
                && data.path("role").stringValue(null).equals(profile.role()), INVALID_PAGE);
        int height = number(data.path("treeHeight"), 0, 32 - profile.leafRank());
        check(object.dagRank() == profile.leafRank() + height && data.path("counts").isArray()
                && data.path("counts").size() == object.edges().size(), INVALID_PAGE);
        int size = object.edges().size();
        check(size <= MAX && (root ? height == 0 ? size > 0 || profile.emptyRoot() : size >= 2 : size >= MIN), INVALID_PAGE);
        List<Link> links = new ArrayList<>();
        long count = 0;
        for (int i = 0; i < size; i++) {
            var edge = object.edges().get(i);
            int amount = number(data.path("counts").get(i), 1, profile.maxEntries());
            check(scope.equals(edge.child().reuseScopeId()) && edge.ordinal() == i
                    && (height == 0 ? amount == 1 && (profile.keyed() == (edge.logicalKey() != null)) : edge.logicalKey() == null), INVALID_PAGE);
            links.add(new Link(edge.logicalKey(), edge.child(), amount));
            count += amount;
        }
        check(count <= profile.maxEntries(), INVALID_PAGE);
        return new Node(ref, height, List.copyOf(links), (int) count);
    }

    /** A child must sit exactly one level below its parent and carry the count its parent recorded. */
    static void requireChildOf(Node parent, int index, Node child) {
        check(child.height() == parent.height() - 1 && child.count() == parent.links().get(index).count(), INVALID_PAGE);
    }

    /** The root's own height and count are checked against the page, never trusted as a cached authority. */
    static void requireRoot(CountedPageTypes.TreeRoot root, Node page) {
        check(page.height() == root.height() && page.count() == root.count(), INVALID_PAGE);
    }

    static int number(JsonNode value, int minimum, int maximum) {
        check(value.isIntegralNumber() && value.canConvertToInt(), INVALID_PAGE);
        int number = value.intValue();
        check(number >= minimum && number <= maximum, INVALID_PAGE);
        return number;
    }

    static void check(boolean condition, CountedPageFailure.Code code) {
        if (!condition) throw new CountedPageFailure(code);
    }
}
