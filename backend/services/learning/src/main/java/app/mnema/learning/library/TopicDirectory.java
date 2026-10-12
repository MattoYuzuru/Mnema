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
    /** The most topics one search query can name; more would turn a text query into a browse of half the catalogue. */
    public static final int MAX_NAMED = 3;
    private static final int MAX_ALIAS_WORDS = 4;
    private static final int MAX_EXTENSION_TEXTS = 24;
    private static final int TITLE_WEIGHT = 3;

    private record Snapshot(long loadedAt, List<Topic> topics, Map<String, Topic> byId, Set<String> leaves, List<Alias> aliases, Map<String, Integer> order,
                            Map<String, List<String>> topicsByAlias, Map<String, List<String>> textsByTopic) { }

    /** A topic as the catalogue routes show it: the Russian name is the one on a card; {@code parentId} is null for a top-level topic. */
    public record Entry(String id, String parentId, String nameRu, String nameEn) { }

    /** The topics a search query names (at most {@value #MAX_NAMED}, the longest alias first) and the texts that extend the query: their names and synonyms. */
    public record Named(List<String> topics, List<String> texts) {
        public static final Named NONE = new Named(List.of(), List.of());
    }

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
        List<Alias> aliases = List.copyOf(repository.aliases());
        // Names count as synonyms: the seed lists most of them, a data edit may not.
        Map<String, Set<String>> byAlias = new HashMap<>();
        Map<String, Set<String>> texts = new LinkedHashMap<>();
        for (Topic topic : topics) {
            for (String name : List.of(topic.nameRu(), topic.nameEn())) {
                String norm = AliasNormalizer.normalize(name);
                byAlias.computeIfAbsent(norm, key -> new java.util.LinkedHashSet<>()).add(topic.topicId());
                texts.computeIfAbsent(topic.topicId(), key -> new java.util.LinkedHashSet<>()).add(name);
            }
        }
        for (Alias alias : aliases) {
            byAlias.computeIfAbsent(alias.aliasNorm(), key -> new java.util.LinkedHashSet<>()).add(alias.topicId());
            texts.computeIfAbsent(alias.topicId(), key -> new java.util.LinkedHashSet<>()).add(alias.aliasNorm());
        }
        Map<String, List<String>> topicsByAlias = new HashMap<>();
        byAlias.forEach((alias, ids) -> topicsByAlias.put(alias, List.copyOf(ids)));
        Map<String, List<String>> textsByTopic = new HashMap<>();
        texts.forEach((id, values) -> textsByTopic.put(id, List.copyOf(values)));
        Snapshot fresh = new Snapshot(now, List.copyOf(topics), byId, Set.copyOf(leaves), aliases, Map.copyOf(order), Map.copyOf(topicsByAlias), Map.copyOf(textsByTopic));
        snapshot = fresh;
        return fresh;
    }

    /** Forgets the cached directory (tests, and an editor of the data that wants to be seen at once). */
    void invalidate() { snapshot = null; }

    /** One topic of the directory, or empty for an unknown id. */
    public java.util.Optional<Entry> find(String topicId) {
        Topic topic = topicId == null ? null : current().byId().get(topicId);
        return topic == null ? java.util.Optional.empty() : java.util.Optional.of(entry(topic));
    }

    /** Every topic in the order of the directory: each top-level topic followed by its children. */
    public List<Entry> entries() {
        Snapshot directory = current();
        return directory.topics().stream().sorted(Comparator.comparingInt(topic -> directory.order().get(topic.topicId()))).map(TopicDirectory::entry).toList();
    }

    /** The ids of the children of a top-level topic, in the order of the directory (empty for a leaf). */
    public List<String> childrenOf(String topicId) {
        Snapshot directory = current();
        return directory.topics().stream().filter(topic -> topicId != null && topicId.equals(topic.parentId()))
                .sorted(Comparator.comparingInt(topic -> directory.order().get(topic.topicId()))).map(Topic::topicId).toList();
    }

    private static Entry entry(Topic topic) { return new Entry(topic.topicId(), topic.parentId(), topic.nameRu(), topic.nameEn()); }

    /**
     * The topics a search query names (Community/2): its normalized text, each run of up to {@value #MAX_ALIAS_WORDS} consecutive words of it, is looked up among the
     * normalized names and synonyms, so «испанский», «spanish» and «español» all name {@code spanish} and «испанский язык» names it as one phrase. The longest synonyms win, at most
     * {@value #MAX_NAMED} topics. The texts that extend the query are the names and synonyms of those topics (never of their children: a named top-level topic is filtered by its
     * children, not searched for by theirs), at most {@value #MAX_EXTENSION_TEXTS}.
     *
     * @param normalizedQuery {@link AliasNormalizer#normalize} of the query
     */
    public Named named(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isEmpty()) return Named.NONE;
        Snapshot directory = current();
        String[] words = normalizedQuery.split("[^\\p{L}\\p{N}]+");
        Map<String, Integer> best = new LinkedHashMap<>();
        java.util.function.Consumer<String> look = phrase -> {
            List<String> ids = directory.topicsByAlias().get(phrase);
            if (ids != null) ids.forEach(id -> best.merge(id, phrase.length(), Math::max));
        };
        look.accept(normalizedQuery);
        for (int from = 0; from < words.length; from++) {
            StringBuilder phrase = new StringBuilder();
            for (int to = from; to < words.length && to < from + MAX_ALIAS_WORDS; to++) {
                if (words[to].isEmpty()) break;
                if (to > from) phrase.append(' ');
                phrase.append(words[to]);
                look.accept(phrase.toString());
            }
        }
        if (best.isEmpty()) return Named.NONE;
        List<String> topics = best.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(entry -> directory.order().get(entry.getKey())))
                .limit(MAX_NAMED).map(Map.Entry::getKey).toList();
        Set<String> texts = new java.util.LinkedHashSet<>();
        for (String id : topics) texts.addAll(directory.textsByTopic().getOrDefault(id, List.of()));
        return new Named(topics, texts.stream().limit(MAX_EXTENSION_TEXTS).toList());
    }

    /**
     * {@code base} with more topics, the ones a typo of a synonym named (Community/2): they come after the exact ones, the total stays at most {@value #MAX_NAMED}, and their names and
     * synonyms extend the text like the others'. Unknown ids are ignored.
     */
    public Named withTopics(Named base, java.util.Collection<String> more) {
        if (more.isEmpty()) return base;
        Snapshot directory = current();
        List<String> topics = new ArrayList<>(base.topics());
        Set<String> texts = new java.util.LinkedHashSet<>(base.texts());
        for (String id : more) {
            if (topics.size() >= MAX_NAMED) break;
            if (topics.contains(id) || !directory.byId().containsKey(id)) continue;
            topics.add(id);
            texts.addAll(directory.textsByTopic().getOrDefault(id, List.of()));
        }
        return new Named(List.copyOf(topics), texts.stream().limit(MAX_EXTENSION_TEXTS).toList());
    }

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
