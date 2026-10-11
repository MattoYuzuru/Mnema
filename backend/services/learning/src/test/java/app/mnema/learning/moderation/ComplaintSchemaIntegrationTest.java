package app.mnema.learning.moderation;

import app.mnema.learning.support.PostgresIntegrationTest;
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

/**
 * V58 (Share/15): the constraints and guard triggers of complaints, takedowns and the journal, and the plans of the statements every reader and the console
 * run. The takedown predicate must be a unique-index probe: it runs for every revision a reader serves.
 */
@SpringBootTest
class ComplaintSchemaIntegrationTest extends PostgresIntegrationTest {
    private static final String CODE = "Kq7xT3mNpR";

    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private UUID complaint(String extraColumns, String extraValues) {
        return jdbc.sql("INSERT INTO app_learning.content_complaint(target_kind, deck_id, public_code, reason, reporter_id, status, created_at, due_at, retain_until"
                        + extraColumns + ") VALUES ('DECK', :deck, :code, 'SPAM', :reporter, 'OPEN', clock_timestamp(), clock_timestamp() + interval '30 days', "
                        + "clock_timestamp() + interval '3 years'" + extraValues + ") RETURNING complaint_id")
                .param("deck", UUID.randomUUID()).param("code", CODE).param("reporter", UUID.randomUUID()).query(UUID.class).single();
    }

    private UUID open() { return complaint("", ""); }

    private void expectRejected(String sql, String message) {
        assertThatThrownBy(() -> jdbc.sql(sql).update()).as(sql).hasMessageContaining(message);
    }

    @Test
    void aComplaintHasExactlyTheShapeOfItsTargetAndOneDecisionOnce() {
        UUID id = open();
        // the target shape
        expectRejected("INSERT INTO app_learning.content_complaint(target_kind, reason, reporter_id, status, created_at, due_at, retain_until) VALUES ('DECK', 'SPAM', gen_random_uuid(), "
                + "'OPEN', now(), now() + interval '30 days', now() + interval '3 years')", "content_complaint_target");
        expectRejected("INSERT INTO app_learning.content_complaint(target_kind, profile_account_id, reason, reporter_id, status, created_at, due_at, retain_until) "
                + "VALUES ('PROFILE', gen_random_uuid(), 'SPAM', gen_random_uuid(), 'OPEN', now(), now() + interval '30 days', now() + interval '3 years')", "content_complaint_target");
        expectRejected("INSERT INTO app_learning.content_complaint(target_kind, deck_id, public_code, profile_account_id, profile_username, reason, reporter_id, status, created_at, "
                + "due_at, retain_until) VALUES ('DECK', gen_random_uuid(), '" + CODE + "', gen_random_uuid(), 'someone', 'SPAM', gen_random_uuid(), 'OPEN', now(), "
                + "now() + interval '30 days', now() + interval '3 years')", "content_complaint_target");
        // closed lists, lengths and times
        expectRejected("UPDATE app_learning.content_complaint SET reason = 'RUDE' WHERE complaint_id = '" + id + "'", "Invalid complaint transition");
        String text = "INSERT INTO app_learning.content_complaint(target_kind, deck_id, public_code, reason, reporter_text, reporter_id, status, created_at, due_at, retain_until) "
                + "VALUES ('DECK', gen_random_uuid(), '" + CODE + "', '%s', %s, gen_random_uuid(), 'OPEN', now(), now() + interval '30 days', now() + interval '3 years')";
        expectRejected(text.formatted("SPAM", "repeat('я', 1001)"), "reporter_text");
        expectRejected(text.formatted("SPAM", "''"), "reporter_text");
        expectRejected(text.formatted("NOPE", "NULL"), "reason");
        expectRejected("INSERT INTO app_learning.content_complaint(target_kind, profile_account_id, profile_username, reason, reporter_id, status, created_at, due_at, retain_until) "
                + "VALUES ('PROFILE', gen_random_uuid(), 'a b', 'SPAM', gen_random_uuid(), 'OPEN', now(), now() + interval '30 days', now() + interval '3 years')", "profile_username");
        expectRejected("INSERT INTO app_learning.content_complaint(target_kind, deck_id, public_code, reason, reporter_id, status, created_at, due_at, retain_until) VALUES "
                + "('DECK', gen_random_uuid(), 'bad code', 'SPAM', gen_random_uuid(), 'OPEN', now(), now() + interval '30 days', now() + interval '3 years')", "public_code");
        expectRejected("INSERT INTO app_learning.content_complaint(target_kind, deck_id, public_code, reason, reporter_id, status, created_at, due_at, retain_until) VALUES "
                + "('DECK', gen_random_uuid(), '" + CODE + "', 'SPAM', gen_random_uuid(), 'OPEN', now(), now() - interval '1 day', now() + interval '3 years')", "content_complaint_times");
        // a decision belongs to a decided complaint, and only the right target takes it
        expectRejected("UPDATE app_learning.content_complaint SET status = 'DECIDED' WHERE complaint_id = '" + id + "'", "content_complaint_decided");
        expectRejected("UPDATE app_learning.content_complaint SET status = 'DECIDED', decision = 'ACCOUNT_REFERRED', decided_at = now(), decided_by = gen_random_uuid() "
                + "WHERE complaint_id = '" + id + "'", "content_complaint_decision_target");
        jdbc.sql("UPDATE app_learning.content_complaint SET status = 'DECIDED', decision = 'HIDDEN', decided_at = now(), decided_by = gen_random_uuid() WHERE complaint_id = :id")
                .param("id", id).update();
        // frozen once decided: no second decision, no reopening, no other column
        expectRejected("UPDATE app_learning.content_complaint SET decision = 'REJECTED' WHERE complaint_id = '" + id + "'", "Invalid complaint transition");
        expectRejected("UPDATE app_learning.content_complaint SET status = 'OPEN', decision = NULL, decided_at = NULL, decided_by = NULL WHERE complaint_id = '" + id + "'",
                "Invalid complaint transition");
        expectRejected("UPDATE app_learning.content_complaint SET reporter_id = gen_random_uuid() WHERE complaint_id = '" + id + "'", "Invalid complaint transition");
        // retention: not deletable (not even by an owner of the table) before retain_until, never truncated
        expectRejected("DELETE FROM app_learning.content_complaint WHERE complaint_id = '" + id + "'", "kept until its retention ends");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE app_learning.content_complaint").update()).isNotNull();
        // once its retention has ended (here: a complaint filed four years ago) the row can be purged; this is the whole of the retention rule in the schema
        UUID old = jdbc.sql("INSERT INTO app_learning.content_complaint(target_kind, deck_id, public_code, reason, reporter_id, status, created_at, due_at, retain_until) "
                + "VALUES ('DECK', gen_random_uuid(), '" + CODE + "', 'SPAM', gen_random_uuid(), 'OPEN', now() - interval '4 years', now() - interval '4 years' + interval '30 days', "
                + "now() - interval '4 years' + interval '3 years') RETURNING complaint_id").query(UUID.class).single();
        assertThat(jdbc.sql("DELETE FROM app_learning.content_complaint WHERE complaint_id = :id").param("id", old).update()).isEqualTo(1);
    }

    private static final String NIL = "00000000-0000-0000-0000-000000000000";

    /** A DECIDED complaint filed four years ago (its 3-year retention has ended), with a takedown that references it when {@code takedown}. */
    private UUID expired(boolean takedown) {
        UUID id = jdbc.sql("INSERT INTO app_learning.content_complaint(target_kind, deck_id, public_code, reason, reporter_text, reporter_id, status, decision, created_at, "
                + "due_at, decided_at, decided_by, retain_until) VALUES ('DECK', gen_random_uuid(), '" + CODE + "', 'COPYRIGHT', 'мой текст', gen_random_uuid(), 'DECIDED', "
                + "'TAKEN_DOWN', now() - interval '4 years', now() - interval '4 years' + interval '30 days', now() - interval '4 years' + interval '2 days', gen_random_uuid(), "
                + "now() - interval '1 year') RETURNING complaint_id").query(UUID.class).single();
        if (takedown) {
            jdbc.sql("INSERT INTO app_learning.content_takedown(reuse_scope_id, unit_kind, unit_key, revision_id, complaint_id, created_at) "
                    + "VALUES (gen_random_uuid(), 'ITEM', gen_random_uuid(), gen_random_uuid(), :id, now())").param("id", id).update();
        }
        return id;
    }

    @Test
    void pastItsRetentionADecidedComplaintIsAnonymisedEvenWithAStandingTakedownAndNothingElseMoves() {
        UUID referenced = expired(true);
        UUID unreferenced = expired(false);
        // the purge contract (#409): a complaint with a standing takedown cannot be deleted, but its personal data can go
        expectRejected("DELETE FROM app_learning.content_complaint WHERE complaint_id = '" + referenced + "'", "");
        assertThat(jdbc.sql("UPDATE app_learning.content_complaint SET reporter_text = NULL, reporter_id = '" + NIL + "' WHERE complaint_id = :id").param("id", referenced).update())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT reporter_text IS NULL AND reporter_id = '" + NIL + "' FROM app_learning.content_complaint WHERE complaint_id = :id").param("id", referenced)
                .query(Boolean.class).single()).isTrue();
        // idempotent, and still anonymised-only
        assertThat(jdbc.sql("UPDATE app_learning.content_complaint SET reporter_text = NULL, reporter_id = '" + NIL + "' WHERE complaint_id = :id").param("id", referenced).update())
                .isEqualTo(1);
        for (String change : List.of("reason = 'SPAM'", "decision = 'HIDDEN'", "reporter_text = 'again'", "reporter_id = gen_random_uuid()", "public_code = 'AAAAAAAAAA'",
                "deck_id = gen_random_uuid()", "decided_by = gen_random_uuid()", "retain_until = now() + interval '10 years'", "status = 'OPEN'", "due_at = now()")) {
            expectRejected("UPDATE app_learning.content_complaint SET " + change + " WHERE complaint_id = '" + referenced + "'", "Invalid complaint transition");
        }
        // anonymising AND changing anything else in one statement is not anonymising
        expectRejected("UPDATE app_learning.content_complaint SET reporter_text = NULL, reporter_id = '" + NIL + "', reason = 'SPAM' WHERE complaint_id = '" + unreferenced + "'",
                "Invalid complaint transition");
        expectRejected("UPDATE app_learning.content_complaint SET reporter_id = '" + NIL + "' WHERE complaint_id = '" + unreferenced + "'", "Invalid complaint transition");
        // a complaint whose retention has NOT ended, and an OPEN one, can never be anonymised
        UUID live = open();
        expectRejected("UPDATE app_learning.content_complaint SET reporter_text = NULL, reporter_id = '" + NIL + "' WHERE complaint_id = '" + live + "'", "Invalid complaint transition");
        UUID decidedLive = open();
        jdbc.sql("UPDATE app_learning.content_complaint SET status = 'DECIDED', decision = 'REJECTED', decided_at = now(), decided_by = gen_random_uuid() WHERE complaint_id = :id")
                .param("id", decidedLive).update();
        expectRejected("UPDATE app_learning.content_complaint SET reporter_text = NULL, reporter_id = '" + NIL + "' WHERE complaint_id = '" + decidedLive + "'",
                "Invalid complaint transition");
        // the one without a takedown can simply be deleted
        assertThat(jdbc.sql("DELETE FROM app_learning.content_complaint WHERE complaint_id = :id").param("id", unreferenced).update()).isEqualTo(1);
    }

    @Test
    void takedownAssetsAreImmutableAndKeyedByTheLineage() {
        UUID complaint = open();
        UUID scope = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        UUID takedown = jdbc.sql("INSERT INTO app_learning.content_takedown(reuse_scope_id, unit_kind, unit_key, revision_id, complaint_id, created_at) "
                + "VALUES (:scope, 'ITEM', gen_random_uuid(), gen_random_uuid(), :complaint, now()) RETURNING takedown_id").param("scope", scope).param("complaint", complaint)
                .query(UUID.class).single();
        jdbc.sql("INSERT INTO app_learning.content_takedown_asset(reuse_scope_id, asset_id, takedown_id) VALUES (:scope, :asset, :takedown)")
                .param("scope", scope).param("asset", asset).param("takedown", takedown).update();
        expectRejected("INSERT INTO app_learning.content_takedown_asset(reuse_scope_id, asset_id, takedown_id) VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid())", "foreign key");
        expectRejected("UPDATE app_learning.content_takedown_asset SET asset_id = gen_random_uuid() WHERE takedown_id = '" + takedown + "'", "immutable");
        expectRejected("DELETE FROM app_learning.content_takedown_asset WHERE takedown_id = '" + takedown + "'", "immutable");
        expectRejected("TRUNCATE app_learning.content_takedown_asset", "immutable");
        assertThat(jdbc.sql("SELECT " + ContentTakedowns.blockedAsset(":scope", ":asset")).param("scope", scope).param("asset", asset).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT " + ContentTakedowns.blockedAsset(":scope", ":asset")).param("scope", UUID.randomUUID()).param("asset", asset).query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT " + ContentTakedowns.blockedAssetAnywhere(":asset")).param("asset", asset).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT " + ContentTakedowns.blockedAssetAnywhere(":asset")).param("asset", UUID.randomUUID()).query(Boolean.class).single()).isFalse();
    }

    @Test
    void aReporterHasOneOpenComplaintPerTargetAndADecidedOneDoesNotCount() {
        UUID reporter = UUID.randomUUID();
        UUID deck = UUID.randomUUID();
        String insert = "INSERT INTO app_learning.content_complaint(target_kind, deck_id, public_code, reason, reporter_id, status, created_at, due_at, retain_until%s) VALUES "
                + "('DECK', '" + deck + "', '" + CODE + "', 'SPAM', '" + reporter + "', '%s', now(), now() + interval '30 days', now() + interval '3 years'%s)";
        jdbc.sql(insert.formatted("", "OPEN", "")).update();
        expectRejected(insert.formatted("", "OPEN", ""), "content_complaint_open_per_reporter");
        jdbc.sql(insert.formatted(", decision, decided_at, decided_by", "DECIDED", ", 'REJECTED', now(), gen_random_uuid()")).update();
        // another target of the same reporter and the same deck by another reporter are independent
        jdbc.sql(insert.formatted("", "OPEN", "").replace("'" + deck + "'", "'" + UUID.randomUUID() + "'")).update();
        jdbc.sql(insert.formatted("", "OPEN", "").replace("'" + reporter + "'", "'" + UUID.randomUUID() + "'")).update();
    }

    @Test
    void aTakedownIsUniquePerRevisionOfTheLineageImmutableAndPinsItsComplaint() {
        UUID complaint = open();
        UUID scope = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        String insert = "INSERT INTO app_learning.content_takedown(reuse_scope_id, unit_kind, unit_key, revision_id, complaint_id, created_at) VALUES ('" + scope + "', '%s', '"
                + member + "', '" + revision + "', '%s', now())";
        jdbc.sql(insert.formatted("ITEM", complaint)).update();
        expectRejected(insert.formatted("ITEM", complaint), "content_takedown_revision_key");
        jdbc.sql(insert.formatted("EXERCISE", complaint)).update();
        expectRejected(insert.formatted("DECK", complaint), "unit_kind");
        expectRejected(insert.formatted("ITEM", UUID.randomUUID()).replace("'" + revision + "'", "'" + UUID.randomUUID() + "'"), "foreign key");
        expectRejected("UPDATE app_learning.content_takedown SET unit_key = gen_random_uuid() WHERE complaint_id = '" + complaint + "'", "immutable");
        expectRejected("DELETE FROM app_learning.content_takedown WHERE complaint_id = '" + complaint + "'", "immutable");
        // (the foreign key of content_takedown_asset already refuses the truncate; the trigger refuses it too when nothing references the table)
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE app_learning.content_takedown").update()).isNotNull();
        // a complaint with a standing takedown is never deleted, whatever its retention
        expectRejected("DELETE FROM app_learning.content_complaint WHERE complaint_id = '" + complaint + "'", "");
    }

    @Test
    void theJournalIsAppendOnlyUntilItsRetentionEnds() {
        UUID complaint = UUID.randomUUID();
        String insert = "INSERT INTO app_learning.content_takedown_journal(complaint_id, action, actor_id, occurred_at, target_kind, deck_id, public_code, unit_count, retain_until) "
                + "VALUES ('" + complaint + "', '%s', gen_random_uuid(), now(), 'DECK', gen_random_uuid(), '" + CODE + "', %d, %s)";
        jdbc.sql(insert.formatted("TAKEN_DOWN", 2, "now() + interval '3 years'")).update();
        jdbc.sql(insert.formatted("UNHIDDEN", 0, "now() + interval '3 years'")).update();
        expectRejected(insert.formatted("DELETED", 0, "now() + interval '3 years'"), "action");
        expectRejected(insert.formatted("HIDDEN", 1, "now() + interval '3 years'"), "content_takedown_journal_units");
        expectRejected("UPDATE app_learning.content_takedown_journal SET action = 'HIDDEN' WHERE complaint_id = '" + complaint + "'", "append-only");
        expectRejected("DELETE FROM app_learning.content_takedown_journal WHERE complaint_id = '" + complaint + "'", "append-only");
        expectRejected("TRUNCATE app_learning.content_takedown_journal", "append-only");
        // a row whose retention has passed may be removed by the purge job that is open work (#409); it can never be rewritten
        UUID expired = UUID.randomUUID();
        jdbc.sql(insert.formatted("REJECTED", 0, "now() - interval '1 day'").replace(complaint.toString(), expired.toString())).update();
        expectRejected("UPDATE app_learning.content_takedown_journal SET unit_count = 1 WHERE complaint_id = '" + expired + "'", "append-only");
        assertThat(jdbc.sql("DELETE FROM app_learning.content_takedown_journal WHERE complaint_id = :id").param("id", expired).update()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.content_takedown_journal WHERE complaint_id = :id").param("id", complaint).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void theConsoleAuditAcceptsTheComplaintActionsAndDeckPublicationKeepsItsFlagAndTimeTogether() {
        for (String action : List.of("COMPLAINT_DECIDE", "COMPLAINT_UNHIDE")) {
            jdbc.sql("INSERT INTO app_learning.admin_audit(actor_account_id, action, resource_id) VALUES (gen_random_uuid(), :action, gen_random_uuid())")
                    .param("action", action).update();
        }
        expectRejected("INSERT INTO app_learning.admin_audit(actor_account_id, action, resource_id) VALUES (gen_random_uuid(), 'COMPLAINT_ERASE', gen_random_uuid())", "admin_audit_action_check");
        // the constraint exists on the publication table, with a column default of "not hidden"
        assertThat(jdbc.sql("SELECT column_default FROM information_schema.columns WHERE table_schema = 'app_learning' AND table_name = 'deck_publication' "
                + "AND column_name = 'hidden_by_moderation'").query(String.class).single()).isEqualTo("false");
        assertThat(jdbc.sql("SELECT count(*) FROM pg_constraint WHERE conname = 'deck_publication_hidden'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void theTakedownPredicateIsAUniqueIndexProbeAndTheQueueUsesItsPartialIndexes() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        List<String> plans = tx.execute(status -> {
            // enough rows that a sequential scan would be the planner's choice if the lookup key were not an index
            jdbc.sql("""
                    INSERT INTO app_learning.content_complaint(target_kind, deck_id, public_code, reason, reporter_id, status, created_at, due_at, retain_until)
                    SELECT 'DECK', gen_random_uuid(), 'Kq7xT3mNpR', 'SPAM', gen_random_uuid(), 'OPEN', clock_timestamp() - make_interval(secs => n),
                           clock_timestamp() + interval '30 days' - make_interval(secs => n), clock_timestamp() + interval '3 years'
                      FROM generate_series(1, 2000) AS n
                    """).update();
            UUID complaint = jdbc.sql("SELECT complaint_id FROM app_learning.content_complaint LIMIT 1").query(UUID.class).single();
            jdbc.sql("INSERT INTO app_learning.content_takedown(reuse_scope_id, unit_kind, unit_key, revision_id, complaint_id, created_at) "
                    + "SELECT gen_random_uuid(), 'ITEM', gen_random_uuid(), gen_random_uuid(), :complaint, now() FROM generate_series(1, 2000)").param("complaint", complaint).update();
            jdbc.sql("ANALYZE app_learning.content_complaint").update();
            jdbc.sql("ANALYZE app_learning.content_takedown").update();
            List<String> result = new java.util.ArrayList<>();
            result.add(String.join("\n", jdbc.sql("EXPLAIN SELECT " + ContentTakedowns.blockedItem(":scope", ":member", ":revision"))
                    .param("scope", UUID.randomUUID()).param("member", UUID.randomUUID()).param("revision", UUID.randomUUID()).query(String.class).list()));
            result.add(String.join("\n", jdbc.sql("EXPLAIN SELECT " + ContentTakedowns.blockedExercise(":scope", ":member", ":revision"))
                    .param("scope", UUID.randomUUID()).param("member", UUID.randomUUID()).param("revision", UUID.randomUUID()).query(String.class).list()));
            jdbc.sql("INSERT INTO app_learning.content_takedown_asset(reuse_scope_id, asset_id, takedown_id) SELECT reuse_scope_id, gen_random_uuid(), takedown_id "
                    + "FROM app_learning.content_takedown LIMIT 2000").update();
            jdbc.sql("ANALYZE app_learning.content_takedown_asset").update();
            result.add(String.join("\n", jdbc.sql("EXPLAIN SELECT " + ContentTakedowns.blockedAsset(":scope", ":asset")).param("scope", UUID.randomUUID())
                    .param("asset", UUID.randomUUID()).query(String.class).list()));
            result.add(String.join("\n", jdbc.sql("EXPLAIN SELECT " + ContentTakedowns.blockedAssetAnywhere(":asset")).param("asset", UUID.randomUUID()).query(String.class).list()));
            result.add(String.join("\n", jdbc.sql("EXPLAIN SELECT complaint_id FROM app_learning.content_complaint WHERE status = 'OPEN' ORDER BY due_at, complaint_id LIMIT 51")
                    .query(String.class).list()));
            result.add(String.join("\n", jdbc.sql("EXPLAIN SELECT complaint_id FROM app_learning.content_complaint WHERE status = 'DECIDED' "
                    + "ORDER BY decided_at DESC, complaint_id DESC LIMIT 51").query(String.class).list()));
            result.add(String.join("\n", jdbc.sql("EXPLAIN SELECT created_at FROM app_learning.content_complaint WHERE reporter_id = :reporter "
                    + "AND created_at > now() - interval '1 day' ORDER BY created_at").param("reporter", UUID.randomUUID()).query(String.class).list()));
            status.setRollbackOnly();
            return result;
        });
        assertThat(plans.get(0)).contains("content_takedown_revision_key").doesNotContain("Seq Scan");
        assertThat(plans.get(1)).contains("content_takedown_revision_key").doesNotContain("Seq Scan");
        assertThat(plans.get(2)).contains("Index Scan using content_takedown_asset_").doesNotContain("Seq Scan");
        assertThat(plans.get(3)).contains("content_takedown_asset_asset").doesNotContain("Seq Scan");
        assertThat(plans.get(4)).contains("content_complaint_queue").doesNotContain("Seq Scan");
        assertThat(plans.get(5)).contains("content_complaint_decided").doesNotContain("Seq Scan");
        assertThat(plans.get(6)).contains("content_complaint_reporter").doesNotContain("Seq Scan");
    }

    @Test
    void theFragmentsRefuseAnythingThatIsNotAColumnOrAParameter() {
        for (String bad : List.of("1; DROP TABLE x", "a.b OR TRUE", "(SELECT 1)", "", "A.b", "a b")) {
            assertThatThrownBy(() -> ContentTakedowns.blockedItem(bad, "p.member", "p.rev")).as(bad).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ContentTakedowns.blockedExercise("p.scope", bad, "p.rev")).as(bad).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ContentTakedowns.blockedPresentation("p.deck", "p.ex", bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ContentTakedowns.blockedExerciseOrQuote("p.scope", "p.ex", bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ContentTakedowns.blockedAsset("p.scope", bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ContentTakedowns.blockedAssetAnywhere(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(ContentTakedowns.blockedItem(":scope", "h.member_key", "h.revision_id")).contains("unit_kind='ITEM'").contains("takedown.unit_key=h.member_key");
    }
}
