package app.mnema.learning.fork;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.fork.ForkWorlds.Source;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V57 against a real PostgreSQL: the guards that make "a copy is made only from what its source published" and "a copy changes state only forwards" hold even for a
 * statement that does not come from the service, the one-live-copy index, and the release of the slot when a copy is deleted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.runtime.roles=worker", "learning.jobs.sweep-interval=PT0.5S", "learning.ai.provider=stub",
                "learning.features.speech-to-text.enabled=true", "learning.speech.sweep-interval=PT5M",
                "learning.generation.worker.sweep-interval=PT5M", "learning.ai.assess.sweep-interval=PT15S"})
class ForkSchemaIntegrationTest extends PostgresIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ManifestPages manifests;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.forks.enabled", () -> true);
        registry.add("learning.community.public-routes.enabled", () -> true);
    }

    private ForkWorlds worlds() {
        return new ForkWorlds(port, decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
    }

    private void insertFork(UUID copy, UUID source, String code, UUID base) {
        jdbc.sql("""
                INSERT INTO app_learning.deck_fork(deck_id, reuse_scope_id, owner_id, upstream_deck_id, upstream_code, base_revision_id, forked_at, job_id)
                SELECT d.deck_id, d.reuse_scope_id, d.owner_id, :source, :code, :base, now(), gen_random_uuid() FROM app_learning.deck d WHERE d.deck_id = :copy
                """).param("copy", copy).param("source", source).param("code", code).param("base", base).update();
    }

    @Test
    void aCopyCanOnlyBeRecordedFromTheRevisionItsSourcePublishedAndItsGenesisPinsThatRevisionsRoots() {
        ForkWorlds worlds = worlds();
        Source source = worlds.source("LINK", 2, 1);
        UUID owner = UUID.randomUUID();
        UUID copy = worlds.forked(owner, source.code());
        UUID published = jdbc.sql("SELECT published_revision_id FROM app_learning.deck_publication WHERE deck_id = :d").param("d", source.deck()).query(UUID.class).single();
        assertThat(jdbc.sql("SELECT base_revision_id FROM app_learning.deck_fork WHERE deck_id = :d").param("d", copy).query(UUID.class).single()).isEqualTo(published);

        // a deck of its own (its own roots) cannot claim to be a copy of the source
        UUID stranger = UUID.randomUUID();
        UUID own = UUID.fromString(decks.create(stranger, new DeckCommand(UUID.randomUUID(), "Своя", "")).acknowledgement().path("deck").path("deckId").stringValue(null));
        assertThatThrownBy(() -> insertFork(own, source.deck(), source.code(), published)).hasStackTraceContaining("published revision of its source");

        // the copy's row removed and the source republished: the same copy can no longer be recorded against the old revision
        jdbc.sql("DELETE FROM app_learning.deck_fork WHERE deck_id = :d").param("d", copy).update();
        worlds.study.addMaterial(source.author(), source.deck(), "после", "публикации");
        worlds.publish(source.author(), source.deck(), "LINK");
        assertThat(jdbc.sql("SELECT published_revision_id FROM app_learning.deck_publication WHERE deck_id = :d").param("d", source.deck()).query(UUID.class).single())
                .isNotEqualTo(published);
        assertThatThrownBy(() -> insertFork(copy, source.deck(), source.code(), published)).hasStackTraceContaining("published revision of its source");
        // a revision that is not even a revision of the source
        assertThatThrownBy(() -> insertFork(copy, source.deck(), source.code(), UUID.randomUUID())).hasMessageContaining("deck_fork");
        // a code of the wrong shape
        assertThatThrownBy(() -> insertFork(copy, source.deck(), "not-a-code", published)).hasMessageContaining("deck_fork");
    }

    @Test
    void aNewCopyIsNeverBornMaterializedOrReleasedAndNeverMovesBackwards() {
        ForkWorlds worlds = worlds();
        Source source = worlds.source("LINK", 2, 1);
        UUID owner = UUID.randomUUID();
        UUID copy = worlds.forked(owner, source.code());
        UUID published = jdbc.sql("SELECT base_revision_id FROM app_learning.deck_fork WHERE deck_id = :d").param("d", copy).query(UUID.class).single();
        worlds.awaitReady(owner, copy);

        // identity columns never change
        for (String column : new String[] {"owner_id = gen_random_uuid()", "upstream_deck_id = gen_random_uuid()", "base_revision_id = gen_random_uuid()",
                "upstream_code = 'zzzzzzzzzz'", "forked_at = now() + interval '1 day'", "auto_update = true", "reuse_scope_id = gen_random_uuid()"}) {
            assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck_fork SET " + column + " WHERE deck_id = :d").param("d", copy).update()).as(column)
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        // a ready copy stays ready; a detach is set once; a job pointer of a finished copy is final
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck_fork SET materialized_at = NULL WHERE deck_id = :d").param("d", copy).update())
                .hasStackTraceContaining("Invalid deck copy transition");
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck_fork SET job_id = gen_random_uuid() WHERE deck_id = :d").param("d", copy).update())
                .hasStackTraceContaining("Invalid deck copy transition");
        assertThat(jdbc.sql("UPDATE app_learning.deck_fork SET detached_at = now() WHERE deck_id = :d").param("d", copy).update()).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck_fork SET detached_at = NULL WHERE deck_id = :d").param("d", copy).update())
                .hasStackTraceContaining("Invalid deck copy transition");

        // a copy is not born finished
        jdbc.sql("DELETE FROM app_learning.deck_fork WHERE deck_id = :d").param("d", copy).update();
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO app_learning.deck_fork(deck_id, reuse_scope_id, owner_id, upstream_deck_id, upstream_code, base_revision_id, forked_at, job_id, materialized_at)
                SELECT d.deck_id, d.reuse_scope_id, d.owner_id, :source, :code, :base, now(), gen_random_uuid(), now() FROM app_learning.deck d WHERE d.deck_id = :copy
                """).param("copy", copy).param("source", source.deck()).param("code", source.code()).param("base", published).update())
                .hasStackTraceContaining("A new copy is not materialized");
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO app_learning.deck_fork(deck_id, reuse_scope_id, owner_id, upstream_deck_id, upstream_code, base_revision_id, forked_at, job_id)
                SELECT d.deck_id, d.reuse_scope_id, d.owner_id, d.deck_id, :code, :base, now(), gen_random_uuid() FROM app_learning.deck d WHERE d.deck_id = :copy
                """).param("copy", copy).param("code", source.code()).param("base", published).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void oneLiveCopyPerOwnerAndSourceIsAnIndexAndADeletedCopyReleasesTheSlot() {
        ForkWorlds worlds = worlds();
        Source source = worlds.source("LINK", 2, 1);
        UUID owner = UUID.randomUUID();
        UUID first = worlds.forked(owner, source.code());
        worlds.awaitReady(owner, first);
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'deck_fork_live'").query(String.class).single())
                .contains("UNIQUE").contains("(owner_id, upstream_deck_id)").contains("released_at IS NULL");
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'deck_fork_unfinished'").query(String.class).single())
                .contains("(owner_id)").contains("materialized_at IS NULL");

        // deleting the copy (the tombstone is the only deck update the head guard allows) releases its slot in the same transaction, and the release is final
        assertThat(worlds.send("DELETE", "/decks/" + first, owner, null, "\"0\"").statusCode()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT released_at IS NOT NULL FROM app_learning.deck_fork WHERE deck_id = :d").param("d", first).query(Boolean.class).single()).isTrue();
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck_fork SET released_at = NULL WHERE deck_id = :d").param("d", first).update())
                .hasStackTraceContaining("Invalid deck copy transition");

        // the owner copies again: a live row exists for the pair
        UUID second = worlds.forked(owner, source.code());
        assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_fork WHERE owner_id = :o AND upstream_deck_id = :s AND released_at IS NULL")
                .param("o", owner).param("s", source.deck()).query(Long.class).single()).isEqualTo(1);

        // behind the service's back and with the triggers switched off for one transaction, the index alone still refuses a second live copy of the pair
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("UPDATE app_learning.deck_fork SET released_at = NULL WHERE deck_id = :d").param("d", first).update();
        })).hasMessageContaining("deck_fork_live");
    }
}
