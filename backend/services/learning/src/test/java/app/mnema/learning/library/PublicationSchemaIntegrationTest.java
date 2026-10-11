package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.LibraryFixtures.Deck;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V54 on a real PostgreSQL: the constraints of the publication metadata, the seeded topic directory, the append-only outbox and the indexes the queries rely on. */
@SpringBootTest
class PublicationSchemaIntegrationTest extends PostgresIntegrationTest {
    @Autowired private DeckPublications publications;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private TopicDirectory directory;
    private LibraryFixtures fixtures;
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void fixtures() { fixtures = new LibraryFixtures(decks, items, exercises, sessions, media, jdbc, publications); }

    private Deck sharedDeck() {
        Deck deck = fixtures.deck(owner, "Схема");
        fixtures.level(deck, DeckVisibility.LINK);
        return deck;
    }

    private void update(Deck deck, String set) {
        jdbc.sql("UPDATE app_learning.deck_publication SET " + set + ", row_version = row_version + 1 WHERE deck_id = :deck").param("deck", deck.id()).update();
    }

    @Test
    void theMetadataColumnsRejectEverythingOutsideTheirShape() {
        Deck deck = sharedDeck();
        update(deck, "topic_id = 'japanese', content_language = 'ru', target_language = 'ja', level = 'A2', tags = ARRAY['a','b','c','d','e'], release_note = 'x', requests_enabled = FALSE");
        record Bad(String set, String constraint) { }
        List<Bad> bad = List.of(
                new Bad("topic_id = 'no-such'", "deck_publication_topic_id_fkey"),
                new Bad("content_language = 'RU'", "deck_publication_content_language_check"),
                new Bad("content_language = 'russian'", "deck_publication_content_language_check"),
                new Bad("target_language = 'r'", "deck_publication_target_language_check"),
                new Bad("level = 'EXPERT'", "deck_publication_level_check"),
                new Bad("level = 'a1'", "deck_publication_level_check"),
                new Bad("tags = ARRAY['a','b','c','d','e','f']", "deck_publication_tags_check"),
                new Bad("tags = ARRAY['a','a']", "deck_publication_tags_check"),
                new Bad("tags = ARRAY['']", "deck_publication_tags_check"),
                new Bad("tags = ARRAY[' padded']", "deck_publication_tags_check"),
                new Bad("tags = ARRAY[repeat('x', 33)]", "deck_publication_tags_check"),
                new Bad("tags = ARRAY[NULL]::text[]", "deck_publication_tags_check"),
                new Bad("release_note = ''", "deck_publication_release_note_check"),
                new Bad("release_note = repeat('я', 501)", "deck_publication_release_note_check"));
        for (Bad value : bad) {
            assertThatThrownBy(() -> update(deck, value.set())).as(value.set()).hasMessageContaining(value.constraint());
        }
        update(deck, "tags = ARRAY[repeat('я', 32)], release_note = repeat('я', 500), level = NULL, topic_id = NULL");
        assertThat(jdbc.sql("SELECT requests_enabled FROM app_learning.deck_publication WHERE deck_id = :deck").param("deck", deck.id()).query(Boolean.class).single()).isFalse();
        // a row of Share/7 has the defaults
        Deck plain = fixtures.deck(owner, "Обычная");
        fixtures.level(plain, DeckVisibility.INVITE);
        assertThat(jdbc.sql("SELECT requests_enabled AND tags = '{}' AND topic_id IS NULL AND release_note IS NULL FROM app_learning.deck_publication WHERE deck_id = :deck")
                .param("deck", plain.id()).query(Boolean.class).single()).isTrue();
    }

    @Test
    void theDirectoryHasTwoLevelsAndEveryAliasIsInItsNormalForm() {
        List<String> tops = jdbc.sql("SELECT topic_id FROM app_learning.topic WHERE parent_id IS NULL ORDER BY ordinal").query(String.class).list();
        assertThat(tops).containsExactly("languages", "exams", "school", "history-culture", "science", "medicine", "it", "professional", "everyday", "other");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.topic c JOIN app_learning.topic p ON p.topic_id = c.parent_id WHERE p.parent_id IS NOT NULL")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.topic WHERE parent_id IS NOT NULL").query(Long.class).single()).isGreaterThanOrEqualTo(50);
        // «Другое» is a selectable top-level leaf; a group is not
        assertThat(directory.selectable("other")).isTrue();
        assertThat(directory.selectable("languages")).isFalse();
        assertThat(directory.selectable("python")).isTrue();
        assertThat(directory.selectable(null)).isFalse();
        // alias_norm is what the normalizer produces, and every name is an alias of its topic
        List<String> aliases = jdbc.sql("SELECT alias_norm FROM app_learning.topic_alias").query(String.class).list();
        assertThat(aliases).isNotEmpty().allSatisfy(alias -> assertThat(AliasNormalizer.normalize(alias)).isEqualTo(alias));
        jdbc.sql("SELECT topic_id, name_ru, name_en FROM app_learning.topic").query((row, ignored) -> {
            String id = row.getString("topic_id");
            for (String name : List.of(row.getString("name_ru"), row.getString("name_en"))) {
                assertThat(jdbc.sql("SELECT count(*) FROM app_learning.topic_alias WHERE topic_id = :topic AND alias_norm = :alias")
                        .param("topic", id).param("alias", AliasNormalizer.normalize(name)).query(Long.class).single()).as(id + " " + name).isOne();
            }
            return id;
        }).list();
        // the synonyms of the seed: native names and abbreviations
        assertThat(jdbc.sql("SELECT string_agg(topic_id, ',' ORDER BY topic_id) FROM app_learning.topic_alias WHERE alias_norm = ANY(:aliases)")
                .param("aliases", new String[] {"日本語", "кандзи", "nihongo", "espanol", "javascript", "js"}).query(String.class).single())
                .contains("japanese").contains("spanish").contains("javascript");
        assertThat(jdbc.sql("SELECT topic_id FROM app_learning.topic_alias WHERE alias_norm = 'егэ'").query(String.class).list()).containsExactly("ege");
        assertThat(jdbc.sql("SELECT topic_id FROM app_learning.topic_alias WHERE alias_norm = 'espanol'").query(String.class).list()).containsExactly("spanish");
    }

    @Test
    void theDirectoryGuardsKeepTwoLevelsAndDistinctSiblingOrdinals() {
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.topic(topic_id, parent_id, name_ru, name_en, ordinal) VALUES ('deep', 'english', 'x', 'x', 5)").update())
                .hasMessageContaining("two levels");
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.topic SET parent_id = 'other' WHERE topic_id = 'languages'").update()).hasMessageContaining("two levels");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.topic(topic_id, parent_id, name_ru, name_en, ordinal) VALUES ('twin', 'languages', 'x', 'x', 10)").update())
                .hasMessageContaining("topic_sibling_ordinal");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.topic(topic_id, parent_id, name_ru, name_en, ordinal) VALUES ('Bad Slug', NULL, 'x', 'x', 500)").update())
                .hasMessageContaining("topic_topic_id_check");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.topic_alias(alias_norm, topic_id) VALUES (' padded', 'english')").update())
                .hasMessageContaining("topic_alias_alias_norm_check");
    }

    @Test
    void theOutboxIsAppendOnlyAndKeyedByAUuidV7() {
        Deck deck = sharedDeck();
        fixtures.publishHead(deck.id());
        UUID revision = jdbc.sql("SELECT published_revision_id FROM app_learning.deck_publication WHERE deck_id = :deck").param("deck", deck.id()).query(UUID.class).single();
        jdbc.sql("INSERT INTO app_learning.deck_publication_event(deck_id, published_revision_id, visibility, occurred_at) VALUES (:deck, :revision, 'LINK', now())")
                .param("deck", deck.id()).param("revision", revision).update();
        UUID event = jdbc.sql("SELECT event_id FROM app_learning.deck_publication_event WHERE deck_id = :deck").param("deck", deck.id()).query(UUID.class).single();
        assertThat(event.version()).isEqualTo(7);
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck_publication_event SET visibility = 'PUBLIC' WHERE event_id = :id").param("id", event).update())
                .hasMessageContaining("Append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM app_learning.deck_publication_event WHERE event_id = :id").param("id", event).update()).hasMessageContaining("Append-only");
        // an event names a revision of THAT deck
        Deck other = fixtures.deck(owner, "Другая");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.deck_publication_event(deck_id, published_revision_id, visibility, occurred_at) VALUES (:deck, :revision, 'LINK', now())")
                .param("deck", other.id()).param("revision", revision).update()).hasMessageContaining("deck_publication_event_deck_id_published_revision_id_fkey");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.deck_publication_event(deck_id, published_revision_id, visibility, occurred_at) VALUES (:deck, :revision, 'SECRET', now())")
                .param("deck", deck.id()).param("revision", revision).update()).hasMessageContaining("deck_publication_event_visibility_check");
    }

    @Test
    void theJournalRangeAndTheNonCommercialProbeAreIndexRangesNotScans() {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        // the journal ranges: the planner may also pick the primary key prefix on a tiny table, so the test pins the definitions the range scans rely on
        for (String index : new String[] {"deck_item_change_sequence", "deck_exercise_change_sequence"}) {
            assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = 'app_learning' AND indexname = :name").param("name", index).query(String.class).single())
                    .contains("(deck_id, deck_sequence)");
        }
        // the provenance probe: the REAL statement of the checklist repeats the predicate of the partial index, so a deck without a non-commercial license costs one empty index scan
        String provenance = transaction.execute(status -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            return String.join("\n", jdbc.sql("EXPLAIN " + PublicationRepository.BLOCKED_MEDIA_SQL).param("owner", owner).param("deck", UUID.randomUUID())
                    .param("regex", PublicationRepository.NC_REGEX).param("limit", 50).query(String.class).list());
        });
        assertThat(provenance).contains("generation_provenance_nc");
    }
}
