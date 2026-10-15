package app.mnema.learning.community;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure bounded candidate scorer and strict diverse shelf. No score leaves this class's server-side callers. */
final class RecommendationRanker {
    record Candidate(PublicDeckRow deck, double base) { }

    record Result(List<PublicDeckRow> decks, String explanation) { }

    private RecommendationRanker() {
    }

    static double score(Candidate candidate, Map<String, Double> interests, Set<String> languages) {
        PublicDeckRow deck = candidate.deck();
        double language = language(deck, languages);
        return candidate.base()*(1 + 0.8*interest(deck, interests))*language;
    }

    static double language(PublicDeckRow deck, Set<String> languages) {
        return languages.isEmpty() || (deck.targetLanguage() != null && languages.contains(deck.targetLanguage())) ? 1 : 0.3;
    }

    static double interest(PublicDeckRow deck, Map<String, Double> interests) {
        return Math.max(deck.topicId() == null ? 0 : interests.getOrDefault(deck.topicId(), 0.0), deck.parentTopicId() == null ? 0 : interests.getOrDefault(deck.parentTopicId(), 0.0));
    }

    static Result rank(List<Candidate> candidates, Map<String, Double> interests, Set<String> languages, Instant now) {
        if (interests.isEmpty() || candidates.stream().filter(c -> interest(c.deck(), interests) > 0).count() < 4) return new Result(List.of(), null);
        List<String> top = interests.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey)).limit(3).map(Map.Entry::getKey).toList();
        List<Candidate> ordered = candidates.stream().sorted(Comparator.<Candidate>comparingDouble(c -> score(c, interests, languages)).reversed()
                .thenComparing(Comparator.<Candidate>comparingDouble(c -> interest(c.deck(), interests)).reversed())
                .thenComparing(Comparator.<Candidate>comparingDouble(c -> language(c.deck(), languages)).reversed())
                .thenComparing(c -> c.deck().firstListedAt(), Comparator.reverseOrder()).thenComparing(c -> c.deck().publicCode())).toList();
        List<PublicDeckRow> decks = ordered.stream().map(Candidate::deck).toList();
        List<PublicDeckRow> exploration = decks.stream().filter(deck -> (!top.contains(deck.topicId()) && !top.contains(deck.parentTopicId()))
                || !deck.firstListedAt().isBefore(now.minus(Duration.ofDays(30)))).toList();
        List<PublicDeckRow> result = fullShelf(decks, exploration);
        if (result == null) result = diverse(decks);
        if (result.size() < 4) return new Result(List.of(), null);
        return new Result(List.copyOf(result), explanation(result, interests));
    }

    /** Reserve the highest-priority exploration pair that still permits ten strictly diverse exploit cards. */
    private static List<PublicDeckRow> fullShelf(List<PublicDeckRow> ordered, List<PublicDeckRow> exploration) {
        if (ordered.size() < 12 || maximumSize(ordered, 0, new HashMap<>(), new HashMap<>(), 10) < 10) return null;
        for (int first = 0; first < exploration.size(); first++) {
            for (int second = first + 1; second < exploration.size(); second++) {
                PublicDeckRow a = exploration.get(first);
                PublicDeckRow b = exploration.get(second);
                List<PublicDeckRow> remaining = ordered.stream().filter(deck -> !deck.deckId().equals(a.deckId()) && !deck.deckId().equals(b.deckId())).toList();
                if (maximumSize(remaining, 0, new HashMap<>(), new HashMap<>(), 10) < 10) continue;
                List<PublicDeckRow> result = diverse(remaining);
                result.add(a);
                result.add(b);
                return result;
            }
        }
        return null;
    }

    /** Strongest current interest actually contributing to an allowed card, including a directory parent. */
    static String explanation(List<PublicDeckRow> decks, Map<String, Double> interests) {
        Set<String> contributed = new HashSet<>();
        decks.forEach(deck -> {
            if (deck.topicId() != null) contributed.add(deck.topicId());
            if (deck.parentTopicId() != null) contributed.add(deck.parentTopicId());
        });
        return interests.entrySet().stream().filter(entry -> contributed.contains(entry.getKey()))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey)).map(Map.Entry::getKey).findFirst().orElse(null);
    }

    /**
     * The lexicographically strongest maximum-cardinality subset, capped at ten. Taking an edge is safe only when
     * the remaining suffix can complete the target; a greedy author/topic counter alone misses crossed assignments.
     */
    static List<PublicDeckRow> diverse(List<PublicDeckRow> ordered) {
        Map<UUID, Integer> authors = new HashMap<>();
        Map<String, Integer> topics = new HashMap<>();
        List<PublicDeckRow> result = new ArrayList<>();
        int target = maximumSize(ordered, 0, authors, topics, 10);
        for (int index = 0; index < ordered.size() && result.size() < target; index++) {
            PublicDeckRow deck = ordered.get(index);
            if (authors.getOrDefault(deck.ownerId(), 0) >= 2 || topics.getOrDefault(deck.topicId(), 0) >= 2) continue;
            authors.merge(deck.ownerId(), 1, Integer::sum);
            topics.merge(deck.topicId(), 1, Integer::sum);
            int needed = target - result.size() - 1;
            if (maximumSize(ordered, index + 1, authors, topics, needed) >= needed) {
                result.add(deck);
            } else {
                authors.merge(deck.ownerId(), -1, Integer::sum);
                topics.merge(deck.topicId(), -1, Integer::sum);
            }
        }
        return result;
    }

    /** One unit-capacity edge per deck, capacity two per author and topic; at most ten augmenting paths. */
    private static int maximumSize(List<PublicDeckRow> ordered, int start, Map<UUID, Integer> usedAuthors, Map<String, Integer> usedTopics, int limit) {
        if (limit == 0) return 0;
        Map<UUID, Integer> authors = new java.util.LinkedHashMap<>();
        Map<String, Integer> topics = new java.util.LinkedHashMap<>();
        for (int index = start; index < ordered.size(); index++) {
            PublicDeckRow deck = ordered.get(index);
            if (usedAuthors.getOrDefault(deck.ownerId(), 0) >= 2 || usedTopics.getOrDefault(deck.topicId(), 0) >= 2) continue;
            authors.computeIfAbsent(deck.ownerId(), ignored -> authors.size() + 2);
            topics.putIfAbsent(deck.topicId(), 0);
        }
        int next = authors.size() + 2;
        for (Map.Entry<String, Integer> topic : topics.entrySet()) topic.setValue(next++);
        Flow flow = new Flow(next);
        authors.forEach((author, node) -> flow.edge(0, node, 2 - usedAuthors.getOrDefault(author, 0)));
        topics.forEach((topic, node) -> flow.edge(node, 1, 2 - usedTopics.getOrDefault(topic, 0)));
        for (int index = start; index < ordered.size(); index++) {
            PublicDeckRow deck = ordered.get(index);
            Integer author = authors.get(deck.ownerId());
            Integer topic = topics.get(deck.topicId());
            if (author != null && topic != null) flow.edge(author, topic, 1);
        }
        return flow.maximum(limit);
    }

    /** Small residual network rather than an exponential subset search; reverse arcs repair earlier crossed choices. */
    private static final class Flow {
        private static final class Arc {
            final int to;
            int capacity;
            Arc reverse;

            Arc(int to, int capacity) {
                this.to = to;
                this.capacity = capacity;
            }
        }

        private final List<List<Arc>> graph;

        Flow(int nodes) {
            graph = new ArrayList<>(nodes);
            for (int node = 0; node < nodes; node++) graph.add(new ArrayList<>());
        }

        void edge(int from, int to, int capacity) {
            Arc forward = new Arc(to, capacity);
            Arc reverse = new Arc(from, 0);
            forward.reverse = reverse;
            reverse.reverse = forward;
            graph.get(from).add(forward);
            graph.get(to).add(reverse);
        }

        int maximum(int limit) {
            int count = 0;
            int[] previous = new int[graph.size()];
            int[] queue = new int[graph.size()];
            Arc[] path = new Arc[graph.size()];
            while (count < limit) {
                java.util.Arrays.fill(previous, -1);
                previous[0] = 0;
                queue[0] = 0;
                int read = 0;
                int written = 1;
                while (read < written && previous[1] == -1) {
                    int node = queue[read++];
                    for (Arc arc : graph.get(node)) {
                        if (arc.capacity == 0 || previous[arc.to] != -1) continue;
                        previous[arc.to] = node;
                        path[arc.to] = arc;
                        queue[written++] = arc.to;
                        if (arc.to == 1) break;
                    }
                }
                if (previous[1] == -1) return count;
                for (int node = 1; node != 0; node = previous[node]) {
                    path[node].capacity--;
                    path[node].reverse.capacity++;
                }
                count++;
            }
            return count;
        }
    }
}
