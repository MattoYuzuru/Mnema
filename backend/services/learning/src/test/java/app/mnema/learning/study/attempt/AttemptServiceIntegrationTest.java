package app.mnema.learning.study.attempt;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseCommand;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.study.restart.StudyRestartCommand;
import app.mnema.learning.study.restart.StudyRestartService;
import app.mnema.learning.study.progress.StudyProgressService;
import app.mnema.learning.study.retention.StudyRetentionService;
import app.mnema.learning.study.session.StudySessionCommand;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AttemptServiceIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired private AttemptService service;
    @Autowired private StudyRestartService restarts;
    @Autowired private StudySessionService sessions;
    @Autowired private StudyProgressService progress;
    @Autowired private StudyRetentionService retention;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private JdbcClient jdbc;

    @Test
    void scheduledAttemptRetriesTransitionsAndRestartRetainsHistoryWhileRejectingOldEpoch() {
        Fixture fixture = fixture("TYPED");
        Presentation first = presentation(fixture, "SCHEDULED");
        Presentation beforeRestart = presentation(fixture, "SCHEDULED");
        UUID attempt = UUID.randomUUID();
        AttemptCommand correct = attempt(attempt, first, "TEXT", " MEMORY ", List.of(), "KNEW");

        AttemptService.SubmitResult submitted = service.submit(fixture.actor(), fixture.deck(), first.session(), correct);
        assertThat(submitted.replayed()).isFalse();
        assertThat(submitted.outcome().path("status").textValue()).isEqualTo("ASSESSED");
        assertThat(submitted.outcome().path("evidence").path("result").textValue()).isEqualTo("CORRECT");
        assertThat(submitted.outcome().path("evidence").path("evidenceClass").textValue()).isEqualTo("HIGH");
        assertThat(submitted.outcome().path("transition").path("afterLevel").intValue()).isEqualTo(2);
        assertThat(service.submit(fixture.actor(), fixture.deck(), first.session(), correct).replayed()).isTrue();
        assertThat(count("study_transition", "account_id", fixture.actor())).isOne();
        assertThat(count("study_evidence", "account_id", fixture.actor())).isOne();
        assertThat(rawCount(fixture.actor())).isOne();
        JsonNode completed = sessions.read(fixture.actor(), fixture.deck(), first.session());
        assertThat(completed.path("status").textValue()).isEqualTo("COMPLETE");
        assertThat(completed.path("presentations")).isEmpty();

        AttemptCommand changedReuse = attempt(attempt, first, "TEXT", "wrong", List.of(), "KNEW");
        assertThatThrownBy(() -> service.submit(fixture.actor(), fixture.deck(), first.session(), changedReuse))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> service.submit(fixture.actor(), fixture.deck(), first.session(),
                attempt(UUID.randomUUID(), first, "TEXT", "memory", List.of(), null)))
                .isInstanceOf(IdempotencyConflictException.class);

        StudyRestartCommand restart = restart(UUID.randomUUID(), fixture.member());
        StudyRestartService.Result restarted = restarts.restart(fixture.actor(), fixture.deck(), restart);
        assertThat(restarted.acknowledgement().path("objectiveCount").intValue()).isOne();
        assertThat(restarted.acknowledgement().path("learningEpochs").get(0).path("learningEpoch").textValue())
                .isEqualTo("1");
        assertThat(restarts.restart(fixture.actor(), fixture.deck(), restart).replayed()).isTrue();
        assertThat(count("study_transition", "account_id", fixture.actor())).isOne();
        assertThat(count("study_restart_audit", "account_id", fixture.actor())).isOne();

        JsonNode old = service.submit(fixture.actor(), fixture.deck(), beforeRestart.session(),
                attempt(UUID.randomUUID(), beforeRestart, "TEXT", "memory", List.of(), null)).outcome();
        assertThat(old.path("status").textValue()).isEqualTo("NOT_ASSESSED");
        assertThat(old.path("transition").isNull()).isTrue();
        assertThat(count("study_transition", "account_id", fixture.actor())).isOne();
        assertThat(jdbc.sql("SELECT learning_epoch FROM app_learning.study_state WHERE account_id=:actor")
                .param("actor", fixture.actor()).query(Long.class).single()).isOne();
    }

    @Test
    void cancelAndPracticeProduceNoCanonicalEvidenceOrRawResponse() {
        Fixture fixture = fixture("TYPED");
        Presentation cancelPresentation = presentation(fixture, "SCHEDULED");
        JsonNode cancelled = service.submit(fixture.actor(), fixture.deck(), cancelPresentation.session(),
                attempt(UUID.randomUUID(), cancelPresentation, "CANCEL", null, List.of(), null)).outcome();
        assertThat(cancelled.path("status").textValue()).isEqualTo("NOT_ASSESSED");

        Presentation practice = presentation(fixture, "PRACTICE");
        JsonNode practiced = service.submit(fixture.actor(), fixture.deck(), practice.session(),
                attempt(UUID.randomUUID(), practice, "TEXT", "memory", List.of(), "GUESSED")).outcome();
        assertThat(practiced.path("status").textValue()).isEqualTo("ASSESSED");
        assertThat(practiced.path("canonicalEffects").booleanValue()).isFalse();
        assertThat(practiced.path("feedback").path("result").textValue()).isEqualTo("CORRECT");
        assertThat(count("study_evidence", "account_id", fixture.actor())).isZero();
        assertThat(count("study_transition", "account_id", fixture.actor())).isZero();
        assertThat(rawCount(fixture.actor())).isZero();
        assertThat(count("study_attempt_tombstone", "account_id", fixture.actor())).isEqualTo(2);

    }

    @Test
    void clozeAndChoiceUseCanonicalReducerWithConservativeEvidence() {
        Fixture clozeFixture = fixture("CLOZE_SINGLE");
        Presentation cloze = presentation(clozeFixture, "SCHEDULED");
        JsonNode clozeOutcome = service.submit(clozeFixture.actor(), clozeFixture.deck(), cloze.session(),
                attempt(UUID.randomUUID(), cloze, "TEXT", " MEMORY ", List.of("REVEAL_FIRST_GRAPHEME"), null))
                .outcome();
        assertThat(clozeOutcome.path("evidence").path("result").textValue()).isEqualTo("CORRECT");
        assertThat(clozeOutcome.path("evidence").path("evidenceClass").textValue()).isEqualTo("MEDIUM");
        assertThat(clozeOutcome.path("transition").path("afterLevel").intValue()).isOne();

        Fixture choiceFixture = fixture("SINGLE_CHOICE");
        Presentation choice = presentation(choiceFixture, "SCHEDULED");
        assertThat(choice.options()).hasSize(2);
        assertThatThrownBy(() -> service.submit(choiceFixture.actor(), choiceFixture.deck(), choice.session(),
                attempt(UUID.randomUUID(), choice, "CHOICE", UUID.randomUUID().toString(), List.of(), null)))
                .isInstanceOf(app.mnema.learning.platform.api.InvalidRequestException.class);

        JsonNode choiceOutcome = service.submit(choiceFixture.actor(), choiceFixture.deck(), choice.session(),
                attempt(UUID.randomUUID(), choice, "CHOICE", choice.options().get(0).toString(), List.of(), null))
                .outcome();
        assertThat(choiceOutcome.path("evidence").path("result").textValue()).isEqualTo("CORRECT");
        assertThat(choiceOutcome.path("evidence").path("evidenceClass").textValue()).isEqualTo("LOW");
        assertThat(choiceOutcome.path("transition").path("afterLevel").intValue()).isOne();
        assertThat(count("study_state", "account_id", choiceFixture.actor())).isOne();
        assertThat(count("study_transition", "account_id", choiceFixture.actor())).isOne();
    }

    @Test
    void selfCheckCreatesLowEvidenceAndForeignRestartIsOpaque() {
        Fixture fixture = fixture("SELF_CHECK");
        Presentation presentation = presentation(fixture, "SCHEDULED");
        ObjectNode body = JSON.createObjectNode().put("attemptId", UUID.randomUUID().toString())
                .put("presentationId", presentation.id().toString()).put("nonce", presentation.nonce());
        body.putObject("response").put("kind", "SELF_CHECK").put("rating", "FULL");
        body.putArray("hintsUsed").add("REVEAL");
        body.putNull("confidence"); body.put("durationMs", 2_000);

        JsonNode outcome = service.submit(fixture.actor(), fixture.deck(), presentation.session(),
                AttemptCommand.read(bytes(body))).outcome();
        assertThat(outcome.path("evidence").path("result").textValue()).isEqualTo("CORRECT");
        assertThat(outcome.path("evidence").path("evidenceClass").textValue()).isEqualTo("LOW");
        assertThat(outcome.path("transition").path("afterLevel").intValue()).isOne();

        StudyRestartCommand concurrentRestart = restart(UUID.randomUUID(), fixture.member());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                ready.countDown(); start.await();
                return restarts.restart(fixture.actor(), fixture.deck(), concurrentRestart);
            });
            var second = executor.submit(() -> {
                ready.countDown(); start.await();
                return restarts.restart(fixture.actor(), fixture.deck(), concurrentRestart);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS).replayed(),
                    second.get(10, TimeUnit.SECONDS).replayed())).containsExactlyInAnyOrder(false, true);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        assertThat(count("study_restart_audit", "account_id", fixture.actor())).isOne();

        assertThatThrownBy(() -> restarts.restart(fixture.actor(), fixture.deck(),
                restart(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(app.mnema.learning.platform.api.ResourceNotFoundException.class);
    }

    @Test
    void concurrentFirstAttemptsLeaveExactlyOneTerminalReceiptAndTransition() throws Exception {
        Fixture fixture = fixture("TYPED");
        Presentation presentation = presentation(fixture, "SCHEDULED");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(ignored -> executor.submit(() -> {
                ready.countDown();
                start.await();
                return service.submit(fixture.actor(), fixture.deck(), presentation.session(),
                        attempt(UUID.randomUUID(), presentation, "TEXT", "memory", List.of(), null));
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int successes = 0, conflicts = 0;
            for (var future : futures) {
                try { future.get(10, TimeUnit.SECONDS); successes++; }
                catch (java.util.concurrent.ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(IdempotencyConflictException.class);
                    conflicts++;
                }
            }
            assertThat(successes).isOne();
            assertThat(conflicts).isOne();
        }
        assertThat(count("study_attempt_tombstone", "account_id", fixture.actor())).isOne();
        assertThat(count("study_transition", "account_id", fixture.actor())).isOne();
    }

    @Test
    void progressIsExplainableAndRawRetentionPreservesDurableRetryEvidence() {
        Fixture fixture = fixture("TYPED");
        JsonNode fresh = progress.read(fixture.actor(), fixture.deck(), 20, null).path("items").get(0);
        assertThat(fresh.path("state").textValue()).isEqualTo("NOT_STARTED");
        assertThat(fresh.path("objectiveCoverage").toString())
                .isEqualTo("{\"enabled\":1,\"introduced\":0,\"assessed\":0}");

        Presentation presentation = presentation(fixture, "SCHEDULED");
        JsonNode introduced = progress.read(fixture.actor(), fixture.deck(), 20, null).path("items").get(0);
        assertThat(introduced.path("state").textValue()).isEqualTo("LEARNING");
        AttemptCommand command = attempt(UUID.randomUUID(), presentation, "TEXT", "memory", List.of(), null);
        service.submit(fixture.actor(), fixture.deck(), presentation.session(), command);

        JsonNode onTrack = progress.read(fixture.actor(), fixture.deck(), 20, null).path("items").get(0);
        assertThat(onTrack.path("state").textValue()).isEqualTo("ON_TRACK");
        assertThat(onTrack.path("objectiveCoverage").path("assessed").intValue()).isOne();
        jdbc.sql("""
                UPDATE app_learning.study_state SET next_due=statement_timestamp()-INTERVAL '1 second'
                 WHERE account_id=:actor AND deck_id=:deck
                """).param("actor", fixture.actor()).param("deck", fixture.deck()).update();
        assertThat(progress.read(fixture.actor(), fixture.deck(), 20, null).path("items").get(0)
                .path("state").textValue()).isEqualTo("DUE");
        jdbc.sql("""
                UPDATE app_learning.study_raw_response raw
                   SET expires_at=statement_timestamp()-INTERVAL '1 second'
                  FROM app_learning.study_attempt_tombstone attempt
                 WHERE attempt.attempt_id=raw.attempt_id AND attempt.account_id=:actor
                """).param("actor", fixture.actor()).update();
        StudyRetentionService.PurgeResult purged = retention.purgeBatch();
        assertThat(purged.rawResponses()).isOne();
        assertThat(rawCount(fixture.actor())).isZero();
        assertThat(count("study_attempt_tombstone", "account_id", fixture.actor())).isOne();
        assertThat(count("study_evidence", "account_id", fixture.actor())).isOne();
        assertThat(count("study_transition", "account_id", fixture.actor())).isOne();
        assertThat(service.submit(fixture.actor(), fixture.deck(), presentation.session(), command).replayed()).isTrue();
    }

    @Test
    void oneHundredPracticeAndReplaySubmissionsCannotChangeCanonicalState() {
        Fixture fixture = fixture("TYPED");
        Presentation scheduled = presentation(fixture, "SCHEDULED");
        service.submit(fixture.actor(), fixture.deck(), scheduled.session(),
                attempt(UUID.randomUUID(), scheduled, "TEXT", "memory", List.of(), null));
        StateSnapshot before = state(fixture.actor());
        long exposures = count("study_exposure", "account_id", fixture.actor());
        long evidence = count("study_evidence", "account_id", fixture.actor());
        long transitions = count("study_transition", "account_id", fixture.actor());

        for (int index = 0; index < 50; index++) {
            String mode = index % 2 == 0 ? "PRACTICE" : "REPLAY";
            Presentation extra = presentation(fixture, mode, scheduled.session());
            AttemptCommand command = attempt(UUID.randomUUID(), extra, "TEXT",
                    index % 3 == 0 ? "wrong" : "memory", List.of(), "GUESSED");
            assertThat(service.submit(fixture.actor(), fixture.deck(), extra.session(), command).replayed()).isFalse();
            assertThat(service.submit(fixture.actor(), fixture.deck(), extra.session(), command).replayed()).isTrue();
        }

        assertThat(state(fixture.actor())).isEqualTo(before);
        assertThat(count("study_exposure", "account_id", fixture.actor())).isEqualTo(exposures);
        assertThat(count("study_evidence", "account_id", fixture.actor())).isEqualTo(evidence);
        assertThat(count("study_transition", "account_id", fixture.actor())).isEqualTo(transitions);
        assertThat(rawCount(fixture.actor())).isOne();

        jdbc.sql("""
                UPDATE app_learning.study_attempt_tombstone
                   SET submitted_at=submitted_at-INTERVAL '2 days',
                       receipt_expires_at=receipt_expires_at-INTERVAL '2 days'
                 WHERE account_id=:actor AND mode<>'SCHEDULED'
                """).param("actor", fixture.actor()).update();
        assertThat(retention.purgeBatch().compactOutcomes()).isEqualTo(50);
        assertThat(count("study_attempt_tombstone", "account_id", fixture.actor())).isEqualTo(51);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM app_learning.study_attempt_tombstone
                 WHERE account_id=:actor AND outcome IS NOT NULL
                """).param("actor", fixture.actor()).query(Long.class).single()).isOne();

        ObjectNode tampered = JSON.createObjectNode().put("attemptId", UUID.randomUUID().toString())
                .put("presentationId", scheduled.id().toString()).put("nonce", scheduled.nonce())
                .put("schedulerAffecting", true);
        tampered.putObject("response").put("kind", "TEXT").put("text", "memory");
        tampered.putArray("hintsUsed"); tampered.putNull("confidence"); tampered.put("durationMs", 1);
        assertThatThrownBy(() -> AttemptCommand.read(bytes(tampered)))
                .isInstanceOf(app.mnema.learning.platform.api.InvalidRequestException.class);
    }

    private Presentation presentation(Fixture fixture, String mode) {
        return presentation(fixture, mode, null);
    }

    private Presentation presentation(Fixture fixture, String mode, UUID sourceSession) {
        ObjectNode request = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("mode", mode);
        if (mode.equals("PRACTICE")) request.put("includeNew", true).put("order", "SEEDED");
        if (mode.equals("REPLAY")) request.put("sourceSessionId", sourceSession.toString());
        ObjectNode budget = JSON.createObjectNode().put("maxPresentations", 20);
        if (mode.equals("SCHEDULED")) budget.put("maxNewObjectives", 20);
        request.set("budget", budget);
        StudySessionService.StartResult started = sessions.start(fixture.actor(), fixture.deck(), "UTC",
                StudySessionCommand.read(bytes(request)));
        UUID session = UUID.fromString(started.body().path("sessionId").textValue());
        JsonNode active = started.preparing() ? sessions.read(fixture.actor(), fixture.deck(), session) : started.body();
        JsonNode value = active.path("presentations").get(0);
        List<UUID> options = new java.util.ArrayList<>();
        value.path("options").forEach(option -> options.add(UUID.fromString(option.path("optionId").textValue())));
        return new Presentation(session, UUID.fromString(value.path("presentationId").textValue()),
                value.path("nonce").textValue(), List.copyOf(options));
    }

    private StateSnapshot state(UUID actor) {
        return jdbc.sql("""
                SELECT learning_epoch,level,correct_streak,lapse_count,transition_sequence,last_assessed_at,next_due
                  FROM app_learning.study_state WHERE account_id=:actor
                """).param("actor", actor).query((row, ignored) -> new StateSnapshot(row.getLong("learning_epoch"),
                row.getInt("level"), row.getInt("correct_streak"), row.getInt("lapse_count"),
                row.getLong("transition_sequence"), row.getTimestamp("last_assessed_at").toInstant(),
                row.getTimestamp("next_due").toInstant())).single();
    }

    private Fixture fixture(String type) {
        UUID actor = UUID.randomUUID();
        UUID deck = UUID.fromString(decks.create(actor, new DeckCommand(UUID.randomUUID(), "Deck", "Description"))
                .acknowledgement().path("deck").path("deckId").textValue());
        JsonNode head = decks.read(actor, deck);
        UUID answerNode = UUID.randomUUID();
        UUID distractorNode = UUID.randomUUID();
        ObjectNode document = JSON.createObjectNode().put("formatVersion", 1);
        ObjectNode root = document.putObject("root").put("id", UUID.randomUUID().toString())
                .put("type", "doc").put("version", 1);
        root.putObject("attrs");
        ObjectNode paragraph = root.putArray("content").addObject().put("id", answerNode.toString())
                .put("type", "paragraph").put("version", 1);
        paragraph.putObject("attrs");
        ObjectNode text = paragraph.putArray("content").addObject().put("id", UUID.randomUUID().toString())
                .put("type", "text").put("version", 1);
        text.putObject("attrs").put("text", "memory"); text.putArray("content");
        ObjectNode distractor = root.withArray("content").addObject().put("id", distractorNode.toString())
                .put("type", "paragraph").put("version", 1);
        distractor.putObject("attrs");
        ObjectNode distractorText = distractor.putArray("content").addObject().put("id", UUID.randomUUID().toString())
                .put("type", "text").put("version", 1);
        distractorText.putObject("attrs").put("text", "forgetting"); distractorText.putArray("content");
        ObjectNode itemBody = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", head.path("revisionId").textValue());
        itemBody.set("document", document);
        JsonNode item = items.publish(actor, deck, 0, ItemPublicationCommand.readCreate(bytes(itemBody)))
                .acknowledgement().path("changes").get(0);
        UUID member = UUID.fromString(item.path("memberKey").textValue());
        UUID revision = UUID.fromString(item.path("itemRevisionId").textValue());
        ObjectNode exerciseBody = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", decks.read(actor, deck).path("revisionId").textValue());
        ObjectNode objective = exerciseBody.putObject("objective").put("operation", "create");
        ObjectNode answer = objective.putObject("answerContract").put("schemaVersion", 1);
        answer.putArray("normalization").add("UNICODE_NFC").add("TRIM").add("CASE_FOLD");
        answer.putArray("accepted").add("memory");
        ObjectNode exercise = exerciseBody.putObject("exercise").put("type", type).put("schemaVersion", 1)
                .put("enabled", true);
        exercise.putObject("prompt").put("kind", "NODE_TEXT").put("memberKey", member.toString())
                .put("itemRevisionId", revision.toString()).put("nodeId", answerNode.toString());
        ObjectNode binding = exercise.putArray("bindings").addObject().put("bindingId", UUID.randomUUID().toString())
                .put("role", "ASSESSED").put("memberKey", member.toString())
                .put("itemRevisionId", revision.toString()).put("ordinal", 0);
        binding.putArray("nodeIds").add(answerNode.toString());
        binding.putObject("display").put("kind", "NODE_TEXT");
        if (type.equals("SINGLE_CHOICE")) {
            addOption(exercise.withArray("bindings"), member, revision, answerNode, 1);
            addOption(exercise.withArray("bindings"), member, revision, distractorNode, 2);
        }
        String evaluator = type.equals("SELF_CHECK") ? "self-check"
                : type.equals("SINGLE_CHOICE") ? "deterministic-choice" : "deterministic-text";
        exercise.putObject("evaluatorPolicy").put("id", evaluator)
                .put("version", "1");
        exercises.publish(actor, deck, null, 1, ExerciseCommand.readCreate(bytes(exerciseBody)));
        return new Fixture(actor, deck, member);
    }

    private static AttemptCommand attempt(UUID id, Presentation presentation, String kind, String value,
                                          List<String> hints, String confidence) {
        ObjectNode body = JSON.createObjectNode().put("attemptId", id.toString())
                .put("presentationId", presentation.id().toString()).put("nonce", presentation.nonce());
        ObjectNode response = body.putObject("response").put("kind", kind);
        if (kind.equals("TEXT")) response.put("text", value);
        if (kind.equals("CHOICE")) response.put("optionId", value);
        body.set("hintsUsed", JSON.valueToTree(hints));
        if (confidence == null) body.putNull("confidence"); else body.put("confidence", confidence);
        body.put("durationMs", 1_000);
        return AttemptCommand.read(bytes(body));
    }

    private static void addOption(com.fasterxml.jackson.databind.node.ArrayNode bindings, UUID member,
                                  UUID revision, UUID node, int ordinal) {
        ObjectNode option = bindings.addObject().put("bindingId", UUID.randomUUID().toString())
                .put("role", "OPTION").put("memberKey", member.toString())
                .put("itemRevisionId", revision.toString()).put("ordinal", ordinal);
        option.putArray("nodeIds").add(node.toString());
        option.putObject("display").put("kind", "NODE_TEXT");
    }

    private static StudyRestartCommand restart(UUID command, UUID member) {
        ObjectNode body = JSON.createObjectNode().put("commandId", command.toString());
        body.putArray("memberKeys").add(member.toString());
        return StudyRestartCommand.read(bytes(body));
    }

    private long count(String table, String column, UUID value) {
        return jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE " + column + "=:value")
                .param("value", value).query(Long.class).single();
    }

    private long rawCount(UUID actor) {
        return jdbc.sql("""
                SELECT count(*) FROM app_learning.study_raw_response raw
                JOIN app_learning.study_attempt_tombstone attempt ON attempt.attempt_id=raw.attempt_id
                WHERE attempt.account_id=:actor
                """).param("actor", actor).query(Long.class).single();
    }

    private static ByteArrayInputStream bytes(JsonNode value) {
        return new ByteArrayInputStream(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private record Fixture(UUID actor, UUID deck, UUID member) { }
    private record Presentation(UUID session, UUID id, String nonce, List<UUID> options) { }
    private record StateSnapshot(long learningEpoch, int level, int correctStreak, int lapseCount,
                                 long transitionSequence, Instant lastAssessedAt, Instant nextDue) { }
}
