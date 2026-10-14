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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community/3 (#450), the daily anomaly query on a database of its own: the threshold edges (19 against 20 copies, 70 % against more than 70 %, in exact arithmetic), the window
 * (inclusive at its start), which decks can be flagged (public ones, listed or not), the figures of a flag, and the dismissal rule: a dismissed deck is raised again only by copies made
 * AFTER the dismissal that cross the threshold on their own. The constants are the ones of {@code contracts/community/catalog.json}.
 */
@SpringBootTest
class CatalogAnomalyIntegrationTest extends IsolatedCatalogDatabase {
    private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");

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
    @Autowired private AnomalyRepository repository;
    private CatalogWorlds worlds;
    private TestClock clock;
    private CatalogAnomalyJob job;
    private World deck;

    @BeforeEach
    void setUp() {
        worlds = new CatalogWorlds(decks, items, exercises, sessions, media, storage, jdbc, transactions, publication, moderation);
        reset(jdbc);
        clock = new TestClock(NOW);
        job = new CatalogAnomalyJob(repository, AnomalySettings.defaults(), new SimpleMeterRegistry(), clock);
        deck = publicDeck(10, true);
    }

    private World publicDeck(int materials, boolean exercise) {
        World world = worlds.deck("Колода " + UUID.randomUUID().toString().substring(0, 8), "Описание", materials);
        if (exercise) worlds.exercise(world.materials().getFirst(), ExerciseType.FREE_RESPONSE);
        worlds.publish(world);
        worlds.project(projector, world.deck());
        return world;
    }

    /** {@code count} copies of {@code world} first made {@code age} before NOW, {@code fresh} of them by new accounts. */
    private void forks(World world, int count, int fresh, Duration age) {
        for (int index = 0; index < count; index++) {
            jdbc.sql("INSERT INTO app_learning.deck_engagement(deck_id, account_id, kind, first_at, last_at, trust, new_account) VALUES (:d, :a, 'FORK', :at, :at, 1.0, :fresh)")
                    .param("d", world.deck()).param("a", UUID.randomUUID()).param("at", java.sql.Timestamp.from(NOW.minus(age))).param("fresh", index < fresh).update();
        }
    }

    private int run() {
        return new TransactionTemplate(transactions).execute(status -> ((app.mnema.learning.platform.jobs.Slice.Done) job.run(
                new Job(UUID.randomUUID(), CatalogAnomalyJob.QUEUE, null, JsonNodeFactory.instance.objectNode(), null, 0, 1))).progressCount() > 0 ? 1 : 0);
    }

    private Optional<AnomalyRepository.Flag> flag(World world) {
        return repository.page(null, 100).stream().filter(row -> row.deckId().equals(world.deck())).findFirst();
    }

    private void clear() {
        jdbc.sql("DELETE FROM app_learning.catalog_anomaly").update();
        jdbc.sql("DELETE FROM app_learning.deck_engagement").update();
    }

    // ---- the threshold ----

    @Test
    void aDeckIsFlaggedAtTwentyCopiesInTheWindowAndMoreThanSeventyPercentFromNewAccounts() {
        record Case(int forks, int fresh, boolean flagged, double share) { }
        for (Case c : List.of(
                new Case(19, 19, false, 0), new Case(20, 14, false, 0), new Case(20, 15, true, 0.75), new Case(20, 20, true, 1.0), new Case(21, 15, true, 0.71),
                new Case(100, 70, false, 0), new Case(100, 71, true, 0.71), new Case(30, 0, false, 0), new Case(25, 18, true, 0.72), new Case(25, 17, false, 0))) {
            clear();
            forks(deck, c.forks(), c.fresh(), Duration.ofDays(1));
            run();
            assertThat(flag(deck).isPresent()).as("%s", c).isEqualTo(c.flagged());
            if (c.flagged()) {
                AnomalyRepository.Flag flag = flag(deck).orElseThrow();
                assertThat(flag.windowForks()).isEqualTo(c.forks());
                assertThat(flag.newShare().doubleValue()).isCloseTo(c.share(), org.assertj.core.api.Assertions.within(0.005));
                assertThat(flag.detectedAt()).isEqualTo(NOW);
                assertThat(flag.dismissedAt()).isNull();
                assertThat(flag.ownerId()).isEqualTo(deck.owner());
                assertThat(flag.code()).isEqualTo(jdbc.sql("SELECT public_code FROM app_learning.public_deck WHERE deck_id = :d").param("d", deck.deck()).query(String.class).single());
            }
        }
    }

    @Test
    void onlyTheCopiesOfTheLastSevenDaysCountAndTheStartOfTheWindowIsInclusive() {
        forks(deck, 12, 12, Duration.ofDays(1));
        forks(deck, 8, 8, Duration.ofDays(7).plusSeconds(1));     // just outside
        run();
        assertThat(flag(deck)).as("12 in the window").isEmpty();
        forks(deck, 8, 8, Duration.ofDays(7));                    // exactly at the start of the window: inside
        run();
        assertThat(flag(deck)).hasValueSatisfying(flag -> assertThat(flag.windowForks()).isEqualTo(20));
    }

    @Test
    void onlyPublicDecksAreFlaggedWhateverTheirSizeAndAHiddenDeckIsNot() {
        World small = publicDeck(3, false);
        forks(deck, 25, 25, Duration.ofDays(1));
        forks(small, 25, 25, Duration.ofDays(1));
        World hidden = publicDeck(10, true);
        forks(hidden, 25, 25, Duration.ofDays(1));
        worlds.hide(hidden);
        // copies of a deck that is not in the catalogue at all (a private deck copied through a link that has since closed)
        UUID gone = UUID.randomUUID();
        for (int index = 0; index < 25; index++) {
            jdbc.sql("INSERT INTO app_learning.deck_engagement(deck_id, account_id, kind, first_at, last_at, trust, new_account) VALUES (:d, :a, 'FORK', :at, :at, 1.0, TRUE)")
                    .param("d", gone).param("a", UUID.randomUUID()).param("at", java.sql.Timestamp.from(NOW.minus(Duration.ofDays(1)))).update();
        }
        run();
        assertThat(flag(deck)).isPresent();
        assertThat(flag(small)).as("a deck below the catalogue threshold is public and can be flagged").isPresent();
        assertThat(flag(hidden)).isEmpty();
        assertThat(repository.page(null, 100)).hasSize(2);
    }

    // ---- the life of a flag ----

    @Test
    void anOpenFlagKeepsItsDateAndRefreshesItsFiguresAndIsNeverClearedByTheJob() {
        forks(deck, 20, 20, Duration.ofDays(1));
        run();
        assertThat(flag(deck)).hasValueSatisfying(flag -> assertThat(flag.windowForks()).isEqualTo(20));
        clock.advance(Duration.ofDays(1));
        forks(deck, 10, 10, Duration.ZERO);
        run();
        assertThat(flag(deck)).hasValueSatisfying(flag -> {
            assertThat(flag.windowForks()).isEqualTo(30);
            assertThat(flag.detectedAt()).as("the date of the first detection").isEqualTo(NOW);
        });
        // the burst is over (all copies leave the window): the flag stays for the owner to decide
        clock.advance(Duration.ofDays(30));
        run();
        assertThat(flag(deck)).hasValueSatisfying(flag -> {
            assertThat(flag.dismissedAt()).isNull();
            assertThat(flag.windowForks()).isEqualTo(30);
        });
    }

    @Test
    void aDismissedDeckIsRaisedAgainOnlyByNewerCopiesThatCrossTheThresholdOnTheirOwn() {
        forks(deck, 25, 25, Duration.ofDays(2));
        run();
        Instant dismissedAt = NOW.plus(Duration.ofHours(10));
        assertThat(dismiss(dismissedAt)).isTrue();
        assertThat(dismiss(dismissedAt.plusSeconds(5))).as("dismissing twice changes nothing").isFalse();
        assertThat(flag(deck)).hasValueSatisfying(flag -> assertThat(flag.dismissedAt()).isEqualTo(dismissedAt));

        // the next days: the dismissed burst still is in the window, but it was before the dismissal
        clock.set(NOW.plus(Duration.ofDays(1)));
        run();
        assertThat(flag(deck)).hasValueSatisfying(flag -> assertThat(flag.dismissedAt()).isEqualTo(dismissedAt));
        // 19 new copies after the dismissal: not a window of its own
        forksAfter(19, 19, dismissedAt.plusSeconds(60));
        run();
        assertThat(flag(deck)).hasValueSatisfying(flag -> assertThat(flag.dismissedAt()).isEqualTo(dismissedAt));
        // 31 after it, but a third of them by ordinary accounts (19 of 31 is 61 %): no
        forksAfter(12, 0, dismissedAt.plusSeconds(120));
        run();
        assertThat(flag(deck)).hasValueSatisfying(flag -> assertThat(flag.dismissedAt()).isEqualTo(dismissedAt));
        // 15 more new ones: 34 new of 46 after the dismissal (74 %): raised again, with a new date and the new figures (the old burst is not in them)
        forksAfter(15, 15, dismissedAt.plusSeconds(180));
        clock.set(NOW.plus(Duration.ofDays(2)));
        run();
        AnomalyRepository.Flag again = flag(deck).orElseThrow();
        assertThat(again.dismissedAt()).isNull();
        assertThat(again.detectedAt()).isEqualTo(NOW.plus(Duration.ofDays(2)));
        assertThat(again.windowForks()).isEqualTo(46);
        assertThat(again.newShare().doubleValue()).isCloseTo(34.0 / 46, org.assertj.core.api.Assertions.within(0.005));
    }

    private boolean dismiss(Instant at) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(status -> repository.dismiss(deck.deck(), at)));
    }

    private Optional<AnomalyRepository.Flag> lock(String code) {
        return new TransactionTemplate(transactions).execute(status -> repository.lockByCode(code));
    }

    private void forksAfter(int count, int fresh, Instant at) {
        for (int index = 0; index < count; index++) {
            jdbc.sql("INSERT INTO app_learning.deck_engagement(deck_id, account_id, kind, first_at, last_at, trust, new_account) VALUES (:d, :a, 'FORK', :at, :at, 1.0, :fresh)")
                    .param("d", deck.deck()).param("a", UUID.randomUUID()).param("at", java.sql.Timestamp.from(at)).param("fresh", index < fresh).update();
        }
    }

    @Test
    void aFlagOfADeckThatLeftTheCatalogueKeepsItsLastCodeAndTitle() {
        forks(deck, 22, 22, Duration.ofDays(1));
        run();
        String code = flag(deck).orElseThrow().code();
        String title = flag(deck).orElseThrow().title();
        worlds.hide(deck);
        AnomalyRepository.Flag after = flag(deck).orElseThrow();
        assertThat(after.code()).isEqualTo(code);
        assertThat(after.title()).isEqualTo(title);
        assertThat(after.ownerId()).isEqualTo(deck.owner());
        assertThat(lock(code)).isPresent();
    }

    @Test
    void theConstantsAreTheOnesOfTheSharedContractFile() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.exists(root.resolve("contracts/community/catalog.json"))) root = root.getParent();
        assertThat(root).isNotNull();
        var constants = JsonMapper.builder().build().readTree(java.nio.file.Files.readString(root.resolve("contracts/community/catalog.json"))).path("constants").path("anomaly");
        AnomalySettings defaults = AnomalySettings.defaults();
        assertThat(defaults.minForks).isEqualTo(constants.path("minForks").intValue());
        assertThat(defaults.windowDays).isEqualTo(constants.path("windowDays").intValue());
        assertThat(defaults.newShareAbove).isEqualByComparingTo(new BigDecimal(constants.path("newShareAbove").asString()));
        // Identity's side of the same contract (48 hours) is the shared claims file
        var claims = JsonMapper.builder().build().readTree(java.nio.file.Files.readString(root.resolve("contracts/identity/user-info-claims.json")));
        assertThat(claims.path("newAccount").path("maxAgeHours").intValue()).isEqualTo(constants.path("newAccountHours").intValue());
    }
}
