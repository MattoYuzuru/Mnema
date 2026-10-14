package app.mnema.learning.community;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.exercise.ExerciseType;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.community.CatalogWorlds.World;
import app.mnema.learning.library.DeckModeration;
import app.mnema.learning.library.DeckVisibility;
import app.mnema.learning.library.PublicationService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.jobs.Slice;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Community/1 (#448): {@code public_deck} is projected from the PUBLISHED revision of a deck by the job {@code catalog.project}, never in the publication transaction. These tests run
 * on a real PostgreSQL with the production services: the author's commands (publication, moderation, deletion) write their outbox row and job in their own transaction, and the
 * handler runs slice by slice in a transaction of the test's own, so the order of the jobs is the test's. The acceptance of the issue: a deck below the threshold has a row that is not
 * {@code listed}; lowering the level, hiding and deleting remove the row; un-hiding and raising bring it back with the same {@code first_listed_at}.
 */
@SpringBootTest
class PublicDeckProjectionIntegrationTest extends PostgresIntegrationTest {
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
    @Autowired private CatalogRepository catalog;
    @Autowired private CatalogOutbox outbox;
    @Autowired private ProjectionRepository projection;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meters;
    private CatalogWorlds worlds;

    @BeforeEach
    void worlds() {
        worlds = new CatalogWorlds(decks, items, exercises, sessions, media, storage, jdbc, transactions, publication, moderation);
        // no executor runs here; jobs left by other test classes of the JVM would only slow the tests that count jobs
        jdbc.sql("UPDATE app_learning.learning_job SET state = 'CANCELLED', finished_at = clock_timestamp() WHERE queue LIKE 'catalog.%' AND state = 'QUEUED'").update();
    }

    private PublicDeckRow row(World world) {
        return catalog.find(world.deck()).orElseThrow(() -> new AssertionError("no row for " + world.deck()));
    }

    private boolean hasRow(World world) {
        return catalog.find(world.deck()).isPresent();
    }

    private List<String> jobKeys(World world) {
        return jdbc.sql("SELECT dedupe_key FROM app_learning.learning_job WHERE queue = 'catalog.project' AND payload ->> 'deckId' = :deck ORDER BY job_id")
                .param("deck", world.deck().toString()).query(String.class).list();
    }

    /** The date the catalogue remembers for the deck, whether or not it has a row right now. */
    private Instant listedAt(World world) {
        return jdbc.sql("SELECT first_listed_at FROM app_learning.catalog_listing WHERE deck_id = :d").param("d", world.deck())
                .query((rs, n) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant()).optional().orElse(null);
    }

    private List<Instant> publicEvents(World world) {
        return jdbc.sql("SELECT occurred_at FROM app_learning.deck_publication_event WHERE deck_id = :deck AND visibility = 'PUBLIC' ORDER BY occurred_at, event_id")
                .param("deck", world.deck()).query((rs, n) -> rs.getTimestamp(1).toInstant()).list();
    }

    /** A deck of {@code materials} plain materials and {@code exercises} FREE_RESPONSE exercises, published PUBLIC. */
    private World publicDeck(String title, String description, int materials, int exercises) {
        World world = worlds.deck(title, description, materials);
        for (int index = 0; index < exercises; index++) worlds.exercise(world.materials().get(index), ExerciseType.FREE_RESPONSE);
        worlds.publish(world);
        return world;
    }

    // ---- the projection of one public deck ----

    @Test
    void aPublicPublishedDeckGetsOneRowWithEveryColumnFromItsPublishedRevision() {
        World world = worlds.deck("Español básico", "Глаголы испанского языка для начинающих", 12);
        worlds.exercise(world.materials().get(0), ExerciseType.FREE_RESPONSE);
        worlds.exercise(world.materials().get(1), ExerciseType.CHOICE);
        worlds.publish(world, DeckVisibility.PUBLIC, CatalogWorlds.metadata("испанский", "español"));

        // the publication wrote its outbox row and ONE job in its own transaction; nothing is projected yet
        List<Instant> events = publicEvents(world);
        assertThat(events).hasSize(1);
        assertThat(jobKeys(world)).hasSize(1).allSatisfy(key -> assertThat(key).startsWith(world.deck() + ":"));
        assertThat(hasRow(world)).isFalse();

        assertThat(worlds.project(projector, world.deck())).isEqualTo(1);
        PublicDeckRow row = row(world);
        assertThat(row.deckId()).isEqualTo(world.deck());
        assertThat(row.ownerId()).isEqualTo(world.owner());
        assertThat(row.publicCode()).isEqualTo(jdbc.sql("SELECT public_code FROM app_learning.deck_publication WHERE deck_id = :d").param("d", world.deck()).query(String.class).single());
        assertThat(row.publishedRevisionId()).isEqualTo(worlds.publishedRevision(world));
        assertThat(row.title()).isEqualTo("Español básico");
        assertThat(row.description()).isEqualTo("Глаголы испанского языка для начинающих");
        assertThat(row.topicId()).isEqualTo("spanish");
        assertThat(row.parentTopicId()).isEqualTo("languages");
        assertThat(row.contentLanguage()).isEqualTo("ru");
        assertThat(row.targetLanguage()).isEqualTo("es");
        assertThat(row.level()).isEqualTo("A2");
        assertThat(row.tags()).containsExactly("испанский", "español");
        assertThat(row.itemCount()).isEqualTo(12);
        assertThat(row.exerciseCount()).isEqualTo(2);
        assertThat(row.mediaMask()).isZero();
        assertThat(row.mechanics()).containsExactlyInAnyOrder(CatalogMechanic.WRITTEN, CatalogMechanic.CHOICE);
        assertThat(row.listed()).isTrue();
        assertThat(row.firstListedAt()).as("the day the projection found the deck listed").isNotNull().isEqualTo(listedAt(world)).isAfterOrEqualTo(events.getFirst());
        assertThat(row.contentUpdatedAt()).isEqualTo(jdbc.sql("SELECT published_at FROM app_learning.deck_publication WHERE deck_id = :d").param("d", world.deck())
                .query((rs, n) -> rs.getTimestamp(1).toInstant()).single());
        assertThat(row.titleNorm()).isEqualTo("espanol basico");
        assertThat(row.projectedRevisionId()).isEqualTo(row.publishedRevisionId());
        assertThat(row.projectedAt()).isNotNull();
    }

    @Test
    void theGeneratedVectorsCarryTheWeightsAndTheSimpleVectorFoldsMarks() {
        World world = worlds.deck("Español básico", "Глаголы испанского языка", 12);
        worlds.exercise(world.materials().getFirst(), ExerciseType.FREE_RESPONSE);
        worlds.publish(world, DeckVisibility.PUBLIC, CatalogWorlds.metadata("испанский", "español"));
        worlds.project(projector, world.deck());

        // the vectors are exactly the definition: weights A title, B description, C tags, russian stemming / simple_unaccent
        assertThat(jdbc.sql("""
                SELECT search_ru = setweight(to_tsvector('russian', title), 'A') || setweight(to_tsvector('russian', description), 'B')
                                   || setweight(to_tsvector('russian', array_to_string(tags, ' ')), 'C')
                   AND search_simple = setweight(to_tsvector('app_learning.simple_unaccent', title), 'A') || setweight(to_tsvector('app_learning.simple_unaccent', description), 'B')
                                   || setweight(to_tsvector('app_learning.simple_unaccent', array_to_string(tags, ' ')), 'C')
                  FROM app_learning.public_deck WHERE deck_id = :d""").param("d", world.deck()).query(Boolean.class).single()).isTrue();
        // the tag is a lexeme of its own weight C, the title word weight A
        String russian = jdbc.sql("SELECT search_ru::text FROM app_learning.public_deck WHERE deck_id = :d").param("d", world.deck()).query(String.class).single();
        assertThat(russian).contains("'испанск':").containsPattern("'глагол':\\d+B").containsPattern("'испанск':[0-9A-D,]*C");
        // «Español» and «espanol» meet in the simple vector (title and tag); the stemmed russian one matches «испанском» to «испанский»
        assertThat(matches(world, "search_simple", "app_learning.simple_unaccent", "espanol")).isTrue();
        assertThat(matches(world, "search_simple", "app_learning.simple_unaccent", "basico")).isTrue();
        assertThat(matches(world, "search_ru", "russian", "испанском")).isTrue();
        assertThat(matches(world, "search_ru", "russian", "математика")).isFalse();
        assertThat(jdbc.sql("SELECT title_norm FROM app_learning.public_deck WHERE deck_id = :d").param("d", world.deck()).query(String.class).single()).isEqualTo("espanol basico");
    }

    private boolean matches(World world, String column, String configuration, String query) {
        return jdbc.sql("SELECT " + column + " @@ websearch_to_tsquery('" + configuration + "', :q) FROM app_learning.public_deck WHERE deck_id = :d")
                .param("q", query).param("d", world.deck()).query(Boolean.class).single();
    }

    @Test
    void theCatalogueThresholdIsTenMaterialsOneExerciseAndADescription() {
        record Case(int materials, int exercises, String description, boolean listed) { }
        for (Case c : List.of(new Case(9, 1, "Описание", false), new Case(10, 1, "Описание", true), new Case(10, 0, "Описание", false),
                new Case(1, 0, "Описание", false), new Case(11, 2, "Описание", true))) {
            World world = publicDeckNoPublish(c.materials(), c.exercises(), c.description());
            worlds.publish(world);
            worlds.project(projector, world.deck());
            // a deck below the threshold keeps its row (the author's page shows it) but is not listed
            assertThat(row(world).listed()).as("%s", c).isEqualTo(c.listed());
            // only a listed deck has entered the catalogue, so only it has a date
            assertThat(row(world).firstListedAt() != null).as("%s", c).isEqualTo(c.listed());
        }
        // a blank description is below the threshold, though the owner's command never lets such a deck become public: the revision was published at LINK
        World blank = publicDeckNoPublish(10, 1, "");
        worlds.publish(blank, DeckVisibility.LINK, CatalogWorlds.metadata());
        jdbc.sql("UPDATE app_learning.deck_publication SET visibility = 'PUBLIC', row_version = row_version + 1 WHERE deck_id = :d").param("d", blank.deck()).update();
        worlds.project(projector, blank.deck());
        assertThat(row(blank).listed()).isFalse();
        assertThat(row(blank).description()).isEmpty();
        // 'space only' is blank too
        jdbc.sql("UPDATE app_learning.public_deck SET description = '   ' WHERE deck_id = :d").param("d", blank.deck()).update();
        assertThat(row(blank).listed()).isFalse();
    }

    private World publicDeckNoPublish(int materials, int exercises, String description) {
        World world = worlds.deck("Колода " + materials + "/" + exercises, description, materials);
        for (int index = 0; index < exercises; index++) worlds.exercise(world.materials().get(index), ExerciseType.FREE_RESPONSE);
        return world;
    }

    // ---- level, moderation, deletion ----

    @Test
    void loweringTheLevelRemovesTheRowAtOnceAndRaisingItBringsItBackWithTheSameFirstListedDate() {
        World world = publicDeck("Колода уровня", "Описание", 10, 1);
        worlds.project(projector, world.deck());
        Instant first = row(world).firstListedAt();
        assertThat(first).isEqualTo(listedAt(world));

        worlds.setLevel(world, DeckVisibility.LINK);
        assertThat(jobKeys(world)).as("one job per effective change").hasSize(2);
        assertThat(hasRow(world)).as("no executor runs here: the command itself took the deck out of the catalogue").isFalse();
        worlds.project(projector, world.deck());
        assertThat(hasRow(world)).as("by-link decks are not in the catalogue").isFalse();
        assertThat(listedAt(world)).as("the catalogue remembers the date while the deck has no row").isEqualTo(first);

        worlds.setLevel(world, DeckVisibility.PUBLIC);
        assertThat(hasRow(world)).as("coming back is the job's work").isFalse();
        worlds.project(projector, world.deck());
        assertThat(publicEvents(world)).hasSize(2);
        assertThat(row(world).firstListedAt()).as("the date of the FIRST time, not of the latest promotion").isEqualTo(first).isNotEqualTo(publicEvents(world).get(1));
        assertThat(row(world).listed()).isTrue();

        worlds.setLevel(world, DeckVisibility.PRIVATE);
        assertThat(hasRow(world)).isFalse();
        worlds.project(projector, world.deck());
        assertThat(hasRow(world)).isFalse();
    }

    @Test
    void aRowThatWasRemovedAndCreatedAgainKeepsTheListingDate() {
        World world = publicDeck("Колода дат", "Описание", 10, 1);
        worlds.project(projector, world.deck());
        Instant first = row(world).firstListedAt();
        jdbc.sql("DELETE FROM app_learning.public_deck WHERE deck_id = :d").param("d", world.deck()).update();
        worlds.project(projector, world.deck());
        assertThat(row(world).firstListedAt()).isEqualTo(first);
    }

    @Test
    void aDeckEntersTheCatalogueWhenItIsFirstListedAndTogglingNeverMovesItUp() {
        // a public deck below the threshold has a row but no date: it has not entered «Новое»
        World late = publicDeckNoPublish(9, 1, "Описание");
        worlds.publish(late);
        worlds.project(projector, late.deck());
        assertThat(row(late).listed()).isFalse();
        assertThat(row(late).firstListedAt()).isNull();
        assertThat(listedAt(late)).isNull();
        World early = publicDeck("Раньше", "Описание", 10, 1);
        worlds.project(projector, early.deck());
        Instant earlyDate = row(early).firstListedAt();

        // it reaches the threshold later: the date is that moment, later than the other deck's
        worlds.addMaterial(late);
        worlds.publish(late);
        worlds.project(projector, late.deck());
        Instant lateDate = row(late).firstListedAt();
        assertThat(lateDate).isNotNull().isAfter(earlyDate);

        // the older deck is hidden and shown, lowered and raised: it must not climb above the newer one
        worlds.hide(early);
        worlds.project(projector, early.deck());
        worlds.unhide(early);
        worlds.project(projector, early.deck());
        worlds.setLevel(early, DeckVisibility.LINK);
        worlds.setLevel(early, DeckVisibility.PUBLIC);
        worlds.project(projector, early.deck());
        assertThat(row(early).firstListedAt()).isEqualTo(earlyDate);
        List<UUID> order = catalog.listedPage(null, null, 1_000).stream().map(PublicDeckRow::deckId).toList();
        assertThat(order).containsSubsequence(late.deck(), early.deck());

        // and a republication with the deck below the threshold again cannot take the date away
        assertThat(listedAt(late)).isEqualTo(lateDate);
    }

    @Test
    void hidingRemovesTheDeckFromTheCatalogueAtOnceAndUnhidingBringsItBack() {
        World world = publicDeck("Скрываемая", "Описание", 10, 1);
        worlds.project(projector, world.deck());
        Instant first = row(world).firstListedAt();
        int jobs = jobKeys(world).size();

        worlds.hide(world);
        assertThat(jobKeys(world)).as("the hiding transaction also enqueued the projection, the backstop").hasSize(jobs + 1);
        assertThat(hasRow(world)).as("gone when the transaction committed, with no executor running").isFalse();
        worlds.project(projector, world.deck());
        assertThat(hasRow(world)).isFalse();

        worlds.unhide(world);
        assertThat(jobKeys(world)).hasSize(jobs + 2);
        assertThat(hasRow(world)).isFalse();
        worlds.project(projector, world.deck());
        assertThat(row(world).firstListedAt()).isEqualTo(first);
        assertThat(row(world).projectedRevisionId()).as("the scan result came back with the row").isEqualTo(worlds.publishedRevision(world));
    }

    @Test
    void deletingTheDeckRemovesItsRowAtOnceAndAnUnpublishedDeckLeavesNoJob() {
        World world = publicDeck("Удаляемая", "Описание", 10, 1);
        worlds.project(projector, world.deck());
        int jobs = jobKeys(world).size();
        worlds.delete(world);
        assertThat(hasRow(world)).as("gone when the transaction committed, with no executor running").isFalse();
        assertThat(jobKeys(world)).hasSize(jobs + 1).last().isEqualTo(world.deck() + ":deleted");
        worlds.project(projector, world.deck());
        assertThat(hasRow(world)).isFalse();

        World privateDeck = worlds.deck("Личная", "Описание", 1);
        worlds.delete(privateDeck);
        assertThat(jobKeys(privateDeck)).as("a deck that never had a published revision needs no job").isEmpty();

        // a deck that was public once and has a published revision still gets the backstop job
        World lowered = publicDeck("Была публичной", "Описание", 10, 1);
        worlds.project(projector, lowered.deck());
        worlds.setLevel(lowered, DeckVisibility.PRIVATE);
        int before = jobKeys(lowered).size();
        worlds.delete(lowered);
        assertThat(jobKeys(lowered)).hasSize(before + 1);
    }

    @Test
    void aDeletedDeckThatAJobMeetsLateIsRemovedByAnyJob() {
        World world = publicDeck("Поздняя", "Описание", 10, 1);
        worlds.project(projector, world.deck());
        // a deletion with its job lost: any later projection of the deck converges anyway
        jdbc.sql("UPDATE app_learning.deck SET deleted_at = statement_timestamp() WHERE deck_id = :d").param("d", world.deck()).update();
        worlds.project(projector, world.deck());
        assertThat(hasRow(world)).isFalse();
    }

    // ---- the published revision, not the head ----

    @Test
    void aRepublicationUpdatesTitleCountsAndDateAndAnEditOfTheHeadChangesNothing() {
        World world = publicDeck("Первая версия", "Описание", 10, 1);
        worlds.project(projector, world.deck());
        PublicDeckRow before = row(world);

        // the head moves (a new title, a new material, a new exercise) but nothing is published: the row is the same row
        worlds.rename(world, "Правка головы", "Другое описание");
        StudyFixtures.Material added = worlds.addMaterial(world);
        worlds.exercise(added, ExerciseType.ORDER);
        worlds.project(projector, world.deck());
        assertThat(row(world)).as("the projection reads the published revision, not the head").isEqualTo(before);

        worlds.publish(world);
        worlds.project(projector, world.deck());
        PublicDeckRow after = row(world);
        assertThat(after.title()).isEqualTo("Правка головы");
        assertThat(after.description()).isEqualTo("Другое описание");
        assertThat(after.itemCount()).isEqualTo(11);
        assertThat(after.exerciseCount()).isEqualTo(2);
        assertThat(after.contentUpdatedAt()).isAfter(before.contentUpdatedAt());
        assertThat(after.firstListedAt()).isEqualTo(before.firstListedAt());
        assertThat(after.publishedRevisionId()).isNotEqualTo(before.publishedRevisionId()).isEqualTo(worlds.publishedRevision(world));
        assertThat(after.projectedRevisionId()).isEqualTo(after.publishedRevisionId());
        assertThat(after.mechanics()).containsExactlyInAnyOrder(CatalogMechanic.WRITTEN, CatalogMechanic.ORDER);
        assertThat(after.titleNorm()).isEqualTo("правка головы");
    }

    @Test
    void aMetadataOnlyEditRewritesTheRowButScansNoManifest() {
        World world = publicDeck("Метаданные", "Описание", 10, 1);
        worlds.project(projector, world.deck());
        PublicDeckRow before = row(world);
        worlds.setLevel(world, DeckVisibility.PUBLIC, CatalogWorlds.metadata("другой тег"));
        // the revision is the same, so the scan is not repeated: the first slice ends the job
        Slice slice = worlds.run(projector, CatalogWorlds.job(world.deck(), null, 0));
        assertThat(slice).isInstanceOf(Slice.Done.class);
        PublicDeckRow after = row(world);
        assertThat(after.tags()).containsExactly("другой тег");
        assertThat(after.projectedRevisionId()).isEqualTo(before.projectedRevisionId());
        assertThat(after.mechanicsMask()).isEqualTo(before.mechanicsMask());
        assertThat(after.contentUpdatedAt()).isEqualTo(before.contentUpdatedAt());
    }

    @Test
    void replayingAJobChangesNothingNotEvenTheProjectionTime() {
        World world = publicDeck("Повтор", "Описание", 10, 1);
        worlds.project(projector, world.deck());
        PublicDeckRow before = row(world);
        long xmin = jdbc.sql("SELECT xmin::text::bigint FROM app_learning.public_deck WHERE deck_id = :d").param("d", world.deck()).query(Long.class).single();
        worlds.project(projector, world.deck());
        worlds.project(projector, world.deck());
        assertThat(row(world)).isEqualTo(before);
        assertThat(jdbc.sql("SELECT xmin::text::bigint FROM app_learning.public_deck WHERE deck_id = :d").param("d", world.deck()).query(Long.class).single())
                .as("no new row version").isEqualTo(xmin);
    }

    // ---- media and mechanics ----

    @Test
    void theMediaBitsFollowTheMaterialsOfThePublishedRevisionAndNotTheExercises() {
        World images = worlds.deck("С картинками", "Описание", 10);
        worlds.addMediaMaterial(images, "image", worlds.study.readyAsset(images.owner(), "image/png"));
        worlds.exercise(images.materials().getFirst(), ExerciseType.FREE_RESPONSE);
        worlds.publish(images);
        worlds.project(projector, images.deck());
        assertThat(row(images).media()).containsExactly(CatalogMedia.IMAGE);

        World sounds = worlds.deck("Со звуком", "Описание", 10);
        worlds.addMediaMaterial(sounds, "audio", worlds.study.readyAsset(sounds.owner(), "audio/mpeg"));
        worlds.addMediaMaterial(sounds, "video", worlds.study.readyAsset(sounds.owner(), "video/mp4"));
        worlds.exercise(sounds.materials().getFirst(), ExerciseType.FREE_RESPONSE);
        worlds.publish(sounds);
        worlds.project(projector, sounds.deck());
        assertThat(row(sounds).media()).containsExactlyInAnyOrder(CatalogMedia.AUDIO, CatalogMedia.VIDEO);
        assertThat(row(sounds).mediaMask()).isEqualTo(CatalogMedia.AUDIO.bit() | CatalogMedia.VIDEO.bit());

        // a sound that only an EXERCISE plays is not shown to a guest, so it sets no media bit, but it makes the deck «на слух»
        World exerciseOnly = worlds.deck("Диктант", "Описание", 10);
        worlds.listening(exerciseOnly.materials().getFirst(), worlds.study.readyAsset(exerciseOnly.owner(), "audio/mpeg"));
        worlds.publish(exerciseOnly);
        worlds.project(projector, exerciseOnly.deck());
        assertThat(row(exerciseOnly).mediaMask()).isZero();
        assertThat(row(exerciseOnly).mechanics()).containsExactlyInAnyOrder(CatalogMechanic.LISTENING, CatalogMechanic.WRITTEN);

        // an upload that is not READY shows nothing yet
        World pending = worlds.deck("Загружается", "Описание", 10);
        worlds.addMediaMaterial(pending, "image", worlds.study.pendingAsset(pending.owner()));
        worlds.exercise(pending.materials().getFirst(), ExerciseType.FREE_RESPONSE);
        worlds.publish(pending);
        worlds.project(projector, pending.deck());
        assertThat(row(pending).mediaMask()).isZero();
    }

    @Test
    void everyMechanicSetsItsBitAndAFlashcardSetsNone() {
        for (ExerciseType type : ExerciseType.values()) {
            World world = worlds.deck("Механика " + type, "Описание", 10);
            worlds.exercise(world.materials().getFirst(), type);
            worlds.publish(world);
            worlds.project(projector, world.deck());
            int expected = CatalogMechanic.maskOf(type, false);
            assertThat(row(world).mechanicsMask()).as(type.name()).isEqualTo(expected);
            assertThat(row(world).projectedRevisionId()).as("%s: a deck with no matching exercise is still scanned", type).isEqualTo(row(world).publishedRevisionId());
        }
        // all of them in one deck, and a disabled exercise advertises nothing
        World all = worlds.deck("Все механики", "Описание", 10);
        int index = 0;
        for (ExerciseType type : ExerciseType.values()) worlds.exercise(all.materials().get(index++), type);
        worlds.listening(all.materials().get(index++), worlds.study.readyAsset(all.owner(), "audio/mpeg"));
        worlds.exercise(all.materials().get(index), ExerciseType.SELF_CHECK, false);
        worlds.publish(all);
        worlds.project(projector, all.deck());
        assertThat(row(all).mechanicsMask()).isEqualTo(CatalogMechanic.ALL);
        assertThat(row(all).mechanics()).containsExactlyInAnyOrder(CatalogMechanic.values());

        World disabled = worlds.deck("Выключено", "Описание", 10);
        worlds.exercise(disabled.materials().getFirst(), ExerciseType.ORDER, false);
        worlds.publish(disabled);
        worlds.project(projector, disabled.deck());
        assertThat(row(disabled).mechanicsMask()).isZero();
        assertThat(row(disabled).exerciseCount()).isEqualTo(1);
    }

    @Test
    void theMediaBitsAreComputedOnlyWhenThePublishedRevisionChanged() {
        World world = worlds.deck("Медиа по ревизии", "Описание", 10);
        worlds.exercise(world.materials().getFirst(), ExerciseType.FREE_RESPONSE);
        worlds.publish(world);
        worlds.project(projector, world.deck());
        assertThat(row(world).mediaMask()).isZero();

        // a value only a stale remembered answer could give: a tag edit must reuse it, not look at the assets again
        jdbc.sql("UPDATE app_learning.catalog_listing SET media_mask = 5 WHERE deck_id = :d").param("d", world.deck()).update();
        worlds.setLevel(world, DeckVisibility.PUBLIC, CatalogWorlds.metadata("другой тег"));
        worlds.project(projector, world.deck());
        assertThat(row(world).mediaMask()).isEqualTo(5);
        // and a row made again takes it from the listing at once
        worlds.hide(world);
        worlds.unhide(world);
        worlds.project(projector, world.deck());
        assertThat(row(world).mediaMask()).isEqualTo(5);

        // a new published revision looks at the assets again
        worlds.addMaterial(world);
        worlds.publish(world);
        worlds.project(projector, world.deck());
        assertThat(row(world).mediaMask()).isZero();
        assertThat(jdbc.sql("SELECT media_revision_id FROM app_learning.catalog_listing WHERE deck_id = :d").param("d", world.deck()).query(UUID.class).single())
                .isEqualTo(worlds.publishedRevision(world));
    }

    @Test
    void theLagGaugeCountsOnlyRowsThatAreBehindTheirRevisionForTenMinutes() {
        World world = publicDeck("Отстающая", "Описание", 10, 1);
        worlds.project(projector, world.deck());
        long[] clock = {0};
        CatalogLagGauge gauge = new CatalogLagGauge(projection, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), () -> clock[0]);
        double baseline = gauge.value();

        // behind its revision but only just: not yet counted
        jdbc.sql("UPDATE app_learning.public_deck SET projected_revision_id = NULL, projected_at = now() WHERE deck_id = :d").param("d", world.deck()).update();
        clock[0] += java.util.concurrent.TimeUnit.MINUTES.toNanos(2);
        assertThat(gauge.value()).isEqualTo(baseline);
        // behind for eleven minutes: counted, but the answer is cached for a minute
        jdbc.sql("UPDATE app_learning.public_deck SET projected_at = now() - interval '11 minutes' WHERE deck_id = :d").param("d", world.deck()).update();
        clock[0] += java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        assertThat(gauge.value()).as("cached for a minute").isEqualTo(baseline);
        clock[0] += java.util.concurrent.TimeUnit.SECONDS.toNanos(31);
        assertThat(gauge.value()).isEqualTo(baseline + 1);
        // caught up: not counted
        worlds.project(projector, world.deck());
        clock[0] += java.util.concurrent.TimeUnit.MINUTES.toNanos(2);
        assertThat(gauge.value()).isEqualTo(baseline);
        // the one registered in the application is there too, and an unreadable database is NaN, not zero
        assertThat(meters.get(CatalogLagGauge.NAME).gauge().value()).isGreaterThanOrEqualTo(0);
        CatalogLagGauge broken = new CatalogLagGauge(new ProjectionRepository(jdbc) {
            @Override
            long lagging(java.time.Duration age) {
                throw new org.springframework.dao.DataAccessResourceFailureException("down");
            }
        }, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), () -> 0L);
        assertThat(broken.value()).isNaN();
    }

    // ---- jobs of one deck in any order ----

    @Test
    void jobsOfOneDeckConvergeWhateverTheOrderAndWhoeverRunsThemTogether() throws Exception {
        World world = publicDeck("Гонка", "Описание", 10, 1);
        // an old job meets a newer state: the state wins
        worlds.setLevel(world, DeckVisibility.LINK);
        worlds.project(projector, world.deck());
        worlds.project(projector, world.deck());
        assertThat(hasRow(world)).isFalse();

        worlds.setLevel(world, DeckVisibility.PUBLIC);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> runs = new ArrayList<>();
            for (int thread = 0; thread < 4; thread++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return worlds.project(projector, world.deck());
                }));
            }
            start.countDown();
            for (Future<Integer> run : runs) run.get();
        } catch (ExecutionException failure) {
            throw new AssertionError("a concurrent projection failed", failure.getCause());
        } finally {
            pool.shutdownNow();
        }
        PublicDeckRow row = row(world);
        assertThat(row.projectedRevisionId()).isEqualTo(row.publishedRevisionId());
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.public_deck WHERE deck_id = :d").param("d", world.deck()).query(Long.class).single()).isOne();
        assertThat(row.mechanics()).containsExactly(CatalogMechanic.WRITTEN);
    }

    @Test
    void aDeckPublishedAtTheSameInstantAsItsHidingEndsAsTheLastStateSays() {
        World world = publicDeck("Скрытие и показ", "Описание", 10, 1);
        worlds.hide(world);
        worlds.unhide(world);
        worlds.hide(world);
        // three jobs of three states; the last state is "hidden" whichever runs last
        worlds.project(projector, world.deck());
        worlds.project(projector, world.deck());
        assertThat(hasRow(world)).isFalse();
        worlds.unhide(world);
        worlds.project(projector, world.deck());
        assertThat(hasRow(world)).isTrue();
    }

    // ---- the outbox ----

    @Test
    void theOutboxNeedsATransactionAndEveryChangeHasItsOwnJobKey() {
        World world = publicDeck("Исходящие", "Описание", 10, 1);
        assertThatThrownBy(() -> outbox.publicationChanged(world.deck(), worlds.publishedRevision(world), DeckVisibility.PUBLIC)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> outbox.deckDeleted(world.deck())).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> outbox.withdrawn(world.deck())).isInstanceOf(IllegalTransactionStateException.class);

        // an unchanged command writes nothing: no event, no job
        int events = publicEvents(world).size();
        int jobs = jobKeys(world).size();
        worlds.setLevel(world, DeckVisibility.PUBLIC, CatalogWorlds.metadata("испанский"));
        assertThat(publicEvents(world)).hasSize(events);
        assertThat(jobKeys(world)).hasSize(jobs);

        // an effective change of a public deck (a tag) writes one event and one job with a key of its own
        worlds.setLevel(world, DeckVisibility.PUBLIC, CatalogWorlds.metadata("новый тег"));
        assertThat(publicEvents(world)).hasSize(events + 1);
        assertThat(jobKeys(world)).hasSize(jobs + 1).doesNotHaveDuplicates();
    }

    @Test
    void aFailedPublicationLeavesNeitherEventNorJob() {
        World world = publicDeck("Откат", "Описание", 10, 1);
        int events = publicEvents(world).size();
        int jobs = jobKeys(world).size();
        // a stale version: the command is refused before anything is written
        UUID head = UUID.fromString(decks.read(world.owner(), world.deck()).path("revisionId").stringValue(null));
        assertThatThrownBy(() -> publication.save(world.owner(), world.deck(), 99,
                new app.mnema.learning.library.PublicationCommand(UUID.randomUUID(), DeckVisibility.PUBLIC, CatalogWorlds.metadata("x"), true,
                        new app.mnema.learning.library.PublicationCommand.Publish(head, null)), true)).isInstanceOf(RuntimeException.class);
        assertThat(publicEvents(world)).hasSize(events);
        assertThat(jobKeys(world)).hasSize(jobs);
    }

    // ---- the reads ----

    @Test
    void theListedPageIsNewestFirstAndSkipsDecksBelowTheThreshold() {
        World first = publicDeck("Первая", "Описание", 10, 1);
        World small = publicDeck("Маленькая", "Описание", 3, 1);
        World second = publicDeck("Вторая", "Описание", 10, 1);
        World third = publicDeck("Третья", "Описание", 10, 1);
        for (World world : List.of(first, small, second, third)) worlds.project(projector, world.deck());

        List<UUID> seen = new ArrayList<>();
        Instant afterTime = null;
        UUID afterDeck = null;
        for (int pages = 0; pages < 1_000; pages++) {
            List<PublicDeckRow> page = catalog.listedPage(afterTime, afterDeck, 2);
            if (page.isEmpty()) break;
            assertThat(page).allSatisfy(row -> assertThat(row.listed()).isTrue());
            page.forEach(row -> seen.add(row.deckId()));
            afterTime = page.getLast().firstListedAt();
            afterDeck = page.getLast().deckId();
        }
        assertThat(seen).doesNotHaveDuplicates().doesNotContain(small.deck());
        assertThat(seen).containsSubsequence(third.deck(), second.deck(), first.deck());

        // the author's page shows the small deck too, newest publication first
        UUID author = small.owner();
        assertThat(catalog.ownerPage(author, null, null, 10)).extracting(PublicDeckRow::deckId).containsExactly(small.deck());
        assertThat(catalog.ownerPage(author, Instant.now().plusSeconds(60), UUID.randomUUID(), 10)).hasSize(1);
        assertThat(catalog.ownerPage(author, catalog.find(small.deck()).orElseThrow().contentUpdatedAt(), small.deck(), 10)).isEmpty();
        assertThat(catalog.ownerPage(author, null, null, 10).getFirst().listed()).isFalse();
        assertThat(catalog.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void theWireVocabularyOfTheFiltersIsDefinedInOnePlace() {
        assertThat(Set.of(CatalogMechanic.values())).extracting(CatalogMechanic::wire).containsExactlyInAnyOrder("listening", "matching", "written", "choice", "order");
        assertThat(CatalogMechanic.fromWire("matching")).contains(CatalogMechanic.MATCHING);
        assertThat(CatalogMechanic.fromWire("MATCHING")).isEmpty();
        assertThat(Set.of(CatalogMedia.values())).extracting(CatalogMedia::wire).containsExactlyInAnyOrder("image", "audio", "video");
        assertThat(CatalogMedia.fromWire("video")).contains(CatalogMedia.VIDEO);
        assertThat(CatalogMedia.fromWire("pdf")).isEmpty();
    }
}
