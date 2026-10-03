package app.mnema.learning.study.attempt;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.study.progress.StudyProgressService;
import app.mnema.learning.study.restart.StudyRestartCommand;
import app.mnema.learning.study.restart.StudyRestartService;
import app.mnema.learning.study.retention.StudyRetentionService;
import app.mnema.learning.study.session.StudyHintCommand;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Issued;
import app.mnema.learning.support.StudyFixtures.Material;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

import static app.mnema.learning.support.ContractFixtures.bytes;
import static app.mnema.learning.support.StudyFixtures.JSON;
import static app.mnema.learning.support.StudyFixtures.attempt;
import static app.mnema.learning.support.StudyFixtures.audio;
import static app.mnema.learning.support.StudyFixtures.blank;
import static app.mnema.learning.support.StudyFixtures.blankKey;
import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.image;
import static app.mnema.learning.support.StudyFixtures.item;
import static app.mnema.learning.support.StudyFixtures.option;
import static app.mnema.learning.support.StudyFixtures.quote;
import static app.mnema.learning.support.StudyFixtures.text;
import static app.mnema.learning.support.StudyFixtures.textResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The five mechanics through the real Study write path: hints, transcripts, pair checks and isolation. */
@SpringBootTest
class StudyMechanicsIntegrationTest extends PostgresIntegrationTest {
    @Autowired private AttemptService attempts;
    @Autowired private StudySessionService sessions;
    @Autowired private StudyProgressService progress;
    @Autowired private StudyRestartService restarts;
    @Autowired private StudyRetentionService retention;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;
    private StudyFixtures fixtures;

    @BeforeEach
    void fixtures() { fixtures = new StudyFixtures(decks, items, exercises, sessions, media, jdbc); }

    @Test
    void clozeHintsAreServerRecordedIdempotentAndLimitedToFirstLetterBlanksAndCapEvidence() {
        Material material = fixtures.material();
        UUID hinted = UUID.randomUUID(), plain = UUID.randomUUID(), repeated = UUID.randomUUID();
        fixtures.publish(material, fixtures.cloze(material, blocks(text("Complete")),
                blocks(text("a "), blank(hinted, false, 0, true), text(" b "), blank(plain, true, 8, false),
                        text(" c "), blank(repeated, true, 5, true)),
                blankKey(hinted, "map"), blankKey(plain, "toList"), blankKey(repeated, "map")));
        Issued presentation = fixtures.issueOne(material);
        assertThat(presentation.json().path("hints")).isEmpty();

        JsonNode first = hint(material, presentation, hinted);
        assertThat(first.toString()).isEqualTo("{\"presentationId\":\"" + presentation.id() + "\",\"blankId\":\""
                + hinted + "\",\"firstLetter\":\"m\"}");
        assertThat(hint(material, presentation, hinted)).as("a repeat returns the same value").isEqualTo(first);
        assertThat(count("study_hint_reveal", "presentation_id", presentation.id())).isOne();
        JsonNode reread = sessions.read(material.actor(), material.deck(), presentation.session()).path("presentations").get(0);
        assertThat(reread.path("hints").toString()).isEqualTo("[{\"blankId\":\"" + hinted + "\",\"firstLetter\":\"m\"}]");

        // only blanks that enable a first-letter hint, only for the presentation's owner and nonce
        assertThatThrownBy(() -> hint(material, presentation, plain)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> hint(material, presentation, UUID.randomUUID())).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> sessions.revealHint(material.actor(), material.deck(), presentation.session(), presentation.id(),
                command("wrong-nonce-0000000", hinted))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> sessions.revealHint(UUID.randomUUID(), material.deck(), presentation.session(), presentation.id(),
                command(presentation.nonce(), hinted))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> sessions.revealHint(material.actor(), material.deck(), presentation.session(), UUID.randomUUID(),
                command(presentation.nonce(), hinted))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(count("study_hint_reveal", "presentation_id", presentation.id())).isOne();

        ObjectNode response = JSON.createObjectNode().put("kind", "CLOZE");
        ArrayNode answers = response.putArray("blanks");
        answers.addObject().put("blankId", hinted.toString()).put("text", "map");
        answers.addObject().put("blankId", plain.toString()).put("text", "wrong");
        answers.addObject().put("blankId", repeated.toString()).put("text", "MAP");
        JsonNode outcome = attempts.submit(material.actor(), material.deck(), presentation.session(),
                attempt(presentation, response)).outcome();
        assertThat(outcome.path("feedback").path("result").stringValue(null)).isEqualTo("PARTIAL");
        assertThat(outcome.path("evidence").path("evidenceClass").stringValue(null)).isEqualTo("MEDIUM");
        assertThat(outcome.path("evidence").path("reasonCodes").toString()).isEqualTo("[\"HINTED\",\"DETERMINISTIC\",\"PRODUCTION\"]");
        assertThat(outcome.path("feedback").path("blanks").findValuesAsString("hinted")).containsExactly("true", "false", "false");
        assertThat(outcome.path("feedback").path("blanks").findValuesAsString("correct")).containsExactly("true", "false", "true");
        assertThat(jdbc.sql("SELECT hints_used::text FROM app_learning.study_evidence WHERE account_id=:actor")
                .param("actor", material.actor()).query(String.class).single())
                .isEqualTo("[\"FIRST_LETTER:" + hinted + "\"]");
        // once answered the presentation is no longer pending: no further hints
        assertThatThrownBy(() -> hint(material, presentation, repeated)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anUnhintedClozeIsHighEvidenceAndHintsOnOtherMechanicsAreRejected() {
        Material material = fixtures.material();
        UUID blank = UUID.randomUUID();
        fixtures.publish(material, fixtures.cloze(material, blocks(), blocks(text("x "), blank(blank, true, 6, true)),
                blankKey(blank, "memory")));
        Issued cloze = fixtures.issueOne(material);
        ObjectNode response = JSON.createObjectNode().put("kind", "CLOZE");
        response.putArray("blanks").addObject().put("blankId", blank.toString()).put("text", "memory");
        JsonNode outcome = attempts.submit(material.actor(), material.deck(), cloze.session(), attempt(cloze, response)).outcome();
        assertThat(outcome.path("evidence").path("evidenceClass").stringValue(null)).isEqualTo("HIGH");
        assertThat(outcome.path("evidence").path("reasonCodes").get(0).stringValue(null)).isEqualTo("UNHINTED");

        Material choiceMaterial = fixtures.material();
        UUID correct = UUID.randomUUID();
        fixtures.publish(choiceMaterial, fixtures.choice(choiceMaterial, false, blocks(text("Pick")), blocks()
                .add(option(correct, text("a"))).add(option(UUID.randomUUID(), text("b"))), correct));
        Issued choice = fixtures.issueOne(choiceMaterial);
        assertThatThrownBy(() -> sessions.revealHint(choiceMaterial.actor(), choiceMaterial.deck(), choice.session(), choice.id(),
                command(choice.nonce(), correct))).isInstanceOf(InvalidRequestException.class);
        assertThat(count("study_hint_reveal", "presentation_id", choice.id())).isZero();
    }

    @Test
    void theFirstLetterIsAWholeGraphemeForCombiningMarksAndEmoji() {
        Material material = fixtures.material();
        String family = "👨‍👩‍👧";
        String decomposed = "éclair";
        UUID accent = UUID.randomUUID(), emoji = UUID.randomUUID(), group = UUID.randomUUID();
        fixtures.publish(material, fixtures.cloze(material, blocks(), blocks(text("a "), blank(accent, false, 0, true),
                text(" b "), blank(emoji, false, 0, true), text(" c "), blank(group, false, 0, true)),
                blankKey(accent, decomposed), blankKey(emoji, Character.toString(0x1F600) + " smile"),
                blankKey(group, family + " family")));
        Issued presentation = fixtures.issueOne(material);
        assertThat(hint(material, presentation, accent).path("firstLetter").stringValue(null)).isEqualTo("é");
        assertThat(hint(material, presentation, emoji).path("firstLetter").stringValue(null)).isEqualTo(Character.toString(0x1F600));
        assertThat(hint(material, presentation, group).path("firstLetter").stringValue(null)).isEqualTo(family);
        // ANSWER_LENGTH counts the same normalized characters: e + combining acute is one
        assertThat(presentation.content().path("passage").get(1).path("size").path("length").intValue())
                .isEqualTo("éclair".length());
    }

    @Test
    void mediaThatIsNotReadyGivesNoTransitionForEveryMechanicAndBlocksPairChecks() {
        for (String kind : new String[] {"audio", "video", "image"}) {
            Material material = fixtures.material();
            UUID asset = fixtures.readyAsset(material.actor(), kind + (kind.equals("image") ? "/png" : kind.equals("audio") ? "/mpeg" : "/mp4"));
            ObjectNode block = kind.equals("audio") ? audio(asset, "Label", null)
                    : kind.equals("video") ? StudyFixtures.video(asset, "Label", null) : image(asset, "Alt");
            fixtures.publish(material, fixtures.freeResponse(material, blocks(block, text("Answer")), blocks(), "memory"));
            Issued presentation = fixtures.issueOne(material);
            jdbc.sql("UPDATE app_learning.media_asset SET state='DELETED',updated_at=CURRENT_TIMESTAMP WHERE asset_id=:asset")
                    .param("asset", asset).update();
            JsonNode outcome = attempts.submit(material.actor(), material.deck(), presentation.session(),
                    attempt(presentation, textResponse("memory"))).outcome();
            assertThat(outcome.path("status").stringValue(null)).as(kind).isEqualTo("NOT_ASSESSED");
            assertThat(outcome.path("feedback").path("reasonCodes").get(0).stringValue(null)).isEqualTo("MEDIA_NOT_READY");
            assertThat(outcome.path("transition").isNull()).isTrue();
            assertThat(outcome.path("evidence").isNull()).isTrue();
            assertThat(count("study_evidence", "account_id", material.actor())).isZero();
            assertThat(count("study_transition", "account_id", material.actor())).isZero();
        }
        // a cancelled answer never reports a media problem
        Material cancelled = fixtures.material();
        UUID asset = fixtures.readyAsset(cancelled.actor(), "audio/mpeg");
        fixtures.publish(cancelled, fixtures.freeResponse(cancelled, blocks(audio(asset, "Label", null)), blocks(), "x"));
        Issued presentation = fixtures.issueOne(cancelled);
        jdbc.sql("UPDATE app_learning.media_asset SET state='DELETED',updated_at=CURRENT_TIMESTAMP WHERE asset_id=:asset")
                .param("asset", asset).update();
        JsonNode outcome = attempts.submit(cancelled.actor(), cancelled.deck(), presentation.session(),
                attempt(presentation, JSON.createObjectNode().put("kind", "CANCEL"))).outcome();
        assertThat(outcome.path("feedback").has("reasonCodes")).isFalse();
    }

    @Test
    void pairChecksRecordWrongThenRightAndAnExactMapAfterAMistakeIsPartialWithPairRetry() {
        Material material = fixtures.material();
        UUID sound = fixtures.readyAsset(material.actor(), "audio/mpeg");
        UUID leftOne = UUID.randomUUID(), leftTwo = UUID.randomUUID(), rightOne = UUID.randomUUID(), rightTwo = UUID.randomUUID();
        fixtures.publish(material, fixtures.match(material, blocks(text("Connect")),
                blocks().add(item(leftOne, audio(sound, "Sound", null))).add(item(leftTwo, text("left two"))),
                blocks().add(item(rightOne, text("right one"))).add(item(rightTwo, text("right two"))),
                new UUID[][] {{leftOne, rightOne}, {leftTwo, rightTwo}}));
        Issued presentation = fixtures.issueOne(material);
        assertThat(presentation.content().path("left")).hasSize(2);
        PairCheckCommand mistake = pair(presentation, leftOne, rightTwo);
        assertThat(attempts.checkPair(material.actor(), material.deck(), presentation.session(), mistake)).isFalse();
        assertThat(attempts.checkPair(material.actor(), material.deck(), presentation.session(), mistake)).isFalse();
        assertThat(count("study_pair_interaction", "presentation_id", presentation.id())).isOne();
        assertThat(attempts.checkPair(material.actor(), material.deck(), presentation.session(),
                pair(presentation, leftOne, rightOne))).isTrue();
        assertThat(count("study_pair_interaction", "presentation_id", presentation.id())).isEqualTo(2);

        // unissued ids, foreign owners and wrong nonces learn nothing
        assertThatThrownBy(() -> attempts.checkPair(material.actor(), material.deck(), presentation.session(),
                pair(presentation, UUID.randomUUID(), rightOne))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> attempts.checkPair(material.actor(), material.deck(), presentation.session(),
                pair(presentation, leftOne, UUID.randomUUID()))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> attempts.checkPair(UUID.randomUUID(), material.deck(), presentation.session(), mistake))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> attempts.checkPair(material.actor(), material.deck(), presentation.session(),
                new PairCheckCommand(presentation.id(), "invalid-nonce-0000", leftOne, rightOne)))
                .isInstanceOf(ResourceNotFoundException.class);

        ObjectNode response = JSON.createObjectNode().put("kind", "MATCH");
        ArrayNode pairs = response.putArray("pairs");
        pairs.addObject().put("leftId", leftOne.toString()).put("rightId", rightOne.toString());
        pairs.addObject().put("leftId", leftTwo.toString()).put("rightId", rightTwo.toString());
        AttemptCommand submitted = attempt(presentation, response);
        JsonNode result = attempts.submit(material.actor(), material.deck(), presentation.session(), submitted).outcome();
        assertThat(result.path("feedback").path("result").stringValue(null)).isEqualTo("PARTIAL");
        assertThat(result.path("feedback").path("appliedRules").toString()).contains("PAIR_RETRY");
        assertThat(result.path("evidence").path("result").stringValue(null)).isEqualTo("PARTIAL");
        assertThat(result.path("evidence").path("evidenceClass").stringValue(null)).isEqualTo("LOW");
        assertThat(result.path("evidence").path("reasonCodes").toString())
                .isEqualTo("[\"MATCHING\",\"DETERMINISTIC\",\"RECOGNITION\",\"PAIR_RETRY\"]");
        assertThat(attempts.submit(material.actor(), material.deck(), presentation.session(), submitted).replayed()).isTrue();
        assertThat(count("study_transition", "account_id", material.actor())).isOne();
        // a recorded pair is still acknowledged after the terminal receipt; a new pair conflicts
        assertThat(attempts.checkPair(material.actor(), material.deck(), presentation.session(), mistake)).isFalse();
        assertThatThrownBy(() -> attempts.checkPair(material.actor(), material.deck(), presentation.session(),
                pair(presentation, leftTwo, rightTwo))).isInstanceOf(IdempotencyConflictException.class);

        // the bounded retention worker removes pair interactions after their window
        jdbc.sql("UPDATE app_learning.study_pair_interaction SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1 hour' "
                + "WHERE presentation_id=:presentation").param("presentation", presentation.id()).update();
        assertThat(retention.purgeBatch().pairInteractions()).isEqualTo(2);
        assertThat(count("study_pair_interaction", "presentation_id", presentation.id())).isZero();
    }

    @Test
    void aMatchWithoutMistakesIsCorrectAndOtherMechanicsRejectPairChecks() {
        Material material = fixtures.material();
        UUID leftOne = UUID.randomUUID(), leftTwo = UUID.randomUUID(), rightOne = UUID.randomUUID(), rightTwo = UUID.randomUUID();
        fixtures.publish(material, fixtures.match(material, blocks(), blocks().add(item(leftOne, text("1"))).add(item(leftTwo, text("2"))),
                blocks().add(item(rightOne, text("one"))).add(item(rightTwo, text("two"))),
                new UUID[][] {{leftOne, rightOne}, {leftTwo, rightTwo}}));
        Issued presentation = fixtures.issueOne(material);
        ObjectNode response = JSON.createObjectNode().put("kind", "MATCH");
        response.putArray("pairs").addObject().put("leftId", leftTwo.toString()).put("rightId", rightTwo.toString());
        response.withArray("pairs").addObject().put("leftId", leftOne.toString()).put("rightId", rightOne.toString());
        JsonNode outcome = attempts.submit(material.actor(), material.deck(), presentation.session(), attempt(presentation, response)).outcome();
        assertThat(outcome.path("feedback").path("result").stringValue(null)).isEqualTo("CORRECT");
        assertThat(outcome.path("feedback").path("pairs")).hasSize(2);
        assertThat(outcome.path("evidence").path("reasonCodes").toString()).doesNotContain("PAIR_RETRY");

        Material typed = fixtures.material();
        fixtures.publish(typed, fixtures.freeResponse(typed, blocks(text("Q")), blocks(), "a"));
        Issued text = fixtures.issueOne(typed);
        assertThatThrownBy(() -> attempts.checkPair(typed.actor(), typed.deck(), text.session(),
                pair(text, leftOne, rightOne))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void invalidResponsesLeaveNoReceiptAndEveryDuplicateSubmitStaysSingleEffect() {
        Material material = fixtures.material();
        UUID correct = UUID.randomUUID(), wrong = UUID.randomUUID();
        fixtures.publish(material, fixtures.choice(material, false, blocks(text("Pick one")),
                blocks().add(option(correct, text("a"))).add(option(wrong, text("b"))), correct));
        Issued presentation = fixtures.issueOne(material);
        for (ObjectNode response : List.of(textResponse("a"),
                JSON.createObjectNode().put("kind", "SELF_CHECK").put("rating", "FULL"),
                choice(correct, wrong), choice(UUID.randomUUID()))) {
            assertThatThrownBy(() -> attempts.submit(material.actor(), material.deck(), presentation.session(),
                    attempt(presentation, response))).isInstanceOf(InvalidRequestException.class);
        }
        assertThat(count("study_attempt_tombstone", "account_id", material.actor())).isZero();
        assertThat(count("study_transition", "account_id", material.actor())).isZero();

        AttemptCommand answer = attempt(presentation, choice(wrong));
        JsonNode first = attempts.submit(material.actor(), material.deck(), presentation.session(), answer).outcome();
        assertThat(first.path("feedback").path("result").stringValue(null)).isEqualTo("INCORRECT");
        assertThat(first.path("feedback").path("correctOptionIds").get(0).stringValue(null)).isEqualTo(correct.toString());
        assertThat(attempts.submit(material.actor(), material.deck(), presentation.session(), answer).replayed()).isTrue();
        assertThatThrownBy(() -> attempts.submit(material.actor(), material.deck(), presentation.session(),
                attempt(presentation, choice(correct)))).isInstanceOf(IdempotencyConflictException.class);
        assertThat(count("study_transition", "account_id", material.actor())).isOne();
        assertThat(count("study_attempt_tombstone", "account_id", material.actor())).isOne();
    }

    @Test
    void transcriptsOfAnyAudioOrVideoBlockAreRevealedOnlyOnRequestAndLowerTheEvidence() {
        Material material = fixtures.material();
        UUID sound = fixtures.readyAsset(material.actor(), "audio/mpeg");
        UUID correct = UUID.randomUUID(), wrong = UUID.randomUUID();
        fixtures.publish(material, fixtures.choice(material, false, blocks(text("Which?")),
                blocks().add(option(correct, audio(sound, "Label", "SECRET-OPTION-TRANSCRIPT")))
                        .add(option(wrong, text("plain"))), correct));
        Issued presentation = fixtures.issueOne(material);
        assertThat(presentation.json().toString()).doesNotContain("SECRET-OPTION-TRANSCRIPT");
        // options are shuffled at issue: the audio option is found by its identifier
        assertThat(issuedOption(presentation.content(), correct).path("blocks").get(0).path("transcriptAvailable").booleanValue()).isTrue();
        assertThatThrownBy(() -> sessions.revealTranscript(material.actor(), material.deck(), presentation.session(),
                presentation.id(), "wrong-nonce-0000000")).isInstanceOf(ResourceNotFoundException.class);
        JsonNode revealed = sessions.revealTranscript(material.actor(), material.deck(), presentation.session(),
                presentation.id(), presentation.nonce());
        assertThat(revealed.path("transcriptRevealed").booleanValue()).isTrue();
        assertThat(revealed.path("presentationId").stringValue(null)).isEqualTo(presentation.id().toString());
        assertThat(issuedOption(revealed.path("content"), correct).path("blocks").get(0).path("transcript").stringValue(null))
                .isEqualTo("SECRET-OPTION-TRANSCRIPT");
        assertThat(sessions.revealTranscript(material.actor(), material.deck(), presentation.session(),
                presentation.id(), presentation.nonce())).isEqualTo(revealed);
        assertThat(count("study_transcript_accommodation", "presentation_id", presentation.id())).isOne();
        JsonNode reread = sessions.read(material.actor(), material.deck(), presentation.session()).path("presentations").get(0);
        assertThat(reread.path("transcriptRevealed").booleanValue()).isTrue();
        assertThat(reread.toString()).contains("SECRET-OPTION-TRANSCRIPT");

        JsonNode outcome = attempts.submit(material.actor(), material.deck(), presentation.session(),
                attempt(presentation, choice(correct))).outcome();
        assertThat(outcome.path("evidence").path("evidenceClass").stringValue(null)).isEqualTo("LOW");
        assertThat(outcome.path("evidence").path("reasonCodes").toString())
                .isEqualTo("[\"RECOGNITION\",\"DETERMINISTIC\",\"TRANSCRIPT_ACCOMMODATION\"]");

        // a presentation without any transcript cannot reveal one
        Material plain = fixtures.material();
        fixtures.publish(plain, fixtures.freeResponse(plain, blocks(text("Q")), blocks(), "a"));
        Issued none = fixtures.issueOne(plain);
        assertThatThrownBy(() -> sessions.revealTranscript(plain.actor(), plain.deck(), none.session(), none.id(), none.nonce()))
                .isInstanceOf(InvalidRequestException.class);
        // the reveal also covers a MATCH side
        Material matching = fixtures.material();
        UUID matchSound = fixtures.readyAsset(matching.actor(), "audio/mpeg");
        UUID leftOne = UUID.randomUUID(), leftTwo = UUID.randomUUID(), rightOne = UUID.randomUUID(), rightTwo = UUID.randomUUID();
        fixtures.publish(matching, fixtures.match(matching, blocks(), blocks().add(item(leftOne, audio(matchSound, "A", "SIDE-TRANSCRIPT")))
                        .add(item(leftTwo, text("two"))), blocks().add(item(rightOne, text("one"))).add(item(rightTwo, text("2"))),
                new UUID[][] {{leftOne, rightOne}, {leftTwo, rightTwo}}));
        Issued sides = fixtures.issueOne(matching);
        assertThat(sessions.revealTranscript(matching.actor(), matching.deck(), sides.session(), sides.id(), sides.nonce())
                .toString()).contains("SIDE-TRANSCRIPT");
    }

    @Test
    void contextMaterialsGetNoExposureEvidenceOrStateAndOnlyTheSubjectObjectiveProgresses() {
        Material subject = fixtures.material();
        Material context = fixtures.addMaterial(subject.actor(), subject.deck(), "context fact", "context other");
        UUID correct = UUID.randomUUID();
        JsonNode published = fixtures.publish(subject, fixtures.choice(subject, false,
                blocks(text("Using"), quote(context, context.node())),
                blocks().add(option(correct, quote(subject, subject.node()))).add(option(UUID.randomUUID(), quote(context, context.distractor()))),
                correct));
        UUID revision = UUID.fromString(published.path("exerciseRevisionId").stringValue(null));
        assertThat(jdbc.sql("SELECT role||':'||member_key FROM app_learning.exercise_content_binding "
                + "WHERE exercise_revision_id=:revision ORDER BY binding_ordinal").param("revision", revision)
                .query(String.class).list()).containsExactlyInAnyOrder("ASSESSED:" + subject.member(),
                "CONTEXT:" + subject.member(), "CONTEXT:" + context.member());
        Issued presentation = fixtures.issueOne(subject);
        assertThat(presentation.content().path("prompt").get(1).path("text").stringValue(null)).isEqualTo("context fact");
        JsonNode outcome = attempts.submit(subject.actor(), subject.deck(), presentation.session(),
                attempt(presentation, choice(correct))).outcome();
        assertThat(outcome.path("evidence").path("objectiveId").stringValue(null)).isEqualTo(published.path("objectiveId").stringValue(null));
        assertThat(count("study_exposure", "account_id", subject.actor())).isOne();
        assertThat(count("study_evidence", "account_id", subject.actor())).isOne();
        assertThat(count("study_transition", "account_id", subject.actor())).isOne();
        assertThat(count("study_state", "account_id", subject.actor())).isOne();
        assertThat(jdbc.sql("SELECT objective_id FROM app_learning.study_state WHERE account_id=:actor")
                .param("actor", subject.actor()).query(UUID.class).single().toString())
                .isEqualTo(published.path("objectiveId").stringValue(null));
        JsonNode page = progress.read(subject.actor(), subject.deck(), 20, null);
        for (JsonNode row : page.path("items")) {
            if (row.path("memberKey").stringValue(null).equals(context.member().toString())) {
                assertThat(row.path("state").stringValue(null)).isEqualTo("NOT_STARTED");
                assertThat(row.path("objectiveCoverage").path("enabled").intValue()).isZero();
            } else {
                assertThat(row.path("state").stringValue(null)).isEqualTo("LEARNING");
            }
        }
    }

    @Test
    void practiceAndReplayOfEveryMechanicNeverCreateCanonicalExposureEvidenceOrState() {
        for (String type : new String[] {"SELF_CHECK", "FREE_RESPONSE", "CLOZE", "CHOICE", "MATCH"}) {
            Material material = fixtures.material();
            ObjectNode response = JSON.createObjectNode();
            UUID blank = UUID.randomUUID(), correct = UUID.randomUUID();
            UUID leftOne = UUID.randomUUID(), leftTwo = UUID.randomUUID(), rightOne = UUID.randomUUID(), rightTwo = UUID.randomUUID();
            switch (type) {
                case "SELF_CHECK" -> {
                    fixtures.publish(material, fixtures.selfCheck(material, blocks(text("Q")), blocks(text("A"))));
                    response.put("kind", "SELF_CHECK").put("rating", "FULL");
                }
                case "FREE_RESPONSE" -> {
                    fixtures.publish(material, fixtures.freeResponse(material, blocks(text("Q")), blocks(), "a"));
                    response.put("kind", "TEXT").put("text", "a");
                }
                case "CLOZE" -> {
                    fixtures.publish(material, fixtures.cloze(material, blocks(), blocks(text("x "), blank(blank, true, 5, true)),
                            blankKey(blank, "alpha")));
                    response.put("kind", "CLOZE").putArray("blanks").addObject().put("blankId", blank.toString()).put("text", "alpha");
                }
                case "CHOICE" -> {
                    fixtures.publish(material, fixtures.choice(material, false, blocks(text("Q")),
                            blocks().add(option(correct, text("a"))).add(option(UUID.randomUUID(), text("b"))), correct));
                    response = choice(correct);
                }
                default -> {
                    fixtures.publish(material, fixtures.match(material, blocks(), blocks().add(item(leftOne, text("1"))).add(item(leftTwo, text("2"))),
                            blocks().add(item(rightOne, text("a"))).add(item(rightTwo, text("b"))),
                            new UUID[][] {{leftOne, rightOne}, {leftTwo, rightTwo}}));
                    response.put("kind", "MATCH").putArray("pairs").addObject().put("leftId", leftOne.toString()).put("rightId", rightOne.toString());
                    response.withArray("pairs").addObject().put("leftId", leftTwo.toString()).put("rightId", rightTwo.toString());
                }
            }
            Issued practice = fixtures.issue(material, "PRACTICE", null).getFirst();
            if (type.equals("CLOZE")) {
                sessions.revealHint(material.actor(), material.deck(), practice.session(), practice.id(), command(practice.nonce(), blank));
            }
            if (type.equals("MATCH")) {
                attempts.checkPair(material.actor(), material.deck(), practice.session(), pair(practice, leftOne, rightTwo));
            }
            JsonNode outcome = attempts.submit(material.actor(), material.deck(), practice.session(), attempt(practice, response)).outcome();
            assertThat(outcome.path("canonicalEffects").booleanValue()).as(type).isFalse();
            assertThat(outcome.path("evidence").isNull()).isTrue();
            assertThat(outcome.path("transition").isNull()).isTrue();
            for (String table : new String[] {"study_evidence", "study_transition", "study_state", "study_exposure"}) {
                assertThat(count(table, "account_id", material.actor())).as(type + " " + table).isZero();
            }
        }
    }

    @Test
    void aMalformedResponseIsRejectedBeforeMediaChecksAndDoesNotConsumeThePresentation() {
        Material material = fixtures.material();
        UUID asset = fixtures.readyAsset(material.actor(), "audio/mpeg");
        fixtures.publish(material, fixtures.freeResponse(material, blocks(audio(asset, "Label", null), text("Q")), blocks(), "memory"));
        Issued presentation = fixtures.issueOne(material);
        jdbc.sql("UPDATE app_learning.media_asset SET state='DELETED',updated_at=CURRENT_TIMESTAMP WHERE asset_id=:asset")
                .param("asset", asset).update();
        assertThatThrownBy(() -> attempts.submit(material.actor(), material.deck(), presentation.session(),
                attempt(presentation, choice(UUID.randomUUID())))).isInstanceOf(InvalidRequestException.class);
        assertThat(count("study_attempt_tombstone", "account_id", material.actor())).isZero();
        // the presentation is still pending: a well-formed answer now gets the media outcome
        JsonNode outcome = attempts.submit(material.actor(), material.deck(), presentation.session(),
                attempt(presentation, textResponse("memory"))).outcome();
        assertThat(outcome.path("feedback").path("reasonCodes").get(0).stringValue(null)).isEqualTo("MEDIA_NOT_READY");
    }

    @Test
    void theIssuedMatchOrderIsPersistedForReadsAndReplayedVerbatim() {
        Material material = fixtures.material();
        UUID[] left = new UUID[4], right = new UUID[4];
        ArrayNode lefts = blocks(), rights = blocks();
        UUID[][] pairs = new UUID[4][];
        for (int index = 0; index < 4; index++) {
            left[index] = UUID.randomUUID();
            right[index] = UUID.randomUUID();
            lefts.add(item(left[index], text("l" + index)));
            rights.add(item(right[index], text("r" + index)));
            pairs[index] = new UUID[] {left[index], right[index]};
        }
        fixtures.publish(material, fixtures.match(material, blocks(), lefts, rights, pairs));
        Issued issued = fixtures.issueOne(material);
        JsonNode reread = sessions.read(material.actor(), material.deck(), issued.session()).path("presentations").get(0);
        assertThat(reread.path("content")).as("read/resume keeps the issued order").isEqualTo(issued.content());

        ObjectNode response = JSON.createObjectNode().put("kind", "MATCH");
        for (UUID[] pair : pairs) {
            response.withArray("pairs").addObject().put("leftId", pair[0].toString()).put("rightId", pair[1].toString());
        }
        attempts.submit(material.actor(), material.deck(), issued.session(), attempt(issued, response));
        Issued replay = fixtures.issue(material, "REPLAY", issued.session()).getFirst();
        assertThat(replay.content().path("left")).as("replay copies the left column").isEqualTo(issued.content().path("left"));
        assertThat(replay.content().path("right")).as("replay copies the right column").isEqualTo(issued.content().path("right"));
    }

    @Test
    void replayAfterARestartLeavesStateAndProgressExactlyAsTheRestartLeftThem() {
        Material material = fixtures.material();
        fixtures.publish(material, fixtures.freeResponse(material, blocks(text("Q")), blocks(), "memory"));
        Issued scheduled = fixtures.issueOne(material);
        attempts.submit(material.actor(), material.deck(), scheduled.session(), attempt(scheduled, textResponse("memory")));
        ObjectNode command = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString());
        command.putArray("memberKeys").add(material.member().toString());
        restarts.restart(material.actor(), material.deck(), StudyRestartCommand.read(
                new java.io.ByteArrayInputStream(command.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        JsonNode restarted = progress.read(material.actor(), material.deck(), 20, null).path("items").get(0);
        String stateBefore = stateRow(material);
        long historyBefore = count("study_transition", "account_id", material.actor())
                + count("study_evidence", "account_id", material.actor());
        assertThat(restarted.path("state").stringValue(null)).isEqualTo("DUE");

        Issued replay = fixtures.issue(material, "REPLAY", scheduled.session()).getFirst();
        JsonNode outcome = attempts.submit(material.actor(), material.deck(), replay.session(),
                attempt(replay, textResponse("memory"))).outcome();
        assertThat(outcome.path("canonicalEffects").booleanValue()).isFalse();

        assertThat(stateRow(material)).as("study_state row").isEqualTo(stateBefore);
        assertThat(count("study_transition", "account_id", material.actor())
                + count("study_evidence", "account_id", material.actor())).isEqualTo(historyBefore);
        JsonNode after = progress.read(material.actor(), material.deck(), 20, null).path("items").get(0);
        assertThat(after).as("progress item after replay").isEqualTo(restarted);
    }

    private String stateRow(Material material) {
        return jdbc.sql("""
                SELECT concat_ws('|',learning_epoch,level,correct_streak,lapse_count,last_assessed_at,next_due,
                       transition_sequence,row_version,updated_at)
                  FROM app_learning.study_state WHERE account_id=:actor AND deck_id=:deck
                """).param("actor", material.actor()).param("deck", material.deck()).query(String.class).single();
    }

    private JsonNode hint(Material material, Issued presentation, UUID blank) {
        return sessions.revealHint(material.actor(), material.deck(), presentation.session(), presentation.id(),
                command(presentation.nonce(), blank));
    }

    private static StudyHintCommand command(String nonce, UUID blank) {
        return StudyHintCommand.read(bytes(JSON.createObjectNode().put("nonce", nonce).put("blankId", blank.toString())));
    }

    private static PairCheckCommand pair(Issued presentation, UUID left, UUID right) {
        return new PairCheckCommand(presentation.id(), presentation.nonce(), left, right);
    }

    private static ObjectNode choice(UUID... ids) {
        ObjectNode response = JSON.createObjectNode().put("kind", "CHOICE");
        ArrayNode values = response.putArray("optionIds");
        for (UUID id : ids) values.add(id.toString());
        return response;
    }

    private long count(String table, String column, UUID value) {
        return jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE " + column + "=:value")
                .param("value", value).query(Long.class).single();
    }

    private static JsonNode issuedOption(JsonNode content, UUID optionId) {
        for (JsonNode option : content.path("options")) {
            if (optionId.toString().equals(option.path("optionId").stringValue(null))) return option;
        }
        throw new AssertionError("option " + optionId + " was not issued");
    }
}
