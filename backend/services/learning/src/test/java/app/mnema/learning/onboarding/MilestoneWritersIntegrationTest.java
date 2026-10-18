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
import tools.jackson.databind.JsonNode;

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
 * Every writer of a «Путь Mnema» milestone, through the real services and the real application (servlet stack, PostgreSQL, the real materialization job): the positive case of each
 * milestone and the cases that must NOT count (practice and replay, the sample deck, somebody else's copy, a materialized copy, a new revision instead of a new thing). One row per
 * account and milestone is the whole truth, so every assertion reads {@code account_milestone}.
 */
class MilestoneWritersIntegrationTest extends SampleDeckIntegrationTest {
    @Autowired private LearningProfileService profile;

    private record Stored(int progress, boolean achieved, Instant achievedAt) { }

    private Map<MilestoneKey, Stored> rows(UUID account) {
        Map<MilestoneKey, Stored> rows = new EnumMap<>(MilestoneKey.class);
        jdbc.sql("SELECT milestone_key, progress, achieved_at FROM app_learning.account_milestone WHERE account_id = :a").param("a", account)
                .query((row, ignored) -> rows.put(MilestoneKey.valueOf(row.getString("milestone_key")),
                        new Stored(row.getInt("progress"), row.getTimestamp("achieved_at") != null,
                                row.getTimestamp("achieved_at") == null ? null : row.getTimestamp("achieved_at").toInstant()))).list();
        return rows;
    }

    private void forget(UUID account, MilestoneKey... keys) {
        for (MilestoneKey key : keys) {
            jdbc.sql("DELETE FROM app_learning.account_milestone WHERE account_id = :a AND milestone_key = :k").param("a", account).param("k", key.name()).update();
        }
    }

    /** The study of a copy of {@code source}: the material the copy inherited, seen by its owner. */
    private Material inCopy(Source source, UUID learner, UUID copy, int index) {
        Material inherited = source.materials().get(index);
        return new Material(learner, copy, inherited.member(), inherited.itemRevision(), inherited.node(), inherited.distractor(), inherited.divider(), inherited.root());
    }

    /** Issues a session of {@code mode} on the material and answers every presentation; the last answer completes it. */
    private UUID study(Material material, String mode, UUID sourceSession) {
        List<Issued> issued = worlds.study.issue(material, mode, sourceSession);
        assertThat(issued).isNotEmpty();
        for (Issued presentation : issued) {
            attempts.submit(material.actor(), material.deck(), presentation.session(), StudyFixtures.attempt(presentation, StudyFixtures.textResponse("перевод")));
        }
        UUID session = issued.getFirst().session();
        assertThat(jdbc.sql("SELECT status FROM app_learning.study_session WHERE session_id = :s").param("s", session).query(String.class).single()).isEqualTo("COMPLETE");
        return session;
    }

    /** The local study date the application stored for {@code session}: «another day» is counted from it, never from the JVM's clock (another time zone near midnight). */
    private LocalDate studyDateOf(UUID session) {
        return jdbc.sql("SELECT local_study_date FROM app_learning.study_session WHERE session_id = :s").param("s", session).query(LocalDate.class).single();
    }

    /** A COMPLETE copy of a session row with another id, local date and mode, as a study on another day would be. */
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
                    default -> column;
                }).collect(Collectors.joining(","));
        jdbc.sql("INSERT INTO app_learning.study_session(" + columns + ") SELECT " + selected + " FROM app_learning.study_session WHERE session_id = :source")
                .param("id", UUID.randomUUID()).param("command", UUID.randomUUID()).param("date", date).param("mode", mode).param("source", session).update();
    }

    // ---- GOAL_TOLD ----

    @Test
    void anAnswerOfTheGoalAndTheDeliberateSkipAreBothTheMilestoneAndAReadIsNot() {
        UUID answered = UUID.randomUUID();
        UUID skipped = UUID.randomUUID();
        UUID reader = UUID.randomUUID();
        profile.read(reader);
        profile.answer(answered, LearningGoal.EXAMS);
        profile.answer(skipped, null);

        assertThat(rows(answered)).containsOnlyKeys(MilestoneKey.GOAL_TOLD);
        assertThat(rows(skipped)).containsOnlyKeys(MilestoneKey.GOAL_TOLD);
        assertThat(rows(reader)).isEmpty();
        Instant first = rows(answered).get(MilestoneKey.GOAL_TOLD).achievedAt();
        profile.answer(answered, LearningGoal.WORK);
        assertThat(rows(answered).get(MilestoneKey.GOAL_TOLD).achievedAt()).as("changing the answer is not a new fact").isEqualTo(first);
    }

    @Test
    void thePutOfTheProfileIsTheMilestoneOverHttpAndAnInvalidBodyIsNot() {
        UUID answered = UUID.randomUUID();
        assertThat(worlds.send("PUT", "/learning-profile", answered, "{\"goal\":\"INTERVIEW\"}", null).statusCode()).isEqualTo(200);
        assertThat(rows(answered)).containsOnlyKeys(MilestoneKey.GOAL_TOLD);
        UUID skipped = UUID.randomUUID();
        assertThat(worlds.send("PUT", "/learning-profile", skipped, "{\"goal\":null,\"skipped\":true}", null).statusCode()).isEqualTo(200);
        assertThat(rows(skipped)).containsOnlyKeys(MilestoneKey.GOAL_TOLD);
        UUID refused = UUID.randomUUID();
        assertThat(worlds.send("PUT", "/learning-profile", refused, "{\"goal\":\"NOPE\"}", null).statusCode()).isEqualTo(400);
        assertThat(rows(refused)).isEmpty();
    }

    @Test
    void aDomainActionThatFailsAfterItsMilestoneWasRecordedLeavesNeitherTheDeckNorTheMilestoneNorTheNotification() {
        UUID owner = UUID.randomUUID();
        long decksBefore = count("SELECT count(*) FROM app_learning.deck WHERE owner_id = :p0", owner);
        // the real writer joins the caller's transaction; the caller fails after it returned (its milestone and, at the commit, its notification are in that transaction)
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            worlds.shared.deck(owner, "Не будет");
            profile.answer(owner, LearningGoal.SELF);
            assertThat(rows(owner)).containsKeys(MilestoneKey.FIRST_DECK, MilestoneKey.GOAL_TOLD);
            throw new IllegalStateException("the rest of the action failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(rows(owner)).isEmpty();
        assertThat(count("SELECT count(*) FROM app_learning.deck WHERE owner_id = :p0", owner)).isEqualTo(decksBefore);
        assertThat(count("SELECT count(*) FROM app_learning.notification WHERE owner_id = :p0", owner)).isZero();
        // and the same action afterwards is the first one
        worlds.shared.deck(owner, "Будет");
        assertThat(rows(owner).get(MilestoneKey.FIRST_DECK).achieved()).isTrue();
        assertThat(count("SELECT count(*) FROM app_learning.notification WHERE owner_id = :p0", owner)).isOne();
    }

    // ---- FIRST_DECK, COMMUNITY_COPY ----

    @Test
    void aDeckOfTheAccountsOwnACopyAndTheSampleDeckAreTheFirstDeckAndNothingElseIsExceptTheCommunityCopy() {
        UUID maker = UUID.randomUUID();
        worlds.shared.deck(maker, "Моя колода");
        assertThat(rows(maker)).containsOnlyKeys(MilestoneKey.FIRST_DECK);

        Source source = worlds.source("PUBLIC", 3, 2);
        Map<MilestoneKey, Stored> authorBefore = rows(source.author());
        UUID copier = UUID.randomUUID();
        UUID copy = worlds.forked(copier, source.code());
        worlds.awaitReady(copier, copy);
        // the copy is FIRST_DECK and the community copy; its materials and exercises, arrived by the materialization job, are nobody's authoring
        assertThat(rows(copier)).containsOnlyKeys(MilestoneKey.FIRST_DECK, MilestoneKey.COMMUNITY_COPY);
        assertThat(rows(copier).values()).allSatisfy(row -> assertThat(row.achieved()).isTrue());
        // somebody else copying the author's deck changes nothing of the author's path
        assertThat(rows(source.author())).isEqualTo(authorBefore);
        assertThat(rows(source.author())).doesNotContainKey(MilestoneKey.COMMUNITY_COPY);

        // a repeat is the existing copy: it writes nothing
        Map<MilestoneKey, Stored> copierBefore = rows(copier);
        assertThat(worlds.fork(copier, source.code()).statusCode()).isEqualTo(200);
        assertThat(rows(copier)).isEqualTo(copierBefore);
        // a deleted and re-made copy is the same account's second deck, not a second fact
        delete(copier, copy);
        worlds.awaitReady(copier, worlds.forked(copier, source.code()));
        assertThat(rows(copier)).isEqualTo(copierBefore);

        // the sample deck is a deck of the account, but not a community copy
        Source editorial = template(4, 2);
        UUID learner = UUID.randomUUID();
        UUID sample = sample(learner);
        worlds.awaitReady(learner, sample);
        assertThat(rows(learner)).containsOnlyKeys(MilestoneKey.FIRST_DECK);
        Map<MilestoneKey, Stored> learnerBefore = rows(learner);
        assertThat(post(learner).statusCode()).as("the repeat is the live sample deck").isEqualTo(200);
        assertThat(rows(learner)).isEqualTo(learnerBefore);
        // the editorial account did not gain anything from the copy of its template
        assertThat(rows(editorial.author())).doesNotContainKey(MilestoneKey.COMMUNITY_COPY);
        // the one who also makes a deck of his own does not move the time the first deck was reached
        worlds.shared.deck(learner, "Своя");
        assertThat(rows(learner)).isEqualTo(learnerBefore);
    }

    // ---- FIRST_SESSION, TWO_DAYS ----

    @Test
    void aCompletedScheduledSessionOfTheSampleDeckIsTheFirstSessionAndOneStudyDayAndTheSecondDayIsTheMilestone() {
        Source editorial = template(6, 3);
        UUID learner = UUID.randomUUID();
        UUID sample = sample(learner);
        worlds.awaitReady(learner, sample);
        Material material = inCopy(editorial, learner, sample, 0);

        // issued but not finished: nothing
        List<Issued> issued = worlds.study.issue(material, "SCHEDULED", null);
        assertThat(issued.size()).isGreaterThan(1);
        attempts.submit(learner, sample, issued.getFirst().session(), StudyFixtures.attempt(issued.getFirst(), StudyFixtures.textResponse("перевод")));
        assertThat(rows(learner)).doesNotContainKeys(MilestoneKey.FIRST_SESSION, MilestoneKey.TWO_DAYS);
        for (Issued presentation : issued.subList(1, issued.size())) {
            attempts.submit(learner, sample, presentation.session(), StudyFixtures.attempt(presentation, StudyFixtures.textResponse("перевод")));
        }
        UUID session = issued.getFirst().session();

        assertThat(rows(learner).get(MilestoneKey.FIRST_SESSION).achieved()).isTrue();
        assertThat(rows(learner).get(MilestoneKey.TWO_DAYS)).isEqualTo(new Stored(1, false, null));
        // the sample deck teaches the path, it does not complete the authoring chapter
        assertThat(rows(learner)).containsOnlyKeys(MilestoneKey.FIRST_DECK, MilestoneKey.FIRST_SESSION, MilestoneKey.TWO_DAYS);

        // the same day again (a practice session): the second date is still missing
        Stored first = rows(learner).get(MilestoneKey.FIRST_SESSION);
        study(material, "PRACTICE", null);
        assertThat(rows(learner).get(MilestoneKey.FIRST_SESSION)).isEqualTo(first);
        assertThat(rows(learner).get(MilestoneKey.TWO_DAYS).progress()).isOne();
        // a study on another day (the previous one, as the clock of the database has it) and then a session today reach the second date
        completedOn(session, studyDateOf(session).minusDays(1), "SCHEDULED");
        study(material, "PRACTICE", null);
        Stored days = rows(learner).get(MilestoneKey.TWO_DAYS);
        assertThat(days.progress()).isEqualTo(2);
        assertThat(days.achieved()).isTrue();
        // and nothing moves it afterwards
        study(material, "PRACTICE", null);
        assertThat(rows(learner).get(MilestoneKey.TWO_DAYS)).isEqualTo(days);
        assertThat(rows(learner).get(MilestoneKey.FIRST_SESSION)).isEqualTo(first);
    }

    @Test
    void aPracticeSessionCountsAndAReplayNeverDoes() {
        Source editorial = template(4, 2);
        UUID practicing = UUID.randomUUID();
        UUID practiceDeck = sample(practicing);
        worlds.awaitReady(practicing, practiceDeck);
        study(inCopy(editorial, practicing, practiceDeck, 0), "PRACTICE", null);
        assertThat(rows(practicing).get(MilestoneKey.FIRST_SESSION).achieved()).isTrue();
        assertThat(rows(practicing).get(MilestoneKey.TWO_DAYS).progress()).isOne();

        // a replay of a completed session: the facts of the account's first study are removed, so that only the replay is left to count
        UUID replaying = UUID.randomUUID();
        UUID replayDeck = sample(replaying);
        worlds.awaitReady(replaying, replayDeck);
        Material material = inCopy(editorial, replaying, replayDeck, 0);
        UUID scheduled = study(material, "SCHEDULED", null);
        forget(replaying, MilestoneKey.FIRST_SESSION, MilestoneKey.TWO_DAYS);
        study(material, "REPLAY", scheduled);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_session WHERE account_id = :a AND mode = 'REPLAY' AND status = 'COMPLETE'").param("a", replaying)
                .query(Long.class).single()).isOne();
        assertThat(rows(replaying)).as("a completed replay").doesNotContainKeys(MilestoneKey.FIRST_SESSION, MilestoneKey.TWO_DAYS);
    }

    @Test
    void theIssuerThatEndsAnExhaustedSessionCountsToo() {
        Source editorial = template(4, 2);
        UUID learner = UUID.randomUUID();
        UUID sample = sample(learner);
        worlds.awaitReady(learner, sample);
        Material material = inCopy(editorial, learner, sample, 0);
        UUID session = worlds.study.issue(material, "SCHEDULED", null).getFirst().session();
        assertThat(rows(learner)).doesNotContainKey(MilestoneKey.FIRST_SESSION);
        // nothing is pending and the budget is spent: the next read of the presentations completes the session in the issuer (StudySessionRepository.complete)
        jdbc.sql("UPDATE app_learning.study_session SET budget = issued_count, max_new_objectives = issued_new_objectives, batch_start = issued_count, batch_size = 0 WHERE session_id = :s")
                .param("s", session).update();
        sessions.presentations(learner, sample, session);
        assertThat(jdbc.sql("SELECT status FROM app_learning.study_session WHERE session_id = :s").param("s", session).query(String.class).single()).isEqualTo("COMPLETE");
        assertThat(rows(learner).get(MilestoneKey.FIRST_SESSION).achieved()).isTrue();
        assertThat(rows(learner).get(MilestoneKey.TWO_DAYS).progress()).isOne();
    }

    // ---- OWN_MATERIAL, MEDIA_IN_MATERIAL ----

    private UUID image(UUID owner) {
        UUID asset = worlds.study.readyAsset(owner, "image/png");
        MediaFixtures.variants(jdbc, asset, "image/webp");
        return asset;
    }

    @Test
    void aNewMaterialInADeckOfTheAccountsOwnIsTheMilestoneAndANewRevisionOfItIsNot() {
        UUID owner = UUID.randomUUID();
        UUID deck = worlds.shared.deck(owner, "Моя");
        assertThat(rows(owner)).containsOnlyKeys(MilestoneKey.FIRST_DECK);
        Material material = worlds.study.addMaterial(owner, deck, "слово", "перевод");
        assertThat(rows(owner)).containsOnlyKeys(MilestoneKey.FIRST_DECK, MilestoneKey.OWN_MATERIAL);
        assertThat(rows(owner)).doesNotContainKey(MilestoneKey.MEDIA_IN_MATERIAL);
        // a new revision of a material is not a new material
        forget(owner, MilestoneKey.OWN_MATERIAL);
        worlds.edit(owner, deck, material.member(), material.itemRevision(), "правка");
        assertThat(rows(owner)).as("a save without media").doesNotContainKeys(MilestoneKey.OWN_MATERIAL, MilestoneKey.MEDIA_IN_MATERIAL);
    }

    @Test
    void aPictureInANewOrASavedMaterialIsTheMediaMilestoneEvenWhenTheMaterialItselfWasNotTheFirst() {
        UUID created = UUID.randomUUID();
        UUID createdDeck = worlds.shared.deck(created, "С картинкой");
        worlds.shared.create(created, createdDeck, MediaFixtures.imageDocument(image(created)));
        assertThat(rows(created).get(MilestoneKey.OWN_MATERIAL).achieved()).isTrue();
        assertThat(rows(created).get(MilestoneKey.MEDIA_IN_MATERIAL).achieved()).isTrue();

        UUID saved = UUID.randomUUID();
        UUID savedDeck = worlds.shared.deck(saved, "Потом картинка");
        Material text = worlds.study.addMaterial(saved, savedDeck, "слово", "перевод");
        assertThat(rows(saved)).doesNotContainKey(MilestoneKey.MEDIA_IN_MATERIAL);
        MediaFixtures.saveImages(decks, items, saved, savedDeck, text.member(), text.itemRevision(), image(saved));
        assertThat(rows(saved).get(MilestoneKey.MEDIA_IN_MATERIAL).achieved()).isTrue();
        assertThat(rows(saved).get(MilestoneKey.OWN_MATERIAL).achievedAt()).isNotNull();

        // media APPEARS: saving the same picture again is not a new fact, a different one is
        UUID picture = image(saved);
        UUID withPicture = MediaFixtures.saveImages(decks, items, saved, savedDeck, text.member(), jdbc.sql(
                "SELECT revision_id FROM app_learning.item_revision WHERE owner_id=:a AND member_key=:m ORDER BY item_sequence DESC LIMIT 1").param("a", saved).param("m", text.member())
                .query(UUID.class).single(), picture);
        forget(saved, MilestoneKey.MEDIA_IN_MATERIAL);
        UUID again = worlds.edit(saved, savedDeck, text.member(), withPicture, "то же изображение, другой текст");
        assertThat(rows(saved)).as("the previous revision already had the picture").doesNotContainKey(MilestoneKey.MEDIA_IN_MATERIAL);
        MediaFixtures.saveImages(decks, items, saved, savedDeck, text.member(), again, picture, image(saved));
        assertThat(rows(saved).get(MilestoneKey.MEDIA_IN_MATERIAL).achieved()).as("a second picture appears").isTrue();
    }

    @Test
    void theSampleDeckTheMaterializedCopyAndAnAcceptedCopyDoNotCountTheAuthoringMilestones() {
        Source editorial = template(4, 2);
        UUID learner = UUID.randomUUID();
        UUID sample = sample(learner);
        worlds.awaitReady(learner, sample);
        // the learner edits the sample deck as he likes: it is a deck for learning the path, not for completing it
        worlds.study.addMaterial(learner, sample, "моё", "слово");
        worlds.shared.create(learner, sample, MediaFixtures.imageDocument(image(learner)));
        Material added = worlds.study.addMaterial(learner, sample, "ещё", "одно");
        worlds.study.publish(added, worlds.study.freeResponse(added, blocks(text("Вопрос?")), blocks(text("Подсказка")), "ответ"), "Своя цель");
        assertThat(rows(learner)).containsOnlyKeys(MilestoneKey.FIRST_DECK);

        // a community copy: materials and exercises arrive by the job, none of them is the copier's own
        Source community = worlds.source("PUBLIC", 4, 3);
        UUID copier = UUID.randomUUID();
        worlds.awaitReady(copier, worlds.forked(copier, community.code()));
        assertThat(rows(copier)).containsOnlyKeys(MilestoneKey.FIRST_DECK, MilestoneKey.COMMUNITY_COPY);
        assertThat(editorial.deck()).isNotNull();
    }

    // ---- OWN_EXERCISES ----

    @Test
    void theExercisesOfADeckOfTheAccountsOwnCountToThreeAndStayThereAndTheSampleDeckDoesNotCount() {
        UUID owner = UUID.randomUUID();
        UUID deck = worlds.shared.deck(owner, "Упражнения");
        Material material = worlds.study.addMaterial(owner, deck, "слово", "перевод");
        for (int index = 1; index <= 5; index++) {
            worlds.study.publish(material, worlds.study.freeResponse(material, blocks(text("Вопрос " + index + "?")), blocks(text("Подсказка")), "ответ"), "Цель " + index);
            Stored row = rows(owner).get(MilestoneKey.OWN_EXERCISES);
            assertThat(row.progress()).isEqualTo(Math.min(index, 3));
            assertThat(row.achieved()).isEqualTo(index >= 3);
        }

        Source editorial = template(4, 2);
        UUID learner = UUID.randomUUID();
        UUID sample = sample(learner);
        worlds.awaitReady(learner, sample);
        Material inSample = worlds.study.addMaterial(learner, sample, "моё", "слово");
        for (int index = 0; index < 4; index++) {
            worlds.study.publish(inSample, worlds.study.freeResponse(inSample, blocks(text("Вопрос " + index + "?")), blocks(text("Подсказка")), "ответ"), "Цель " + index);
        }
        assertThat(rows(learner)).doesNotContainKey(MilestoneKey.OWN_EXERCISES);
        assertThat(editorial.exercises()).isNotEmpty();
    }

    // ---- CAPTURE_NOTE ----

    private UUID note(UUID owner, UUID deck, String text) {
        String body = "{\"commandId\":\"" + UUID.randomUUID() + "\",\"deckId\":\"" + deck + "\",\"source\":\"источник\",\"text\":\"" + text + "\"}";
        HttpResponse<String> created = worlds.send("POST", "/capture-notes", owner, body, null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(json(created).path("capture").path("noteId").stringValue(null));
    }

    private HttpResponse<String> archive(UUID owner, UUID note, boolean archived) {
        String version = json(worlds.get("/capture-notes/" + note, owner)).path("rowVersion").stringValue(null);
        return worlds.send("POST", "/capture-notes/" + note + "/archive", owner, "{\"archived\":" + archived + "}", "\"" + version + "\"");
    }

    @Test
    void aSavedNoteIsTheFirstStageAndSortingItOutBySavingOrArchivingIsTheSecond() {
        UUID owner = UUID.randomUUID();
        UUID deck = worlds.shared.deck(owner, "Заметки");
        UUID note = note(owner, deck, "потом разобрать");
        assertThat(rows(owner).get(MilestoneKey.CAPTURE_NOTE)).isEqualTo(new Stored(1, false, null));
        note(owner, deck, "ещё одна");
        assertThat(rows(owner).get(MilestoneKey.CAPTURE_NOTE).progress()).as("a second saved note stays at the first stage").isOne();
        // archive then restore: the restoring is no fact
        assertThat(archive(owner, note, false).statusCode()).isEqualTo(200);
        assertThat(rows(owner).get(MilestoneKey.CAPTURE_NOTE).progress()).isOne();
        assertThat(archive(owner, note, true).statusCode()).isEqualTo(200);
        assertThat(rows(owner).get(MilestoneKey.CAPTURE_NOTE).progress()).isEqualTo(2);
        assertThat(rows(owner).get(MilestoneKey.CAPTURE_NOTE).achieved()).isTrue();
        Stored done = rows(owner).get(MilestoneKey.CAPTURE_NOTE);
        note(owner, deck, "после");
        assertThat(rows(owner).get(MilestoneKey.CAPTURE_NOTE)).as("a new note does not undo the milestone").isEqualTo(done);

        // a note sorted out without ever being saved at this account's stage one: archive reaches the second stage on its own is impossible (the note exists), so convert is the other way
        UUID converter = UUID.randomUUID();
        UUID convertDeck = worlds.shared.deck(converter, "Превращение");
        UUID toConvert = note(converter, convertDeck, "в материал");
        JsonNode head = json(worlds.get("/decks/" + convertDeck, converter));
        String version = json(worlds.get("/capture-notes/" + toConvert, converter)).path("rowVersion").stringValue(null);
        String conversion = "{\"commandId\":\"" + UUID.randomUUID() + "\",\"expectedDeckVersion\":\"" + head.path("rowVersion").stringValue(null)
                + "\",\"expectedDeckRevisionId\":\"" + head.path("revisionId").stringValue(null) + "\",\"document\":" + worlds.shared.document("Материал из заметки") + "}";
        HttpResponse<String> converted = worlds.send("POST", "/capture-notes/" + toConvert + "/conversions", converter, conversion, "\"" + version + "\"");
        assertThat(converted.statusCode()).as(converted.body()).isEqualTo(200);
        assertThat(rows(converter).get(MilestoneKey.CAPTURE_NOTE).achieved()).isTrue();
        assertThat(rows(converter).get(MilestoneKey.OWN_MATERIAL).achieved()).as("a conversion publishes a new material").isTrue();
        // a replay of the conversion changes nothing
        Map<MilestoneKey, Stored> before = rows(converter);
        assertThat(worlds.send("POST", "/capture-notes/" + toConvert + "/conversions", converter, conversion, "\"" + version + "\"").statusCode()).isEqualTo(200);
        assertThat(rows(converter)).isEqualTo(before);
    }

    @Test
    void aNoteInTheSampleDeckIsNotTheMilestone() {
        Source editorial = template(4, 2);
        UUID learner = UUID.randomUUID();
        UUID sample = sample(learner);
        worlds.awaitReady(learner, sample);
        UUID note = note(learner, sample, "в учебной");
        assertThat(archive(learner, note, true).statusCode()).isEqualTo(200);
        assertThat(rows(learner)).containsOnlyKeys(MilestoneKey.FIRST_DECK);
        assertThat(editorial.deck()).isNotNull();
    }
}
