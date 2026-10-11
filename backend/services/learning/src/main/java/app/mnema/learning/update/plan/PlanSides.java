package app.mnema.learning.update.plan;

import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Result;
import app.mnema.learning.library.ManifestDiff;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.update.plan.PlanRepository.Binding;
import app.mnema.learning.update.plan.PlanRepository.Copy;
import app.mnema.learning.update.plan.PlanRepository.ExerciseBody;
import app.mnema.learning.update.plan.PlanRepository.ObjectiveRev;
import app.mnema.learning.update.plan.PlanRepository.Pair;
import app.mnema.learning.update.plan.PlanRepository.Rev;
import app.mnema.learning.update.plan.PlanRepository.SourceRevision;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the review screen shows of a plan: the one-line titles of a page of units, and the two sides of one unit. Everything is addressed by the revisions the plan holds (a side is
 * never found by an id from the request) and nothing is written. A taken-down revision of the copy's own head keeps its identity and shows nothing of its content; the author's
 * taken-down revisions are not in a plan at all.
 */
@Component
final class PlanSides {
    /** The longest title of a unit row, in code points. */
    static final int TITLE_LIMIT = 160;
    /** Positions of moved materials are read from the manifests only up to this size (a scan); a bigger deck names the keys without positions. */
    static final int POSITION_SCAN_LIMIT = 5_000;
    /** At most this many moved materials are listed per side. */
    static final int MOVED_LIMIT = 50;
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final PlanRepository repository;
    private final PlanContent contents;
    private final ManifestPages manifests;
    private final ManifestDiff diffs;

    PlanSides(PlanRepository repository, PlanContent contents, ManifestPages manifests, ManifestDiff diffs) {
        this.repository = repository;
        this.contents = contents;
        this.manifests = manifests;
        this.diffs = diffs;
    }

    // ---- titles of a page ----

    /** The title of each unit of a page, by unit id; empty text when the revision shown is taken down, null for the order unit. */
    Map<String, String> titles(Copy copy, SourceRevision published, List<PlanUnit> page) {
        UUID scope = copy.scopeId();
        Map<String, String> result = new HashMap<>();
        Set<Pair> materials = new LinkedHashSet<>();
        Set<Pair> exercises = new LinkedHashSet<>();
        Set<Pair> objectives = new LinkedHashSet<>();
        for (PlanUnit unit : page) {
            UUID shown = unit.authorRevisionId() != null ? unit.authorRevisionId() : unit.mineRevisionId();
            switch (unit.kind()) {
                case MATERIAL -> materials.add(new Pair(unit.key(), shown));
                case EXERCISE -> exercises.add(new Pair(unit.key(), shown));
                case OBJECTIVE -> objectives.add(new Pair(unit.key(), shown));
                case METADATA -> result.put(unit.unitId(), clip(published.title()));
                case ORDER -> result.put(unit.unitId(), null);
            }
        }
        if (!materials.isEmpty()) materialTitles(scope, materials).forEach((pair, title) -> result.put(UnitKind.MATERIAL.unitId(pair.key()), title));
        if (!exercises.isEmpty()) {
            Map<Pair, Rev> revisions = repository.exercisesById(scope, exercises);
            Map<Pair, JsonNode> contents = repository.exerciseContents(scope, exercises);
            for (Pair pair : exercises) {
                Rev revision = revisions.get(pair);
                JsonNode content = contents.get(pair);
                result.put(UnitKind.EXERCISE.unitId(pair.key()), revision == null || revision.blocked() || content == null ? "" : clip(prompt(content)));
            }
        }
        if (!objectives.isEmpty()) {
            Map<Pair, ObjectiveRev> revisions = repository.objectiveRevisions(scope, objectives);
            for (Pair pair : objectives) {
                ObjectiveRev revision = revisions.get(pair);
                result.put(UnitKind.OBJECTIVE.unitId(pair.key()), revision == null ? "" : clip(revision.title()));
            }
        }
        return result;
    }

    private Map<Pair, String> materialTitles(UUID scope, Set<Pair> pairs) {
        Map<Pair, Rev> revisions = repository.itemsById(scope, pairs);
        Map<Pair, String> cached = repository.cachedTitles(scope, pairs);
        Map<Pair, String> result = new HashMap<>();
        for (Pair pair : pairs) {
            Rev revision = revisions.get(pair);
            if (revision == null || revision.blocked()) {
                result.put(pair, "");
                continue;
            }
            String title = cached.get(pair);
            result.put(pair, clip(title != null ? title : contents.title(scope, revision.contentRootId())));
        }
        return result;
    }

    static String clip(String text) {
        if (text == null) return "";
        int length = text.codePointCount(0, text.length());
        return length <= TITLE_LIMIT ? text : text.substring(0, text.offsetByCodePoints(0, TITLE_LIMIT - 1)) + "…";
    }

    /** The plain-text question of an exercise: its prompt's TEXT blocks, or a cloze passage with each blank as an ellipsis. Never an answer key, option or reference. */
    static String prompt(JsonNode content) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content.path("prompt")) {
            if ("TEXT".equals(block.path("kind").stringValue(null))) append(text, block.path("text").stringValue(""));
        }
        if (text.isEmpty()) {
            for (JsonNode segment : content.path("passage")) {
                String kind = segment.path("kind").stringValue(null);
                if ("TEXT".equals(kind)) append(text, segment.path("text").stringValue(""));
                else if ("BLANK".equals(kind)) append(text, "…");
            }
        }
        return text.toString().replaceAll("\\s+", " ").strip();
    }

    private static void append(StringBuilder text, String part) {
        if (!text.isEmpty()) text.append(' ');
        text.append(part);
    }

    // ---- the two sides of one unit ----

    /** {@code {unitId, kind, key, class, reason, planVersion, mine, author, changedNodeIds}}. */
    ObjectNode read(UpdatePlanService.Computed computed, PlanUnit unit) {
        Copy copy = computed.copy();
        ObjectNode result = JSON.objectNode().put("unitId", unit.unitId()).put("kind", unit.kind().name()).put("key", unit.key().toString())
                .put("class", unit.unitClass().name());
        if (unit.reason() == null) result.putNull("reason"); else result.put("reason", unit.reason().name());
        result.put("planVersion", computed.plan().planVersion());
        ArrayNode changed = JSON.arrayNode();
        switch (unit.kind()) {
            case MATERIAL -> {
                JsonNode mine = material(copy, unit.key(), unit.mineRevisionId());
                JsonNode author = material(copy, unit.key(), unit.authorRevisionId());
                result.set("mine", mine);
                result.set("author", author);
                if (mine.has("document") && author.has("document")) changedNodes(mine.path("document"), author.path("document")).forEach(changed::add);
            }
            case EXERCISE -> {
                result.set("mine", exercise(copy, unit.key(), unit.mineRevisionId()));
                result.set("author", exercise(copy, unit.key(), unit.authorRevisionId()));
            }
            case OBJECTIVE -> {
                result.set("mine", objective(copy, unit.key(), unit.mineRevisionId()));
                result.set("author", objective(copy, unit.key(), unit.authorRevisionId()));
            }
            case METADATA -> {
                result.set("mine", JSON.objectNode().put("title", copy.title()).put("description", copy.description()));
                result.set("author", JSON.objectNode().put("title", computed.published().title()).put("description", computed.published().description()));
            }
            case ORDER -> order(computed, unit, result);
        }
        result.set("changedNodeIds", changed);
        return result;
    }

    private JsonNode material(Copy copy, UUID member, UUID revision) {
        if (revision == null) return JSON.nullNode();
        Rev found = repository.itemsById(copy.scopeId(), List.of(new Pair(member, revision))).get(new Pair(member, revision));
        if (found == null) return JSON.nullNode();
        ObjectNode side = JSON.objectNode().put("memberKey", member.toString()).put("itemRevisionId", revision.toString());
        if (found.blocked()) return side.put("unavailable", true);
        side.set("document", contents.document(copy.scopeId(), found.contentRootId()));
        return side;
    }

    private JsonNode exercise(Copy copy, UUID exercise, UUID revision) {
        if (revision == null) return JSON.nullNode();
        Pair pair = new Pair(exercise, revision);
        Rev found = repository.exercisesById(copy.scopeId(), List.of(pair)).get(pair);
        ExerciseBody body = repository.exerciseBodies(copy.scopeId(), List.of(pair)).get(pair);
        if (found == null || body == null) return JSON.nullNode();
        ObjectNode side = JSON.objectNode().put("exerciseId", exercise.toString()).put("exerciseRevisionId", revision.toString());
        if (found.blocked()) return side.put("unavailable", true);
        side.put("exerciseVersion", Long.toString(body.sequence())).put("type", body.type()).put("enabled", body.enabled());
        List<Binding> bindings = repository.bindings(copy.scopeId(), List.of(pair)).getOrDefault(pair, List.of());
        for (Binding binding : bindings) {
            if (!"ASSESSED".equals(binding.role())) continue;
            side.putObject("subject").put("memberKey", binding.memberKey().toString()).put("itemRevisionId", binding.itemRevisionId().toString());
            ObjectiveRev objective = repository.objectiveRevisions(copy.scopeId(), List.of(new Pair(binding.objectiveId(), binding.objectiveRevisionId())))
                    .get(new Pair(binding.objectiveId(), binding.objectiveRevisionId()));
            side.putObject("objective").put("objectiveId", binding.objectiveId().toString()).put("objectiveRevisionId", binding.objectiveRevisionId().toString())
                    .put("title", objective == null ? "" : objective.title());
        }
        side.set("content", body.content());
        side.set("answerKey", body.answerKey());
        side.set("evaluatorPolicy", body.evaluatorPolicy());
        return side;
    }

    private JsonNode objective(Copy copy, UUID objective, UUID revision) {
        if (revision == null) return JSON.nullNode();
        ObjectiveRev found = repository.objectiveRevisions(copy.scopeId(), List.of(new Pair(objective, revision))).get(new Pair(objective, revision));
        if (found == null) return JSON.nullNode();
        return JSON.objectNode().put("objectiveId", objective.toString()).put("objectiveRevisionId", revision.toString()).put("title", found.title());
    }

    /**
     * Stable node ids whose text, attributes, type, version or list of children differ between two documents, or that only one of them has: what the review highlights. Two
     * documents of one material keep the ids of the nodes they share, so this is a walk of ids, not a text diff.
     */
    static List<String> changedNodes(JsonNode mine, JsonNode author) {
        Map<String, JsonNode> left = nodes(mine);
        Map<String, JsonNode> right = nodes(author);
        Set<String> changed = new java.util.TreeSet<>();
        for (Map.Entry<String, JsonNode> entry : left.entrySet()) {
            JsonNode other = right.get(entry.getKey());
            if (other == null || !shape(entry.getValue()).equals(shape(other))) changed.add(entry.getKey());
        }
        for (String id : right.keySet()) {
            if (!left.containsKey(id)) changed.add(id);
        }
        return List.copyOf(changed);
    }

    private static Map<String, JsonNode> nodes(JsonNode document) {
        Map<String, JsonNode> result = new HashMap<>();
        ArrayDeque<JsonNode> pending = new ArrayDeque<>();
        pending.add(document.path("root"));
        while (!pending.isEmpty()) {
            JsonNode node = pending.removeLast();
            result.put(node.path("id").stringValue(""), node);
            node.path("content").forEach(pending::add);
        }
        return result;
    }

    /** The node without its descendants: type, version, attributes and the ids of its children in order. */
    private static JsonNode shape(JsonNode node) {
        ObjectNode shape = JSON.objectNode();
        shape.set("type", node.path("type"));
        shape.set("version", node.path("version"));
        shape.set("attrs", node.path("attrs"));
        ArrayNode children = shape.putArray("children");
        node.path("content").forEach(child -> children.add(child.path("id")));
        return shape;
    }

    // ---- the order of materials ----

    private void order(UpdatePlanService.Computed computed, PlanUnit unit, ObjectNode result) {
        Copy copy = computed.copy();
        SourceRevision published = computed.published();
        SourceRevision baseline = unit.baseRevisionId() == null ? null : repository.sourceRevision(copy.upstreamDeckId(), unit.baseRevisionId()).orElse(null);
        if (baseline == null) {
            result.putNull("mine");
            result.putNull("author");
            return;
        }
        UUID scope = copy.scopeId();
        Result authorMoves = diffs.members(scope, baseline.membersRootId(), baseline.memberCount(), published.membersRootId(), published.memberCount());
        Result mineMoves = diffs.members(scope, baseline.membersRootId(), baseline.memberCount(), copy.membersRootId(), copy.memberCount());
        result.set("author", moved(scope, copy.deckId(), baseline, authorMoves.order().movedKeys(), published.membersRootId(), published.memberCount(), true));
        result.set("mine", moved(scope, copy.deckId(), baseline, mineMoves.order().movedKeys(), copy.membersRootId(), copy.memberCount(), false));
    }

    private record Placed(int ordinal, Entry entry) { }

    /** {@code {moved: [{memberKey, title, from, to}], total, truncated}}: from = place in the baseline manifest, to = place in the manifest of this side. */
    private ObjectNode moved(UUID scope, UUID deck, SourceRevision baseline, List<UUID> keys, UUID root, int count, boolean author) {
        ObjectNode side = JSON.objectNode();
        side.put("total", keys.size());
        List<UUID> shown = keys.size() > MOVED_LIMIT ? keys.subList(0, MOVED_LIMIT) : keys;
        boolean scan = baseline.memberCount() <= POSITION_SCAN_LIMIT && count <= POSITION_SCAN_LIMIT;
        side.put("truncated", keys.size() > MOVED_LIMIT || !scan);
        ArrayNode list = side.putArray("moved");
        Map<UUID, Placed> from = scan ? scan(scope, baseline.membersRootId(), baseline.memberCount(), shown) : Map.of();
        Map<UUID, Placed> to = scan ? scan(scope, root, count, shown) : Map.of();
        Map<UUID, String> titles = scan ? movedTitles(scope, deck, shown, to, author) : Map.of();
        for (UUID key : shown) {
            ObjectNode item = list.addObject().put("memberKey", key.toString());
            if (titles.containsKey(key)) item.put("title", titles.get(key)); else item.putNull("title");
            if (from.containsKey(key)) item.put("from", from.get(key).ordinal()); else item.putNull("from");
            if (to.containsKey(key)) item.put("to", to.get(key).ordinal()); else item.putNull("to");
        }
        return side;
    }

    /** The places of the keys in a members manifest: one scan of its pages (the manifest is small, see {@link #POSITION_SCAN_LIMIT}); keys the manifest lacks are absent. */
    private Map<UUID, Placed> scan(UUID scope, UUID root, int count, Collection<UUID> keys) {
        Set<UUID> wanted = new HashSet<>(keys);
        Map<UUID, Placed> found = new HashMap<>();
        for (int at = 0; at < count && found.size() < wanted.size(); at += 100) {
            List<Entry> page = manifests.members(scope, root, count, at, 100);
            for (int index = 0; index < page.size(); index++) {
                if (wanted.contains(page.get(index).key())) found.put(page.get(index).key(), new Placed(at + index, page.get(index)));
            }
        }
        return found;
    }

    /** The titles of moved materials: the author's revision named by the published manifest, or the copy's own head. */
    private Map<UUID, String> movedTitles(UUID scope, UUID deck, List<UUID> keys, Map<UUID, Placed> placed, boolean author) {
        Map<UUID, Rev> revisions = new HashMap<>();
        if (author) {
            Set<Pair> descriptors = new LinkedHashSet<>();
            placed.forEach((key, found) -> descriptors.add(new Pair(key, found.entry().target().objectId())));
            repository.itemsByDescriptor(scope, descriptors).forEach((pair, rev) -> revisions.put(pair.key(), rev));
        } else {
            revisions.putAll(repository.itemHeads(deck, keys));
        }
        Set<Pair> pairs = new LinkedHashSet<>();
        revisions.forEach((key, rev) -> pairs.add(new Pair(key, rev.revisionId())));
        Map<Pair, String> titles = materialTitles(scope, pairs);
        Map<UUID, String> result = new HashMap<>();
        revisions.forEach((key, rev) -> result.put(key, titles.getOrDefault(new Pair(key, rev.revisionId()), "")));
        return result;
    }
}
