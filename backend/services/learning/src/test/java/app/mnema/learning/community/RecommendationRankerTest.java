package app.mnema.learning.community;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationRankerTest {
    static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    private static RecommendationRanker.Candidate deck(String code, String topic, String parent, UUID author, String language, double score, boolean fresh) {
        return new RecommendationRanker.Candidate(new PublicDeckRow(UUID.randomUUID(), code, author, UUID.randomUUID(), code, "Описание", topic, parent, "ru", language, null, List.of(), 20, 2, 0, 0, true, fresh ? NOW : NOW.minus(Duration.ofDays(60)), NOW, code, null, NOW), score);
    }

    @Test void exactFormulaAndUncertainLanguageDoesNotLowerAnyDeck() {
        var deck = deck("1111111111", "spanish", "languages", UUID.randomUUID(), "es", 2, false);
        assertThat(RecommendationRanker.score(deck, Map.of("spanish", 1.0), Set.of())).isEqualTo(3.6);
        assertThat(RecommendationRanker.score(deck, Map.of("spanish", 1.0), Set.of("es"))).isEqualTo(3.6);
        assertThat(RecommendationRanker.score(deck, Map.of("spanish", 1.0), Set.of("en"))).isCloseTo(1.08, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(RecommendationRanker.score(deck, Map.of("languages", 0.5), Set.of())).isEqualTo(2.8);
    }

    @Test void zeroEngagementKeepsInterestsBeforeUnrelatedTopicsWithoutChangingFormula() {
        List<RecommendationRanker.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 4; i++) candidates.add(deck("s" + i, "spanish", "languages", UUID.randomUUID(), "es", 0, false));
        for (String topic : List.of("anatomy", "english", "programming")) for (int i = 0; i < 2; i++) candidates.add(deck(topic + i, topic, null, UUID.randomUUID(), null, 0, false));
        var result = RecommendationRanker.rank(candidates, Map.of("spanish", 1.0), Set.of("es"), NOW);
        assertThat(RecommendationRanker.score(candidates.getFirst(), Map.of("spanish", 1.0), Set.of("es"))).isZero();
        assertThat(result.decks().getFirst().topicId()).isEqualTo("spanish");
    }

    @Test void zeroAndSparseInterestsHideShelf() {
        assertThat(RecommendationRanker.rank(List.of(), Map.of(), Set.of(), NOW).decks()).isEmpty();
        var deck = deck("1111111111", "spanish", "languages", UUID.randomUUID(), "es", 1, false);
        assertThat(RecommendationRanker.rank(List.of(deck), Map.of("spanish", 1.0), Set.of(), NOW).decks()).isEmpty();
    }

    @Test void strictAuthorAndTopicLimitsNeverRelaxEvenWhenThatLeavesTwo() {
        UUID author = UUID.randomUUID();
        List<RecommendationRanker.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 30; i++) candidates.add(deck(String.format(java.util.Locale.ROOT, "%010d", i), "spanish", "languages", author, "es", 30 - i, false));
        assertThat(RecommendationRanker.rank(candidates, Map.of("spanish", 1.0), Set.of(), NOW).decks()).isEmpty();
    }

    @Test void fullShelfReservesExplorationAtElevenAndTwelveAndUsesContributingExplanation() {
        List<RecommendationRanker.Candidate> candidates = new ArrayList<>();
        String[] topics = {
            "spanish", "english", "programming", "math", "business", "law", "anatomy"
        };
        int n = 0;
        for (String topic : topics) for (int j = 0; j < 4; j++) candidates.add(deck(String.format(java.util.Locale.ROOT, "%010d", ++n), topic, topic.equals("spanish") || topic.equals("english") ? "languages" : null, UUID.randomUUID(), null, 100 - n, topic.equals("spanish") && j < 2));
        var result = RecommendationRanker.rank(candidates, Map.of("spanish", 1.0, "english", 0.8, "programming", 0.7), Set.of(), NOW);
        assertThat(result.decks()).hasSize(12);
        assertThat(result.explanation()).isEqualTo("spanish");
        assertThat(result.decks().subList(10, 12)).allSatisfy(d -> assertThat(d.firstListedAt()).isEqualTo(NOW));
        Map<String, Integer> counts = new HashMap<>();
        result.decks().stream().limit(10).forEach(d -> counts.merge(d.topicId(), 1, Integer::sum));
        assertThat(counts.values()).allSatisfy(count -> assertThat(count).isLessThanOrEqualTo(2));
        assertThat(result.decks().stream().map(PublicDeckRow::deckId)).doesNotHaveDuplicates();
    }

    @Test void parentGoalExplainsLanguagesWithoutInventingSpanishPreference() {
        var candidates = List.of(deck("1111111111", "spanish", "languages", UUID.randomUUID(), "es", 1, false), deck("2222222222", "spanish", "languages", UUID.randomUUID(), "es", 1, false), deck("3333333333", "english", "languages", UUID.randomUUID(), "en", 1, false), deck("4444444444", "english", "languages", UUID.randomUUID(), "en", 1, false));
        var result = RecommendationRanker.rank(candidates, Map.of("languages", 1.0), Set.of(), NOW);
        assertThat(result.decks()).hasSize(4);
        assertThat(result.explanation()).isEqualTo("languages");
        assertThat(result.decks().stream().map(PublicDeckRow::publicCode)).containsExactly("1111111111", "2222222222", "3333333333", "4444444444");
    }

    @Test void explanationsUseTheStrongestTopicActuallyInTheShelf() {
        var candidates = List.of(deck("1111111111", "spanish", "languages", UUID.randomUUID(), null, 1, false), deck("2222222222", "spanish", "languages", UUID.randomUUID(), null, 1, false), deck("3333333333", "english", "languages", UUID.randomUUID(), null, 1, false), deck("4444444444", "english", "languages", UUID.randomUUID(), null, 1, false));
        assertThat(RecommendationRanker.rank(candidates, Map.of("anatomy", 1.0, "spanish", 0.8, "english", 0.7), Set.of(), NOW).explanation()).isEqualTo("spanish");
    }
}
