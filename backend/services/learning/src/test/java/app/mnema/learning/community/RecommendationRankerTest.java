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
    @Test void explorationPairMustLeaveAFeasibleTenCardPrefix() {
        List<RecommendationRanker.Candidate> candidates = new ArrayList<>();
        String[] topics = {"spanish", "spanish", "english", "english", "english", "english", "sql-data", "sql-data", "math", "math", "law", "law"};
        for (int index = 0; index < topics.length; index++) {
            candidates.add(deck("code" + index, topics[index], null, UUID.randomUUID(), null, 100 - index, true));
        }
        var result = RecommendationRanker.rank(candidates, Map.of("spanish", 1.0, "english", 0.8), Set.of(), NOW);
        assertThat(result.decks()).hasSize(12);
        assertThat(result.decks().stream().limit(10).collect(java.util.stream.Collectors.groupingBy(PublicDeckRow::topicId, java.util.stream.Collectors.counting())))
                .containsOnlyKeys("spanish", "english", "sql-data", "math", "law").allSatisfy((topic, count) -> assertThat(count).isEqualTo(2));
        assertThat(result.decks().subList(10, 12)).allSatisfy(row -> assertThat(row.topicId()).isEqualTo("english"));
        assertThat(result.decks().stream().map(PublicDeckRow::deckId)).doesNotHaveDuplicates();
    }

    @Test void augmentingAssignmentsRepairCrossedAuthorsAndTopicsWithoutRelaxingLimits() {
        UUID authorA = UUID.randomUUID(), authorB = UUID.randomUUID();
        List<PublicDeckRow> rows = List.of(
                deck("a-x", "x", null, authorA, null, 6, false).deck(),
                deck("a-y1", "y", null, authorA, null, 5, false).deck(),
                deck("a-y2", "y", null, authorA, null, 4, false).deck(),
                deck("b-x1", "x", null, authorB, null, 3, false).deck(),
                deck("b-x2", "x", null, authorB, null, 2, false).deck());
        assertThat(RecommendationRanker.diverse(rows).stream().map(PublicDeckRow::publicCode)).containsExactly("a-y1", "a-y2", "b-x1", "b-x2");
    }

    @Test void boundedMatchingAgreesWithExhaustiveCardinalityAndCanonicalPriorityOracle() {
        var random = new java.util.Random(8127);
        List<UUID> authors = java.util.stream.IntStream.range(0, 6).mapToObj(index -> UUID.randomUUID()).toList();
        for (int fixture = 0; fixture < 40; fixture++) {
            List<PublicDeckRow> rows = new ArrayList<>();
            for (int index = 0; index < 12; index++) rows.add(deck("oracle" + index, "topic" + random.nextInt(6), null, authors.get(random.nextInt(6)), null, 100 - index, false).deck());
            int best = 0, cardinality = 0;
            for (int mask = 1; mask < 1 << rows.size(); mask++) {
                int size = Integer.bitCount(mask);
                if (size > 10 || size < cardinality) continue;
                Map<UUID, Integer> authorCounts = new HashMap<>();
                Map<String, Integer> topicCounts = new HashMap<>();
                boolean valid = true;
                for (int index = 0; index < rows.size(); index++) {
                    if ((mask & (1 << (rows.size() - index - 1))) == 0) continue;
                    PublicDeckRow row = rows.get(index);
                    if (authorCounts.merge(row.ownerId(), 1, Integer::sum) > 2 || topicCounts.merge(row.topicId(), 1, Integer::sum) > 2) { valid = false; break; }
                }
                if (valid && (size > cardinality || mask > best)) { cardinality = size; best = mask; }
            }
            List<PublicDeckRow> expected = new ArrayList<>();
            for (int index = 0; index < rows.size(); index++) if ((best & (1 << (rows.size() - index - 1))) != 0) expected.add(rows.get(index));
            assertThat(RecommendationRanker.diverse(rows)).as("matching fixture %s", fixture).containsExactlyElementsOf(expected);
        }
    }

}
