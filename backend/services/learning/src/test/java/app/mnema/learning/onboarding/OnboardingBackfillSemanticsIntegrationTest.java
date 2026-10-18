package app.mnema.learning.onboarding;

import app.mnema.learning.fork.ForkWorlds.Source;
import app.mnema.learning.profile.LearningGoal;
import app.mnema.learning.profile.LearningProfileService;
import app.mnema.learning.support.MediaFixtures;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Issued;
import app.mnema.learning.support.StudyFixtures.Material;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What each milestone's backfill reads from history, per account and through the real writers (OB-302): the positive case, and the cases that must not count (practice and replay
 * sessions, the sample deck, somebody else's copy, a materialized copy, an accepted update, a revision instead of a new thing). Each test builds history with the real services,
 * removes the milestone rows the live writers made on the way (history from before the feature), and merges exactly the one account's range with the statement of every source.
 */
class OnboardingBackfillSemanticsIntegrationTest extends SampleDeckIntegrationTest {
    @Autowired private LearningProfileService profile;
    @Autowired private BackfillRepository backfill;

    record Stored(int progress, Instant achievedAt, Instant firstAt, String source) { }

    private Map<MilestoneKey, Stored> rows(UUID account) {
        Map<MilestoneKey, Stored> rows = new EnumMap<>(MilestoneKey.class);
        jdbc.sql("SELECT milestone_key, progress, achieved_at, first_at, source FROM app_learning.account_milestone WHERE account_id = :a").param("a", account)
                .query((row, ignored) -> rows.put(MilestoneKey.valueOf(row.getString("milestone_key")), new Stored(row.getInt("progress"),
                        row.getTimestamp("achieved_at") == null ? null : row.getTimestamp("achieved_at").toInstant(), row.getTimestamp("first_at").toInstant(), row.getString("source")))).list();
        return rows;
    }

    private void forget(UUID account) {
        jdbc.sql("DELETE FROM app_learning.account_milestone WHERE account_id = :a").param("a", account).update();
        jdbc.sql("DELETE FROM app_learning.notification WHERE owner_id = :a").param("a", account).update();
    }

    /** The backfill of exactly one account: every source over the range {@code (prev(account), account]}. */
    private void backfill(UUID account) {
        for (BackfillRepository.Source source : BackfillRepository.SOURCES) backfill.write(source, BackfillTestSupport.prev(account), account);
    }

    private Map<MilestoneKey, Stored> history(UUID account) {
        forget(account);
        backfill(account);
        return rows(account);
    }

    private Instant instant(String sql, UUID account) {
        return jdbc.sql(sql).param("a", account).query(java.sql.Timestamp.class).single().toInstant();
    }

    private Material inCopy(Source source, UUID learner, UUID copy, int index) {
        Material inherited = source.materials().get(index);
        return new Material(learner, copy, inherited.member(), inherited.itemRevision(), inherited.node(), inherited.distractor(), inherited.divider(), inherited.root());
    }

    private UUID study(Material material, String mode, UUID sourceSession) {
        List<Issued> issued = worlds.study.issue(material, mode, sourceSession);
        for (Issued presentation : issued) attempts.submit(material.actor(), material.deck(), presentation.session(), StudyFixtures.attempt(presentation, StudyFixtures.textResponse("перевод")));
        return issued.getFirst().session();
    }

    /** The local study date the application stored for {@code session}: «another day» is counted from it, never from the JVM's clock (another time zone near midnight). */
    private LocalDate studyDateOf(UUID session) {
        return jdbc.sql("SELECT local_study_date FROM app_learning.study_session WHERE session_id = :s").param("s", session).query(LocalDate.class).single();
    }

    private void completedOn(UUID session, LocalDate date, String mode) {
        String columns = jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema = 'app_learning' AND table_name = 'study_session' ORDER BY ordinal_position")
                .query(String.class).list().stream().collect(Collectors.joining(","));
        String selected = jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema = 'app_learning' AND table_name = 'study_session' ORDER BY ordinal_position")
                .query(String.class).list().stream().map(column -> switch (column) {
                    case "session_id" -> ":id";
                    case "command_id" -> ":command";
                    case "local_study_date" -> ":date";
                    case "mode" -> ":mode";
                    case "practice_order" -> "CASE WHEN :mode = 'PRACTICE' THEN 'SEEDED' END";
                    case "completed_at" -> "completed_at + (CAST(:shift AS integer) * interval '1 day')";
                    default -> column;
                }).collect(Collectors.joining(","));
        jdbc.sql("INSERT INTO app_learning.study_session(" + columns + ") SELECT " + selected + " FROM app_learning.study_session WHERE session_id = :source")
                .param("id", UUID.randomUUID()).param("command", UUID.randomUUID()).param("date", date).param("mode", mode).param("source", session)
                .param("shift", (int) java.time.temporal.ChronoUnit.DAYS.between(studyDateOf(session), date)).update();
    }

    private UUID image(UUID owner) {
        UUID asset = worlds.study.readyAsset(owner, "image/png");
        MediaFixtures.variants(jdbc, asset, "image/webp");
        return asset;
    }

    // ---- GOAL_TOLD, FIRST_DECK, COMMUNITY_COPY ----

    @Test
    void theGoalIsTheTimeItWasAnsweredAndAProfileThatWasNeverAnsweredIsNothing() {
        UUID answered = UUID.randomUUID();
        UUID silent = UUID.randomUUID();
        profile.answer(answered, LearningGoal.WORK);
        profile.read(silent);
        Map<MilestoneKey, Stored> rows = history(answered);
        assertThat(rows).containsOnlyKeys(MilestoneKey.GOAL_TOLD);
        assertThat(rows.get(MilestoneKey.GOAL_TOLD).source()).isEqualTo("BACKFILL");
        assertThat(rows.get(MilestoneKey.GOAL_TOLD).achievedAt()).isEqualTo(instant("SELECT answered_at FROM app_learning.learning_profile WHERE owner_id = :a", answered));
        assertThat(history(silent)).isEmpty();
    }

    @Test
    void anyDeckCountsAsTheFirstDeckEvenADeletedOneOrTheSampleDeckAndACommunityCopyIsOnlyACopyOfSomebodyElsesUserDeck() {
        UUID maker = UUID.randomUUID();
        UUID deck = worlds.shared.deck(maker, "Удалённая");
        delete(maker, deck);
        Map<MilestoneKey, Stored> made = history(maker);
        assertThat(made).containsOnlyKeys(MilestoneKey.FIRST_DECK);
        assertThat(made.get(MilestoneKey.FIRST_DECK).achievedAt()).isEqualTo(instant("SELECT min(created_at) FROM app_learning.deck WHERE owner_id = :a", maker));

        Source editorial = template(4, 2);
        UUID learner = UUID.randomUUID();
        worlds.awaitReady(learner, sample(learner));
        assertThat(history(learner)).as("the sample deck is a deck, but not a community copy").containsOnlyKeys(MilestoneKey.FIRST_DECK);

        Source community = worlds.source("PUBLIC", 3, 2);
        UUID copier = UUID.randomUUID();
        UUID copy = worlds.forked(copier, community.code());
        worlds.awaitReady(copier, copy);
        Map<MilestoneKey, Stored> copied = history(copier);
        // a copy's materials and exercises arrive by the job and an accepted update writes heads only: none of them is the copier's authoring
        assertThat(copied).containsOnlyKeys(MilestoneKey.FIRST_DECK, MilestoneKey.COMMUNITY_COPY);
        assertThat(copied.get(MilestoneKey.COMMUNITY_COPY).achievedAt()).isEqualTo(instant("SELECT min(forked_at) FROM app_learning.deck_fork WHERE owner_id = :a", copier));
        // the author, whose deck was copied, got nothing from it
        assertThat(history(community.author())).doesNotContainKey(MilestoneKey.COMMUNITY_COPY);
        // a detached copy is still a fact
        worlds.send("POST", "/decks/" + copy + "/fork:detach", copier, null, null);
        assertThat(history(copier)).containsKey(MilestoneKey.COMMUNITY_COPY);
        assertThat(editorial.deck()).isNotNull();
    }

    // ---- sessions ----

    @Test
    void completedScheduledAndPracticeSessionsCountAndEverythingElseDoesNot() {
        Source editorial = template(6, 3);
        UUID learner = UUID.randomUUID();
        UUID sample = sample(learner);
        worlds.awaitReady(learner, sample);
        Material material = inCopy(editorial, learner, sample, 0);
        UUID scheduled = study(material, "SCHEDULED", null);

        Map<MilestoneKey, Stored> one = history(learner);
        assertThat(one.get(MilestoneKey.FIRST_SESSION).achievedAt()).isEqualTo(instant("SELECT min(completed_at) FROM app_learning.study_session WHERE account_id = :a", learner));
        assertThat(one.get(MilestoneKey.TWO_DAYS)).extracting(Stored::progress).isEqualTo(1);
        assertThat(one.get(MilestoneKey.TWO_DAYS).achievedAt()).as("one study day is progress, not the milestone").isNull();

        // a practice session on another day, and a third day: the second distinct date makes the milestone, a third changes nothing
        completedOn(scheduled, studyDateOf(scheduled).minusDays(1), "PRACTICE");
        Map<MilestoneKey, Stored> two = history(learner);
        assertThat(two.get(MilestoneKey.TWO_DAYS).progress()).isEqualTo(2);
        Instant second = two.get(MilestoneKey.TWO_DAYS).achievedAt();
        assertThat(second).isNotNull();
        // the second date to appear is the later of the two completions (the clone of "yesterday" completed a day earlier than the original)
        assertThat(second).isEqualTo(instant("SELECT max(completed_at) FROM app_learning.study_session WHERE account_id = :a", learner));
        completedOn(scheduled, studyDateOf(scheduled).minusDays(5), "SCHEDULED");
        assertThat(history(learner).get(MilestoneKey.TWO_DAYS).progress()).isEqualTo(2);

        // a replay never counts; the sessions that are not complete do not either
        UUID replayer = UUID.randomUUID();
        UUID replayDeck = sample(replayer);
        worlds.awaitReady(replayer, replayDeck);
        Material replayMaterial = inCopy(editorial, replayer, replayDeck, 0);
        UUID source = study(replayMaterial, "SCHEDULED", null);
        study(replayMaterial, "REPLAY", source);
        jdbc.sql("UPDATE app_learning.study_session SET status = 'ACTIVE' WHERE session_id = :s").param("s", source).update();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_session WHERE account_id = :a AND status = 'COMPLETE' AND mode = 'REPLAY'").param("a", replayer).query(Long.class).single()).isOne();
        assertThat(history(replayer)).as("only a replay is complete").doesNotContainKeys(MilestoneKey.FIRST_SESSION, MilestoneKey.TWO_DAYS);
    }

    // ---- authoring ----

    @Test
    void newMaterialsInADeckOfTheAccountsOwnCountAndRevisionsTheSampleDeckAndCopiesDoNot() {
        UUID owner = UUID.randomUUID();
        UUID deck = worlds.shared.deck(owner, "Своя");
        Material text = worlds.study.addMaterial(owner, deck, "слово", "перевод");
        Map<MilestoneKey, Stored> plain = history(owner);
        assertThat(plain).containsOnlyKeys(MilestoneKey.FIRST_DECK, MilestoneKey.OWN_MATERIAL);
        assertThat(plain.get(MilestoneKey.OWN_MATERIAL).achievedAt()).isEqualTo(instant("SELECT min(created_at) FROM app_learning.learning_item WHERE owner_id = :a", owner));

        // a picture arrives with a later revision of the same material
        MediaFixtures.saveImages(decks, items, owner, deck, text.member(), text.itemRevision(), image(owner));
        Map<MilestoneKey, Stored> withMedia = history(owner);
        assertThat(withMedia).containsKeys(MilestoneKey.OWN_MATERIAL, MilestoneKey.MEDIA_IN_MATERIAL);
        assertThat(withMedia.get(MilestoneKey.MEDIA_IN_MATERIAL).achievedAt()).isEqualTo(instant("SELECT max(created_at) FROM app_learning.item_revision WHERE owner_id = :a", owner));

        // another revision that keeps the same picture is not the arrival of media: the milestone keeps the time of the revision that brought it
        UUID latest = jdbc.sql("SELECT revision_id FROM app_learning.item_revision WHERE owner_id = :a AND member_key = :m ORDER BY item_sequence DESC LIMIT 1")
                .param("a", owner).param("m", text.member()).query(UUID.class).single();
        worlds.edit(owner, deck, text.member(), latest, "ещё одна правка");
        assertThat(history(owner).get(MilestoneKey.MEDIA_IN_MATERIAL).achievedAt()).isEqualTo(withMedia.get(MilestoneKey.MEDIA_IN_MATERIAL).achievedAt());

        // a revision without media is not a new material
        UUID reviser = UUID.randomUUID();
        UUID reviserDeck = worlds.shared.deck(reviser, "Правки");
        Material revised = worlds.study.addMaterial(reviser, reviserDeck, "слово", "перевод");
        forget(reviser);
        worlds.edit(reviser, reviserDeck, revised.member(), revised.itemRevision(), "правка");
        assertThat(history(reviser)).as("the material counts once, the revision adds nothing").containsKey(MilestoneKey.OWN_MATERIAL).doesNotContainKey(MilestoneKey.MEDIA_IN_MATERIAL);

        // the sample deck: whatever the learner adds there
        Source editorial = template(4, 2);
        UUID learner = UUID.randomUUID();
        UUID sample = sample(learner);
        worlds.awaitReady(learner, sample);
        worlds.study.addMaterial(learner, sample, "моё", "слово");
        worlds.shared.create(learner, sample, MediaFixtures.imageDocument(image(learner)));
        assertThat(history(learner)).containsOnlyKeys(MilestoneKey.FIRST_DECK);

        // a copy's inherited materials belong to the origin deck, and the copy's owner editing one writes a revision, not a material
        Source community = worlds.source("PUBLIC", 3, 1);
        UUID copier = UUID.randomUUID();
        UUID copy = worlds.forked(copier, community.code());
        worlds.awaitReady(copier, copy);
        Material inherited = inCopy(community, copier, copy, 1);
        worlds.edit(copier, copy, inherited.member(), inherited.itemRevision(), "моя правка");
        assertThat(history(copier)).containsOnlyKeys(MilestoneKey.FIRST_DECK, MilestoneKey.COMMUNITY_COPY);
        assertThat(editorial.deck()).isNotNull();
    }

    @Test
    void exercisesCountToThreeInADeckOfTheAccountsOwnAndOnlyNewOnes() {
        UUID owner = UUID.randomUUID();
        UUID deck = worlds.shared.deck(owner, "Упражнения");
        Material material = worlds.study.addMaterial(owner, deck, "слово", "перевод");
        for (int index = 0; index < 2; index++) {
            worlds.study.publish(material, worlds.study.freeResponse(material, blocks(text("Вопрос " + index)), blocks(text("Подсказка")), "ответ"), "Цель " + index);
        }
        Map<MilestoneKey, Stored> two = history(owner);
        assertThat(two.get(MilestoneKey.OWN_EXERCISES)).extracting(Stored::progress, Stored::achievedAt).containsExactly(2, null);
        for (int index = 2; index < 5; index++) {
            worlds.study.publish(material, worlds.study.freeResponse(material, blocks(text("Вопрос " + index)), blocks(text("Подсказка")), "ответ"), "Цель " + index);
        }
        Map<MilestoneKey, Stored> five = history(owner);
        assertThat(five.get(MilestoneKey.OWN_EXERCISES).progress()).as("capped at the target").isEqualTo(3);
        assertThat(five.get(MilestoneKey.OWN_EXERCISES).achievedAt()).as("the time of the third").isEqualTo(
                jdbc.sql("SELECT created_at FROM app_learning.exercise_definition WHERE owner_id = :a ORDER BY created_at, exercise_id OFFSET 2 LIMIT 1").param("a", owner)
                        .query(java.sql.Timestamp.class).single().toInstant());

        // the sample deck and the exercises a copy inherits
        Source editorial = template(4, 3);
        UUID learner = UUID.randomUUID();
        UUID sample = sample(learner);
        worlds.awaitReady(learner, sample);
        Material inSample = worlds.study.addMaterial(learner, sample, "моё", "слово");
        for (int index = 0; index < 4; index++) {
            worlds.study.publish(inSample, worlds.study.freeResponse(inSample, blocks(text("Вопрос " + index)), blocks(text("Подсказка")), "ответ"), "Цель " + index);
        }
        assertThat(history(learner)).doesNotContainKey(MilestoneKey.OWN_EXERCISES);
        // the template is no longer a USER deck (it was named the template), so what the editorial account wrote there is not an authoring milestone; the live writers agree
        assertThat(history(editorial.author())).doesNotContainKey(MilestoneKey.OWN_EXERCISES);
    }

    private UUID note(UUID owner, UUID deck) {
        String body = "{\"commandId\":\"" + UUID.randomUUID() + "\",\"deckId\":\"" + deck + "\",\"source\":\"источник\",\"text\":\"заметка\"}";
        HttpResponse<String> created = worlds.send("POST", "/capture-notes", owner, body, null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(json(created).path("capture").path("noteId").stringValue(null));
    }

    @Test
    void aNoteIsStageOneAndSortingItOutIsStageTwoAndANoteInTheSampleDeckIsNothing() {
        UUID owner = UUID.randomUUID();
        UUID deck = worlds.shared.deck(owner, "Заметки");
        UUID note = note(owner, deck);
        Map<MilestoneKey, Stored> saved = history(owner);
        assertThat(saved.get(MilestoneKey.CAPTURE_NOTE)).extracting(Stored::progress, Stored::achievedAt).containsExactly(1, null);
        assertThat(saved.get(MilestoneKey.CAPTURE_NOTE).firstAt()).isEqualTo(instant("SELECT min(created_at) FROM app_learning.capture_note WHERE owner_id = :a", owner));

        String version = json(worlds.get("/capture-notes/" + note, owner)).path("rowVersion").stringValue(null);
        assertThat(worlds.send("POST", "/capture-notes/" + note + "/archive", owner, "{\"archived\":true}", "\"" + version + "\"").statusCode()).isEqualTo(200);
        Map<MilestoneKey, Stored> sorted = history(owner);
        assertThat(sorted.get(MilestoneKey.CAPTURE_NOTE).progress()).isEqualTo(2);
        assertThat(sorted.get(MilestoneKey.CAPTURE_NOTE).achievedAt()).isEqualTo(instant("SELECT updated_at FROM app_learning.capture_note WHERE note_id = '" + note + "' AND owner_id = :a", owner));

        Source editorial = template(4, 2);
        UUID learner = UUID.randomUUID();
        UUID sample = sample(learner);
        worlds.awaitReady(learner, sample);
        note(learner, sample);
        assertThat(history(learner)).doesNotContainKey(MilestoneKey.CAPTURE_NOTE);
        assertThat(editorial.deck()).isNotNull();
    }

    // ---- the three that the services cannot make without a model ----

    /** History rows made by hand with the constraints of the referenced tables switched off for the transaction: the shape of the rows is the table's, the story is the test's. */
    private void synthetic(String sql, Object... params) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            var statement = jdbc.sql(sql);
            for (int index = 0; index < params.length; index++) statement = statement.param("p" + index, params[index]);
            statement.update();
        });
    }

    @Test
    void aModelGradeASpokenAnswerAndAPublishedWorkshopArtifactAreRestoredFromTheirOwnTables() {
        UUID account = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        for (UUID who : new UUID[] {account, other}) {
            // only the evidence of the model counts, not the learner's own rating
            synthetic("""
                    INSERT INTO app_learning.study_evidence(attempt_id, account_id, deck_id, objective_id, objective_revision_id, learning_epoch, result, evidence_class, reason_codes,
                                                            evaluator_id, evaluator_version, hints_used, duration_ms, accepted_at)
                    VALUES (gen_random_uuid(), :p0, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 0, 'CORRECT', 'LOW', '[]', :p1, '1', '[]', 1000, :p2)
                    """, who, who.equals(account) ? "ai-semantic" : "self-check", java.sql.Timestamp.from(Instant.parse("2026-03-01T10:00:00Z")));
        }
        synthetic("""
                INSERT INTO app_learning.study_assessment(attempt_id, account_id, session_id, presentation_id, deck_id, state, strictness, payload_hash, answer_source, duration_ms, created_at,
                                                          deadline_at, expires_at, resolved_at)
                VALUES (gen_random_uuid(), :p0, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'DONE', 'S1', decode(repeat('ab', 32), 'hex'), :p1, 1000, :p2, :p3, :p3, :p3)
                """, account, "SPEECH", java.sql.Timestamp.from(Instant.parse("2026-03-02T10:00:00Z")), java.sql.Timestamp.from(Instant.parse("2026-03-02T11:00:00Z")));
        synthetic("""
                INSERT INTO app_learning.study_assessment(attempt_id, account_id, session_id, presentation_id, deck_id, state, strictness, payload_hash, answer_source, duration_ms, created_at,
                                                          deadline_at, expires_at, resolved_at)
                VALUES (gen_random_uuid(), :p0, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'DONE', 'S1', decode(repeat('ab', 32), 'hex'), :p1, 1000, :p2, :p3, :p3, :p3)
                """, other, "TYPED", java.sql.Timestamp.from(Instant.parse("2026-03-02T10:00:00Z")), java.sql.Timestamp.from(Instant.parse("2026-03-02T11:00:00Z")));
        synthetic("""
                INSERT INTO app_learning.generation_provenance(provenance_id, owner_id, session_id, artifact_id, revision_id, published_ref, created_at)
                VALUES (gen_random_uuid(), :p0, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), '{}', :p1)
                """, account, java.sql.Timestamp.from(Instant.parse("2026-03-03T10:00:00Z")));

        Map<MilestoneKey, Stored> rows = history(account);
        assertThat(rows).containsOnlyKeys(MilestoneKey.AI_EXPLANATION, MilestoneKey.VOICE_ANSWER, MilestoneKey.WORKSHOP_APPROVED);
        assertThat(rows.get(MilestoneKey.AI_EXPLANATION).achievedAt()).isEqualTo(Instant.parse("2026-03-01T10:00:00Z"));
        assertThat(rows.get(MilestoneKey.VOICE_ANSWER).achievedAt()).isEqualTo(Instant.parse("2026-03-02T10:00:00Z"));
        assertThat(rows.get(MilestoneKey.WORKSHOP_APPROVED).achievedAt()).isEqualTo(Instant.parse("2026-03-03T10:00:00Z"));
        assertThat(rows.values()).allSatisfy(row -> assertThat(row.source()).isEqualTo("BACKFILL"));
        assertThat(history(other)).as("a rating of the learner's own, a typed answer").isEmpty();
    }

    // ---- the merge with what the live writers wrote ----

    private long announced(UUID account) {
        return jdbc.sql("SELECT count(*) FROM app_learning.notification WHERE owner_id = :a AND kind = 'PATH_MILESTONE'").param("a", account).query(Long.class).single();
    }

    @Test
    void aLiveRowIsNeverLoweredNorReSourcedAndAHigherHistoryRaisesItWithoutANotification() {
        UUID owner = UUID.randomUUID();
        UUID deck = worlds.shared.deck(owner, "Живая");
        Material material = worlds.study.addMaterial(owner, deck, "слово", "перевод");
        for (int index = 0; index < 3; index++) {
            worlds.study.publish(material, worlds.study.freeResponse(material, blocks(text("Вопрос " + index)), blocks(text("Подсказка")), "ответ"), "Цель " + index);
        }
        profile.answer(owner, LearningGoal.SELF);
        Map<MilestoneKey, Stored> live = rows(owner);
        long announcedBefore = announced(owner);
        assertThat(announcedBefore).as("the live writers announced them").isPositive();

        // what the live rows say is higher than the history (a note stage, which the history has no note for) or equal to it (the goal); one is lower (the counter)
        jdbc.sql("INSERT INTO app_learning.account_milestone(account_id, milestone_key, progress, achieved_at, first_at, source) VALUES (:a, 'CAPTURE_NOTE', 2, statement_timestamp(), statement_timestamp(), 'LIVE')")
                .param("a", owner).update();
        jdbc.sql("UPDATE app_learning.account_milestone SET progress = 1, achieved_at = NULL WHERE account_id = :a AND milestone_key = 'OWN_EXERCISES'").param("a", owner).update();
        Map<MilestoneKey, Stored> before = rows(owner);

        backfill(owner);
        Map<MilestoneKey, Stored> after = rows(owner);

        // kept: the LIVE time of the goal, the higher LIVE stage of the note, and nobody's source changed
        assertThat(after.get(MilestoneKey.GOAL_TOLD).achievedAt()).isEqualTo(live.get(MilestoneKey.GOAL_TOLD).achievedAt());
        assertThat(after.get(MilestoneKey.CAPTURE_NOTE)).isEqualTo(before.get(MilestoneKey.CAPTURE_NOTE));
        assertThat(after.values()).allSatisfy(row -> assertThat(row.source()).isEqualTo("LIVE"));
        // raised: the counter the history knows to be three, with the time of the third exercise, still a LIVE row
        assertThat(after.get(MilestoneKey.OWN_EXERCISES).progress()).isEqualTo(3);
        assertThat(after.get(MilestoneKey.OWN_EXERCISES).achievedAt()).isEqualTo(
                jdbc.sql("SELECT created_at FROM app_learning.exercise_definition WHERE owner_id = :a ORDER BY created_at, exercise_id OFFSET 2 LIMIT 1").param("a", owner)
                        .query(java.sql.Timestamp.class).single().toInstant());
        // the first time of every row is the earlier of the two, never later than it was
        after.forEach((key, row) -> assertThat(row.firstAt()).as(key.name()).isBeforeOrEqualTo(before.get(key).firstAt()));
        // and nothing was announced by it, and a second pass writes nothing
        assertThat(announced(owner)).isEqualTo(announcedBefore);
        backfill(owner);
        assertThat(rows(owner)).isEqualTo(after);
    }

    @Test
    void aLiveEventAfterTheBackfillWorksAsEverAchievesAndAnnouncesWhenTheTourIsOn() {
        UUID owner = UUID.randomUUID();
        UUID deck = worlds.shared.deck(owner, "Заметки после");
        note(owner, deck);
        UUID second = note(owner, deck);
        profile.answer(owner, null);
        Map<MilestoneKey, Stored> backfilled = history(owner);
        assertThat(backfilled.get(MilestoneKey.CAPTURE_NOTE)).extracting(Stored::progress, Stored::achievedAt).containsExactly(1, null);
        assertThat(backfilled.get(MilestoneKey.GOAL_TOLD).achievedAt()).isNotNull();
        assertThat(announced(owner)).isZero();

        // sorting a note out is the second stage, live: it achieves the milestone of a BACKFILL row and tells the account
        String version = json(worlds.get("/capture-notes/" + second, owner)).path("rowVersion").stringValue(null);
        assertThat(worlds.send("POST", "/capture-notes/" + second + "/archive", owner, "{\"archived\":true}", "\"" + version + "\"").statusCode()).isEqualTo(200);
        Map<MilestoneKey, Stored> now = rows(owner);
        assertThat(now.get(MilestoneKey.CAPTURE_NOTE).progress()).isEqualTo(2);
        assertThat(now.get(MilestoneKey.CAPTURE_NOTE).achievedAt()).isNotNull();
        assertThat(now.get(MilestoneKey.CAPTURE_NOTE).source()).as("the row keeps the source it was created with").isEqualTo("BACKFILL");
        assertThat(announced(owner)).isOne();
        // what the backfill had achieved is not announced later and not touched by a live repeat
        profile.answer(owner, LearningGoal.EXAMS);
        assertThat(rows(owner).get(MilestoneKey.GOAL_TOLD)).isEqualTo(backfilled.get(MilestoneKey.GOAL_TOLD));
        assertThat(announced(owner)).isOne();
    }

    @Test
    void theRhythmHasNoHistoryAndNothingIsEverWrittenForIt() {
        assertThat(BackfillRepository.SOURCES).extracting(BackfillRepository.Source::key).doesNotContain(MilestoneKey.RHYTHM_WEEK);
        assertThat(BackfillRepository.SOURCES).extracting(BackfillRepository.Source::key).doesNotHaveDuplicates()
                .containsExactlyElementsOf(java.util.Arrays.stream(MilestoneKey.values()).filter(key -> key != MilestoneKey.RHYTHM_WEEK).toList());
    }
}
