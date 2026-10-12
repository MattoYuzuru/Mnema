package app.mnema.learning.community;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.exercise.ExerciseType;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.community.CatalogWorlds.World;
import app.mnema.learning.library.DeckModeration;
import app.mnema.learning.library.PublicationService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.Slice;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Community/3 (#450), the rank job on a database of its own: the formula of RFC 11.2 against a fixture computed BY HAND (three listed decks and one public deck below the threshold,
 * a fixed clock), the ranks and percentiles of listed decks only, the raw audience counts, the owner's engagement ignored, the generation switch and its 24-hour retention (the newest
 * completed generation always kept), the browse indexes, an empty catalogue, and the read side {@link CatalogRanks}.
 */
@SpringBootTest
class CatalogRankIntegrationTest extends IsolatedCatalogDatabase {
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private PublicationService publication;
    @Autowired private DeckModeration moderation;
    @Autowired private CatalogProjector projector;
    @Autowired private RankRepository repository;
    @Autowired private CatalogRanks ranks;
    private CatalogWorlds worlds;
    private TestClock clock;
    private CatalogRank job;

    @BeforeEach
    void setUp() {
        worlds = new CatalogWorlds(decks, items, exercises, sessions, media, storage, jdbc, transactions, publication, moderation);
        reset(jdbc);
        clock = new TestClock(NOW);
        job = new CatalogRank(repository, RankSettings.defaults(), new SimpleMeterRegistry(), clock);
    }

    // ---- fixtures ----

    /** A public deck of {@code materials} materials (a listed one has one exercise too), projected, with the dates and the topic the test wants. */
    private World publicDeck(int materials, boolean exercise, Instant contentUpdatedAt, String topic, String parent) {
        World world = worlds.deck("Колода " + UUID.randomUUID().toString().substring(0, 8), "Описание", materials);
        if (exercise) worlds.exercise(world.materials().getFirst(), ExerciseType.FREE_RESPONSE);
        worlds.publish(world);
        worlds.project(projector, world.deck());
        jdbc.sql("UPDATE app_learning.public_deck SET content_updated_at = :at, topic_id = :topic, parent_topic_id = :parent WHERE deck_id = :d")
                .param("at", java.sql.Timestamp.from(contentUpdatedAt)).param("topic", topic).param("parent", parent).param("d", world.deck()).update();
        return world;
    }

    private void fork(World world, UUID account, Instant at, float trust) {
        jdbc.sql("INSERT INTO app_learning.deck_engagement(deck_id, account_id, kind, first_at, last_at, trust, new_account) VALUES (:d, :a, 'FORK', :at, :at, :trust, FALSE)")
                .param("d", world.deck()).param("a", account).param("at", java.sql.Timestamp.from(at)).param("trust", trust).update();
    }

    private void study(World world, UUID account, Instant first, Instant last, int days) {
        jdbc.sql("INSERT INTO app_learning.deck_engagement(deck_id, account_id, kind, first_at, last_at, study_days, last_study_date) VALUES (:d, :a, 'STUDY', :first, :last, :days, CAST(:last AS date))")
                .param("d", world.deck()).param("a", account).param("first", java.sql.Timestamp.from(first)).param("last", java.sql.Timestamp.from(last)).param("days", days).update();
    }

    private static Instant ago(double days) {
        return NOW.minusMillis((long) (days * 86_400_000L));
    }

    private long generate() {
        Slice slice = new TransactionTemplate(transactions).execute(status -> job.run(new Job(UUID.randomUUID(), CatalogRank.QUEUE, null, JsonNodeFactory.instance.objectNode(), null, 0, 1)));
        assertThat(slice).isInstanceOf(Slice.Done.class);
        return jdbc.sql("SELECT max(generation) FROM app_learning.rank_generation").query(Long.class).single();
    }

    private record Rank(double score, Integer rank, Integer learningRank, float popPct, int added, int learningNow, int activated, String topic, String parent, boolean listed) { }

    private Rank rank(long generation, World world) {
        return jdbc.sql("SELECT score, rank, learning_rank, pop_pct, added_count, learning_now_count, activated_count, topic_id, parent_topic_id, listed FROM app_learning.deck_rank WHERE generation = :g AND deck_id = :d")
                .param("g", generation).param("d", world.deck())
                .query((row, ignored) -> new Rank(row.getDouble("score"), (Integer) row.getObject("rank"), (Integer) row.getObject("learning_rank"), row.getFloat("pop_pct"),
                        row.getInt("added_count"), row.getInt("learning_now_count"), row.getInt("activated_count"), row.getString("topic_id"), row.getString("parent_topic_id"),
                        row.getBoolean("listed"))).single();
    }

    // ---- the formula, by hand ----

    /**
     * Clock 2026-10-12T12:00Z. Constants of RFC 11.2: window 30 days, half-life 14, activation at 3 study days, weights 1 and 2, Q prior (10 copies at 0.25), freshness 0.6 + 0.4 x 2^(-d/120).
     * <pre>
     * deck A (listed, updated now, fresh 1.0), owner o:
     *   u1  FORK 14 d ago, trust 1.0;  STUDY 3 days, last 7 d ago   fork30 = 1, activated30 = 1: c = 1 x (1 + 2) x 2^(-7/14)        = 3 / sqrt 2         = 2.1213203436
     *   u2  FORK 28 d ago, trust 0.25                               fork30 = 1:                  c = 0.25 x 1 x 2^(-28/14)       = 0.25 x 0.25        = 0.0625
     *   u3  FORK 40 d ago, trust 1.0;  STUDY 5 days, last 1 d ago   fork30 = 0, activated30 = 1: c = 1 x 2 x 2^(-1/14)             = 2 x 0.9516951      = 1.9033903060
     *   u4  FORK 3 d ago,  trust 1.0;  STUDY 2 days, last 3 d ago   fork30 = 1, not activated:   c = 1 x 1 x 2^(-3/14)             = 0.8619728212
     *   o   (the owner) FORK now, trust 1.0                          never counted
     *   S = 4.9491834708;  forks = 4, activated = 2 (u1, u3), learning now = 3 (u1, u3, u4)
     *   Q = (2 + 10 x 0.25) / (4 + 10) = 4.5 / 14 = 0.3214285714;   score = ln(1 + S) x (0.5 + Q) x 1.0 = 1.4648157679
     * deck B (listed, updated 120 d ago, fresh 0.6 + 0.4 x 0.5 = 0.8):
     *   u1  FORK 1 d ago, trust 1.0: c = 2^(-1/14) = 0.9516951;   u5 FORK now, trust 0.25: c = 0.25
     *   S = 1.2016951530; forks = 2, activated = 0, learning now = 0;  Q = 2.5 / 12 = 0.2083333333;   score = ln(1 + S) x (0.5 + Q) x 0.8 = 0.4472289664
     * deck C (listed, updated 240 d ago, fresh 0.7): no engagement: S = 0, score = 0
     * deck U (PUBLIC, below the threshold, updated now): u1 FORK 2 d ago, trust 1.0: S = 2^(-2/14) = 0.9057236643; Q = 2.5 / 11; score = 0.4689904092; not ranked
     * </pre>
     */
    @Test
    void theScoreRanksPercentilesAndCountsAreTheFormulaOfTheRfcComputedByHand() {
        World a = publicDeck(10, true, NOW, "spanish", "languages");
        World b = publicDeck(10, true, ago(120), "spanish", "languages");
        World c = publicDeck(10, true, ago(240), "japanese", "languages");
        World u = publicDeck(3, false, NOW, "spanish", "languages");
        UUID u1 = UUID.randomUUID(), u2 = UUID.randomUUID(), u3 = UUID.randomUUID(), u4 = UUID.randomUUID(), u5 = UUID.randomUUID();
        fork(a, u1, ago(14), 1.0f);
        study(a, u1, ago(10), ago(7), 3);
        fork(a, u2, ago(28), 0.25f);
        fork(a, u3, ago(40), 1.0f);
        study(a, u3, ago(35), ago(1), 5);
        fork(a, u4, ago(3), 1.0f);
        study(a, u4, ago(3), ago(3), 2);
        fork(a, a.owner(), NOW, 1.0f);
        study(a, a.owner(), NOW, NOW, 9);
        fork(b, u1, ago(1), 1.0f);
        fork(b, u5, NOW, 0.25f);
        fork(u, u1, ago(2), 1.0f);

        long generation = generate();
        assertThat(generation).isEqualTo(1);
        Rank ra = rank(generation, a), rb = rank(generation, b), rc = rank(generation, c), ru = rank(generation, u);
        assertThat(ra.score()).isCloseTo(1.4648157679276934, within(1e-9));
        assertThat(rb.score()).isCloseTo(0.44722896639660575, within(1e-9));
        assertThat(rc.score()).isZero();
        assertThat(ru.score()).isCloseTo(0.46899040923488505, within(1e-9));
        // raw audience counts: distinct non-owner accounts
        assertThat(List.of(ra.added(), ra.learningNow(), ra.activated())).containsExactly(4, 3, 2);
        assertThat(List.of(rb.added(), rb.learningNow(), rb.activated())).containsExactly(2, 0, 0);
        assertThat(List.of(rc.added(), rc.learningNow(), rc.activated())).containsExactly(0, 0, 0);
        assertThat(List.of(ru.added(), ru.learningNow(), ru.activated())).containsExactly(1, 0, 0);
        // listed decks are ranked by score, then by deck id (newest first); the deck below the threshold has a row and no rank
        assertThat(List.of(ra.rank(), rb.rank(), rc.rank())).containsExactly(1, 2, 3);
        assertThat(List.of(ra.listed(), rb.listed(), rc.listed(), ru.listed())).containsExactly(true, true, true, false);
        assertThat(ru.rank()).isNull();
        assertThat(ru.learningRank()).isNull();
        assertThat(ru.popPct()).isZero();
        // «Учат сейчас»: A (3), then B before C by score
        assertThat(List.of(ra.learningRank(), rb.learningRank(), rc.learningRank())).containsExactly(1, 2, 3);
        // percent rank of the score among the three listed decks
        assertThat(List.of(ra.popPct(), rb.popPct(), rc.popPct())).containsExactly(1.0f, 0.5f, 0.0f);
        // the browse columns are copied from public_deck
        assertThat(List.of(ra.topic(), rc.topic())).containsExactly("spanish", "japanese");
        assertThat(ra.parent()).isEqualTo("languages");
        // the generation is complete and current
        assertThat(jdbc.sql("SELECT deck_count FROM app_learning.rank_generation WHERE generation = 1").query(Integer.class).single()).isEqualTo(4);
        assertThat(ranks.current()).hasValue(1);
        assertThat(ranks.counts(1, List.of(a.deck(), b.deck(), u.deck(), UUID.randomUUID()))).containsOnlyKeys(a.deck(), b.deck(), u.deck())
                .containsEntry(a.deck(), new CatalogRanks.Counts(4, 3)).containsEntry(u.deck(), new CatalogRanks.Counts(1, 0));
    }

    @Test
    void tiesAreBrokenByDeckIdAndAnAccountWithOnlyAStudyRowCountsAsUntrusted() {
        World older = publicDeck(10, true, NOW, "spanish", "languages");
        World newer = publicDeck(10, true, NOW, "spanish", "languages");
        long generation = generate();
        // no engagement at all: score 0 everywhere, so the order is the one of deck_id DESC (PostgreSQL's byte order of the uuid)
        List<UUID> byRank = jdbc.sql("SELECT deck_id FROM app_learning.deck_rank WHERE generation = :g ORDER BY rank").param("g", generation).query(UUID.class).list();
        List<UUID> byId = jdbc.sql("SELECT deck_id FROM app_learning.deck_rank WHERE generation = :g ORDER BY deck_id DESC").param("g", generation).query(UUID.class).list();
        assertThat(byRank).hasSize(2).containsExactlyElementsOf(byId).containsExactlyInAnyOrder(older.deck(), newer.deck());
        assertThat(rank(generation, newer).popPct()).isZero();
        assertThat(rank(generation, older).popPct()).isZero();
        // a STUDY row with no FORK row (it should not happen) weighs 0.25 and counts as activated, not as added
        UUID learner = UUID.randomUUID();
        study(older, learner, ago(5), ago(2), 4);
        long second = generate();
        Rank row = rank(second, older);
        assertThat(row.added()).isZero();
        assertThat(row.activated()).isEqualTo(1);
        assertThat(row.learningNow()).isEqualTo(1);
        // S = 0.25 x 2 x 2^(-2/14); Q = (1 + 2.5) / (0 + 10); fresh 1
        double s = 0.25 * 2 * Math.pow(2, -2.0 / 14);
        assertThat(row.score()).isCloseTo(Math.log(1 + s) * (0.5 + 3.5 / 10), within(1e-9));
        assertThat(row.rank()).isEqualTo(1);
    }

    @Test
    void theHiddenAndWithdrawnDecksHaveNoRowAndAnEmptyCatalogueGivesAnEmptyGeneration() {
        assertThat(ranks.current()).isEmpty();
        long empty = generate();
        assertThat(jdbc.sql("SELECT deck_count FROM app_learning.rank_generation WHERE generation = :g").param("g", empty).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_rank WHERE generation = :g").param("g", empty).query(Long.class).single()).isZero();
        assertThat(ranks.current()).hasValue(empty);

        World hidden = publicDeck(10, true, NOW, "spanish", "languages");
        World shown = publicDeck(10, true, NOW, "spanish", "languages");
        fork(hidden, UUID.randomUUID(), ago(1), 1.0f);
        worlds.hide(hidden);
        long generation = generate();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_rank WHERE generation = :g AND deck_id = :d").param("g", generation).param("d", hidden.deck()).query(Long.class).single()).isZero();
        assertThat(rank(generation, shown).rank()).isEqualTo(1);
    }

    // ---- generations ----

    @Test
    void aGenerationLivesTwentyFourHoursAfterItsCompletionAndTheNewestCompletedOneIsAlwaysKept() {
        World deck = publicDeck(10, true, NOW, "spanish", "languages");
        fork(deck, UUID.randomUUID(), ago(1), 1.0f);
        long first = generate();                         // 12:00
        clock.advance(Duration.ofHours(1));
        long second = generate();                        // 13:00
        clock.set(NOW.plus(Duration.ofHours(23).plusMinutes(30)));
        long third = generate();                         // next day 11:30: the first (12:00) is 23.5 h old
        assertThat(kept()).containsExactly(first, second, third);
        assertThat(ranks.current()).hasValue(third);
        assertThat(rowsOf(first)).isEqualTo(1);

        clock.set(NOW.plus(Duration.ofHours(24).plusMinutes(30)));
        long fourth = generate();                        // next day 12:30: the first is 24.5 h old, the second (13:00) 23.5 h
        assertThat(kept()).containsExactly(second, third, fourth);
        assertThat(rowsOf(first)).as("the rows of a deleted generation follow it").isZero();
        assertThat(ranks.kept(first)).isFalse();
        assertThat(ranks.kept(second)).isTrue();

        clock.set(NOW.plus(Duration.ofDays(30)));
        long fifth = generate();
        assertThat(kept()).containsExactly(fifth);
        // the newest completed generation survives even when nothing newer comes and it is far past the retention
        new TransactionTemplate(transactions).executeWithoutResult(status -> repository.prune(NOW.plus(Duration.ofDays(900)), Duration.ofHours(24)));
        assertThat(kept()).containsExactly(fifth);
        assertThat(ranks.current()).hasValue(fifth);
        assertThat(rowsOf(fifth)).isEqualTo(1);
    }

    @Test
    void aGenerationThatIsNotCompletedIsNeverCurrentAndIsRemovedWhenItIsOld() {
        World deck = publicDeck(10, true, NOW, "spanish", "languages");
        long good = generate();
        long half = jdbc.sql("INSERT INTO app_learning.rank_generation(generation, started_at) VALUES (:g, :at) RETURNING generation").param("g", good + 1)
                .param("at", java.sql.Timestamp.from(NOW)).query(Long.class).single();
        assertThat(ranks.current()).hasValue(good);
        assertThat(ranks.kept(half)).isFalse();
        clock.advance(Duration.ofHours(25));
        long next = generate();
        assertThat(kept()).containsExactly(next);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.rank_generation").query(Long.class).single()).isEqualTo(1);
        assertThat(deck).isNotNull();
    }

    @Test
    void settingsAreValidatedWhenTheyAreRead() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RankSettings(true, 0, 14, 3, 1, 2, 10, 0.25, 0.6, 120, Duration.ofHours(24))).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RankSettings(true, 30, 14, 3, 1, 2, 10, 1.5, 0.6, 120, Duration.ofHours(24))).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RankSettings(true, 30, 14, 3, 1, 2, 10, 0.25, 0.6, 120, Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AnomalySettings(true, 0, 7, "0.7", 3)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AnomalySettings(true, 20, 7, "1", 3)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AnomalySettings(true, 20, 7, "x", 3)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AnomalySettings(true, 20, 7, "0.7", 24)).isInstanceOf(IllegalArgumentException.class);
        assertThat(RankSettings.defaults().retention).isEqualTo(Duration.ofHours(24));
        assertThat(AnomalySettings.defaults().newShareAbove).isEqualByComparingTo("0.7");
    }

    private List<Long> kept() {
        return new ArrayList<>(jdbc.sql("SELECT generation FROM app_learning.rank_generation ORDER BY generation").query(Long.class).list());
    }

    private long rowsOf(long generation) {
        return jdbc.sql("SELECT count(*) FROM app_learning.deck_rank WHERE generation = :g").param("g", generation).query(Long.class).single();
    }

    // ---- the browse indexes ----

    @Test
    void everyBrowseQueryOfTheCatalogueIsServedByItsIndexInTheOrderOfTheQuery() {
        for (int index = 0; index < 3; index++) {
            publicDeck(10, true, NOW, index == 0 ? "japanese" : "spanish", "languages");
        }
        long generation = generate();
        assertThat(ranks.current()).hasValue(generation);
        Map<String, String> queries = new java.util.LinkedHashMap<>();
        queries.put("deck_rank_popular", "SELECT deck_id FROM app_learning.deck_rank WHERE generation = :g AND listed AND rank > 1 ORDER BY rank LIMIT 24");
        queries.put("deck_rank_learning", "SELECT deck_id FROM app_learning.deck_rank WHERE generation = :g AND listed ORDER BY learning_rank LIMIT 24");
        queries.put("deck_rank_topic", "SELECT deck_id FROM app_learning.deck_rank WHERE generation = :g AND listed AND topic_id = 'spanish' ORDER BY score DESC, deck_id DESC LIMIT 24");
        queries.put("deck_rank_parent_topic",
                "SELECT deck_id FROM app_learning.deck_rank WHERE generation = :g AND listed AND parent_topic_id = 'languages' ORDER BY score DESC, deck_id DESC LIMIT 24");
        queries.put("deck_rank_pkey", "SELECT deck_id, added_count, learning_now_count FROM app_learning.deck_rank WHERE generation = :g AND deck_id IN ('" + UUID.randomUUID() + "')");
        for (var entry : queries.entrySet()) {
            String plan = new TransactionTemplate(transactions).execute(status -> {
                // a table of a test is too small for the planner to prefer an index by cost: ask for the plan it would take if it were large
                jdbc.sql("SET LOCAL enable_seqscan = off").update();
                jdbc.sql("SET LOCAL enable_bitmapscan = off").update();
                String text = String.join("\n", jdbc.sql("EXPLAIN " + entry.getValue()).param("g", generation).query(String.class).list());
                status.setRollbackOnly();
                return text;
            });
            assertThat(plan).as(entry.getKey() + "\n" + plan).containsPattern("Index (Only )?Scan using " + entry.getKey()).doesNotContain("Seq Scan").doesNotContain("Sort");
        }
        // the inventory: nothing but the key and the four partial browse indexes
        assertThat(jdbc.sql("SELECT indexname FROM pg_indexes WHERE schemaname = 'app_learning' AND tablename = 'deck_rank' ORDER BY indexname").query(String.class).list())
                .containsExactly("deck_rank_learning", "deck_rank_parent_topic", "deck_rank_pkey", "deck_rank_popular", "deck_rank_topic");
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = 'app_learning' AND indexname = 'deck_rank_topic'").query(String.class).single())
                .contains("(generation, topic_id, score DESC, deck_id DESC) WHERE listed");
    }
}
