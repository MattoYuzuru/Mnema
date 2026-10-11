package app.mnema.learning.update.plan;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.update.plan.PlanWorlds.Copy;
import app.mnema.learning.update.plan.PlanWorlds.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Updates/3 (#442), migration {@code V61__fork_unit_decisions.sql} on real data: the exact shape of {@code fork_unit_decision}, every row shape it admits and every one it refuses,
 * the cascade with the copy, and the one change to the transition guard of {@code deck_fork} (the base moves forward and only forward, for a ready, attached, live copy).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.runtime.roles=worker", "learning.community.forks.slice-size=10", "learning.jobs.sweep-interval=PT0.5S",
                "learning.ai.provider=stub", "learning.features.speech-to-text.enabled=true", "learning.speech.sweep-interval=PT5M",
                "learning.generation.worker.sweep-interval=PT5M", "learning.ai.assess.sweep-interval=PT15S"})
class UpdatePlanSchemaIntegrationTest extends PostgresIntegrationTest {
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
    private PlanWorlds worlds;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.forks.enabled", () -> true);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
    }

    @BeforeEach
    void worlds() {
        worlds = new PlanWorlds(port, decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
    }

    private List<String> columns() {
        return jdbc.sql("SELECT column_name || ' ' || data_type || ' ' || is_nullable || ' ' || COALESCE(column_default, '-') FROM information_schema.columns "
                + "WHERE table_schema = 'app_learning' AND table_name = 'fork_unit_decision' ORDER BY ordinal_position").query(String.class).list();
    }

    @Test
    void theTableHasExactlyTheDesignedShape() {
        assertThat(columns()).containsExactly(
                "deck_id uuid NO -",
                "reuse_scope_id uuid NO -",
                "unit_kind text NO -",
                "unit_key uuid NO -",
                "decision text NO -",
                "resolved_upstream_revision_id uuid YES -",
                "resolved_upstream_absent boolean NO false",
                "pending_base_revision_id uuid YES -",
                "pending_base_absent boolean NO false",
                "decided_at timestamp with time zone NO -");
        // the NOT NULL constraints (PostgreSQL 18 lists them too) are the columns' own: contype 'n'
        assertThat(jdbc.sql("SELECT conname FROM pg_constraint WHERE conrelid = 'app_learning.fork_unit_decision'::regclass AND contype <> 'n' ORDER BY conname").query(String.class).list())
                .containsExactly("fork_unit_decision_added_deleted_kinds", "fork_unit_decision_author_absent_kinds", "fork_unit_decision_base_absent_kinds",
                        "fork_unit_decision_decided_at_check", "fork_unit_decision_decision_check", "fork_unit_decision_deck_fkey", "fork_unit_decision_fork_fkey",
                        "fork_unit_decision_pending_shape", "fork_unit_decision_pkey", "fork_unit_decision_resolved_shape", "fork_unit_decision_single_unit",
                        "fork_unit_decision_unit_kind_check");
        assertThat(jdbc.sql("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'fork_unit_decision_pkey'").query(String.class).single())
                .isEqualTo("PRIMARY KEY (deck_id, unit_kind, unit_key)");
        assertThat(jdbc.sql("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'fork_unit_decision_fork_fkey'").query(String.class).single())
                .contains("FOREIGN KEY (deck_id) REFERENCES app_learning.deck_fork(deck_id) ON DELETE CASCADE");
        assertThat(jdbc.sql("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'fork_unit_decision_deck_fkey'").query(String.class).single())
                .contains("FOREIGN KEY (deck_id, reuse_scope_id) REFERENCES app_learning.deck(deck_id, reuse_scope_id)");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.fork_unit_decision WHERE false").query(Long.class).single()).isZero();
    }

    private void decide(Copy copy, String kind, UUID key, String decision, UUID resolved, boolean resolvedAbsent, UUID pending, boolean pendingAbsent) {
        worlds.decide(copy.deck(), kind, key, decision, resolved, resolvedAbsent, pending, pendingAbsent);
    }

    private long rows(Copy copy) {
        return jdbc.sql("SELECT count(*) FROM app_learning.fork_unit_decision WHERE deck_id = :d").param("d", copy.deck()).query(Long.class).single();
    }

    @Test
    void everyAdmittedRowShapeIsAcceptedAndEveryOtherIsRefusedByName() {
        Source source = worlds.source("LINK", 2, 0);
        Copy copy = worlds.copy(source);
        UUID revision = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        UUID deck = copy.deck();

        // admitted: a decision on a revision; "the author had removed it"; a declined addition; a unit kept deleted; pending with a base and with "absent at the base"; an objective with no head
        decide(copy, "MATERIAL", UUID.randomUUID(), "KEEP_MINE", revision, false, null, false);
        decide(copy, "MATERIAL", UUID.randomUUID(), "KEEP_MINE", null, true, null, false);
        decide(copy, "EXERCISE", UUID.randomUUID(), "DECLINE_NEW", revision, false, null, false);
        decide(copy, "MATERIAL", UUID.randomUUID(), "KEEP_DELETED", revision, false, null, false);
        decide(copy, "MATERIAL", UUID.randomUUID(), "PENDING", revision, false, other, false);
        decide(copy, "MATERIAL", UUID.randomUUID(), "PENDING", null, true, null, true);
        decide(copy, "EXERCISE", UUID.randomUUID(), "PENDING", revision, false, null, true);
        decide(copy, "OBJECTIVE", UUID.randomUUID(), "PENDING", revision, false, null, true);
        decide(copy, "OBJECTIVE", UUID.randomUUID(), "KEEP_MINE", revision, false, null, false);
        decide(copy, "METADATA", deck, "KEEP_MINE", revision, false, null, false);
        decide(copy, "ORDER", deck, "PENDING", revision, false, other, false);
        assertThat(rows(copy)).isEqualTo(11);
        // the same unit decided again is the same row (the primary key)
        decide(copy, "METADATA", deck, "KEEP_MINE", other, false, null, false);
        assertThat(rows(copy)).isEqualTo(11);
        assertThat(jdbc.sql("SELECT resolved_upstream_revision_id FROM app_learning.fork_unit_decision WHERE deck_id = :d AND unit_kind = 'METADATA'").param("d", deck)
                .query(UUID.class).single()).isEqualTo(other);

        // refused
        assertThatThrownBy(() -> decide(copy, "NOPE", key, "KEEP_MINE", revision, false, null, false)).hasStackTraceContaining("fork_unit_decision_unit_kind_check");
        assertThatThrownBy(() -> decide(copy, "MATERIAL", key, "FORGET", revision, false, null, false)).hasStackTraceContaining("fork_unit_decision_decision_check");
        // the deck-wide units are the copy's own single units
        assertThatThrownBy(() -> decide(copy, "METADATA", key, "KEEP_MINE", revision, false, null, false)).hasStackTraceContaining("fork_unit_decision_single_unit");
        assertThatThrownBy(() -> decide(copy, "ORDER", key, "KEEP_MINE", revision, false, null, false)).hasStackTraceContaining("fork_unit_decision_single_unit");
        // only a material or an exercise is added or deleted by the author
        assertThatThrownBy(() -> decide(copy, "OBJECTIVE", key, "DECLINE_NEW", revision, false, null, false)).hasStackTraceContaining("fork_unit_decision_added_deleted_kinds");
        assertThatThrownBy(() -> decide(copy, "ORDER", deck, "KEEP_DELETED", revision, false, null, false)).hasStackTraceContaining("fork_unit_decision_added_deleted_kinds");
        assertThatThrownBy(() -> decide(copy, "METADATA", deck, "DECLINE_NEW", revision, false, null, false)).hasStackTraceContaining("fork_unit_decision_added_deleted_kinds");
        // "absent" belongs to the kinds that can be absent
        assertThatThrownBy(() -> decide(copy, "OBJECTIVE", key, "KEEP_MINE", null, true, null, false)).hasStackTraceContaining("fork_unit_decision_author_absent_kinds");
        assertThatThrownBy(() -> decide(copy, "METADATA", deck, "PENDING", revision, false, null, true)).hasStackTraceContaining("fork_unit_decision_base_absent_kinds");
        assertThatThrownBy(() -> decide(copy, "ORDER", deck, "KEEP_MINE", null, true, null, false)).hasStackTraceContaining("fork_unit_decision_author_absent_kinds");
        // PENDING names its base: a revision or "absent", exactly one; and the author's offered state, exactly one
        assertThatThrownBy(() -> decide(copy, "MATERIAL", key, "PENDING", revision, false, other, true)).hasStackTraceContaining("fork_unit_decision_pending_shape");
        assertThatThrownBy(() -> decide(copy, "MATERIAL", key, "PENDING", revision, false, null, false)).hasStackTraceContaining("fork_unit_decision_pending_shape");
        assertThatThrownBy(() -> decide(copy, "MATERIAL", key, "PENDING", null, false, other, false)).hasStackTraceContaining("fork_unit_decision_resolved_shape");
        assertThatThrownBy(() -> decide(copy, "MATERIAL", key, "PENDING", revision, true, other, false)).hasStackTraceContaining("fork_unit_decision_resolved_shape");
        // the other decisions have no base, and an author's state: KEEP_MINE one of the two, DECLINE_NEW and KEEP_DELETED a revision
        assertThatThrownBy(() -> decide(copy, "MATERIAL", key, "KEEP_MINE", revision, false, other, false)).hasStackTraceContaining("fork_unit_decision_pending_shape");
        assertThatThrownBy(() -> decide(copy, "MATERIAL", key, "KEEP_MINE", revision, false, null, true)).hasStackTraceContaining("fork_unit_decision_pending_shape");
        assertThatThrownBy(() -> decide(copy, "MATERIAL", key, "KEEP_MINE", null, false, null, false)).hasStackTraceContaining("fork_unit_decision_resolved_shape");
        assertThatThrownBy(() -> decide(copy, "MATERIAL", key, "KEEP_MINE", revision, true, null, false)).hasStackTraceContaining("fork_unit_decision_resolved_shape");
        assertThatThrownBy(() -> decide(copy, "MATERIAL", key, "DECLINE_NEW", null, true, null, false)).hasStackTraceContaining("fork_unit_decision_resolved_shape");
        assertThatThrownBy(() -> decide(copy, "MATERIAL", key, "KEEP_DELETED", null, false, null, false)).hasStackTraceContaining("fork_unit_decision_resolved_shape");
        assertThat(rows(copy)).isEqualTo(11);

        // a row belongs to a copy (a deck_fork row) of its own scope
        UUID ordinary = worlds.shared.deck(UUID.randomUUID(), "Обычная колода");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.fork_unit_decision(deck_id,reuse_scope_id,unit_kind,unit_key,decision,resolved_upstream_revision_id,decided_at) "
                + "SELECT deck_id,reuse_scope_id,'MATERIAL',gen_random_uuid(),'KEEP_MINE',gen_random_uuid(),now() FROM app_learning.deck WHERE deck_id = :d").param("d", ordinary).update())
                .hasStackTraceContaining("fork_unit_decision_fork_fkey");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.fork_unit_decision(deck_id,reuse_scope_id,unit_kind,unit_key,decision,resolved_upstream_revision_id,decided_at) "
                + "VALUES (:d, gen_random_uuid(), 'MATERIAL', gen_random_uuid(), 'KEEP_MINE', gen_random_uuid(), now())").param("d", deck).update())
                .hasStackTraceContaining("fork_unit_decision_deck_fkey");

        // the rows go with the copy's deck_fork row (the account purge removes deck_fork before the deck)
        assertThat(jdbc.sql("DELETE FROM app_learning.deck_fork WHERE deck_id = :d").param("d", deck).update()).isOne();
        assertThat(rows(copy)).isZero();
    }

    @Test
    void theBaseOfACopyMovesForwardOnlyForAReadyAttachedCopyAndNothingElseOfItsIdentityMoves() {
        Source source = worlds.source("LINK", 3, 0);
        Copy copy = worlds.copy(source);
        Copy detached = worlds.copy(source);
        Copy deleted = worlds.copy(source);
        UUID first = worlds.base(copy.deck());
        worlds.editText(source.author(), source.deck(), source.materials().get(0).member(), "second");
        worlds.publish(source.author(), source.deck(), "LINK");
        UUID second = worlds.published(source.deck());
        worlds.editText(source.author(), source.deck(), source.materials().get(1).member(), "third");
        worlds.publish(source.author(), source.deck(), "LINK");
        UUID third = worlds.published(source.deck());
        assertThat(List.of(first, second, third)).doesNotHaveDuplicates();

        // forward, in two steps, and even by skipping one
        worlds.moveBase(copy.deck(), second);
        assertThat(worlds.base(copy.deck())).isEqualTo(second);
        worlds.moveBase(copy.deck(), third);
        assertThat(worlds.base(copy.deck())).isEqualTo(third);
        // never backwards, never sideways, never to a revision that is not the source's
        for (UUID target : new UUID[] {second, first, UUID.randomUUID(), worlds.headRevisionOf(copy.deck())}) {
            assertThatThrownBy(() -> worlds.moveBase(copy.deck(), target)).as(target.toString()).isInstanceOf(DataAccessException.class);
        }
        assertThat(worlds.base(copy.deck())).isEqualTo(third);
        // staying where it is is not a move
        worlds.moveBase(copy.deck(), third);
        // the rest of the identity still never changes, with or without a base move in the same statement (auto_update left this list in V62: it is the owner's setting)
        for (String column : new String[] {"owner_id = gen_random_uuid()", "upstream_deck_id = gen_random_uuid()", "upstream_code = 'zzzzzzzzzz'", "forked_at = now() + interval '1 day'",
                "reuse_scope_id = gen_random_uuid()", "materialized_at = NULL", "job_id = gen_random_uuid()"}) {
            assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck_fork SET " + column + " WHERE deck_id = :d").param("d", copy.deck()).update()).as(column)
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck_fork SET base_revision_id = :base, " + column + " WHERE deck_id = :d").param("base", third).param("d", copy.deck()).update())
                    .as("with a base: " + column).isInstanceOf(DataAccessException.class);
        }

        // a detached copy does not follow the author any more: its base stays
        UUID detachedBase = worlds.base(detached.deck());
        assertThat(worlds.send("POST", "/decks/" + detached.deck() + "/fork:detach", detached.owner(), null, null).statusCode()).isEqualTo(200);
        assertThatThrownBy(() -> worlds.moveBase(detached.deck(), third)).isInstanceOf(DataAccessException.class).hasStackTraceContaining("Invalid deck copy transition");
        assertThat(worlds.base(detached.deck())).isEqualTo(detachedBase);

        // nor does a deleted one
        assertThat(worlds.send("DELETE", "/decks/" + deleted.deck(), deleted.owner(), null,
                "\"" + decks.read(deleted.owner(), deleted.deck()).path("rowVersion").stringValue(null) + "\"").statusCode()).isEqualTo(204);
        assertThatThrownBy(() -> worlds.moveBase(deleted.deck(), third)).isInstanceOf(DataAccessException.class).hasStackTraceContaining("Invalid deck copy transition");
    }

    @Test
    void theBaseNeverMovesToADraftNorOnAPreparingCopyNorTogetherWithADetachOrARelease() {
        Source source = worlds.source("LINK", 3, 0);
        Copy copy = worlds.copy(source);
        Copy preparing = worlds.copy(source);
        Copy together = worlds.copy(source);
        worlds.editText(source.author(), source.deck(), source.materials().get(0).member(), "published");
        worlds.publish(source.author(), source.deck(), "LINK");
        UUID published = worlds.published(source.deck());
        // a later revision of the source that is a draft: saved, never published
        worlds.editText(source.author(), source.deck(), source.materials().get(1).member(), "draft");
        UUID draft = worlds.headRevisionOf(source.deck());
        assertThat(draft).isNotEqualTo(published);
        assertThat(jdbc.sql("SELECT (SELECT sequence FROM app_learning.deck_revision WHERE revision_id = :draft) > (SELECT sequence FROM app_learning.deck_revision WHERE revision_id = :published)")
                .param("draft", draft).param("published", published).query(Boolean.class).single()).isTrue();
        assertThatThrownBy(() -> worlds.moveBase(copy.deck(), draft)).isInstanceOf(DataAccessException.class).hasStackTraceContaining("Invalid deck copy transition");
        worlds.moveBase(copy.deck(), published);
        assertThat(worlds.base(copy.deck())).isEqualTo(published);

        // a copy that is not (yet) materialized keeps its base
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("UPDATE app_learning.deck_fork SET materialized_at = NULL WHERE deck_id = :d").param("d", preparing.deck()).update();
        });
        assertThatThrownBy(() -> worlds.moveBase(preparing.deck(), published)).isInstanceOf(DataAccessException.class).hasStackTraceContaining("Invalid deck copy transition");

        // moving the base and detaching or releasing in ONE statement is refused as well, and the row is unchanged
        UUID before = worlds.base(together.deck());
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck_fork SET base_revision_id = :base, detached_at = now() WHERE deck_id = :d").param("base", published).param("d", together.deck()).update())
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("Invalid deck copy transition");
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck_fork SET base_revision_id = :base, released_at = now() WHERE deck_id = :d").param("base", published).param("d", together.deck()).update())
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("Invalid deck copy transition");
        assertThat(worlds.base(together.deck())).isEqualTo(before);
        assertThat(jdbc.sql("SELECT detached_at IS NULL AND released_at IS NULL FROM app_learning.deck_fork WHERE deck_id = :d").param("d", together.deck()).query(Boolean.class).single()).isTrue();
        // each alone is still fine, and a base move before it is
        worlds.moveBase(together.deck(), published);
        assertThat(jdbc.sql("UPDATE app_learning.deck_fork SET detached_at = now() WHERE deck_id = :d").param("d", together.deck()).update()).isOne();
    }
}
