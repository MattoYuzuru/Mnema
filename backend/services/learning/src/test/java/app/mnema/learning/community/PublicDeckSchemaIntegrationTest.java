package app.mnema.learning.community;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.exercise.ExerciseType;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.community.CatalogWorlds.World;
import app.mnema.learning.library.DeckModeration;
import app.mnema.learning.library.PublicationService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Community/1 (#448), migration {@code V67__public_deck.sql}: the shape of the table (generated columns, checks, keys), the indexes and the statements that use them. Every index must be
 * used by a query that exists now or that Community/2 and /6 will write; the plans are EXPLAINed with sequential scans disabled (a table of a test suite is too small for the planner to
 * prefer an index by cost, so the test proves that the index CAN serve the query and that nothing forces a scan), in a transaction that is rolled back.
 */
@SpringBootTest
class PublicDeckSchemaIntegrationTest extends PostgresIntegrationTest {
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
    private CatalogWorlds worlds;
    private World world;

    @BeforeEach
    void world() {
        worlds = new CatalogWorlds(decks, items, exercises, sessions, media, storage, jdbc, transactions, publication, moderation);
        jdbc.sql("UPDATE app_learning.learning_job SET state = 'CANCELLED', finished_at = clock_timestamp() WHERE queue LIKE 'catalog.%' AND state = 'QUEUED'").update();
        world = worlds.deck("Схема", "Описание", 10);
        worlds.exercise(world.materials().getFirst(), ExerciseType.MATCH);
        worlds.publish(world);
        worlds.project(projector, world.deck());
    }

    private <T> T rolledBack(Supplier<T> body) {
        return new TransactionTemplate(transactions).execute(status -> {
            T result = body.get();
            status.setRollbackOnly();
            return result;
        });
    }

    private String sqlState(Runnable statement) {
        try {
            rolledBack(() -> {
                statement.run();
                return null;
            });
        } catch (DataAccessException failure) {
            return state(failure);
        }
        return "none";
    }

    private static String state(Exception failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (cause instanceof java.sql.SQLException sql) return sql.getSQLState();
        return "unknown";
    }

    /** An insert of a row for {@code deck} with the given values for the columns the tests vary. */
    private void insert(UUID deck, UUID revision, String code, String mediaMask, String mechanicsMask, String level) {
        jdbc.sql("DELETE FROM app_learning.public_deck WHERE deck_id = :d").param("d", deck).update();
        jdbc.sql("""
                INSERT INTO app_learning.public_deck(deck_id, public_code, owner_id, published_revision_id, title, description, level, item_count, exercise_count,
                        media_mask, mechanics_mask, first_listed_at, content_updated_at, title_norm, projected_at)
                VALUES (:d, :code, :owner, :revision, 't', 'd', :level, 10, 1, CAST(:media AS smallint), CAST(:mechanics AS smallint), now(), now(), 't', now())
                """).param("d", deck).param("code", code).param("owner", world.owner()).param("revision", revision).param("level", level, java.sql.Types.VARCHAR)
                .param("media", mediaMask).param("mechanics", mechanicsMask).update();
    }

    @Test
    void theGeneratedColumnsAreGeneratedAndTheTagsFunctionIsImmutable() {
        assertThat(jdbc.sql("SELECT provolatile::text FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'app_learning' AND p.proname = 'catalog_tags_text'")
                .query(String.class).single()).isEqualTo("i");
        List<String> generated = jdbc.sql("""
                SELECT attname || ':' || attgenerated::text FROM pg_attribute WHERE attrelid = 'app_learning.public_deck'::regclass AND attgenerated <> '' ORDER BY attname""")
                .query(String.class).list();
        assertThat(generated).containsExactly("listed:s", "search_ru:s", "search_simple:s");
        // a writer cannot disagree with the definition
        assertThat(sqlState(() -> jdbc.sql("UPDATE app_learning.public_deck SET listed = TRUE WHERE deck_id = :d").param("d", world.deck()).update())).isEqualTo("428C9");
        assertThat(sqlState(() -> jdbc.sql("UPDATE app_learning.public_deck SET search_ru = to_tsvector('x') WHERE deck_id = :d").param("d", world.deck()).update())).isEqualTo("428C9");
        // the expressions are fixed to their configurations by object, not by the search_path of the session
        assertThat(jdbc.sql("""
                SELECT pg_get_expr(d.adbin, d.adrelid) FROM pg_attrdef d JOIN pg_attribute a ON a.attrelid = d.adrelid AND a.attnum = d.adnum
                 WHERE d.adrelid = 'app_learning.public_deck'::regclass AND a.attname = 'search_simple'""").query(String.class).single()).contains("simple_unaccent");
        // listed: ten materials, one exercise, a description (the constants of the publication checklist)
        assertThat(jdbc.sql("SELECT listed FROM app_learning.public_deck WHERE deck_id = :d").param("d", world.deck()).query(Boolean.class).single()).isTrue();
        assertThat(app.mnema.learning.library.PublicationService.NEED_MATERIALS).isEqualTo(10);
        assertThat(app.mnema.learning.library.PublicationService.NEED_EXERCISES).isEqualTo(1);
        assertThat(jdbc.sql("SELECT pg_get_expr(d.adbin, d.adrelid) FROM pg_attrdef d JOIN pg_attribute a ON a.attrelid = d.adrelid AND a.attnum = d.adnum "
                + "WHERE d.adrelid = 'app_learning.public_deck'::regclass AND a.attname = 'listed'").query(String.class).single())
                .contains(">= 10").contains(">= 1").contains("btrim");
    }

    @Test
    void theChecksAndTheKeysHold() {
        UUID revision = worlds.publishedRevision(world);
        UUID deck = world.deck();
        assertThat(sqlState(() -> insert(deck, revision, "2ZkQ8mXn5T", "0", "0", "A1"))).isEqualTo("none");
        assertThat(sqlState(() -> insert(deck, revision, "2ZkQ8mXn5T", "8", "0", null))).isEqualTo("23514");
        assertThat(sqlState(() -> insert(deck, revision, "2ZkQ8mXn5T", "7", "32", null))).isEqualTo("23514");
        assertThat(sqlState(() -> insert(deck, revision, "2ZkQ8mXn5T", "-1", "0", null))).isEqualTo("23514");
        assertThat(sqlState(() -> insert(deck, revision, "short", "0", "0", null))).as("a public code is ten base58 characters").isEqualTo("23514");
        assertThat(sqlState(() -> insert(deck, revision, "2ZkQ8mXn5T", "0", "0", "Z9"))).as("one closed list of levels").isEqualTo("23514");
        // the published revision must be a revision of THAT deck
        World other = worlds.deck("Чужая", "Описание", 1);
        UUID foreignRevision = UUID.fromString(decks.read(other.owner(), other.deck()).path("revisionId").stringValue(null));
        assertThat(sqlState(() -> insert(deck, foreignRevision, "2ZkQ8mXn5T", "0", "0", null))).isEqualTo("23503");
        assertThat(sqlState(() -> insert(UUID.randomUUID(), revision, "2ZkQ8mXn5T", "0", "0", null))).as("only a real deck").isEqualTo("23503");
        // one row per deck
        assertThat(sqlState(() -> jdbc.sql("""
                INSERT INTO app_learning.public_deck(deck_id, public_code, owner_id, published_revision_id, title, description, item_count, exercise_count,
                        first_listed_at, content_updated_at, title_norm, projected_at)
                SELECT deck_id, public_code, owner_id, published_revision_id, title, description, item_count, exercise_count, first_listed_at, content_updated_at, title_norm, projected_at
                  FROM app_learning.public_deck WHERE deck_id = :d""").param("d", deck).update())).isEqualTo("23505");
    }

    @Test
    void everyIndexIsThereAsDocumentedAndTheOutboxIndexIsPartial() {
        List<String> definitions = jdbc.sql("SELECT indexname || ' ' || indexdef FROM pg_indexes WHERE schemaname = 'app_learning' AND tablename = 'public_deck' ORDER BY indexname")
                .query(String.class).list();
        assertThat(String.join("\n", definitions))
                .contains("public_deck_search_ru ON app_learning.public_deck USING gin (search_ru)")
                .contains("public_deck_search_simple ON app_learning.public_deck USING gin (search_simple)")
                .contains("public_deck_title_trgm ON app_learning.public_deck USING gin (title_norm app_learning.gin_trgm_ops)")
                .contains("public_deck_owner ON app_learning.public_deck USING btree (owner_id, content_updated_at DESC, deck_id DESC)")
                .contains("public_deck_new ON app_learning.public_deck USING btree (first_listed_at DESC, deck_id DESC) WHERE listed")
                .contains("public_deck_updated ON app_learning.public_deck USING btree (content_updated_at DESC, deck_id DESC) WHERE listed")
                .contains("public_deck_topic ON app_learning.public_deck USING btree (topic_id, first_listed_at DESC, deck_id DESC) WHERE listed")
                .contains("public_deck_parent_topic ON app_learning.public_deck USING btree (parent_topic_id, first_listed_at DESC, deck_id DESC) WHERE listed");
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = 'app_learning' AND indexname = 'deck_publication_event_public'").query(String.class).single())
                .contains("(deck_id, occurred_at)").contains("visibility = 'PUBLIC'");
    }

    // ---- the plans of the queries that use the indexes ----

    private String plan(String sql, java.util.function.UnaryOperator<JdbcClient.StatementSpec> parameters) {
        return rolledBack(() -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            return String.join("\n", parameters.apply(jdbc.sql("EXPLAIN " + sql)).query(String.class).list());
        });
    }

    private String plan(String sql) {
        return plan(sql, spec -> spec);
    }

    @Test
    void theSearchIndexesServeTheSearchQueriesOfTheNextTasks() {
        String text = plan("SELECT deck_id FROM app_learning.public_deck WHERE search_ru @@ websearch_to_tsquery('russian', 'глаголы')");
        assertThat(text).contains("public_deck_search_ru");
        assertThat(plan("SELECT deck_id FROM app_learning.public_deck WHERE search_simple @@ websearch_to_tsquery('app_learning.simple_unaccent', 'espanol')"))
                .contains("public_deck_search_simple");
        // the typo tolerance: word_similarity over the normalized title (the operator is schema-qualified, the extension lives in app_learning)
        assertThat(plan("SELECT deck_id FROM app_learning.public_deck WHERE title_norm OPERATOR(app_learning.%>) 'espanol'")).contains("public_deck_title_trgm");
        // the three together, as one search query
        String all = plan("""
                SELECT deck_id FROM app_learning.public_deck
                 WHERE (search_ru @@ websearch_to_tsquery('russian', 'глаголы') OR search_simple @@ websearch_to_tsquery('app_learning.simple_unaccent', 'verbos')
                        OR title_norm OPERATOR(app_learning.%>) 'verbos')""");
        assertThat(all).contains("BitmapOr").contains("public_deck_search_ru").contains("public_deck_search_simple").contains("public_deck_title_trgm");
    }

    @Test
    void theShelvesTheAuthorPageAndTheKeysetPagesAreIndexRanges() {
        Timestamp at = Timestamp.from(Instant.now());
        UUID after = UUID.randomUUID();
        // the keyset pages of the repository, the statements it really runs
        String listed = plan(CatalogRepository.listedSql(true), spec -> spec.param("t", at).param("d", after).param("limit", 20));
        assertThat(listed).contains("public_deck_new").doesNotContain("Seq Scan");
        assertThat(plan(CatalogRepository.listedSql(false), spec -> spec.param("limit", 20))).contains("public_deck_new");
        String owner = plan(CatalogRepository.ownerSql(true), spec -> spec.param("owner", world.owner()).param("t", at).param("d", after).param("limit", 20));
        assertThat(owner).contains("public_deck_owner").containsPattern("owner_id = ").doesNotContain("Seq Scan");
        assertThat(plan(CatalogRepository.ownerSql(false), spec -> spec.param("owner", world.owner()).param("limit", 20))).contains("public_deck_owner");
        // the other shelves and sorts
        assertThat(plan("SELECT deck_id FROM app_learning.public_deck WHERE listed ORDER BY content_updated_at DESC, deck_id DESC LIMIT 20")).contains("public_deck_updated");
        // a topic that few decks have (the test data are all Spanish, for which any index is as good as another): the equality is an index condition
        assertThat(plan("SELECT deck_id FROM app_learning.public_deck WHERE listed AND topic_id = 'physics' ORDER BY first_listed_at DESC, deck_id DESC LIMIT 20"))
                .contains("public_deck_topic").containsPattern("Index Cond: \\(topic_id = ");
        assertThat(plan("SELECT deck_id FROM app_learning.public_deck WHERE listed AND parent_topic_id = 'school' ORDER BY first_listed_at DESC, deck_id DESC LIMIT 20"))
                .contains("public_deck_parent_topic").containsPattern("Index Cond: \\(parent_topic_id = ");
        assertThat(plan("SELECT count(*) FROM app_learning.public_deck WHERE listed AND topic_id = 'physics'")).contains("public_deck_topic");
    }

    @Test
    void theStatementsThatRunPerDeckAreKeyProbesNotScans() {
        UUID deck = world.deck();
        UUID revision = worlds.publishedRevision(world);
        String state = plan(ProjectionRepository.PUBLISHED_SQL, spec -> spec.param("deck", deck));
        assertThat(state).doesNotContain("Seq Scan");
        assertThat(state).containsPattern("deck_pkey|deck_publication_pkey");

        String mediaTypes = plan(ProjectionRepository.MEDIA_TYPES_SQL, spec -> spec.param("deck", deck));
        assertThat(mediaTypes).contains("published_media_pkey").doesNotContain("Seq Scan on published_media");

        String upsert = plan(ProjectionRepository.UPSERT_SQL, spec -> spec.param("deck", deck).param("revision", revision).param("media", 0).param("norm", "t"));
        assertThat(upsert).as("first_listed_at is a probe of the journal's partial index, not a read of the journal").contains("deck_publication_event_public")
                .doesNotContain("Seq Scan on deck_publication_event").doesNotContain("Seq Scan on published_media");

        String kinds = plan(ProjectionRepository.KINDS_SQL, spec -> spec.param("pairs", "[]").param("scope", UUID.randomUUID()));
        assertThat(kinds).contains("Nested Loop").containsPattern("Index Scan using exercise_revision_\\w+ on exercise_revision").containsPattern("exercise_id = m\\.k").contains("exercise_media_ref_pkey").doesNotContain("Seq Scan on exercise_revision")
                .doesNotContain("Seq Scan on exercise_media_ref");

        String rebuild = plan(ProjectionRepository.REBUILD_PAGE_SQL, spec -> spec.param("after", new UUID(0, 0)).param("limit", 2));
        assertThat(rebuild).contains("deck_publication_pkey").contains("public_deck_pkey").doesNotContain("Seq Scan").doesNotContain("Hash");
    }
}
