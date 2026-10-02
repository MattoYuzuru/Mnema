package app.mnema.learning.study.attempt;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.study.restart.StudyRestartCommand;
import app.mnema.learning.study.restart.StudyRestartService;
import app.mnema.learning.study.progress.StudyProgressService;
import app.mnema.learning.study.retention.StudyRetentionService;
import app.mnema.learning.study.session.StudyHintCommand;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
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
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;
    private StudyFixtures fixtures;

    @BeforeEach
    void fixtures() { fixtures = new StudyFixtures(decks, items, exercises, sessions, media, jdbc); }

    @Test
    void scheduledAttemptRetriesTransitionsAndRestartRetainsHistoryWhileRejectingOldEpoch() {
        Fixture fixture = fixture("FREE_RESPONSE");
        Presentation first = presentation(fixture, "SCHEDULED");
        Presentation beforeRestart = presentation(fixture, "SCHEDULED");
        UUID attempt = UUID.randomUUID();
        AttemptCommand correct = attempt(attempt, first, "TEXT", " MEMORY ", "KNEW");

        AttemptService.SubmitResult submitted = service.submit(fixture.actor(), fixture.deck(), first.session(), correct);
        assertThat(submitted.replayed()).isFalse();
        assertThat(submitted.outcome().path("status").stringValue(null)).isEqualTo("ASSESSED");
        assertThat(submitted.outcome().path("evidence").path("result").stringValue(null)).isEqualTo("CORRECT");
        assertThat(submitted.outcome().path("evidence").path("evidenceClass").stringValue(null)).isEqualTo("HIGH");
        assertThat(submitted.outcome().path("transition").path("afterLevel").intValue()).isEqualTo(2);
        assertThat(service.submit(fixture.actor(), fixture.deck(), first.session(), correct).replayed()).isTrue();
        assertThat(count("study_transition", "account_id", fixture.actor())).isOne();
        assertThat(count("study_evidence", "account_id", fixture.actor())).isOne();
        assertThat(rawCount(fixture.actor())).isOne();
        JsonNode completed = sessions.read(fixture.actor(), fixture.deck(), first.session());
        assertThat(completed.path("status").stringValue(null)).isEqualTo("COMPLETE");
        assertThat(completed.path("presentations")).isEmpty();

        AttemptCommand changedReuse = attempt(attempt, first, "TEXT", "wrong", "KNEW");
        assertThatThrownBy(() -> service.submit(fixture.actor(), fixture.deck(), first.session(), changedReuse))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> service.submit(fixture.actor(), fixture.deck(), first.session(),
                attempt(UUID.randomUUID(), first, "TEXT", "memory", null)))
                .isInstanceOf(IdempotencyConflictException.class);

        StudyRestartCommand restart = restart(UUID.randomUUID(), fixture.member());
        StudyRestartService.Result restarted = restarts.restart(fixture.actor(), fixture.deck(), restart);
        assertThat(restarted.acknowledgement().path("objectiveCount").intValue()).isOne();
        assertThat(restarted.acknowledgement().path("learningEpochs").get(0).path("learningEpoch").stringValue(null))
                .isEqualTo("1");
        assertThat(restarts.restart(fixture.actor(), fixture.deck(), restart).replayed()).isTrue();
        assertThat(count("study_transition", "account_id", fixture.actor())).isOne();
        assertThat(count("study_restart_audit", "account_id", fixture.actor())).isOne();

        JsonNode old = service.submit(fixture.actor(), fixture.deck(), beforeRestart.session(),
                attempt(UUID.randomUUID(), beforeRestart, "TEXT", "memory", null)).outcome();
        assertThat(old.path("status").stringValue(null)).isEqualTo("NOT_ASSESSED");
        assertThat(old.path("transition").isNull()).isTrue();
        assertThat(count("study_transition", "account_id", fixture.actor())).isOne();
        assertThat(jdbc.sql("SELECT learning_epoch FROM app_learning.study_state WHERE account_id=:actor")
                .param("actor", fixture.actor()).query(Long.class).single()).isOne();
    }

    @Test
    void cancelAndPracticeProduceNoCanonicalEvidenceOrRawResponse() {
        Fixture fixture = fixture("FREE_RESPONSE");
        Presentation cancelPresentation = presentation(fixture, "SCHEDULED");
        JsonNode cancelled = service.submit(fixture.actor(), fixture.deck(), cancelPresentation.session(),
                attempt(UUID.randomUUID(), cancelPresentation, "CANCEL", null, null)).outcome();
        assertThat(cancelled.path("status").stringValue(null)).isEqualTo("NOT_ASSESSED");

        Presentation practice = presentation(fixture, "PRACTICE");
        JsonNode practiced = service.submit(fixture.actor(), fixture.deck(), practice.session(),
                attempt(UUID.randomUUID(), practice, "TEXT", "memory", "GUESSED")).outcome();
        assertThat(practiced.path("status").stringValue(null)).isEqualTo("ASSESSED");
        assertThat(practiced.path("canonicalEffects").booleanValue()).isFalse();
        assertThat(practiced.path("feedback").path("result").stringValue(null)).isEqualTo("CORRECT");
        assertThat(count("study_evidence", "account_id", fixture.actor())).isZero();
        assertThat(count("study_transition", "account_id", fixture.actor())).isZero();
        assertThat(rawCount(fixture.actor())).isZero();
        assertThat(count("study_attempt_tombstone", "account_id", fixture.actor())).isEqualTo(2);

    }

    @Test
    void clozeAndChoiceUseCanonicalReducerWithConservativeEvidence() {
        Fixture clozeFixture = fixture("CLOZE");
        Presentation cloze = presentation(clozeFixture, "SCHEDULED");
        UUID blank = UUID.fromString(cloze.blank());
        // hint use is recorded by the server, never claimed by the client
        assertThat(sessions.revealHint(clozeFixture.actor(), clozeFixture.deck(), cloze.session(), cloze.id(),
                StudyHintCommand.read(bytes(hintCommand(cloze.nonce(), blank)))).path("firstLetter").stringValue(null)).isEqualTo("m");
        JsonNode clozeOutcome = service.submit(clozeFixture.actor(), clozeFixture.deck(), cloze.session(),
                attempt(UUID.randomUUID(), cloze, "CLOZE", " MEMORY ", null))
                .outcome();
        assertThat(clozeOutcome.path("evidence").path("result").stringValue(null)).isEqualTo("CORRECT");
        assertThat(clozeOutcome.path("evidence").path("evidenceClass").stringValue(null)).isEqualTo("MEDIUM");
        assertThat(clozeOutcome.path("transition").path("afterLevel").intValue()).isOne();

        Fixture choiceFixture = fixture("CHOICE");
        Presentation choice = presentation(choiceFixture, "SCHEDULED");
        assertThat(choice.options()).hasSize(2);
        assertThatThrownBy(() -> service.submit(choiceFixture.actor(), choiceFixture.deck(), choice.session(),
                attempt(UUID.randomUUID(), choice, "CHOICE", UUID.randomUUID().toString(), null)))
                .isInstanceOf(app.mnema.learning.platform.api.InvalidRequestException.class);

        JsonNode choiceOutcome = service.submit(choiceFixture.actor(), choiceFixture.deck(), choice.session(),
                attempt(UUID.randomUUID(), choice, "CHOICE", choice.options().get(0).toString(), null))
                .outcome();
        assertThat(choiceOutcome.path("evidence").path("result").stringValue(null)).isEqualTo("CORRECT");
        assertThat(choiceOutcome.path("evidence").path("evidenceClass").stringValue(null)).isEqualTo("LOW");
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
        body.putNull("confidence"); body.put("durationMs", 2_000);

        JsonNode outcome = service.submit(fixture.actor(), fixture.deck(), presentation.session(),
                AttemptCommand.read(bytes(body))).outcome();
        assertThat(outcome.path("evidence").path("result").stringValue(null)).isEqualTo("CORRECT");
        assertThat(outcome.path("evidence").path("evidenceClass").stringValue(null)).isEqualTo("LOW");
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
        Fixture fixture = fixture("FREE_RESPONSE");
        Presentation presentation = presentation(fixture, "SCHEDULED");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(ignored -> executor.submit(() -> {
                ready.countDown();
                start.await();
                return service.submit(fixture.actor(), fixture.deck(), presentation.session(),
                        attempt(UUID.randomUUID(), presentation, "TEXT", "memory", null));
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
        Fixture fixture = fixture("FREE_RESPONSE");
        JsonNode fresh = progress.read(fixture.actor(), fixture.deck(), 20, null).path("items").get(0);
        assertThat(fresh.path("state").stringValue(null)).isEqualTo("NOT_STARTED");
        assertThat(fresh.path("objectiveCoverage").toString())
                .isEqualTo("{\"enabled\":1,\"introduced\":0,\"assessed\":0}");

        Presentation presentation = presentation(fixture, "SCHEDULED");
        JsonNode introduced = progress.read(fixture.actor(), fixture.deck(), 20, null).path("items").get(0);
        assertThat(introduced.path("state").stringValue(null)).isEqualTo("LEARNING");
        AttemptCommand command = attempt(UUID.randomUUID(), presentation, "TEXT", "memory", null);
        service.submit(fixture.actor(), fixture.deck(), presentation.session(), command);

        JsonNode onTrack = progress.read(fixture.actor(), fixture.deck(), 20, null).path("items").get(0);
        assertThat(onTrack.path("state").stringValue(null)).isEqualTo("ON_TRACK");
        assertThat(onTrack.path("objectiveCoverage").path("assessed").intValue()).isOne();
        jdbc.sql("""
                UPDATE app_learning.study_state SET next_due=statement_timestamp()-INTERVAL '1 second'
                 WHERE account_id=:actor AND deck_id=:deck
                """).param("actor", fixture.actor()).param("deck", fixture.deck()).update();
        assertThat(progress.read(fixture.actor(), fixture.deck(), 20, null).path("items").get(0)
                .path("state").stringValue(null)).isEqualTo("DUE");
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
        Fixture fixture = fixture("FREE_RESPONSE");
        Presentation scheduled = presentation(fixture, "SCHEDULED");
        service.submit(fixture.actor(), fixture.deck(), scheduled.session(),
                attempt(UUID.randomUUID(), scheduled, "TEXT", "memory", null));
        StateSnapshot before = state(fixture.actor());
        long exposures = count("study_exposure", "account_id", fixture.actor());
        long evidence = count("study_evidence", "account_id", fixture.actor());
        long transitions = count("study_transition", "account_id", fixture.actor());

        for (int index = 0; index < 50; index++) {
            String mode = index % 2 == 0 ? "PRACTICE" : "REPLAY";
            Presentation extra = presentation(fixture, mode, scheduled.session());
            AttemptCommand command = attempt(UUID.randomUUID(), extra, "TEXT",
                    index % 3 == 0 ? "wrong" : "memory", "GUESSED");
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
        tampered.putNull("confidence"); tampered.put("durationMs", 1);
        assertThatThrownBy(() -> AttemptCommand.read(bytes(tampered)))
                .isInstanceOf(app.mnema.learning.platform.api.InvalidRequestException.class);
    }

    private Presentation presentation(Fixture fixture, String mode) {
        return presentation(fixture, mode, null);
    }

    private Presentation presentation(Fixture fixture, String mode, UUID sourceSession) {
        StudyFixtures.Issued issued = fixtures.issue(fixture.material(), mode, sourceSession).getFirst();
        List<UUID> options = new java.util.ArrayList<>();
        issued.content().path("options").forEach(option -> options.add(UUID.fromString(option.path("optionId").stringValue(null))));
        String blank = null;
        for (JsonNode segment : issued.content().path("passage")) {
            if (segment.path("kind").stringValue(null).equals("BLANK")) blank = segment.path("blankId").stringValue(null);
        }
        return new Presentation(issued.session(), issued.id(), issued.nonce(), List.copyOf(options), blank);
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
        StudyFixtures.Material material = fixtures.material();
        ObjectNode exercise = switch (type) {
            case "SELF_CHECK" -> fixtures.selfCheck(material, StudyFixtures.blocks(StudyFixtures.text("Recall it")),
                    StudyFixtures.blocks(StudyFixtures.quote(material, material.node())));
            case "CLOZE" -> {
                UUID blank = UUID.randomUUID();
                yield fixtures.cloze(material, StudyFixtures.blocks(), StudyFixtures.blocks(StudyFixtures.text("Recall: "),
                        StudyFixtures.blank(blank, false, 0, true)), StudyFixtures.blankKey(blank, "memory"));
            }
            case "CHOICE" -> {
                UUID correct = UUID.randomUUID();
                yield fixtures.choice(material, false, StudyFixtures.blocks(StudyFixtures.text("Pick")),
                        StudyFixtures.blocks().add(StudyFixtures.option(correct, StudyFixtures.quote(material, material.node())))
                                .add(StudyFixtures.option(UUID.randomUUID(), StudyFixtures.quote(material, material.distractor()))),
                        correct);
            }
            default -> fixtures.freeResponse(material, StudyFixtures.blocks(StudyFixtures.text("Recall it")),
                    StudyFixtures.blocks(), "memory");
        };
        fixtures.publish(material, exercise);
        return new Fixture(material.actor(), material.deck(), material.member(), material);
    }

    private static AttemptCommand attempt(UUID id, Presentation presentation, String kind, String value, String confidence) {
        ObjectNode body = JSON.createObjectNode().put("attemptId", id.toString())
                .put("presentationId", presentation.id().toString()).put("nonce", presentation.nonce());
        ObjectNode response = body.putObject("response").put("kind", kind);
        if (kind.equals("TEXT")) response.put("text", value);
        if (kind.equals("CLOZE")) {
            response.putArray("blanks").addObject().put("blankId", presentation.blank()).put("text", value);
        }
        if (kind.equals("CHOICE")) response.putArray("optionIds").add(value);
        if (confidence == null) body.putNull("confidence"); else body.put("confidence", confidence);
        body.put("durationMs", 1_000);
        return AttemptCommand.read(bytes(body));
    }

    private static JsonNode hintCommand(String nonce, UUID blank) {
        return JSON.createObjectNode().put("nonce", nonce).put("blankId", blank.toString());
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

    private record Fixture(UUID actor, UUID deck, UUID member, StudyFixtures.Material material) { }
    private record Presentation(UUID session, UUID id, String nonce, List<UUID> options, String blank) { }
    private record StateSnapshot(long learningEpoch, int level, int correctStreak, int lapseCount,
                                 long transitionSequence, Instant lastAssessedAt, Instant nextDue) { }
}
