package app.mnema.learning.catalog.content.pages;

import app.mnema.learning.storage.StorageTypes.NewEdge;
import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.ObjectKind;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static app.mnema.learning.catalog.content.pages.CountedPageTypes.*;
import static app.mnema.learning.catalog.content.pages.CountedPageFailure.Code.*;

/** Pure immutable page edits. Domain adapters own entry uniqueness, descriptor semantics and root pins. */
public final class CountedPages {
    private static final int MIN = 16;
    private static final int MAX = 32;
    private static final int LOCAL_READ_BUDGET = 256;
    private final Profile profile;
    private final ObjectSource source;

    public CountedPages(Profile profile, ObjectSource source) {
        this.profile = Objects.requireNonNull(profile);
        this.source = Objects.requireNonNull(source);
    }

    /** Bounded O(N) bulk preparation; request-serving callers must use the local operations below. */
    public PageEdit build(UUID scope, List<Entry> entries) {
        check(entries.size() <= profile.maxEntries(), BUDGET_EXCEEDED);
        check(profile.emptyRoot() || !entries.isEmpty(), INVALID_RANGE);
        Session session = new Session(scope, Math.max(LOCAL_READ_BUDGET, entries.size()));
        List<Link> links = entries.stream().map(session::entry).toList();
        if (links.isEmpty()) return session.finish(session.create(0, links));
        int height = 0;
        do {
            int groups = (links.size() + MAX - 1) / MAX;
            List<Link> parents = new ArrayList<>();
            int start = 0;
            for (int group = 0; group < groups; group++) {
                int length = links.size() / groups + (group < links.size() % groups ? 1 : 0);
                parents.add(session.link(session.create(height, links.subList(start, start + length))));
                start += length;
            }
            links = parents;
            height++;
        } while (links.size() > 1);
        return session.finish(session.page(links.getFirst().target(), true));
    }

    public PageEdit insert(TreeRoot root, int ordinal, Entry entry) {
        check(ordinal >= 0 && ordinal <= root.count(), INVALID_RANGE);
        check(root.count() < profile.maxEntries(), BUDGET_EXCEEDED);
        Session session = new Session(root.ref().reuseScopeId(), LOCAL_READ_BUDGET);
        Node before = session.root(root);
        List<Node> inserted = session.insert(before, ordinal, session.entry(entry));
        Node result = inserted.size() == 1 ? inserted.getFirst()
                : session.create(before.height() + 1, inserted.stream().map(session::link).toList());
        return session.finish(result);
    }

    public PageEdit delete(TreeRoot root, int ordinal, UUID expectedKey) {
        check(ordinal >= 0 && ordinal < root.count(), INVALID_RANGE);
        check(profile.emptyRoot() || root.count() > 1, INVALID_RANGE);
        Session session = new Session(root.ref().reuseScopeId(), LOCAL_READ_BUDGET);
        Node before = session.root(root);
        check(Objects.equals(session.at(before, ordinal).key(), expectedKey), KEY_MISMATCH);
        return session.finish(session.collapse(session.delete(before, ordinal)));
    }

    /** Destination is the insertion ordinal in the sequence after removing the source entry. */
    public PageEdit move(TreeRoot root, int from, int destinationAfterRemoval, UUID expectedKey) {
        check(from >= 0 && from < root.count() && destinationAfterRemoval >= 0 && destinationAfterRemoval < root.count(), INVALID_RANGE);
        Session session = new Session(root.ref().reuseScopeId(), LOCAL_READ_BUDGET);
        Node before = session.root(root);
        Link selected = session.at(before, from);
        check(Objects.equals(selected.key(), expectedKey), KEY_MISMATCH);
        if (from == destinationAfterRemoval) return new PageEdit(root, List.of());
        Node removed = session.collapse(session.delete(before, from));
        List<Node> inserted = session.insert(removed, destinationAfterRemoval, selected);
        Node result = inserted.size() == 1 ? inserted.getFirst()
                : session.create(removed.height() + 1, inserted.stream().map(session::link).toList());
        return session.finish(result);
    }

    /** Replace one target without changing ordering, for metadata/record updates. */
    public PageEdit replace(TreeRoot root, int ordinal, UUID expectedKey, Entry entry) {
        check(ordinal >= 0 && ordinal < root.count(), INVALID_RANGE);
        Session session = new Session(root.ref().reuseScopeId(), LOCAL_READ_BUDGET);
        Node before = session.root(root);
        Link selected = session.at(before, ordinal);
        check(Objects.equals(selected.key(), expectedKey) && Objects.equals(entry.key(), expectedKey), KEY_MISMATCH);
        Link replacement = session.entry(entry);
        if (selected.equals(replacement)) return new PageEdit(root, List.of());
        return session.finish(session.replace(before, ordinal, replacement));
    }

    /** At most 100 results; only intersecting paths/pages are read. Visited child counts are checked. */
    public List<Entry> read(TreeRoot root, int start, int limit) {
        check(start >= 0 && start <= root.count() && limit >= 1 && limit <= 100, INVALID_RANGE);
        Session session = new Session(root.ref().reuseScopeId(), LOCAL_READ_BUDGET);
        Node page = session.root(root);
        List<Entry> result = new ArrayList<>();
        session.range(page, start, Math.min(limit, root.count() - start), result);
        return List.copyOf(result);
    }

    private final class Session {
        private final UUID scope;
        private final int readLimit;
        private final Map<UUID, NewObject> cache = new HashMap<>();
        private final Map<UUID, NewObject> additions = new LinkedHashMap<>();
        private int reads;

        Session(UUID scope, int readLimit) { this.scope = scope; this.readLimit = readLimit; }

        NewObject object(ObjectRef ref) {
            check(scope.equals(ref.reuseScopeId()), INVALID_PAGE);
            NewObject value = additions.get(ref.objectId());
            if (value != null) return value;
            return cache.computeIfAbsent(ref.objectId(), id -> {
                check(++reads <= readLimit, BUDGET_EXCEEDED);
                NewObject loaded = source.read(ref);
                check(loaded != null && loaded.objectId().equals(id), INVALID_PAGE);
                return loaded;
            });
        }

        Node root(TreeRoot root) {
            Node page = page(root.ref(), true);
            check(page.height() == root.height() && page.count() == root.count(), INVALID_PAGE);
            return page;
        }

        Node page(ObjectRef ref, boolean root) {
            NewObject object = object(ref);
            JsonNode data = object.payload();
            check(object.kind() == ObjectKind.PAGE && object.encodingVersion() == 1 && data.size() == 4
                    && data.has("codec") && data.has("role") && data.has("treeHeight") && data.has("counts")
                    && number(data.path("codec"), 1, 1) == 1 && data.path("role").isTextual()
                    && data.path("role").textValue().equals(profile.role()), INVALID_PAGE);
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

        Node child(Node parent, int index) {
            Link edge = parent.links().get(index);
            Node child = page(edge.target(), false);
            check(child.height() == parent.height() - 1 && child.count() == edge.count(), INVALID_PAGE);
            return child;
        }

        Link entry(Entry entry) {
            check(profile.keyed() == (entry.key() != null) && scope.equals(entry.target().reuseScopeId()), INVALID_PAGE);
            check(object(entry.target()).dagRank() < profile.leafRank(), INVALID_PAGE);
            return new Link(entry.key(), entry.target(), 1);
        }

        Node create(int height, List<Link> links) {
            check(height + profile.leafRank() <= 32 && links.size() <= MAX, BUDGET_EXCEEDED);
            var payload = JsonNodeFactory.instance.objectNode().put("codec", 1).put("role", profile.role()).put("treeHeight", height);
            var counts = payload.putArray("counts");
            List<NewEdge> edges = new ArrayList<>();
            int count = 0;
            for (int i = 0; i < links.size(); i++) {
                Link link = links.get(i);
                counts.add(link.count());
                count = Math.addExact(count, link.count());
                edges.add(new NewEdge(i, link.key(), link.target()));
            }
            NewObject value = new NewObject(UUID.randomUUID(), ObjectKind.PAGE, (short) 1,
                    (short) (profile.leafRank() + height), payload, edges);
            additions.put(value.objectId(), value);
            return new Node(new ObjectRef(scope, value.objectId()), height, List.copyOf(links), count);
        }

        Link link(Node node) { return new Link(null, node.ref(), node.count()); }

        Position position(Node node, int ordinal) {
            int remaining = ordinal;
            for (int i = 0; i < node.links().size(); i++) {
                if (remaining < node.links().get(i).count() || i == node.links().size() - 1) return new Position(i, remaining);
                remaining -= node.links().get(i).count();
            }
            throw new CountedPageFailure(INVALID_RANGE);
        }

        Link at(Node node, int ordinal) {
            if (node.height() == 0) return node.links().get(ordinal);
            Position position = position(node, ordinal);
            return at(child(node, position.index()), position.offset());
        }

        List<Node> insert(Node node, int ordinal, Link entry) {
            List<Link> links = new ArrayList<>(node.links());
            if (node.height() == 0) links.add(ordinal, entry);
            else {
                Position position = position(node, ordinal);
                List<Node> changed = insert(child(node, position.index()), position.offset(), entry);
                links.remove(position.index());
                links.addAll(position.index(), changed.stream().map(this::link).toList());
            }
            return links.size() <= MAX ? List.of(create(node.height(), links))
                    : List.of(create(node.height(), links.subList(0, MIN)), create(node.height(), links.subList(MIN, links.size())));
        }

        Node delete(Node node, int ordinal) {
            List<Link> links = new ArrayList<>(node.links());
            if (node.height() == 0) links.remove(ordinal);
            else {
                Position position = position(node, ordinal);
                Node changed = delete(child(node, position.index()), position.offset());
                links.set(position.index(), link(changed));
                if (changed.links().size() < MIN) rebalance(node, links, position.index(), changed);
            }
            return create(node.height(), links);
        }

        void rebalance(Node parent, List<Link> links, int index, Node changed) {
            Node left = index > 0 ? child(parent, index - 1) : null;
            Node right = index + 1 < parent.links().size() ? child(parent, index + 1) : null;
            List<Link> values = new ArrayList<>(changed.links());
            if (left != null && left.links().size() > MIN) {
                var neighbor = new ArrayList<>(left.links());
                values.addFirst(neighbor.removeLast());
                links.set(index - 1, link(create(left.height(), neighbor)));
                links.set(index, link(create(changed.height(), values)));
            } else if (right != null && right.links().size() > MIN) {
                var neighbor = new ArrayList<>(right.links());
                values.addLast(neighbor.removeFirst());
                links.set(index, link(create(changed.height(), values)));
                links.set(index + 1, link(create(right.height(), neighbor)));
            } else if (left != null) {
                var merged = new ArrayList<>(left.links());
                merged.addAll(values);
                links.set(index - 1, link(create(changed.height(), merged)));
                links.remove(index);
            } else {
                check(right != null, INVALID_PAGE);
                values.addAll(right.links());
                links.set(index, link(create(changed.height(), values)));
                links.remove(index + 1);
            }
        }

        Node replace(Node node, int ordinal, Link replacement) {
            var links = new ArrayList<>(node.links());
            if (node.height() == 0) links.set(ordinal, replacement);
            else {
                Position position = position(node, ordinal);
                links.set(position.index(), link(replace(child(node, position.index()), position.offset(), replacement)));
            }
            return create(node.height(), links);
        }

        Node collapse(Node root) {
            Node result = root;
            while (result.height() > 0 && result.links().size() == 1) {
                // Its sole child may now be a legal root even when it would not be a legal non-root.
                result = page(result.links().getFirst().target(), true);
            }
            return result;
        }

        void range(Node node, int start, int length, List<Entry> output) {
            if (length == 0) return;
            if (node.height() == 0) {
                node.links().subList(start, start + length).forEach(link -> output.add(new Entry(link.key(), link.target())));
                return;
            }
            Position position = position(node, start);
            int index = position.index();
            int offset = position.offset();
            int remaining = length;
            while (remaining > 0) {
                Node child = child(node, index++);
                int selected = Math.min(remaining, child.count() - offset);
                range(child, offset, selected, output);
                remaining -= selected;
                offset = 0;
            }
        }

        PageEdit finish(Node root) {
            List<NewObject> reachable = new ArrayList<>();
            postorder(root.ref(), new HashSet<>(), reachable);
            return new PageEdit(new TreeRoot(root.ref(), root.height(), root.count()), reachable);
        }

        void postorder(ObjectRef ref, Set<UUID> visited, List<NewObject> result) {
            NewObject object = additions.get(ref.objectId());
            if (object == null || !visited.add(ref.objectId())) return;
            object.edges().forEach(edge -> postorder(edge.child(), visited, result));
            result.add(object);
        }
    }

    private static int number(JsonNode value, int minimum, int maximum) {
        check(value.isIntegralNumber() && value.canConvertToInt(), INVALID_PAGE);
        int number = value.intValue();
        check(number >= minimum && number <= maximum, INVALID_PAGE);
        return number;
    }
    private static void check(boolean condition, CountedPageFailure.Code code) {
        if (!condition) throw new CountedPageFailure(code);
    }
    private record Link(UUID key, ObjectRef target, int count) { }
    private record Node(ObjectRef ref, int height, List<Link> links, int count) { }
    private record Position(int index, int offset) { }
}
