package app.mnema.learning.study.attempt;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Issued;
import app.mnema.learning.support.StudyFixtures.Material;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static app.mnema.learning.study.attempt.PreviewRequests.exercise;
import static app.mnema.learning.study.attempt.PreviewRequests.request;
import static app.mnema.learning.study.attempt.PreviewRequests.submit;
import static app.mnema.learning.support.ContractFixtures.bytes;
import static app.mnema.learning.support.StudyFixtures.JSON;
import static app.mnema.learning.support.StudyFixtures.attempt;
import static app.mnema.learning.support.StudyFixtures.audio;
import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.category;
import static app.mnema.learning.support.StudyFixtures.image;
import static app.mnema.learning.support.StudyFixtures.item;
import static app.mnema.learning.support.StudyFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ORDER and CATEGORIZE through the real Study path: issue, persistence, strict responses, evidence, isolation. */
@SpringBootTest
class OrderCategorizeStudyIntegrationTest extends PostgresIntegrationTest {
    private static final String CODE = "for (int i = 0; i < n; i++) {\n    sum += i;\n}";
    @Autowired private AttemptService attempts;
    @Autowired private StudySessionService sessions;
    @Autowired private ExercisePreviewService previews;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;
    private StudyFixtures fixtures;

    @BeforeEach
    void fixtures() { fixtures = new StudyFixtures(decks, items, exercises, sessions, media, jdbc); }

    /** Ids of the authored sentence in key order: «Это очень очень важно, [код]». */
    private record Sentence(ObjectNode exercise, UUID[] key) { }

    private Sentence sentence(Material material) {
        UUID[] key = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        String[] words = {"Это", "очень", "очень", "важно,", CODE};
        ArrayNode tiles = blocks();
        for (int index = 0; index < key.length; index++) tiles.add(item(key[index], text(words[index])));
        return new Sentence(fixtures.order(material, blocks(text("Восстановите порядок.")), tiles, key), key);
    }

    @Test
    void anOrderIsIssuedShuffledWithoutTheKeyAndPersistedForReadsAndReplays() {
        Material material = fixtures.material();
        Sentence sentence = sentence(material);
        fixtures.publish(material, sentence.exercise());
        Issued issued = fixtures.issueOne(material);

        assertThat(issued.json().path("type").textValue()).isEqualTo("ORDER");
        assertThat(issued.json().path("evaluator").path("id").textValue()).isEqualTo("deterministic-order");
        assertThat(ids(issued.content().path("items"))).containsExactlyInAnyOrder(strings(sentence.key()));
        assertThat(issued.json().toString()).doesNotContain("sequence", "answerKey", "correct");
        assertThat(texts(issued.content().path("items"))).as("code keeps its newlines and indentation").contains(CODE);
        // read/resume returns exactly the issued permutation
        JsonNode reread = sessions.read(material.actor(), material.deck(), issued.session()).path("presentations").get(0);
        assertThat(reread.path("content")).isEqualTo(issued.content());
        assertThat(jdbc.sql("SELECT content::text FROM app_learning.study_presentation WHERE presentation_id=:id")
                .param("id", issued.id()).query(String.class).single()).contains("очень");

        attempts.submit(material.actor(), material.deck(), issued.session(),
                attempt(issued, order(sentence.key())));
        Issued replay = fixtures.issue(material, "REPLAY", issued.session()).getFirst();
        assertThat(replay.content().path("items")).as("replay copies the issued order").isEqualTo(issued.content().path("items"));
        JsonNode again = sessions.read(material.actor(), material.deck(), replay.session()).path("presentations").get(0);
        assertThat(again.path("content")).isEqualTo(replay.content());
    }

    @Test
    void theEquivalentSwapOfIdenticalTilesIsCorrectAndADuplicateSubmitGivesOneTransition() {
        Material material = fixtures.material();
        Sentence sentence = sentence(material);
        fixtures.publish(material, sentence.exercise());
        Issued issued = fixtures.issueOne(material);
        UUID[] key = sentence.key();
        UUID attemptId = UUID.randomUUID();
        AttemptCommand swapped = attempt(attemptId, issued, order(key[0], key[2], key[1], key[3], key[4]));

        AttemptService.SubmitResult first = attempts.submit(material.actor(), material.deck(), issued.session(), swapped);
        JsonNode outcome = first.outcome();
        assertThat(first.replayed()).isFalse();
        assertThat(outcome.path("status").textValue()).isEqualTo("ASSESSED");
        assertThat(outcome.path("feedback").path("result").textValue()).isEqualTo("CORRECT");
        assertThat(outcome.path("feedback").path("positions").findValuesAsText("correct"))
                .containsExactly("true", "true", "true", "true", "true");
        assertThat(outcome.path("feedback").path("correctSequence")).hasSize(5);
        assertThat(outcome.path("evidence").path("evidenceClass").textValue()).isEqualTo("MEDIUM");
        assertThat(outcome.path("evidence").path("reasonCodes").toString()).isEqualTo("[\"SEQUENCING\",\"DETERMINISTIC\"]");
        assertThat(outcome.path("transition").path("afterLevel").intValue()).isOne();

        assertThat(attempts.submit(material.actor(), material.deck(), issued.session(), swapped).replayed()).isTrue();
        assertThatThrownBy(() -> attempts.submit(material.actor(), material.deck(), issued.session(),
                attempt(UUID.randomUUID(), issued, order(key)))).isInstanceOf(IdempotencyConflictException.class);
        assertThat(count("study_transition", material)).isOne();
        assertThat(count("study_evidence", material)).isOne();
    }

    @Test
    void aMisplacedOrderIsIncorrectWithPerPositionFeedbackAndOneEvidenceRow() {
        Material material = fixtures.material();
        Sentence sentence = sentence(material);
        fixtures.publish(material, sentence.exercise());
        Issued issued = fixtures.issueOne(material);
        UUID[] key = sentence.key();
        JsonNode outcome = attempts.submit(material.actor(), material.deck(), issued.session(),
                attempt(issued, order(key[4], key[1], key[2], key[3], key[0]))).outcome();
        assertThat(outcome.path("feedback").path("result").textValue()).isEqualTo("INCORRECT");
        assertThat(outcome.path("feedback").path("positions").findValuesAsText("correct"))
                .containsExactly("false", "true", "true", "true", "false");
        assertThat(outcome.path("feedback").path("correctSequence").get(0).textValue()).isEqualTo(key[0].toString());
        assertThat(outcome.path("evidence").path("result").textValue()).isEqualTo("INCORRECT");
        assertThat(count("study_evidence", material)).isOne();
    }

    @Test
    void unknownMissingDuplicateAndForeignIdsAreRejectedAndConsumeNothing() {
        Material material = fixtures.material();
        Sentence sentence = sentence(material);
        fixtures.publish(material, sentence.exercise());
        Issued issued = fixtures.issueOne(material);
        Material other = fixtures.material();
        fixtures.publish(other, sentence(other).exercise());
        Issued foreign = fixtures.issueOne(other);
        UUID foreignId = UUID.fromString(foreign.content().path("items").get(0).path("itemId").textValue());
        UUID[] key = sentence.key();

        for (ObjectNode response : List.of(
                order(key[0], key[1], key[2], key[3], UUID.randomUUID()),
                order(key[0], key[1], key[2], key[3]),
                order(key[0], key[1], key[2], key[3], key[3]),
                order(key[0], key[1], key[2], key[3], foreignId),
                order(key[0], key[1], key[2], key[3], key[4], UUID.randomUUID()))) {
            assertThatThrownBy(() -> attempts.submit(material.actor(), material.deck(), issued.session(),
                    attempt(issued, response))).as(response.toString()).isInstanceOf(InvalidRequestException.class);
        }
        // a categorize response never fits an ORDER presentation
        assertThatThrownBy(() -> attempts.submit(material.actor(), material.deck(), issued.session(),
                attempt(issued, categorize(new UUID[][] {{key[0], key[1]}, {key[2], key[3]}}))))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(count("study_attempt_tombstone", material)).isZero();
        assertThat(count("study_transition", material)).isZero();
        // the presentation is still pending
        assertThat(attempts.submit(material.actor(), material.deck(), issued.session(), attempt(issued, order(key)))
                .outcome().path("feedback").path("result").textValue()).isEqualTo("CORRECT");
    }

    /** Authored order «дом, бежать, река, читать» with the third group left empty as a distractor. */
    private record Groups(ObjectNode exercise, UUID[] items, UUID[] categories) { }

    private Groups groups(Material material) {
        UUID[] tiles = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        UUID[] categories = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        String[] words = {"дом", "бежать", "река", "читать"};
        ArrayNode tileNodes = blocks();
        for (int index = 0; index < tiles.length; index++) tileNodes.add(item(tiles[index], text(words[index])));
        ObjectNode exercise = fixtures.categorize(material, blocks(text("Распределите слова.")),
                blocks().add(category(categories[0], "Существительное")).add(category(categories[1], "Глагол"))
                        .add(category(categories[2], "Наречие")),
                tileNodes, new UUID[][] {{tiles[0], categories[0]}, {tiles[1], categories[1]},
                        {tiles[2], categories[0]}, {tiles[3], categories[1]}});
        return new Groups(exercise, tiles, categories);
    }

    @Test
    void aCategorizeIsIssuedWithAuthoredCategoriesShuffledItemsAndNoKey() {
        Material material = fixtures.material();
        Groups groups = groups(material);
        fixtures.publish(material, groups.exercise());
        Issued issued = fixtures.issueOne(material);
        assertThat(issued.json().path("type").textValue()).isEqualTo("CATEGORIZE");
        assertThat(ids(issued.content().path("items"))).containsExactlyInAnyOrder(strings(groups.items()));
        assertThat(issued.content().path("categories")).isEqualTo(groups.exercise().path("content").path("categories"));
        assertThat(issued.json().toString()).doesNotContain("assignments", "answerKey", "correct");
        JsonNode reread = sessions.read(material.actor(), material.deck(), issued.session()).path("presentations").get(0);
        assertThat(reread.path("content")).isEqualTo(issued.content());
    }

    @Test
    void categorizeScoresEachItemAndRejectsIncompleteDuplicateForeignAndUnknownAssignments() {
        Material material = fixtures.material();
        Groups groups = groups(material);
        fixtures.publish(material, groups.exercise());
        Issued issued = fixtures.issueOne(material);
        Material other = fixtures.material();
        fixtures.publish(other, groups(other).exercise());
        Issued foreign = fixtures.issueOne(other);
        UUID foreignItem = UUID.fromString(foreign.content().path("items").get(0).path("itemId").textValue());
        UUID[] t = groups.items();
        UUID[] c = groups.categories();

        for (ObjectNode response : List.of(
                categorize(new UUID[][] {{t[0], c[0]}, {t[1], c[1]}, {t[2], c[0]}}),
                categorize(new UUID[][] {{t[0], c[0]}, {t[1], c[1]}, {t[2], c[0]}, {t[3], c[1]}, {t[3], c[1]}}),
                categorize(new UUID[][] {{t[0], c[0]}, {t[0], c[1]}, {t[2], c[0]}, {t[3], c[1]}}),
                categorize(new UUID[][] {{t[0], c[0]}, {t[1], c[1]}, {t[2], c[0]}, {foreignItem, c[1]}}),
                categorize(new UUID[][] {{t[0], c[0]}, {t[1], c[1]}, {t[2], c[0]}, {t[3], UUID.randomUUID()}}),
                categorize(new UUID[][] {{t[0], c[0]}, {t[1], c[1]}, {t[2], c[0]}, {t[3], c[1]}, {foreignItem, c[0]}}),
                order(t))) {
            assertThatThrownBy(() -> attempts.submit(material.actor(), material.deck(), issued.session(),
                    attempt(issued, response))).as(response.toString()).isInstanceOf(InvalidRequestException.class);
        }
        assertThat(count("study_attempt_tombstone", material)).isZero();

        // a wrong group for one item: partial, with the correct group reported per item; the empty group is allowed
        JsonNode outcome = attempts.submit(material.actor(), material.deck(), issued.session(), attempt(issued,
                categorize(new UUID[][] {{t[0], c[0]}, {t[1], c[1]}, {t[2], c[2]}, {t[3], c[1]}}))).outcome();
        assertThat(outcome.path("feedback").path("result").textValue()).isEqualTo("PARTIAL");
        assertThat(outcome.path("feedback").path("assignments").findValuesAsText("correct"))
                .containsExactly("true", "true", "false", "true");
        assertThat(outcome.path("feedback").path("assignments").get(2).path("correctCategoryId").textValue())
                .isEqualTo(c[0].toString());
        assertThat(outcome.path("feedback").path("assignments").get(2).path("selectedCategoryId").textValue())
                .isEqualTo(c[2].toString());
        assertThat(outcome.path("evidence").path("evidenceClass").textValue()).isEqualTo("LOW");
        assertThat(outcome.path("evidence").path("reasonCodes").toString())
                .isEqualTo("[\"CATEGORIZING\",\"DETERMINISTIC\",\"RECOGNITION\"]");
        assertThat(count("study_evidence", material)).isOne();
    }

    @Test
    void severalItemsInOneGroupAndACorrectMapAreCorrectWithOneTransition() {
        Material material = fixtures.material();
        Groups groups = groups(material);
        fixtures.publish(material, groups.exercise());
        Issued issued = fixtures.issueOne(material);
        UUID[] t = groups.items();
        UUID[] c = groups.categories();
        AttemptCommand answer = attempt(issued, categorize(new UUID[][] {{t[3], c[1]}, {t[2], c[0]}, {t[1], c[1]}, {t[0], c[0]}}));
        JsonNode outcome = attempts.submit(material.actor(), material.deck(), issued.session(), answer).outcome();
        assertThat(outcome.path("feedback").path("result").textValue()).isEqualTo("CORRECT");
        assertThat(outcome.path("transition").path("afterLevel").intValue()).isOne();
        assertThat(attempts.submit(material.actor(), material.deck(), issued.session(), answer).replayed()).isTrue();
        assertThat(count("study_transition", material)).isOne();
    }

    @Test
    void practiceAndReplayOfBothMechanicsNeverCreateCanonicalExposureEvidenceOrState() {
        for (String type : new String[] {"ORDER", "CATEGORIZE"}) {
            Material material = fixtures.material();
            ObjectNode response;
            if (type.equals("ORDER")) {
                Sentence sentence = sentence(material);
                fixtures.publish(material, sentence.exercise());
                response = order(sentence.key());
            } else {
                Groups groups = groups(material);
                fixtures.publish(material, groups.exercise());
                UUID[] t = groups.items();
                UUID[] c = groups.categories();
                response = categorize(new UUID[][] {{t[0], c[0]}, {t[1], c[1]}, {t[2], c[0]}, {t[3], c[1]}});
            }
            Issued practice = fixtures.issue(material, "PRACTICE", null).getFirst();
            JsonNode outcome = attempts.submit(material.actor(), material.deck(), practice.session(),
                    attempt(practice, response)).outcome();
            assertThat(outcome.path("canonicalEffects").booleanValue()).as(type).isFalse();
            assertThat(outcome.path("feedback").path("result").textValue()).isEqualTo("CORRECT");
            assertThat(outcome.path("evidence").isNull()).isTrue();
            assertThat(outcome.path("transition").isNull()).isTrue();
            for (String table : new String[] {"study_evidence", "study_transition", "study_state", "study_exposure"}) {
                assertThat(count(table, material)).as(type + " " + table).isZero();
            }

            // a replay of a completed scheduled session is feedback-only as well and keeps the issued order
            Issued scheduled = fixtures.issueOne(material);
            attempts.submit(material.actor(), material.deck(), scheduled.session(), attempt(scheduled, response));
            long transitions = count("study_transition", material);
            Issued replay = fixtures.issue(material, "REPLAY", scheduled.session()).getFirst();
            assertThat(replay.content().path("items")).isEqualTo(scheduled.content().path("items"));
            JsonNode replayed = attempts.submit(material.actor(), material.deck(), replay.session(),
                    attempt(replay, response)).outcome();
            assertThat(replayed.path("canonicalEffects").booleanValue()).isFalse();
            assertThat(count("study_transition", material)).isEqualTo(transitions);
        }
    }

    @Test
    void anUnreadyMediaItemGivesNoAssessmentNoEvidenceAndNoTransitionForBothMechanics() {
        for (String type : new String[] {"ORDER", "CATEGORIZE"}) {
            Material material = fixtures.material();
            UUID sound = fixtures.readyAsset(material.actor(), "audio/mpeg");
            UUID picture = fixtures.readyAsset(material.actor(), "image/png");
            UUID one = UUID.randomUUID(), two = UUID.randomUUID(), three = UUID.randomUUID();
            ObjectNode response;
            if (type.equals("ORDER")) {
                fixtures.publish(material, fixtures.order(material, blocks(), blocks()
                        .add(item(one, audio(sound, "Реплика 1", null))).add(item(two, text("Ответ")))
                        .add(item(three, image(picture, "Кадр"))), one, two, three));
                response = order(one, two, three);
            } else {
                UUID group = UUID.randomUUID(), otherGroup = UUID.randomUUID();
                fixtures.publish(material, fixtures.categorize(material, blocks(),
                        blocks().add(category(group, "Звуки")).add(category(otherGroup, "Слова")),
                        blocks().add(item(one, audio(sound, "Запись", null))).add(item(two, text("слово")))
                                .add(item(three, image(picture, "Кадр"))),
                        new UUID[][] {{one, group}, {two, otherGroup}, {three, group}}));
                response = categorize(new UUID[][] {{one, group}, {two, otherGroup}, {three, group}});
            }
            Issued issued = fixtures.issueOne(material);
            jdbc.sql("UPDATE app_learning.media_asset SET state='DELETED',updated_at=CURRENT_TIMESTAMP WHERE asset_id=:asset")
                    .param("asset", sound).update();
            JsonNode outcome = attempts.submit(material.actor(), material.deck(), issued.session(),
                    attempt(issued, response)).outcome();
            assertThat(outcome.path("status").textValue()).as(type).isEqualTo("NOT_ASSESSED");
            assertThat(outcome.path("feedback").path("reasonCodes").get(0).textValue()).isEqualTo("MEDIA_NOT_READY");
            assertThat(outcome.path("transition").isNull()).isTrue();
            assertThat(outcome.path("evidence").isNull()).isTrue();
            assertThat(count("study_evidence", material)).isZero();
            assertThat(count("study_transition", material)).isZero();
        }
    }

    @Test
    void transcriptsOfAnItemAreRevealedOnRequestAndAreNeverPartOfTheIssuedBoard() {
        Material material = fixtures.material();
        UUID sound = fixtures.readyAsset(material.actor(), "audio/mpeg");
        UUID one = UUID.randomUUID(), two = UUID.randomUUID();
        fixtures.publish(material, fixtures.order(material, blocks(), blocks()
                .add(item(one, audio(sound, "Реплика", "SECRET-LINE-ONE"))).add(item(two, text("Вторая"))), one, two));
        Issued issued = fixtures.issueOne(material);
        assertThat(issued.json().toString()).doesNotContain("SECRET-LINE-ONE", "Реплика");
        JsonNode revealed = sessions.revealTranscript(material.actor(), material.deck(), issued.session(), issued.id(),
                issued.nonce());
        assertThat(revealed.toString()).contains("SECRET-LINE-ONE");
        JsonNode outcome = attempts.submit(material.actor(), material.deck(), issued.session(),
                attempt(issued, order(one, two))).outcome();
        assertThat(outcome.path("evidence").path("reasonCodes").toString())
                .isEqualTo("[\"SEQUENCING\",\"DETERMINISTIC\",\"TRANSCRIPT_ACCOMMODATION\"]");
    }

    @Test
    void thePreviewGivesTheStudyFeedbackForBothMechanicsAndWritesNothing() {
        Material material = fixtures.material();
        Sentence sentence = sentence(material);
        Groups groups = groups(material);
        UUID[] key = sentence.key();
        ObjectNode misplaced = order(key[4], key[1], key[2], key[3], key[0]);
        UUID[] t = groups.items();
        UUID[] c = groups.categories();
        ObjectNode wrongGroup = categorize(new UUID[][] {{t[0], c[0]}, {t[1], c[1]}, {t[2], c[2]}, {t[3], c[1]}});

        fixtures.publish(material, sentence.exercise());
        fixtures.publish(material, groups.exercise());
        List<Issued> issued = fixtures.issue(material, "PRACTICE", null);
        assertThat(issued).hasSize(2);
        for (Issued presentation : issued) {
            boolean order = presentation.json().path("type").textValue().equals("ORDER");
            ObjectNode response = order ? misplaced : wrongGroup;
            JsonNode study = attempts.submit(material.actor(), material.deck(), presentation.session(),
                    attempt(presentation, response)).outcome().path("feedback");
            JsonNode preview = previews.evaluate(ExercisePreviewCommand.read(bytes(request(
                    exercise(order ? sentence.exercise() : groups.exercise()), submit(response))))).path("feedback");
            assertThat(preview).as(presentation.json().path("type").textValue()).isEqualTo(study);
            assertThat(study.path("result").textValue()).isIn("INCORRECT", "PARTIAL");
        }

        Map<String, Long> before = rowCounts();
        for (int repeat = 0; repeat < 2; repeat++) {
            previews.evaluate(ExercisePreviewCommand.read(bytes(request(exercise(sentence.exercise()), submit(misplaced)))));
            previews.evaluate(ExercisePreviewCommand.read(bytes(request(exercise(groups.exercise()), submit(wrongGroup)))));
        }
        assertThat(before).containsKeys("exercise_revision", "study_evidence", "media_asset", "study_presentation");
        assertThat(rowCounts()).as("a preview writes no row anywhere").isEqualTo(before);
    }

    // ---- helpers ----

    private Map<String, Long> rowCounts() {
        Map<String, Long> counts = new TreeMap<>();
        for (String table : jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema='app_learning' "
                + "AND table_type='BASE TABLE'").query(String.class).list()) {
            counts.put(table, jdbc.sql("SELECT count(*) FROM app_learning.\"" + table + "\"").query(Long.class).single());
        }
        return counts;
    }

    private static ObjectNode order(UUID... sequence) {
        ObjectNode response = JSON.createObjectNode().put("kind", "ORDER");
        ArrayNode ids = response.putArray("sequence");
        for (UUID id : sequence) ids.add(id.toString());
        return response;
    }

    private static ObjectNode categorize(UUID[][] assignments) {
        ObjectNode response = JSON.createObjectNode().put("kind", "CATEGORIZE");
        ArrayNode values = response.putArray("assignments");
        for (UUID[] assignment : assignments) {
            values.addObject().put("itemId", assignment[0].toString()).put("categoryId", assignment[1].toString());
        }
        return response;
    }

    private static List<String> texts(JsonNode items) {
        List<String> texts = new ArrayList<>();
        items.forEach(item -> item.path("blocks").forEach(block -> texts.add(block.path("text").asText())));
        return texts;
    }

    private static List<String> ids(JsonNode items) {
        List<String> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.path("itemId").textValue()));
        return ids;
    }

    private static String[] strings(UUID[] ids) {
        return java.util.Arrays.stream(ids).map(UUID::toString).toArray(String[]::new);
    }

    private long count(String table, Material material) {
        return jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE account_id=:actor")
                .param("actor", material.actor()).query(Long.class).single();
    }
}
