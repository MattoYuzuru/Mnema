package app.mnema.learning.library;

import app.mnema.learning.library.PublicationRepository.Alias;
import app.mnema.learning.library.PublicationRepository.Topic;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The two-level topic directory with synonyms (architecture section 3, {@code topic} and {@code topic_alias}). It is a few dozen rows of data, read from the
 * database and kept in memory for {@value #TTL_MINUTES} minutes per instance, so an edit of the data reaches every instance within that time and the publication
 * form costs no extra query. A deck picks a <em>leaf</em>: a topic without children (a top-level topic without children, «Другое», is a leaf).
 *
 * <p>Suggestion: the normalized title and description are searched for the normalized names and synonyms of the leaves, at word boundaries (scripts written without
 * spaces, such as Han, kana, Hangul and Thai, match inside a run); a hit in the title weighs three times a hit in the description and a longer synonym more than a
 * short one. At most {@value #MAX_SUGGESTIONS} topics, best first, ties by the order of the directory.
 */
@Service
public class TopicDirectory {
    static final int MAX_SUGGESTIONS = 3;
    static final int TTL_MINUTES = 10;
    private static final int TITLE_WEIGHT = 3;

    private record Snapshot(long loadedAt, List<Topic> topics, Map<String, Topic> byId, Set<String> leaves, List<Alias> aliases, Map<String, Integer> order) { }

    private final PublicationRepository repository;
    private volatile Snapshot snapshot;

    TopicDirectory(PublicationRepository repository) { this.repository = repository; }

    private Snapshot current() {
        Snapshot known = snapshot;
        long now = System.nanoTime();
        if (known != null && now - known.loadedAt() < TimeUnit.MINUTES.toNanos(TTL_MINUTES)) return known;
        List<Topic> topics = repository.topics();
        Map<String, Topic> byId = new LinkedHashMap<>();
        topics.forEach(topic -> byId.put(topic.topicId(), topic));
        Set<String> parents = new java.util.HashSet<>();
        topics.forEach(topic -> { if (topic.parentId() != null) parents.add(topic.parentId()); });
        Set<String> leaves = new java.util.LinkedHashSet<>();
        topics.forEach(topic -> { if (!parents.contains(topic.topicId())) leaves.add(topic.topicId()); });
        // The order of the directory: top-level ordinal, then the topic's own ordinal.
        Map<String, Integer> order = new HashMap<>();
        List<Topic> sorted = new ArrayList<>(topics);
        sorted.sort(Comparator.comparingInt((Topic topic) -> topic.parentId() == null ? topic.ordinal() : byId.get(topic.parentId()).ordinal())
                .thenComparingInt(topic -> topic.parentId() == null ? -1 : topic.ordinal()));
        for (int index = 0; index < sorted.size(); index++) order.put(sorted.get(index).topicId(), index);
        Snapshot fresh = new Snapshot(now, List.copyOf(topics), byId, Set.copyOf(leaves), List.copyOf(repository.aliases()), Map.copyOf(order));
        snapshot = fresh;
        return fresh;
    }

    /** Forgets the cached directory (tests, and an editor of the data that wants to be seen at once). */
    void invalidate() { snapshot = null; }

    /** Whether the topic exists and a deck may pick it. */
    public boolean selectable(String topicId) { return topicId != null && current().leaves().contains(topicId); }

    /** The tree for {@code GET /api/topics}: top-level topics in order, each with its children in order. */
    public ObjectNode tree() {
        Snapshot directory = current();
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        var tops = result.putArray("topics");
        directory.topics().stream().filter(topic -> topic.parentId() == null).sorted(Comparator.comparingInt(Topic::ordinal)).forEach(top -> {
            ObjectNode node = tops.addObject();
            node.put("topicId", top.topicId()).put("nameRu", top.nameRu()).put("nameEn", top.nameEn()).put("ordinal", top.ordinal());
            var children = node.putArray("children");
            directory.topics().stream().filter(child -> top.topicId().equals(child.parentId())).sorted(Comparator.comparingInt(Topic::ordinal))
                    .forEach(child -> children.addObject().put("topicId", child.topicId()).put("nameRu", child.nameRu()).put("nameEn", child.nameEn())
                            .put("ordinal", child.ordinal()));
        });
        return result;
    }

    /** At most three leaf topics whose names or synonyms occur in the title and description, best first. */
    public List<String> suggest(String title, String description) {
        Snapshot directory = current();
        String titleText = AliasNormalizer.normalize(title);
        String descriptionText = AliasNormalizer.normalize(description);
        Map<String, Integer> score = new HashMap<>();
        for (Alias alias : directory.aliases()) {
            if (!directory.leaves().contains(alias.topicId())) continue;
            int points = alias.aliasNorm().codePointCount(0, alias.aliasNorm().length()) * (occurs(titleText, alias.aliasNorm()) ? TITLE_WEIGHT : 0)
                    + alias.aliasNorm().codePointCount(0, alias.aliasNorm().length()) * (occurs(descriptionText, alias.aliasNorm()) ? 1 : 0);
            if (points > 0) score.merge(alias.topicId(), points, Math::max);
        }
        return score.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(entry -> directory.order().get(entry.getKey())))
                .limit(MAX_SUGGESTIONS).map(Map.Entry::getKey).toList();
    }

    /** Whether {@code alias} occurs in {@code text} at word boundaries (both are already normalized). */
    static boolean occurs(String text, String alias) {
        if (text.isEmpty() || alias.isEmpty()) return false;
        int first = alias.codePointAt(0);
        int last = alias.codePointBefore(alias.length());
        for (int from = text.indexOf(alias); from >= 0; from = text.indexOf(alias, from + 1)) {
            int end = from + alias.length();
            boolean leftOk = from == 0 || boundary(text.codePointBefore(from), first);
            boolean rightOk = end == text.length() || boundary(text.codePointAt(end), last);
            if (leftOk && rightOk) return true;
        }
        return false;
    }

    /** A boundary between the alias edge and its neighbour: the neighbour is not a letter or digit, or one of the two is written without spaces. */
    private static boolean boundary(int neighbour, int edge) {
        return !Character.isLetterOrDigit(neighbour) || unspaced(neighbour) || unspaced(edge);
    }

    private static boolean unspaced(int point) {
        return switch (Character.UnicodeScript.of(point)) {
            case HAN, HIRAGANA, KATAKANA, HANGUL, THAI -> true;
            default -> false;
        };
    }
}
