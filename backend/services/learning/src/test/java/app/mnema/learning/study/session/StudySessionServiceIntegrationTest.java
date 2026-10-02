package app.mnema.learning.study.session;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseCommand;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.study.attempt.AttemptService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Material;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
import static app.mnema.learning.support.StudyFixtures.video;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class StudySessionServiceIntegrationTest extends PostgresIntegrationTest {
    @Autowired private StudySessionService service;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionRepository sessions;
    @Autowired private JdbcClient jdbc;
    @Autowired private AttemptService attempts;
    @Autowired private MediaCatalog media;
    private StudyFixtures fixtures;

    @BeforeEach
    void fixtures() { fixtures = new StudyFixtures(decks, items, exercises, service, media, jdbc); }

    @Test
    void materialDeletionExcludesNewIssuanceButKeepsOtherMaterialsAndIssuedSnapshots() {
        Material removed = fixtures.material();
        JsonNode removedExercise = fixtures.publish(removed, fixtures.freeResponse(removed,
                blocks(text("Removed question")), blocks(), "memory"));
        Material retained = fixtures.addMaterial(removed.actor(), removed.deck(), "kept", "other");
        JsonNode retainedExercise = fixtures.publish(retained, fixtures.freeResponse(retained,
                blocks(text("Retained question")), blocks(), "kept"));
        // an exercise about the retained material that quotes the removed one as context
        UUID correct = UUID.randomUUID();
        fixtures.publish(retained, fixtures.choice(retained, false, blocks(text("Pick")), StudyFixtures.blocks()
                .add(option(correct, text("Right"))).add(option(UUID.randomUUID(), quote(removed, removed.node()))), correct));

        var started = service.start(removed.actor(), removed.deck(), "UTC", fixtures.scheduled());
        UUID oldSession = UUID.fromString(started.body().path("sessionId").asString());
        JsonNode old = service.read(removed.actor(), removed.deck(), oldSession);
        assertThat(old.path("presentations")).hasSize(3);
        JsonNode issued = null;
        for (JsonNode presentation : old.path("presentations")) {
            if (presentation.path("exerciseRevisionId").asString().equals(removedExercise.path("exerciseRevisionId").asString())) {
                issued = presentation;
            }
        }
        assertThat(issued).isNotNull();
        JsonNode deck = decks.read(removed.actor(), removed.deck());
        ObjectNode delete = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", deck.path("revisionId").asString());
        delete.putArray("changes").addObject().put("operation", "delete")
                .put("memberKey", removed.member().toString()).put("expectedItemRevisionId", removed.itemRevision().toString())
                .put("expectedOrdinal", 0);
        items.publish(removed.actor(), removed.deck(), Long.parseLong(deck.path("rowVersion").asString()),
                ItemPublicationCommand.readBulk(bytes(delete)));
        assertThat(service.presentations(removed.actor(), removed.deck(), oldSession).path("presentations"))
                .isEqualTo(old.path("presentations"));
        for (StudySessionCommand command : new StudySessionCommand[] {fixtures.scheduled(), fixtures.start("PRACTICE", null)}) {
            var fresh = service.start(removed.actor(), removed.deck(), "UTC", command);
            UUID freshId = UUID.fromString(fresh.body().path("sessionId").asString());
            JsonNode presentations = service.read(removed.actor(), removed.deck(), freshId).path("presentations");
            // neither the removed material nor the exercise quoting it as context is issued any more
            assertThat(presentations).hasSize(1);
            assertThat(presentations.get(0).path("exerciseRevisionId").asString())
                    .isEqualTo(retainedExercise.path("exerciseRevisionId").asString());
        }
        assertThat(attempts.submit(removed.actor(), removed.deck(), oldSession,
                attempt(new StudyFixtures.Issued(oldSession, issued), textResponse("memory")))
                .outcome().path("feedback").path("result").asString()).isEqualTo("CORRECT");
        assertThat(items.read(removed.actor(), removed.deck(), removed.member(), removed.itemRevision()).path("document").isObject())
                .isTrue();
        assertThat(exercises.read(removed.actor(), removed.deck(),
                UUID.fromString(removedExercise.path("exerciseId").stringValue(null)), null).path("type").stringValue(null))
                .isEqualTo("FREE_RESPONSE");
        jdbc.sql("UPDATE app_learning.study_session SET status='COMPLETE',completed_at=statement_timestamp() "
                + "WHERE session_id=:session").param("session", oldSession).update();
        var replayed = service.start(removed.actor(), removed.deck(), "UTC", fixtures.start("REPLAY", oldSession));
        JsonNode replayPresentations = replayed.body().path("presentations");
        assertThat(replayPresentations).hasSize(3);
        Set<String> revisions = new HashSet<>();
        replayPresentations.forEach(presentation -> revisions.add(presentation.path("exerciseRevisionId").asString()));
        assertThat(revisions).contains(removedExercise.path("exerciseRevisionId").asString(),
                retainedExercise.path("exerciseRevisionId").asString());
        // the quoted removed material was resolved when the choice was issued and stays in the snapshot
        assertThat(replayed.body().toString()).contains("memory");
    }

    @Test
    void scheduledPreparationPinsPresentationAndExactRetryResumesIt() {
        Material material = fixtures.material();
        JsonNode published = fixtures.publish(material, fixtures.freeResponse(material,
                blocks(quote(material, material.node()), text("Type it")), blocks(), "memory"));
        StudySessionCommand command = fixtures.scheduled();

        StudySessionService.StartResult started = service.start(material.actor(), material.deck(), "Europe/Moscow", command);
        assertThat(started.preparing()).isTrue();
        assertThat(started.body().path("status").stringValue(null)).isEqualTo("PREPARING");
        assertThat(service.start(material.actor(), material.deck(), "Pacific/Honolulu", command).replayed()).isTrue();

        UUID session = UUID.fromString(started.body().path("sessionId").stringValue(null));
        JsonNode active = service.read(material.actor(), material.deck(), session);
        JsonNode presentation = active.path("presentations").get(0);
        assertThat(active.path("status").stringValue(null)).isEqualTo("ACTIVE");
        assertThat(active.path("timezone").stringValue(null)).isEqualTo("Europe/Moscow");
        assertThat(active.path("budget").path("maxPresentations").intValue()).isEqualTo(20);
        assertThat(active.path("budget").path("maxNewObjectives").intValue()).isEqualTo(20);
        assertThat(active.path("issuedCount").intValue()).isOne();
        assertThat(active.path("presentations")).hasSize(1);
        assertThat(presentation.path("type").stringValue(null)).isEqualTo("FREE_RESPONSE");
        // MATERIAL is resolved to plain TEXT from the pinned revision at issue time
        assertThat(presentation.path("content").path("prompt").get(0).toString())
                .isEqualTo("{\"kind\":\"TEXT\",\"text\":\"memory\"}");
        assertThat(presentation.path("content").path("responseInput").stringValue(null)).isEqualTo("TEXT");
        assertThat(presentation.path("evaluator").toString()).isEqualTo("{\"id\":\"deterministic-text\",\"version\":\"1\"}");

        // editing the exercise afterwards never changes an issued presentation
        ObjectNode revise = fixtures.createBody(material, fixtures.freeResponse(material,
                blocks(text("Changed")), blocks(), "other"), "x");
        revise.set("objective", JSON.createObjectNode().put("operation", "reuse")
                .put("objectiveId", published.path("objectiveId").stringValue(null))
                .put("objectiveRevisionId", published.path("objectiveRevisionId").stringValue(null)));
        revise.put("expectedExerciseRevisionId", published.path("exerciseRevisionId").stringValue(null));
        exercises.publish(material.actor(), material.deck(), UUID.fromString(published.path("exerciseId").stringValue(null)),
                fixtures.deckVersion(material), ExerciseCommand.readUpdate(bytes(revise)));
        JsonNode resumed = service.presentations(material.actor(), material.deck(), session);
        assertThat(resumed.path("presentations").get(0)).isEqualTo(presentation);
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.study_presentation SET content=content "
                        + "WHERE session_id=:session").param("session", session).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void emptyPracticeIsolationExpiryAndCompletedSameDayReplayAreEnforced() {
        UUID emptyActor = UUID.randomUUID();
        UUID emptyDeck = UUID.fromString(decks.create(emptyActor, new DeckCommand(UUID.randomUUID(), "Deck", "Description"))
                .acknowledgement().path("deck").path("deckId").stringValue(null));
        StudySessionService.StartResult empty = service.start(emptyActor, emptyDeck, "not/a-zone", fixtures.scheduled());
        assertThat(empty.preparing()).isFalse();
        assertThat(empty.body().path("status").stringValue(null)).isEqualTo("EMPTY");
        assertThat(empty.body().path("timezone").stringValue(null)).isEqualTo("UTC");

        Material material = fixtures.material();
        fixtures.publish(material, fixtures.freeResponse(material, blocks(text("Q")), blocks(), "a"));
        StudySessionService.StartResult practiceStart = service.start(material.actor(), material.deck(), "UTC",
                practice(false));
        UUID practiceSession = UUID.fromString(practiceStart.body().path("sessionId").stringValue(null));
        assertThat(service.read(material.actor(), material.deck(), practiceSession).path("status").stringValue(null))
                .isEqualTo("EMPTY");

        StudySessionService.StartResult scheduledStart = service.start(material.actor(), material.deck(), "UTC",
                fixtures.scheduled());
        UUID source = UUID.fromString(scheduledStart.body().path("sessionId").stringValue(null));
        JsonNode sourceActive = service.read(material.actor(), material.deck(), source);
        StudySessionService.StartResult introducedPractice = service.start(material.actor(), material.deck(), "UTC",
                practice(false));
        assertThat(service.read(material.actor(), material.deck(),
                UUID.fromString(introducedPractice.body().path("sessionId").stringValue(null))).path("status").stringValue(null))
                .isEqualTo("ACTIVE");
        assertThatThrownBy(() -> service.read(UUID.randomUUID(), material.deck(), source))
                .isInstanceOf(ResourceNotFoundException.class);

        jdbc.sql("UPDATE app_learning.study_session SET status='COMPLETE',completed_at=statement_timestamp() "
                        + "WHERE account_id=:actor AND session_id=:session")
                .param("actor", material.actor()).param("session", source).update();
        JsonNode sources = service.replaySources(material.actor(), material.deck(), "UTC");
        assertThat(sources.path("items")).hasSize(1);
        assertThat(sources.path("items").get(0).path("sessionId").stringValue(null)).isEqualTo(source.toString());
        StudySessionService.StartResult replay = service.start(material.actor(), material.deck(), "UTC",
                fixtures.start("REPLAY", source));
        assertThat(replay.body().path("status").stringValue(null)).isEqualTo("ACTIVE");
        assertThat(replay.body().path("presentations")).hasSize(1);
        assertThat(replay.body().path("presentations").get(0).path("exerciseRevisionId"))
                .isEqualTo(sourceActive.path("presentations").get(0).path("exerciseRevisionId"));
        // a replay is a verbatim copy of the issued content under a fresh presentation and nonce
        assertThat(replay.body().path("presentations").get(0).path("content"))
                .isEqualTo(sourceActive.path("presentations").get(0).path("content"));
        assertThat(replay.body().path("presentations").get(0).path("nonce"))
                .isNotEqualTo(sourceActive.path("presentations").get(0).path("nonce"));

        jdbc.sql("UPDATE app_learning.study_session SET expires_at=created_at + interval '1 millisecond' "
                        + "WHERE account_id=:actor AND session_id=:session")
                .param("actor", material.actor()).param("session", source).update();
        assertThatThrownBy(() -> service.read(material.actor(), material.deck(), source))
                .isInstanceOf(StudySessionExpiredException.class);
    }

    @Test
    void quickBudgetSelectsKnownFirstAndIntroducesAtMostTwoObjectives() {
        UUID actor = UUID.randomUUID();
        UUID deck = deckWithExercises(actor, 12);

        StudySessionService.StartResult introduction = service.start(actor, deck, "UTC", scheduled(8, 8));
        JsonNode introduced = service.read(actor, deck, UUID.fromString(introduction.body().path("sessionId").stringValue(null)));
        Set<String> known = new HashSet<>();
        introduced.path("presentations").forEach(value -> known.add(value.path("objectiveId").stringValue(null)));
        assertThat(known).hasSize(8);

        StudySessionService.StartResult quickStart = service.start(actor, deck, "UTC", scheduled(10, 2));
        JsonNode quick = quickStart.preparing() ? service.read(actor, deck,
                UUID.fromString(quickStart.body().path("sessionId").stringValue(null))) : quickStart.body();
        assertThat(quick.path("budget").path("maxPresentations").intValue()).isEqualTo(10);
        assertThat(quick.path("budget").path("maxNewObjectives").intValue()).isEqualTo(2);
        assertThat(quick.path("presentations")).hasSize(10);
        assertThat(quick.path("presentations").findValuesAsString("objectiveId").stream()
                .filter(value -> !known.contains(value))).hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_transition WHERE account_id=:actor")
                .param("actor", actor).query(Long.class).single()).isZero();
    }

    @Test
    void selectionReadsOnlyTheSeededBoundedWindowIncludingWrap() {
        UUID actor = UUID.randomUUID();
        UUID deck = deckWithExercises(actor, 12);

        StudySessionService.StartResult started = service.start(actor, deck, "UTC", scheduled(1, 1));
        UUID sessionId = UUID.fromString(started.body().path("sessionId").stringValue(null));
        service.read(actor, deck, sessionId);
        // The first presentation initializes the generation; restore the new quota for this repository probe.
        jdbc.sql("UPDATE app_learning.study_session SET issued_new_objectives=0 "
                + "WHERE account_id=:actor AND session_id=:session")
                .param("actor", actor).param("session", sessionId).update();
        StudySessionRepository.Session session = sessions.session(actor, deck, sessionId).orElseThrow();

        assertThat(sessions.eligibleCandidates(session, 10, 4, Instant.now(), false))
                .extracting(StudySessionRepository.Candidate::ordinal)
                .isNotEmpty().hasSizeLessThanOrEqualTo(4)
                .allSatisfy(ordinal -> assertThat(ordinal).isIn(10, 11, 0, 1));
        assertThat(sessions.eligibleCandidates(session, 4, 4, Instant.now(), false))
                .extracting(StudySessionRepository.Candidate::ordinal)
                .isNotEmpty().hasSizeLessThanOrEqualTo(4)
                .allSatisfy(ordinal -> assertThat(ordinal).isBetween(4, 7));

        UUID knownOutsideWindow = jdbc.sql("""
                SELECT candidate.objective_id FROM app_learning.study_candidate candidate
                 WHERE candidate.generation_id=:generation AND candidate.candidate_ordinal>=10
                   AND NOT EXISTS (
                       SELECT 1 FROM app_learning.study_presentation presented
                        WHERE presented.session_id=:session AND presented.objective_id=candidate.objective_id
                   )
                 ORDER BY candidate.candidate_ordinal LIMIT 1
                """).param("generation", session.generationId()).param("session", sessionId)
                .query(UUID.class).single();
        sessions.ensureState(actor, deck, knownOutsideWindow, session.configId(), Instant.now());
        assertThat(sessions.eligibleCandidates(session, 4, 4, Instant.now(), false))
                .extracting(StudySessionRepository.Candidate::objectiveId)
                .contains(knownOutsideWindow);
    }

    @Test
    void learnerPresentationsNeverCarryKeysTitlesTranscriptsOrBindingsForAnyMechanic() {
        Material material = fixtures.material();
        UUID sound = fixtures.readyAsset(material.actor(), "audio/mpeg");
        UUID clip = fixtures.readyAsset(material.actor(), "video/mp4");
        UUID picture = fixtures.readyAsset(material.actor(), "image/png");
        UUID blankOne = UUID.randomUUID(), blankTwo = UUID.randomUUID();
        UUID right = UUID.randomUUID(), wrong = UUID.randomUUID();
        UUID leftOne = UUID.randomUUID(), leftTwo = UUID.randomUUID(), rightOne = UUID.randomUUID(), rightTwo = UUID.randomUUID();
        fixtures.publish(material, fixtures.selfCheck(material,
                blocks(image(picture, "Visible alt"), text("Self question")),
                blocks(audio(sound, "TITLE-SELF", "TRANSCRIPT-SELF"), quote(material, material.distractor()))));
        fixtures.publish(material, fixtures.freeResponse(material,
                blocks(audio(sound, "TITLE-FREE", "TRANSCRIPT-FREE"), text("Free question")),
                blocks(text("EXPLANATION-FREE")), "ACCEPTED-FREE", "ACCEPTED-FREE-TWO"));
        fixtures.publish(material, fixtures.cloze(material, blocks(video(clip, "TITLE-CLOZE", "TRANSCRIPT-CLOZE")),
                blocks(text("pre "), blank(blankOne, false, 0, true), text(" mid "), blank(blankTwo, true, 9, false)),
                blankKey(blankOne, "ACCEPTED-CLOZE"), blankKey(blankTwo, "ACCEPTED-CLOZE-TWO")));
        fixtures.publish(material, fixtures.choice(material, true, blocks(video(clip, "TITLE-CHOICE", "TRANSCRIPT-CHOICE")),
                blocks().add(option(right, audio(sound, "TITLE-OPTION", "TRANSCRIPT-OPTION"), text("Right option")))
                        .add(option(wrong, image(picture, "Wrong alt"))), right));
        fixtures.publish(material, fixtures.match(material, blocks(text("Match them")),
                blocks().add(item(leftOne, audio(sound, "TITLE-LEFT", "TRANSCRIPT-LEFT"))).add(item(leftTwo, text("left two"))),
                blocks().add(item(rightOne, text("right one"))).add(item(rightTwo, image(picture, "Right alt"))),
                new UUID[][] {{leftOne, rightOne}, {leftTwo, rightTwo}}));

        JsonNode session = fixtures.session(material, "SCHEDULED", null);
        assertThat(session.path("presentations")).hasSize(5);
        String raw = session.toString();
        for (String forbidden : new String[] {"TITLE-", "TRANSCRIPT-", "ACCEPTED-", "EXPLANATION-FREE", "answerKey",
                "accepted", "correctOptionIds", "\"pairs\"", "bindings", "rubric", "\"title\"", "normalization",
                "matchingMode"}) {
            assertThat(raw).as(forbidden).doesNotContain(forbidden);
        }
        Map<String, JsonNode> byType = new java.util.HashMap<>();
        session.path("presentations").forEach(presentation -> {
            byType.put(presentation.path("type").stringValue(null), presentation);
            for (String legacy : new String[] {"reference", "prompt", "options", "bindings", "selectionMode", "answerKey"}) {
                assertThat(presentation.has(legacy)).as(legacy).isFalse();
            }
            assertThat(presentation.path("transcriptRevealed").booleanValue()).isFalse();
            assertThat(presentation.path("hints")).isEmpty();
            assertThat(presentation.path("evaluator").size()).isEqualTo(2);
        });
        assertThat(byType).containsOnlyKeys("SELF_CHECK", "FREE_RESPONSE", "CLOZE", "CHOICE", "MATCH");
        // what the learner may see: resolved text, alt text, availability of a transcript, never its text
        assertThat(byType.get("SELF_CHECK").path("content").path("reference").get(0).toString())
                .isEqualTo("{\"kind\":\"AUDIO\",\"assetId\":\"" + sound + "\",\"transcriptAvailable\":true}");
        assertThat(byType.get("SELF_CHECK").path("content").path("reference").get(1).path("text").stringValue(null))
                .isEqualTo("forgetting");
        assertThat(byType.get("CHOICE").path("content").path("selectionMode").stringValue(null)).isEqualTo("MULTIPLE");
        assertThat(byType.get("CLOZE").path("content").path("passage").get(1).path("size").toString())
                .isEqualTo("{\"mode\":\"ANSWER_LENGTH\",\"length\":" + "ACCEPTED-CLOZE".length() + "}");
        // the private key is persisted, but only server side
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_presentation WHERE answer_key::text LIKE '%ACCEPTED-%'")
                .query(Long.class).single()).isGreaterThanOrEqualTo(2);
        // a second read returns exactly the same issued content, including the shuffled order
        assertThat(service.read(material.actor(), material.deck(), UUID.fromString(session.path("sessionId").stringValue(null)))
                .path("presentations")).isEqualTo(session.path("presentations"));
    }

    @Test
    void candidatesAreIssuedOnlyWhileEveryPinnedAssetIsReadyAndOfTheDeclaredKind() {
        Material material = fixtures.material();
        UUID sound = fixtures.pendingAsset(material.actor());
        JsonNode published = fixtures.publish(material, fixtures.freeResponse(material,
                blocks(audio(sound, "Voice", null), text("Type it")), blocks(), "x"));
        assertThat(published.path("exerciseRevisionId").stringValue(null)).isNotBlank();
        assertThat(fixtures.issue(material, "SCHEDULED", null)).isEmpty();
        // READY but really an image: audio must not be an image
        fixtures.ready(sound, "image/png");
        assertThat(fixtures.issue(material, "SCHEDULED", null)).isEmpty();

        Material video = fixtures.material();
        UUID clip = fixtures.readyAsset(video.actor(), "video/mp4");
        UUID picture = fixtures.pendingAsset(video.actor());
        UUID correct = UUID.randomUUID();
        fixtures.publish(video, fixtures.choice(video, false, blocks(video(clip, "Clip", null)), blocks().add(option(correct, image(picture, "Alt")))
                .add(option(UUID.randomUUID(), text("Other"))), correct));
        assertThat(fixtures.issue(video, "SCHEDULED", null)).isEmpty();
        fixtures.ready(picture, "image/webp");
        assertThat(fixtures.issue(video, "SCHEDULED", null)).hasSize(1);
        // a text-only exercise is trivially ready
        Material plain = fixtures.material();
        fixtures.publish(plain, fixtures.freeResponse(plain, blocks(text("Plain")), blocks(), "x"));
        assertThat(fixtures.issue(plain, "SCHEDULED", null)).hasSize(1);
    }

    private UUID deckWithExercises(UUID actor, int count) {
        UUID deck = UUID.fromString(decks.create(actor, new DeckCommand(UUID.randomUUID(), "Deck", "Description"))
                .acknowledgement().path("deck").path("deckId").stringValue(null));
        for (int index = 0; index < count; index++) {
            Material material = fixtures.addMaterial(actor, deck, "memory", "other");
            fixtures.publish(material, fixtures.freeResponse(material, blocks(text("Q" + index)), blocks(), "memory"));
        }
        return deck;
    }

    private static StudySessionCommand scheduled(int budget, int maxNew) {
        return StudySessionCommand.read(bytes(JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("mode", "SCHEDULED").set("budget", JSON.createObjectNode().put("maxPresentations", budget)
                        .put("maxNewObjectives", maxNew))));
    }

    private static StudySessionCommand practice(boolean includeNew) {
        ObjectNode value = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("mode", "PRACTICE")
                .put("includeNew", includeNew).put("order", "WEAKEST_FIRST");
        value.set("budget", JSON.createObjectNode().put("maxPresentations", 20));
        return StudySessionCommand.read(bytes(value));
    }

}
