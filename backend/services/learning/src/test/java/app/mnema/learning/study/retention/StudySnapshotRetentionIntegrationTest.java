package app.mnema.learning.study.retention;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.study.attempt.AttemptService;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Issued;
import app.mnema.learning.support.StudyFixtures.Material;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static app.mnema.learning.support.StudyFixtures.attempt;
import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.text;
import static app.mnema.learning.support.StudyFixtures.textResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The snapshot retention of Study (#460, V59) on real PostgreSQL and the production services. The horizon is lowered to its floor
 * (five days, so a session is "old" at 120 h), the batches are tiny so that passes have to loop and the chunk is ten candidates.
 * Every test owns its learner; a pass only ever touches sessions older than the horizon, which only the tests of this class backdate.
 */
@SpringBootTest(properties = {"learning.study.retention.snapshot-horizon=P5D", "learning.study.retention.session-batch=5",
        "learning.study.retention.row-batch=2", "learning.study.retention.generation-chunk=10"})
class StudySnapshotRetentionIntegrationTest extends PostgresIntegrationTest {
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private MediaCatalog media;
    @Autowired private StudySessionService sessions;
    @Autowired private AttemptService attempts;
    @Autowired private StudyRetentionService expiry;
    @Autowired private StudySnapshotRetention snapshots;
    @Autowired private StudySnapshotRepository snapshotRepository;
    @Autowired private StudyRetentionSettings settings;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private StudyFixtures fx;
    private SimpleMeterRegistry meters;
    private StudyRetentionPass pass;

    @BeforeEach
    void fixtures() {
        fx = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
        meters = new SimpleMeterRegistry();
        pass = new StudyRetentionPass(expiry, snapshots, snapshotRepository, settings, meters);
        // whatever an earlier test left behind (a failed one included) must not count in this test
        new StudyRetentionPass(expiry, snapshots, snapshotRepository, settings, new SimpleMeterRegistry()).run();
    }

    // ---------------------------------------------------------------------------------------------------------- helpers

    private Material learner() {
        Material material = fx.material();
        fx.publish(material, fx.freeResponse(material, blocks(text("Что такое память?")), blocks(), "memory"), "Память");
        return material;
    }

    /** Another exercise on the learner's deck: its own material, hence a new exercises root. */
    private void addExercise(Material learner, String question) {
        Material more = fx.addMaterial(learner.actor(), learner.deck(), question, question + " ещё");
        fx.publish(more, fx.freeResponse(more, blocks(text(question)), blocks(), "memory"), question);
    }

    private Issued answered(Material material, String mode) {
        Issued issued = fx.issue(material, mode, null).getFirst();
        attempts.submit(material.actor(), material.deck(), issued.session(), attempt(issued, textResponse("memory")));
        return issued;
    }

    private Issued unanswered(Material material, String mode) { return fx.issue(material, mode, null).getFirst(); }

    private void backdate(UUID session, String age) {
        jdbc.sql("UPDATE app_learning.study_session SET created_at=statement_timestamp()-CAST(:age AS interval) WHERE session_id=:s")
                .param("age", age).param("s", session).update();
    }

    private long count(String table, String column, UUID value) {
        return jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE " + column + "=:value").param("value", value)
                .query(Long.class).single();
    }

    private String frozen(String table, UUID actor) {
        return jdbc.sql("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM app_learning." + table
                + " t WHERE t.account_id=:a").param("a", actor).query(String.class).single();
    }

    private String frozenByAttempt(String table, UUID actor) {
        return jdbc.sql("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM app_learning." + table
                + " t JOIN app_learning.study_attempt_tombstone a ON a.attempt_id=t.attempt_id WHERE a.account_id=:a")
                .param("a", actor).query(String.class).single();
    }

    private String receipt(UUID session) {
        return jdbc.sql("SELECT r.result::text FROM app_learning.command_receipt r JOIN app_learning.study_session s ON s.command_id=r.command_id "
                + "WHERE s.session_id=:s").param("s", session).query(String.class).single();
    }

    private boolean purged(UUID session) {
        return jdbc.sql("SELECT presentations_purged_at IS NOT NULL FROM app_learning.study_session WHERE session_id=:s")
                .param("s", session).query(Boolean.class).single();
    }

    private UUID generationOf(UUID session) {
        return jdbc.sql("SELECT exercise_generation_id FROM app_learning.study_session WHERE session_id=:s").param("s", session)
                .query(UUID.class).single();
    }

    private boolean generationExists(UUID generation) {
        return count("study_candidate_generation", "generation_id", generation) == 1;
    }

    // ------------------------------------------------------------------------------------------------------------ tests

    @Test
    void aPassPurgesTheSnapshotOfSessionsPastTheHorizonAndNothingYounger() {
        Material m = learner();
        Issued scheduled = answered(m, "SCHEDULED");
        Issued practice = answered(m, "PRACTICE");
        Issued justInside = answered(m, "PRACTICE");
        Issued openFresh = unanswered(m, "PRACTICE");
        UUID old = scheduled.session();
        // rows only the snapshot owns, for the old practice session: a reveal of each kind, a pair check, a finished AI answer row
        UUID oldPresentation = practice.id();
        jdbc.sql("INSERT INTO app_learning.study_hint_reveal(account_id,session_id,presentation_id,blank_id,revealed_at) "
                + "VALUES (:a,:s,:p,gen_random_uuid(),now())").param("a", m.actor()).param("s", practice.session()).param("p", oldPresentation).update();
        jdbc.sql("INSERT INTO app_learning.study_transcript_accommodation(account_id,session_id,presentation_id,revealed_at) "
                + "VALUES (:a,:s,:p,now())").param("a", m.actor()).param("s", practice.session()).param("p", oldPresentation).update();
        jdbc.sql("INSERT INTO app_learning.study_pair_interaction(account_id,session_id,presentation_id,left_id,right_id,correct,expires_at) "
                + "VALUES (:a,:s,:p,gen_random_uuid(),gen_random_uuid(),TRUE,now()+interval '1 day')")
                .param("a", m.actor()).param("s", practice.session()).param("p", oldPresentation).update();
        jdbc.sql("INSERT INTO app_learning.study_assessment(attempt_id,account_id,session_id,presentation_id,deck_id,state,strictness,payload_hash,"
                + "answer_source,duration_ms,created_at,deadline_at,expires_at,resolved_at) VALUES (gen_random_uuid(),:a,:s,:p,:d,'DONE','S1',"
                + "decode(repeat('ab',32),'hex'),'TYPED',1000,now(),now()+interval '20 seconds',now()+interval '1 day',now())")
                .param("a", m.actor()).param("s", practice.session()).param("p", oldPresentation).param("d", m.deck()).update();
        backdate(old, "6 days");
        backdate(practice.session(), "121 hours");
        backdate(justInside.session(), "119 hours 59 minutes");

        String tombstones = frozen("study_attempt_tombstone", m.actor());
        String evidence = frozen("study_evidence", m.actor());
        String transitions = frozen("study_transition", m.actor());
        String states = frozen("study_state", m.actor());
        String policy = frozen("study_policy_assignment", m.actor());
        String raw = frozenByAttempt("study_raw_response", m.actor());
        String youngReceipt = receipt(justInside.session());
        String freshReceipt = receipt(openFresh.session());
        String oldHash = jdbc.sql("SELECT encode(r.payload_hash,'hex') FROM app_learning.command_receipt r JOIN app_learning.study_session s "
                + "ON s.command_id=r.command_id WHERE s.session_id=:s").param("s", old).query(String.class).single();
        assertThat(receipt(old)).doesNotContain("compacted");

        StudyRetentionPass.Result result = pass.run();

        assertThat(result.sessions()).isEqualTo(2);
        for (UUID gone : List.of(old, practice.session())) {
            assertThat(purged(gone)).isTrue();
            for (String table : List.of("study_presentation", "study_exposure", "study_hint_reveal", "study_transcript_accommodation",
                    "study_pair_interaction", "study_assessment")) {
                assertThat(count(table, "session_id", gone)).as(table).isZero();
            }
            // the session row stays, and its start receipt keeps its identity and drops the presentations
            assertThat(count("study_session", "session_id", gone)).isOne();
            assertThat(receipt(gone)).contains("\"compacted\": true", "sessionId").doesNotContain("presentations", "nonce");
        }
        assertThat(jdbc.sql("SELECT encode(r.payload_hash,'hex') FROM app_learning.command_receipt r JOIN app_learning.study_session s "
                + "ON s.command_id=r.command_id WHERE s.session_id=:s").param("s", old).query(String.class).single()).isEqualTo(oldHash);
        // younger sessions are untouched, to the byte
        assertThat(purged(justInside.session())).isFalse();
        assertThat(purged(openFresh.session())).isFalse();
        assertThat(count("study_presentation", "session_id", justInside.session())).isOne();
        assertThat(count("study_presentation", "session_id", openFresh.session())).isOne();
        assertThat(receipt(justInside.session())).isEqualTo(youngReceipt);
        assertThat(receipt(openFresh.session())).isEqualTo(freshReceipt);
        // receipts, evidence, transitions, state, raw responses and policy rows are the same rows with the same content
        assertThat(frozen("study_attempt_tombstone", m.actor())).isEqualTo(tombstones);
        assertThat(frozen("study_evidence", m.actor())).isEqualTo(evidence);
        assertThat(frozen("study_transition", m.actor())).isEqualTo(transitions);
        assertThat(frozen("study_state", m.actor())).isEqualTo(states);
        assertThat(frozen("study_policy_assignment", m.actor())).isEqualTo(policy);
        assertThat(frozenByAttempt("study_raw_response", m.actor())).isEqualTo(raw);
        // a second pass has nothing left to do for them
        assertThat(pass.run().sessions()).isZero();
        assertThat(meters.counter("mnema_study_retention_rows_total", "table", "presentation").count()).isEqualTo(2.0);
        assertThat(meters.counter("mnema_study_retention_rows_total", "table", "exposure").count()).isEqualTo(1.0);
        assertThat(meters.counter("mnema_study_retention_sessions_total", "outcome", "purged").count()).isEqualTo(2.0);
    }

    @Test
    void theReceiptOfAPurgedSessionStillReplaysTheSubmitButADisputeOrANewAnswerIsTheOpaque404() {
        Material m = learner();
        Issued issued = fx.issue(m, "SCHEDULED", null).getFirst();
        var command = attempt(issued, textResponse("memory"));
        var outcome = attempts.submit(m.actor(), m.deck(), issued.session(), command).outcome();
        Issued other = fx.issue(m, "PRACTICE", null).getFirst();
        backdate(issued.session(), "9 days");
        backdate(other.session(), "9 days");
        pass.run();
        assertThat(count("study_presentation", "session_id", issued.session())).isZero();

        // the durable outcome is still the answer of an exact retry
        AttemptService.SubmitResult replay = attempts.submit(m.actor(), m.deck(), issued.session(), command);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.outcome()).isEqualTo(outcome);
        // a presentation that was never answered is gone for good: the same opaque 404 as any unknown id
        assertThatThrownBy(() -> attempts.submit(m.actor(), m.deck(), other.session(), attempt(other, textResponse("memory"))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void replayOfTodayAndResumeWithinADayAreUnaffectedByAPass() {
        Material m = learner();
        Issued done = answered(m, "SCHEDULED");
        Issued open = unanswered(m, "PRACTICE");
        Material stale = learner();
        Issued staleSession = answered(stale, "PRACTICE");
        backdate(staleSession.session(), "10 days");

        assertThat(pass.run().sessions()).isGreaterThanOrEqualTo(1);
        assertThat(purged(staleSession.session())).isTrue();
        assertThat(purged(done.session())).isFalse();

        // replay sources of today are listed and a replay starts from them, with the original presentation
        JsonNode sources = sessions.replaySources(m.actor(), m.deck(), "UTC");
        assertThat(sources.path("items")).extracting(item -> item.path("sessionId").stringValue(null)).contains(done.session().toString());
        List<Issued> replay = fx.issue(m, "REPLAY", done.session());
        assertThat(replay).hasSize(1);
        assertThat(replay.getFirst().json().path("exerciseRevisionId").stringValue(null))
                .isEqualTo(done.json().path("exerciseRevisionId").stringValue(null));
        // resume of a session that is a few hours old returns its pending presentation exactly as issued
        backdate(open.session(), "23 hours");
        pass.run();
        JsonNode resumed = sessions.read(m.actor(), m.deck(), open.session());
        assertThat(resumed.path("presentations")).hasSize(1);
        assertThat(resumed.path("presentations").get(0).path("presentationId").stringValue(null)).isEqualTo(open.id().toString());
        assertThat(attempts.submit(m.actor(), m.deck(), open.session(), attempt(open, textResponse("memory"))).outcome()
                .path("status").stringValue(null)).isEqualTo("ASSESSED");
    }

    @Test
    void theReplayWindowAcrossTimeZonesIsInsideTheFiveDayFloor() {
        Material m = learner();
        Issued source = answered(m, "SCHEDULED");
        // the worst case of the replay rule, with the offsets ZoneId accepts: a session started in UTC+18 on local date D, replayed from UTC-18 while
        // that is still D (just under 60 hours later). Its stored date is the UTC-18 "today", its age is 59 h 50 min.
        java.time.LocalDate today = java.time.Instant.now().atZone(java.time.ZoneId.of("-18:00")).toLocalDate();
        jdbc.sql("UPDATE app_learning.study_session SET created_at=statement_timestamp()-interval '59 hours 50 minutes',"
                + "local_study_date=:date,timezone='+18:00' WHERE session_id=:s").param("date", today).param("s", source.session()).update();
        assertThat(sessions.replaySources(m.actor(), m.deck(), "-18:00").path("items")).hasSize(1);
        pass.run();
        assertThat(purged(source.session())).isFalse();
        JsonNode replay = sessions.start(m.actor(), m.deck(), "-18:00", fx.start("REPLAY", source.session())).body();
        assertThat(replay.path("mode").stringValue(null)).isEqualTo("REPLAY");
        assertThat(replay.path("presentations")).hasSize(1);

        // the boundary of the horizon itself: 119 h 59 min stays, 120 h 01 min goes
        Material edge = learner();
        Issued young = answered(edge, "PRACTICE");
        Issued older = answered(edge, "PRACTICE");
        backdate(young.session(), "119 hours 59 minutes");
        backdate(older.session(), "120 hours 1 minute");
        pass.run();
        assertThat(purged(young.session())).isFalse();
        assertThat(purged(older.session())).isTrue();
    }

    @Test
    void aSessionIsNotPurgedWhileAReplayChildStillReadsItAndTheChildsRefillGetsItsPresentations() {
        Material m = learner();
        for (int index = 0; index < 21; index++) addExercise(m, "Тема " + index);          // 22 objectives: a second batch of 2
        UUID deck = m.deck();
        // two sessions introduce the 22 objectives (20, then 2); when they are all due, a third issues them from the due list: 20 now, 2 on the refill
        runScheduled(m, 20);
        runScheduled(m, 20);
        jdbc.sql("UPDATE app_learning.study_state SET next_due=statement_timestamp()-interval '1 hour' WHERE account_id=:a")
                .param("a", m.actor()).update();
        UUID source = runScheduled(m, 22);
        assertThat(count("study_presentation", "session_id", source)).isEqualTo(22);
        assertThat(sessions.read(m.actor(), deck, source).path("status").stringValue(null)).isEqualTo("COMPLETE");

        // a replay of all 22: the first batch (20) is copied at start, the second only when the first is answered
        JsonNode replay = sessions.start(m.actor(), deck, "UTC", replayOf(source, 22)).body();
        UUID child = UUID.fromString(replay.path("sessionId").stringValue(null));
        assertThat(replay.path("presentations")).hasSize(20);
        // the source is past the horizon (by the clock); the child is a day old at most
        backdate(source, "7 days");
        pass.run();
        assertThat(purged(source)).as("a REPLAY child still reads it").isFalse();
        assertThat(count("study_presentation", "session_id", source)).isEqualTo(22);
        for (JsonNode presentation : replay.path("presentations")) {
            attempts.submit(m.actor(), deck, child, attempt(new Issued(child, presentation), textResponse("memory")));
        }
        JsonNode refill = sessions.presentations(m.actor(), deck, child);
        assertThat(refill.path("presentations")).as("the second batch still has its source").hasSize(2);

        // once the child is itself past the horizon the source goes with it (the child first, then the source)
        backdate(child, "7 days");
        pass.run();
        pass.run();
        assertThat(purged(child)).isTrue();
        assertThat(purged(source)).isTrue();
        assertThat(count("study_presentation", "session_id", source)).isZero();
    }

    @Test
    void theSweepFindsAGenerationAfterTheDeckMovedOnWithoutAnyPurgedSession() {
        Material m = learner();
        UUID session = studied(m, "PRACTICE");
        UUID generation = generationOf(session);
        backdate(session, "7 days");
        pass.run();
        // the deck's current generation stays, whatever its age
        assertThat(generationExists(generation)).isTrue();
        // the deck moves on and is not studied again: no purge event will ever come for the old generation
        addExercise(m, "Новая тема");
        jdbc.sql("UPDATE app_learning.study_candidate_generation SET created_at=statement_timestamp()-interval '8 days' WHERE generation_id=:g")
                .param("g", generation).update();
        StudyRetentionPass.Result result = pass.run();
        assertThat(result.rows()).isGreaterThanOrEqualTo(2);        // its candidate and its row
        assertThat(generationExists(generation)).isFalse();
        assertThat(count("study_candidate", "generation_id", generation)).isZero();
    }

    @Test
    void theSweepRotatesSoAGenerationTheDeckStillUsesNeverHoldsTheFrontOfTheLine() {
        Material m = learner();
        UUID session = studied(m, "PRACTICE");
        UUID generation = generationOf(session);
        backdate(session, "7 days");
        jdbc.sql("UPDATE app_learning.study_candidate_generation SET created_at=statement_timestamp()-interval '30 days' WHERE generation_id=:g")
                .param("g", generation).update();
        pass.run();
        assertThat(generationExists(generation)).isTrue();
        assertThat(jdbc.sql("SELECT swept_at IS NOT NULL FROM app_learning.study_candidate_generation WHERE generation_id=:g")
                .param("g", generation).query(Boolean.class).single()).as("it was looked at and put behind the others").isTrue();
    }

    @Test
    void aGenerationGoesOnlyWhenNoSnapshotReadsItAndTheDeckNoLongerUsesIt() {
        Material m = learner();
        UUID first = studied(m, "PRACTICE");                 // generation 1: one exercise
        addExercise(m, "Вторая тема");
        UUID second = studied(m, "PRACTICE");                // generation 2
        addExercise(m, "Третья тема");
        UUID third = studied(m, "PRACTICE");                 // generation 3: the deck's current one
        UUID g1 = generationOf(first);
        UUID g2 = generationOf(second);
        UUID g3 = generationOf(third);
        assertThat(List.of(g1, g2, g3)).doesNotHaveDuplicates();
        assertThat(count("study_candidate", "generation_id", g1)).isOne();
        assertThat(count("study_candidate", "generation_id", g2)).isEqualTo(2);
        assertThat(count("study_candidate", "generation_id", g3)).isEqualTo(3);

        // only generation 1's session is old: generation 1 is superseded and unread, generation 2's session is young
        backdate(first, "7 days");
        pass.run();
        assertThat(generationExists(g1)).isFalse();
        assertThat(count("study_candidate", "generation_id", g1)).isZero();
        assertThat(generationExists(g2)).isTrue();
        assertThat(count("study_candidate", "generation_id", g2)).isEqualTo(2);
        assertThat(generationExists(g3)).isTrue();

        // generation 2 is superseded and now unread; generation 3 is the deck's current one and stays whatever its age
        backdate(second, "7 days");
        backdate(third, "7 days");
        pass.run();
        assertThat(generationExists(g2)).isFalse();
        assertThat(generationExists(g3)).isTrue();
        assertThat(count("study_candidate", "generation_id", g3)).isEqualTo(3);
        assertThat(purged(third)).isTrue();
        // the session rows that pointed at the removed generations are intact (provenance, no foreign key any more)
        assertThat(count("study_session", "session_id", first)).isOne();
        assertThat(count("study_session", "session_id", second)).isOne();

        // a deleted deck no longer uses its current generation either
        UUID fourth = studied(m, "PRACTICE");
        assertThat(generationOf(fourth)).isEqualTo(g3);
        backdate(fourth, "7 days");
        jdbc.sql("UPDATE app_learning.deck SET deleted_at=now() WHERE deck_id=:d").param("d", m.deck()).update();
        pass.run();
        assertThat(generationExists(g3)).isFalse();
        assertThat(count("study_candidate", "generation_id", g3)).isZero();
        assertThat(meters.counter("mnema_study_retention_rows_total", "table", "candidate_generation").count()).isEqualTo(3.0);
        assertThat(meters.counter("mnema_study_retention_rows_total", "table", "candidate").count()).isEqualTo(1.0 + 2 + 3);
    }

    @Test
    void aBigGenerationIsEvictedInChunksAndIsUnreachableWhileItIs() {
        Material m = learner();
        for (int index = 0; index < 11; index++) addExercise(m, "Тема " + index);   // 12 exercises: two chunks of ten
        UUID big = studied(m, "PRACTICE");
        UUID bigGeneration = generationOf(big);
        assertThat(count("study_candidate", "generation_id", bigGeneration)).isEqualTo(12);
        addExercise(m, "Последняя тема");                // the deck moves on
        UUID current = studied(m, "PRACTICE");
        assertThat(generationOf(current)).isNotEqualTo(bigGeneration);
        UUID bigRoot = jdbc.sql("SELECT exercises_root_id FROM app_learning.study_candidate_generation WHERE generation_id=:g")
                .param("g", bigGeneration).query(UUID.class).single();
        backdate(big, "7 days");

        // purge the session and mark the generation, but delete no candidate yet: the first step of a pass
        StudySnapshotRetention.SessionBatch batch = new TransactionTemplate(transactions).execute(status -> snapshots.purgeSessions());
        assertThat(batch.sessions()).isOne();
        assertThat(batch.generationsEvicting()).isOne();
        assertThat(jdbc.sql("SELECT evicting_since IS NOT NULL AS evicting, exercises_root_id<>:root AS other_root FROM "
                + "app_learning.study_candidate_generation WHERE generation_id=:g").param("root", bigRoot).param("g", bigGeneration)
                .query().singleRow()).containsEntry("evicting", true).containsEntry("other_root", true);
        assertThat(count("study_candidate", "generation_id", bigGeneration)).isEqualTo(12);

        // chunks of ten: ten, then the last two with the generation row
        StudySnapshotRetention.Eviction one = new TransactionTemplate(transactions).execute(status -> snapshots.evictChunk());
        assertThat(one.candidates()).isEqualTo(10);
        assertThat(one.generationRemoved()).isFalse();
        assertThat(count("study_candidate", "generation_id", bigGeneration)).isEqualTo(2);
        StudySnapshotRetention.Eviction two = new TransactionTemplate(transactions).execute(status -> snapshots.evictChunk());
        assertThat(two.candidates()).isEqualTo(2);
        assertThat(two.generationRemoved()).isTrue();
        assertThat(generationExists(bigGeneration)).isFalse();
        assertThat(new TransactionTemplate(transactions).execute(status -> snapshots.evictChunk()).rows()).isZero();
    }

    @Test
    void aStartThatIsReadingAGenerationKeepsItAliveUntilItCommits() throws Exception {
        Material m = learner();
        UUID first = studied(m, "PRACTICE");
        UUID generation = generationOf(first);
        addExercise(m, "Другая тема");
        studied(m, "PRACTICE");                       // the deck moved on: the first generation is superseded
        backdate(first, "7 days");
        TransactionTemplate tx = new TransactionTemplate(transactions);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            int marked = tx.execute(status -> {
                // this transaction reads the generation the way a start does (FOR SHARE) and holds the lock
                jdbc.sql("SELECT 1 FROM app_learning.study_candidate_generation WHERE generation_id=:g FOR SHARE").param("g", generation)
                        .query().listOfRows();
                try {
                    return executor.submit(() -> tx.execute(inner -> snapshots.purgeSessions().generationsEvicting()))
                            .get(20, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            assertThat(marked).as("a generation another transaction holds is skipped, not waited for").isZero();
        }
        assertThat(generationExists(generation)).isTrue();
        assertThat(purged(first)).isTrue();
        // looked at again with the next purged session of the deck
        UUID later = studied(m, "PRACTICE");
        backdate(later, "7 days");
        pass.run();
        assertThat(generationExists(generation)).isFalse();
    }

    @Test
    void twoPassesAtOnceNeverPurgeTheSameSessionTwice() throws Exception {
        Material m = learner();
        List<Issued> old = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            Issued issued = answered(m, "PRACTICE");
            backdate(issued.session(), "6 days");
            old.add(issued);
        }
        long presentations = old.stream().mapToLong(issued -> count("study_presentation", "session_id", issued.session())).sum();
        assertThat(presentations).isEqualTo(24);
        CountDownLatch go = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<int[]>> futures = new ArrayList<>();
            for (int worker = 0; worker < 3; worker++) {
                futures.add(executor.submit(() -> {
                    go.await();
                    int sessionsDone = 0;
                    int presentationsDone = 0;
                    StudySnapshotRetention.SessionBatch batch;
                    do {
                        batch = tx.execute(status -> snapshots.purgeSessions());
                        sessionsDone += batch.sessions();
                        presentationsDone += batch.presentations();
                    } while (batch.sessions() > 0);
                    return new int[] {sessionsDone, presentationsDone};
                }));
            }
            go.countDown();
            int sessionsDone = 0;
            int presentationsDone = 0;
            for (Future<int[]> future : futures) {
                int[] done = future.get(60, TimeUnit.SECONDS);
                sessionsDone += done[0];
                presentationsDone += done[1];
            }
            assertThat(sessionsDone).isEqualTo(24);
            assertThat(presentationsDone).isEqualTo(24);
        }
        assertThat(old).allSatisfy(issued -> assertThat(purged(issued.session())).isTrue());
    }

    @Test
    void aPassKeepsLoopingUntilTheBatchesAreNotFullSoTheExpiryStepsKeepUp() {
        Material m = fx.material();
        for (int index = 0; index < 5; index++) {
            Material more = index == 0 ? m : fx.addMaterial(m.actor(), m.deck(), "тема " + index, "ещё " + index);
            fx.publish(more, fx.freeResponse(more, blocks(text("Вопрос " + index)), blocks(), "memory"), "Цель " + index);
        }
        List<Issued> issued = fx.issue(m, "SCHEDULED", null);
        assertThat(issued).hasSize(5);
        for (Issued presentation : issued) {
            attempts.submit(m.actor(), m.deck(), presentation.session(), attempt(presentation, textResponse("memory")));
        }
        jdbc.sql("UPDATE app_learning.study_raw_response r SET expires_at=statement_timestamp()-interval '1 second' FROM "
                + "app_learning.study_attempt_tombstone t WHERE t.attempt_id=r.attempt_id AND t.account_id=:a").param("a", m.actor()).update();
        // one batch of the expiry steps takes row-batch (2) rows; the pass repeats until nothing is left
        assertThat(expiry.purgeBatch().rawResponses()).isEqualTo(2);
        StudyRetentionPass.Result result = pass.run();
        assertThat(result.rounds()).isGreaterThanOrEqualTo(2);
        assertThat(result.capped()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_raw_response r JOIN app_learning.study_attempt_tombstone t "
                + "ON t.attempt_id=r.attempt_id WHERE t.account_id=:a").param("a", m.actor()).query(Long.class).single()).isZero();
        assertThat(meters.counter("mnema_study_retention_rows_total", "table", "raw_response").count()).isEqualTo(3.0);
    }

    @Test
    void gaugesReportTheBacklogAndTheOldestUnpurgedSession() {
        Material m = learner();
        Issued issued = answered(m, "PRACTICE");
        backdate(issued.session(), "6 days");
        assertThat(snapshotRepository.backlog(settings.snapshotHorizon())).isGreaterThanOrEqualTo(1);
        assertThat(snapshotRepository.oldestAgeSeconds()).isGreaterThanOrEqualTo(6 * 86_400.0 - 5);
        assertThat(meters.get("mnema_study_retention_backlog_sessions").gauge().value()).isGreaterThanOrEqualTo(1);
        pass.run();
        assertThat(snapshotRepository.backlog(settings.snapshotHorizon())).isZero();
        assertThat(meters.get("mnema_study_retention_oldest_unpurged_age_seconds").gauge().value()).isLessThan(5 * 86_400.0);
    }

    @Test
    void theSnapshotGuardsRefuseUpdatesAndDeletesWithoutTheRetentionSetting() {
        Material m = learner();
        Issued issued = answered(m, "SCHEDULED");
        for (String table : List.of("study_presentation", "study_exposure", "study_candidate")) {
            assertThatThrownBy(() -> jdbc.sql("DELETE FROM app_learning." + table + " WHERE ctid IN (SELECT ctid FROM app_learning." + table
                    + " LIMIT 1)").update()).as(table).hasMessageContaining("Immutable study snapshot");
        }
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.study_presentation SET nonce=nonce WHERE presentation_id=:p")
                .param("p", issued.id()).update()).hasMessageContaining("Immutable study snapshot");
        TransactionTemplate tx = new TransactionTemplate(transactions);
        // with the setting a DELETE is let through, an UPDATE never, and the permanent tables never
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.sql("SELECT set_config('mnema.study_retention','on',true)").query().listOfRows();
            jdbc.sql("UPDATE app_learning.study_presentation SET nonce=nonce WHERE presentation_id=:p").param("p", issued.id()).update();
        })).hasMessageContaining("Immutable study snapshot");
        for (String table : List.of("study_evidence", "study_transition", "study_policy_assignment")) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                jdbc.sql("SELECT set_config('mnema.study_retention','on',true)").query().listOfRows();
                jdbc.sql("DELETE FROM app_learning." + table + " WHERE account_id=:a").param("a", m.actor()).update();
            })).as(table).hasMessageContaining("Immutable study snapshot");
        }
        // the setting is transaction-local: it does not leak into the next statement of the connection
        tx.executeWithoutResult(status -> jdbc.sql("SELECT set_config('mnema.study_retention','on',true)").query().listOfRows());
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM app_learning.study_exposure WHERE presentation_id=:p").param("p", issued.id()).update())
                .hasMessageContaining("Immutable study snapshot");
        assertThat(count("study_presentation", "presentation_id", issued.id())).isOne();
    }

    @Test
    void theProbesOfTheWorkerAndOfTheFirstAttemptReadUseTheirIndexes() {
        // the tables are tiny here, so the planner is told not to prefer a sequential scan: the point is that an index exists for each probe
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL enable_seqscan=off").update();
            String driver = plan("SELECT account_id,session_id FROM app_learning.study_session WHERE presentations_purged_at IS NULL "
                    + "AND created_at < statement_timestamp() - make_interval(secs => 259200) ORDER BY created_at,session_id LIMIT 100");
            assertThat(driver).contains("study_session_unpurged_age");
            assertThat(plan("SELECT 1 FROM app_learning.study_session WHERE deck_id=gen_random_uuid() AND exercise_generation_id=gen_random_uuid() "
                    + "AND presentations_purged_at IS NULL")).contains("study_session_unpurged_generation");
            assertThat(plan("SELECT deck_id,generation_id FROM app_learning.study_candidate_generation WHERE evicting_since IS NOT NULL "
                    + "ORDER BY evicting_since,generation_id LIMIT 1")).contains("study_candidate_generation_evicting");
            assertThat(plan("SELECT 1 FROM app_learning.study_exercise_attempt WHERE account_id=gen_random_uuid() AND deck_id=gen_random_uuid() "
                    + "AND exercise_id=gen_random_uuid() AND learning_epoch=0")).contains("study_exercise_attempt_pkey");
            assertThat(plan("SELECT 1 FROM app_learning.study_presentation WHERE deck_id=gen_random_uuid() AND generation_id=gen_random_uuid() "
                    + "AND candidate_ordinal=0")).contains("study_presentation_candidate");
            // the children of a session are found by the primary-key prefix (account, session), the same probe for every table
            for (String table : List.of("study_presentation", "study_exposure", "study_hint_reveal", "study_transcript_accommodation",
                    "study_pair_interaction", "study_assessment")) {
                assertThat(plan("SELECT 1 FROM app_learning." + table + " WHERE account_id=gen_random_uuid() AND session_id=gen_random_uuid()"))
                        .as(table).contains("Index").doesNotContain("Seq Scan");
            }
        });
    }

    private String plan(String sql) {
        return String.join("\n", jdbc.sql("EXPLAIN " + sql).query(String.class).list());
    }

    private app.mnema.learning.study.session.StudySessionCommand scheduled(int budget, int maxNew) {
        return command("SCHEDULED", budget, maxNew, null);
    }

    private app.mnema.learning.study.session.StudySessionCommand replayOf(UUID source, int budget) {
        return command("REPLAY", budget, 0, source);
    }

    private app.mnema.learning.study.session.StudySessionCommand command(String mode, int budget, int maxNew, UUID source) {
        var request = StudyFixtures.JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("mode", mode);
        if (mode.equals("REPLAY")) request.put("sourceSessionId", source.toString());
        var limits = request.putObject("budget").put("maxPresentations", budget);
        if (mode.equals("SCHEDULED")) limits.put("maxNewObjectives", maxNew);
        return app.mnema.learning.study.session.StudySessionCommand.read(
                new java.io.ByteArrayInputStream(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** Starts a scheduled session with the given budget and answers every presentation of every batch. */
    private UUID runScheduled(Material m, int budget) {
        JsonNode started = sessions.start(m.actor(), m.deck(), "UTC", scheduled(budget, budget)).body();
        UUID session = UUID.fromString(started.path("sessionId").stringValue(null));
        JsonNode current = started.path("status").stringValue(null).equals("PREPARING") ? prepared(m.actor(), m.deck(), session) : started;
        while (true) {
            List<JsonNode> batch = new ArrayList<>();
            current.path("presentations").forEach(batch::add);
            if (batch.isEmpty()) return session;
            for (JsonNode presentation : batch) {
                attempts.submit(m.actor(), m.deck(), session, attempt(new Issued(session, presentation), textResponse("memory")));
            }
            current = sessions.presentations(m.actor(), m.deck(), session);
        }
    }

    private JsonNode prepared(UUID actor, UUID deck, UUID session) {
        JsonNode read = sessions.read(actor, deck, session);
        while (read.path("status").stringValue(null).equals("PREPARING")) read = sessions.read(actor, deck, session);
        return read;
    }

    /** The id of a new session of the given mode (its first batch is issued, none of it answered). */
    private UUID studied(Material m, String mode) {
        Issued issued = fx.issue(m, mode, null).getFirst();
        return issued.session();
    }
}
