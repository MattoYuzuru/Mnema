package app.mnema.learning.onboarding;

import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.JobState;
import app.mnema.learning.platform.jobs.LearningJobs;
import app.mnema.learning.platform.jobs.Slice;
import app.mnema.learning.support.StudyFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backfill job over an H-shaped synthetic dataset (OB-302): 3000 accounts with a realistic mix of history in every source table (built in bulk, constraints off, as a restored
 * database would have it), run as the executor runs it, slice by slice with a cursor. A run twice changes nothing; a run interrupted after every statement ends in the same table as
 * one run; the backfill writes no notification; and the statement of every source is index-driven (EXPLAIN (ANALYZE, BUFFERS) printed for the evidence file).
 */
class OnboardingBackfillJobIntegrationTest extends SampleDeckIntegrationTest {
    private static final int ACCOUNTS = 3_000;
    private static final AtomicBoolean BUILT = new AtomicBoolean();
    private static final String H = "(SELECT md5('acct' || g)::uuid FROM generate_series(1, " + ACCOUNTS + ") g)";

    @Autowired private OnboardingBackfill handler;
    @Autowired private BackfillRepository backfill;
    @Autowired private MeterRegistry meters;
    @Autowired private LearningJobs jobs;

    private void tx(Runnable work) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> work.run());
    }

    private void sql(String sql) {
        jdbc.sql(sql).param("n", ACCOUNTS).update();
    }

    /** The accounts: {@code a(i, id)}. */
    private static final String A = "WITH a AS (SELECT g AS i, md5('acct' || g)::uuid AS id FROM generate_series(1, :n) g) ";

    private void build() {
        synchronized (BUILT) {
            if (BUILT.get()) return;
            StudyFixtures fixtures = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
            StudyFixtures.Material material = fixtures.material();
            fixtures.publish(material, fixtures.freeResponse(material, blocks(text("Вопрос?")), blocks(text("Ответ")), "ответ"), "Цель");
            UUID templateSession = fixtures.issueOne(material).session();

            tx(() -> {
                sql("SET LOCAL session_replication_role = replica");
                sql(A + "INSERT INTO app_learning.learning_profile(owner_id, goal, answered_at) SELECT id, 'WORK', now() - i * interval '1 minute' FROM a WHERE i % 2 = 0");
                // decks: one or two of the account's own, a deleted one for some, the sample deck for a third
                sql(A + """
                        INSERT INTO app_learning.deck(deck_id, owner_id, reuse_scope_id, head_revision_id, row_version, created_at, deleted_at, purpose)
                        SELECT md5('deck' || i || '-' || j)::uuid, id, md5('scope' || i || '-' || j)::uuid, gen_random_uuid(), 0, now() - (i * 3 + j) * interval '1 hour',
                               CASE WHEN j = 2 AND i % 7 = 0 THEN now() END, 'USER'
                          FROM a CROSS JOIN generate_series(1, 2) j WHERE j = 1 OR i % 4 <> 3
                        """);
                sql(A + """
                        INSERT INTO app_learning.deck(deck_id, owner_id, reuse_scope_id, head_revision_id, row_version, created_at, purpose)
                        SELECT md5('sample' || i)::uuid, id, md5('sscope' || i)::uuid, gen_random_uuid(), 0, now() - i * interval '1 hour', 'SAMPLE' FROM a WHERE i % 3 = 0
                        """);
                // materials: three in the first deck of two accounts out of three, two in a sample deck (they must not count)
                sql(A + """
                        INSERT INTO app_learning.learning_item(deck_id, member_key, owner_id, reuse_scope_id, created_at)
                        SELECT md5('deck' || i || '-1')::uuid, md5('item' || i || '-' || k)::uuid, id, md5('scope' || i || '-1')::uuid, now() - (i + k) * interval '1 minute'
                          FROM a CROSS JOIN generate_series(1, 3) k WHERE i % 3 <> 0
                        """);
                sql(A + """
                        INSERT INTO app_learning.learning_item(deck_id, member_key, owner_id, reuse_scope_id, created_at)
                        SELECT md5('sample' || i)::uuid, md5('sitem' || i || '-' || k)::uuid, id, md5('sscope' || i)::uuid, now() - (i + k) * interval '1 minute'
                          FROM a CROSS JOIN generate_series(1, 2) k WHERE i % 3 = 0
                        """);
                // a revision of the first material, with a picture for one account in five and without for the next
                sql(A + """
                        INSERT INTO app_learning.item_revision(deck_id, member_key, revision_id, reuse_scope_id, owner_id, item_sequence, deck_revision_id, deck_sequence, command_id,
                                                               format_version, content_root_id, descriptor_root_id, created_at)
                        SELECT md5('deck' || i || '-1')::uuid, md5('item' || i || '-1')::uuid, md5('rev' || i)::uuid, md5('scope' || i || '-1')::uuid, id, 0, gen_random_uuid(), 1,
                               gen_random_uuid(), 1, gen_random_uuid(), gen_random_uuid(), now() - i * interval '30 seconds'
                          FROM a WHERE i % 3 <> 0 AND i % 5 IN (0, 1)
                        """);
                sql(A + """
                        INSERT INTO app_learning.content_media_ref(deck_id, member_key, revision_id, node_id, asset_owner_id, asset_id, reuse_scope_id)
                        SELECT md5('deck' || i || '-1')::uuid, md5('item' || i || '-1')::uuid, md5('rev' || i)::uuid, gen_random_uuid(), id, gen_random_uuid(), md5('scope' || i || '-1')::uuid
                          FROM a WHERE i % 3 <> 0 AND i % 5 = 0
                        """);
                // exercises: none, one, two or five per account
                sql(A + """
                        INSERT INTO app_learning.exercise_definition(deck_id, exercise_id, owner_id, reuse_scope_id, created_at)
                        SELECT md5('deck' || i || '-1')::uuid, md5('ex' || i || '-' || k)::uuid, id, md5('scope' || i || '-1')::uuid, now() - (i + k) * interval '1 minute'
                          FROM a CROSS JOIN generate_series(1, 5) k WHERE k <= CASE i % 6 WHEN 0 THEN 5 WHEN 1 THEN 1 WHEN 2 THEN 2 ELSE 0 END
                        """);
                // notes: saved, archived and converted
                sql(A + """
                        INSERT INTO app_learning.capture_note(note_id, owner_id, deck_id, row_version, source, note_text, archived, created_at, updated_at, reuse_scope_id)
                        SELECT md5('note' || i || '-' || k)::uuid, id, md5('deck' || i || '-1')::uuid, 0, 'источник', 'заметка', k = 2, now() - i * interval '1 hour', now() - i * interval '1 minute',
                               md5('scope' || i || '-1')::uuid
                          FROM a CROSS JOIN generate_series(1, 2) k WHERE i % 5 = 2 AND (k = 1 OR i % 10 = 2)
                        """);
                sql(A + """
                        INSERT INTO app_learning.capture_note(note_id, owner_id, deck_id, row_version, source, note_text, archived, created_at, updated_at, reuse_scope_id,
                                                              conversion_command_id, conversion_payload_hash, converted_member_key, converted_revision_id, converted_at, conversion_result)
                        SELECT md5('cnote' || i)::uuid, id, md5('deck' || i || '-1')::uuid, 1, 'источник', 'заметка', false, now() - i * interval '1 hour', now() - i * interval '1 minute',
                               md5('scope' || i || '-1')::uuid, gen_random_uuid(), decode(repeat('ab', 32), 'hex'), gen_random_uuid(), gen_random_uuid(), now() - i * interval '1 minute', '{}'
                          FROM a WHERE i % 15 = 7
                        """);
                // copies of somebody else's deck (and of the sample template: they must not count). A copy of the template is a SAMPLE deck of its own, as copyTemplate
                // writes it (deleted here, so the one-live-sample index does not meet the sample decks above).
                sql(A + """
                        INSERT INTO app_learning.deck(deck_id, owner_id, reuse_scope_id, head_revision_id, row_version, created_at, deleted_at, purpose)
                        SELECT md5('tcopy' || i)::uuid, id, md5('tscope' || i)::uuid, gen_random_uuid(), 0, now() - i * interval '1 hour', now(), 'SAMPLE' FROM a WHERE i % 55 = 0
                        """);
                sql(A + """
                        INSERT INTO app_learning.deck_fork(deck_id, reuse_scope_id, owner_id, upstream_deck_id, upstream_code, base_revision_id, forked_at, job_id)
                        SELECT CASE WHEN i % 11 = 0 THEN md5('tcopy' || i)::uuid ELSE md5('deck' || i || '-1')::uuid END,
                               CASE WHEN i % 11 = 0 THEN md5('tscope' || i)::uuid ELSE md5('scope' || i || '-1')::uuid END, id,
                               CASE WHEN i % 11 = 0 THEN md5('sample' || (i % 3 * 0 + 3 * ((i % 100) + 1)))::uuid ELSE md5('deck' || (i % :n + 1) || '-1')::uuid END,
                               'abcdefghjk', gen_random_uuid(), now() - i * interval '1 minute', gen_random_uuid()
                          FROM a WHERE i % 5 = 0
                        """);
                // the evidence of the model, the spoken answers, the Workshop
                sql(A + """
                        INSERT INTO app_learning.study_evidence(attempt_id, account_id, deck_id, objective_id, objective_revision_id, learning_epoch, result, evidence_class, reason_codes,
                                                                evaluator_id, evaluator_version, hints_used, duration_ms, accepted_at)
                        SELECT gen_random_uuid(), id, md5('deck' || i || '-1')::uuid, gen_random_uuid(), gen_random_uuid(), 0, 'CORRECT', 'LOW', '[]',
                               CASE WHEN k = 1 THEN 'self-check' ELSE 'ai-semantic' END, '1', '[]', 1000, now() - (i + k) * interval '1 minute'
                          FROM a CROSS JOIN generate_series(1, 3) k WHERE i % 20 = 0 OR (i % 4 = 1 AND k = 1)
                        """);
                sql(A + """
                        INSERT INTO app_learning.study_assessment(attempt_id, account_id, session_id, presentation_id, deck_id, state, strictness, payload_hash, answer_source, duration_ms,
                                                                  created_at, deadline_at, expires_at, resolved_at)
                        SELECT gen_random_uuid(), id, gen_random_uuid(), gen_random_uuid(), md5('deck' || i || '-1')::uuid, 'DONE', 'S1', decode(repeat('ab', 32), 'hex'),
                               CASE WHEN i % 25 = 0 THEN 'SPEECH' ELSE 'TYPED' END, 1000, now() - i * interval '1 minute', now(), now(), now()
                          FROM a WHERE i % 25 = 0 OR i % 9 = 1
                        """);
                sql(A + """
                        INSERT INTO app_learning.generation_provenance(provenance_id, owner_id, session_id, artifact_id, revision_id, published_ref, created_at)
                        SELECT gen_random_uuid(), id, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), '{}', now() - i * interval '1 minute' FROM a WHERE i % 30 = 0
                        """);
            });
            // the sessions are clones of a real one: one to eight completed ones on up to three days for six accounts of ten, a few practice, a few still active
            String columns = jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema = 'app_learning' AND table_name = 'study_session' ORDER BY ordinal_position")
                    .query(String.class).list().stream().collect(Collectors.joining(","));
            String selected = jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema = 'app_learning' AND table_name = 'study_session' ORDER BY ordinal_position")
                    .query(String.class).list().stream().map(column -> switch (column) {
                        case "account_id" -> "a.id";
                        case "session_id", "command_id" -> "gen_random_uuid()";
                        case "mode" -> "CASE WHEN a.i % 3 = 0 AND s % 2 = 0 THEN 'PRACTICE' ELSE 'SCHEDULED' END";
                        case "practice_order" -> "CASE WHEN a.i % 3 = 0 AND s % 2 = 0 THEN 'SEEDED' END";
                        case "status" -> "CASE WHEN a.i % 13 = 0 THEN 'ACTIVE' ELSE 'COMPLETE' END";
                        case "local_study_date" -> "CURRENT_DATE - (s % 3)";
                        case "completed_at" -> "CASE WHEN a.i % 13 = 0 THEN NULL ELSE now() - (s % 3) * interval '1 day' - a.i * interval '1 minute' END";
                        default -> "t." + column;
                    }).collect(Collectors.joining(","));
            jdbc.sql("INSERT INTO app_learning.study_session(" + columns + ") " + A.replace("WITH a AS (", "WITH a AS (") + "SELECT " + selected
                            + " FROM app_learning.study_session t CROSS JOIN a CROSS JOIN LATERAL generate_series(1, 1 + a.i % 8) s WHERE t.session_id = :template AND a.i % 10 < 6")
                    .param("n", ACCOUNTS).param("template", templateSession).update();
            for (String table : new String[] {"learning_profile", "deck", "learning_item", "item_revision", "content_media_ref", "exercise_definition", "capture_note", "deck_fork",
                    "study_evidence", "study_assessment", "generation_provenance", "study_session", "account_milestone"}) {
                jdbc.sql("ANALYZE app_learning." + table).update();
            }
            BUILT.set(true);
        }
    }

    private record Run(long rows, int slices, List<ObjectNode> cursors) { }

    /** The job as the executor drives it: a slice per call, each in its own transaction, the cursor carried from one to the next. */
    private Run run(int maxBatches) {
        ObjectNode progress = null;
        long count = 0;
        int slices = 0;
        List<ObjectNode> cursors = new ArrayList<>();
        while (true) {
            Job job = new Job(UUID.randomUUID(), OnboardingBackfill.QUEUE, null, JsonNodeFactory.instance.objectNode().put("registryVersion", 1), progress, count, 1);
            Slice slice = new TransactionTemplate(transactions).execute(status -> handler.run(job, maxBatches));
            slices++;
            if (slice instanceof Slice.Done done) return new Run(done.progressCount(), slices, cursors);
            Slice.Continue next = (Slice.Continue) slice;
            progress = next.progress();
            count = next.progressCount();
            cursors.add(progress);
            assertThat(slices).as("the run makes progress").isLessThan(10_000);
        }
    }

    /** The rows of the dataset's accounts, in a comparable order. */
    private List<String> snapshot() {
        return jdbc.sql("SELECT account_id || ' ' || milestone_key || ' ' || progress || ' ' || COALESCE(achieved_at::text, '-') || ' ' || first_at || ' ' || source "
                + "FROM app_learning.account_milestone WHERE account_id IN " + H + " ORDER BY account_id, milestone_key").query(String.class).list();
    }

    private long notifications() {
        return jdbc.sql("SELECT count(*) FROM app_learning.notification").query(Long.class).single();
    }

    @Test
    void aRunTwiceChangesNothingAnInterruptedRunEndsInTheSameTableAndNothingIsAnnounced() {
        build();
        double rowsBefore = meters.counter("mnema_onboarding_backfill_rows_total", "key", "FIRST_DECK").count();
        double achievedBefore = meters.counter("mnema_path_milestone_total", "key", "FIRST_DECK", "source", "BACKFILL").count();
        long announced = notifications();

        // interrupted: one statement per slice, the cursor carried in between
        Run interrupted = run(1);
        List<String> first = snapshot();
        assertThat(interrupted.slices()).as("many slices").isGreaterThan(20);
        assertThat(interrupted.cursors().getFirst().propertyNames()).containsExactlyInAnyOrder("key", "after");
        assertThat(interrupted.cursors().stream().map(cursor -> cursor.path("key").stringValue()).distinct()).as("the cursor walks the keys in registry order")
                .containsSubsequence("GOAL_TOLD", "FIRST_DECK", "FIRST_SESSION", "OWN_MATERIAL", "TWO_DAYS", "COMMUNITY_COPY");
        assertThat(first).isNotEmpty();
        assertThat(notifications()).as("the backfill never announces").isEqualTo(announced);
        assertThat(meters.counter("mnema_onboarding_backfill_rows_total", "key", "FIRST_DECK").count() - rowsBefore).isGreaterThanOrEqualTo(ACCOUNTS);
        assertThat(meters.counter("mnema_path_milestone_total", "key", "FIRST_DECK", "source", "BACKFILL").count() - achievedBefore).isGreaterThanOrEqualTo(ACCOUNTS);

        // one run from nothing ends in the same rows, to the microsecond (the times are the history's, not the clock's)
        jdbc.sql("DELETE FROM app_learning.account_milestone WHERE source = 'BACKFILL'").update();
        Run single = run(Integer.MAX_VALUE);
        assertThat(snapshot()).isEqualTo(first);
        assertThat(single.rows()).isPositive();

        // and again: no row is written
        Run again = run(Integer.MAX_VALUE);
        assertThat(again.rows()).isZero();
        assertThat(snapshot()).isEqualTo(first);
        Run againInterrupted = run(3);
        assertThat(againInterrupted.rows()).isZero();
        assertThat(snapshot()).isEqualTo(first);
        assertThat(notifications()).isEqualTo(announced);

        // the dataset has every shape: what the formulas of its construction say
        Map<String, Long> perKey = new TreeMap<>();
        jdbc.sql("SELECT milestone_key, count(*) FROM app_learning.account_milestone WHERE account_id IN " + H + " GROUP BY milestone_key")
                .query((row, n) -> perKey.put(row.getString(1), row.getLong(2))).list();
        assertThat(perKey).doesNotContainKey("RHYTHM_WEEK");
        assertThat(perKey).containsEntry("GOAL_TOLD", (long) ACCOUNTS / 2).containsEntry("FIRST_DECK", (long) ACCOUNTS);
        for (MilestoneKey key : MilestoneKey.values()) if (key != MilestoneKey.RHYTHM_WEEK) assertThat(perKey).as(key.name()).containsKey(key.name());
        assertThat(count("SELECT count(*) FROM app_learning.account_milestone WHERE account_id IN " + H + " AND milestone_key = 'OWN_EXERCISES' AND progress = 3 AND achieved_at IS NOT NULL"))
                .as("five exercises cap at three").isEqualTo(count("SELECT count(*) FROM generate_series(1, " + ACCOUNTS + ") i WHERE i % 6 = 0"));
        assertThat(count("SELECT count(*) FROM app_learning.account_milestone WHERE account_id IN " + H + " AND milestone_key = 'CAPTURE_NOTE' AND progress = 1 AND achieved_at IS NULL")).isPositive();
        assertThat(count("SELECT count(*) FROM app_learning.account_milestone WHERE account_id IN " + H + " AND milestone_key = 'CAPTURE_NOTE' AND progress = 2 AND achieved_at IS NOT NULL")).isPositive();
        assertThat(count("SELECT count(*) FROM app_learning.account_milestone WHERE account_id IN " + H + " AND milestone_key = 'TWO_DAYS' AND progress = 1 AND achieved_at IS NULL")).isPositive();
        assertThat(count("SELECT count(*) FROM app_learning.account_milestone WHERE account_id IN " + H + " AND milestone_key = 'COMMUNITY_COPY'"))
                .as("the copies of the sample template are not community copies").isEqualTo(count("SELECT count(*) FROM generate_series(1, " + ACCOUNTS + ") i WHERE i % 5 = 0 AND i % 11 <> 0"));
        assertThat(count("SELECT count(*) FROM app_learning.account_milestone WHERE account_id IN " + H + " AND source <> 'BACKFILL'")).isZero();
        // the sample deck's materials are nobody's authoring: an account that only has them has no OWN_MATERIAL
        assertThat(count("SELECT count(*) FROM app_learning.account_milestone WHERE account_id IN " + H + " AND milestone_key = 'OWN_MATERIAL'"))
                .isEqualTo(count("SELECT count(*) FROM generate_series(1, " + ACCOUNTS + ") i WHERE i % 3 <> 0"));
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @Test
    void theExecutorRunsTheQueueToTheEndOnItsOwn() throws Exception {
        UUID account = UUID.randomUUID();
        UUID deck = worlds.shared.deck(account, "Давняя");
        jdbc.sql("DELETE FROM app_learning.account_milestone WHERE account_id = :a").param("a", account).update();
        UUID job = jobs.enqueue(OnboardingBackfill.QUEUE, BackfillSchedule.dedupeKey() + ":test:" + UUID.randomUUID(), null,
                JsonNodeFactory.instance.objectNode().put("registryVersion", MilestoneKey.REGISTRY_VERSION));
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        JobState state = JobState.QUEUED;
        while (System.nanoTime() < deadline && state != JobState.SUCCEEDED && state != JobState.FAILED) {
            Thread.sleep(200);
            state = jobs.status(job).orElseThrow().state();
        }
        assertThat(state).isEqualTo(JobState.SUCCEEDED);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.onboarding_backfill_run WHERE registry_version = :v").param("v", MilestoneKey.REGISTRY_VERSION).query(Long.class).single())
                .as("the finished version is recorded for the enqueuer").isEqualTo(1L);
        assertThat(jdbc.sql("SELECT source FROM app_learning.account_milestone WHERE account_id = :a AND milestone_key = 'FIRST_DECK'").param("a", account).query(String.class).single())
                .isEqualTo("BACKFILL");
        assertThat(deck).isNotNull();
    }

    @Test
    void aWorkerThatDoesNotGetTheLockWaitsWithTheSameCursorAndWritesNothing() throws Exception {
        long rowsBefore = count("SELECT count(*) FROM app_learning.account_milestone");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            // another worker is in the middle of its slice: it holds the transaction lock of the job
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('onboarding.backfill', 0))").query().listOfRows();
            try (var other = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                ObjectNode cursor = JsonNodeFactory.instance.objectNode().put("key", "OWN_MATERIAL").put("after", BackfillRepository.START.toString());
                Job job = new Job(UUID.randomUUID(), OnboardingBackfill.QUEUE, null, JsonNodeFactory.instance.objectNode(), cursor, 42, 1);
                Slice slice = other.submit(() -> new TransactionTemplate(transactions).execute(inner -> handler.run(job, 5))).get();
                assertThat(slice).isInstanceOf(Slice.Continue.class);
                Slice.Continue waiting = (Slice.Continue) slice;
                assertThat(waiting.progress()).isEqualTo(cursor);
                assertThat(waiting.progressCount()).isEqualTo(42);
                assertThat(waiting.delay()).isPositive();
            } catch (InterruptedException | java.util.concurrent.ExecutionException failure) {
                throw new AssertionError(failure);
            }
        });
        assertThat(count("SELECT count(*) FROM app_learning.account_milestone")).isEqualTo(rowsBefore);
    }

    // ---- the plans ----

    private static String literal(String sql, Map<String, Object> values) {
        String result = sql;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            result = result.replace(":" + entry.getKey(), value instanceof Number ? value.toString() : "'" + value + "'");
        }
        return result;
    }

    private void explain(List<String> plans, BackfillRepository.Source source, String what, String statement) {
        List<String> lines = jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + statement).query(String.class).list();
        String name = source.key() + " [" + what + "]";
        lines.forEach(line -> System.out.println("BFPLAN " + name + " | " + line));
        plans.add(name + "\n" + String.join("\n", lines));
    }

    @Test
    void everyBatchStatementIsDrivenByAnIndexOnTheOwnersOfItsSource() {
        build();
        List<String> plans = new ArrayList<>();
        for (BackfillRepository.Source source : BackfillRepository.SOURCES) {
            UUID upper = backfill.upperBound(source, BackfillRepository.START, 1000);
            assertThat(upper).as(source.key().name()).isNotNull();
            String write = literal(BackfillRepository.writeSql(source), Map.of("from", BackfillRepository.START, "to", upper, "key", source.key().name(), "target", source.key().target()));
            String bound = literal(BackfillRepository.upperBoundSql(source), Map.of("after", BackfillRepository.START, "batch", 1000));
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                // the merge is only planned and run, not kept
                status.setRollbackOnly();
                jdbc.sql("SET LOCAL enable_seqscan = off").update();
                explain(plans, source, "owner bound", bound);
                // the first run (nothing of the batch is in the table yet), then the rerun (everything is, so nothing is written)
                jdbc.sql("DELETE FROM app_learning.account_milestone WHERE source = 'BACKFILL' AND milestone_key = :k AND account_id > :from AND account_id <= :to")
                        .param("k", source.key().name()).param("from", BackfillRepository.START).param("to", upper).update();
                explain(plans, source, "batch merge, first run", write);
                explain(plans, source, "batch merge, rerun", write);
            });
        }
        // with sequential scans switched off a statement that has no index to use still shows one: none of them may
        assertThat(plans).allSatisfy(plan -> assertThat(plan).doesNotContain("Seq Scan on"));
        // and the natural plans at this size, with timings, for the evidence
        for (BackfillRepository.Source source : BackfillRepository.SOURCES) {
            UUID upper = backfill.upperBound(source, BackfillRepository.START, 1000);
            long started = System.nanoTime();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                backfill.write(source, BackfillRepository.START, upper);
                status.setRollbackOnly();
            });
            System.out.printf("BFTIME %-20s batch=1000 accounts: %.1f ms%n", source.key(), (System.nanoTime() - started) / 1e6);
        }
    }

    /**
     * The work of a batch follows the batch, not the table: ten owners read a few hundred buffers however big the source is, where a pass over the source (even by its index) reads thousands
     * at this size. The first Buffers line of the plan is the whole statement's.
     */
    @Test
    void theWorkOfABatchFollowsTheNumberOfOwnersAndNotTheSizeOfTheSource() {
        build();
        java.util.regex.Pattern buffers = java.util.regex.Pattern.compile("Buffers: shared hit=(\\d+)");
        for (BackfillRepository.Source source : BackfillRepository.SOURCES) {
            UUID upper = backfill.upperBound(source, BackfillRepository.START, 10);
            String write = literal(BackfillRepository.writeSql(source), Map.of("from", BackfillRepository.START, "to", upper, "key", source.key().name(), "target", source.key().target()));
            long[] read = new long[1];
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                status.setRollbackOnly();
                jdbc.sql("SET LOCAL enable_seqscan = off").update();
                for (String line : jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + write).query(String.class).list()) {
                    java.util.regex.Matcher matcher = buffers.matcher(line);
                    if (matcher.find()) {
                        read[0] = Long.parseLong(matcher.group(1));
                        break;
                    }
                }
            });
            System.out.println("BFBUFFERS " + source.key() + " batch=10 owners: " + read[0] + " buffers");
            assertThat(read[0]).as(source.key() + " reads for ten owners").isBetween(1L, 400L);
        }
    }
}
