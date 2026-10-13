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
        List<Candidate> exploration = ordered.stream().filter(c -> (!top.contains(c.deck().topicId()) && !top.contains(c.deck().parentTopicId())) || !c.deck().firstListedAt().isBefore(now.minus(Duration.ofDays(30)))).limit(2).toList();
        Set<UUID> reserved = new HashSet<>();
        exploration.forEach(c -> reserved.add(c.deck().deckId()));
        List<PublicDeckRow> result = diverse(ordered.stream().filter(c -> !reserved.contains(c.deck().deckId())).map(Candidate::deck).toList());
        // Reserve positions 11/12 only for a full shelf; scarcity keeps a strictly diverse shorter shelf.
        if (result.size() == 10 && exploration.size() == 2) {
            for (Candidate candidate : exploration) result.add(candidate.deck());
        }
        else result = diverse(ordered.stream().map(Candidate::deck).toList());
        if (result.size() < 4) return new Result(List.of(), null);
        Set<String> contributed = new HashSet<>();
        result.forEach(d -> {
            contributed.add(d.topicId());  if (d.parentTopicId() != null) contributed.add(d.parentTopicId());
        });
        String explanation = interests.entrySet().stream().filter(e -> contributed.contains(e.getKey()))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey)).map(Map.Entry::getKey).findFirst().orElse(null);
        return new Result(List.copyOf(result), explanation);
    }

    static List<PublicDeckRow> diverse(List<PublicDeckRow> ordered) {
        Map<UUID, Integer> authors = new HashMap<>();
        Map<String, Integer> topics = new HashMap<>();
        List<PublicDeckRow> result = new ArrayList<>();
        for (PublicDeckRow deck : ordered) {
            if (authors.getOrDefault(deck.ownerId(), 0) >= 2 || topics.getOrDefault(deck.topicId(), 0) >= 2) continue;
            result.add(deck);
            authors.merge(deck.ownerId(), 1, Integer::sum);
            topics.merge(deck.topicId(), 1, Integer::sum);
            if (result.size() == 10) break;
        }

        return result;
    }
}
