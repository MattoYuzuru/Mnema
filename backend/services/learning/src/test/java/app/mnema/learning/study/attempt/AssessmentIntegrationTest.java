package app.mnema.learning.study.attempt;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseCommand;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Issued;
import app.mnema.learning.support.StudyFixtures.Material;
import app.mnema.learning.usage.Bucket;
import app.mnema.learning.usage.UsageLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import static app.mnema.learning.support.ContractFixtures.bytes;
import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The AI assessment on the real Spring context, PostgreSQL and the deterministic Stub (no network, no key): an answer to an
 * {@code ai-semantic} exercise is accepted, graded on a virtual thread and concluded; or the learner rates themselves. The
 * deadline is short (4 s) and the sweeper fast, so the deadline path is observable; a slow provider is {@code [[fake:block]]}.
 * Each test owns its learner, so tests never see each other's rows or fair-use counters.
 */
@SpringBootTest(properties = {
        "learning.ai.provider=stub",
        "learning.features.ai-assessment.enabled=true",
        "learning.usage.entitlements.default-plan=PLUS",
        "learning.ai.assess.deadline=PT4S",
        "learning.ai.assess.sweep-interval=PT0.2S",
        "spring.datasource.hikari.maximum-pool-size=6", "spring.datasource.hikari.minimum-idle=2"})
@Import(AssessmentTestConfiguration.class)
abstract class AssessmentIntegrationTest extends PostgresIntegrationTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String REFERENCE = "Оптимизатор перебирает планы выполнения запроса и выбирает план с наименьшей оценкой стоимости.";

    @Autowired protected AttemptService attempts;
    @Autowired protected AssessmentService assessments;
    @Autowired protected AssessmentRunner runner;
    @Autowired protected StudySessionService sessions;
    @Autowired protected DeckService decks;
    @Autowired protected ItemService items;
    @Autowired protected ExerciseService exercises;
    @Autowired protected MediaCatalog media;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected UsageLedger ledger;
    @Autowired protected io.micrometer.core.instrument.MeterRegistry meters;
    @Autowired protected PlatformTransactionManager transactions;
    @Autowired protected AssessmentTestConfiguration.Scripted provider;
    protected StudyFixtures fixtures;

    @BeforeEach
    void fixtures() {
        provider.reset();
        fixtures = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
    }

    @AfterEach
    void releaseSlowProvider() { provider.release.countDown(); }

    /** A learner with one deck, one material and one issued {@code ai-semantic} presentation. */
    protected record Case(Material material, Issued issued) {
        UUID actor() { return material.actor(); }

        UUID deck() { return material.deck(); }

        UUID session() { return issued.session(); }
    }

    /** The optimizer exercise; the rubric is written so that the Stub's lexical heuristic can read it (two content words per point). */
    protected ObjectNode rubric() {
        ObjectNode rubric = JSON.createObjectNode().put("referenceAnswer", REFERENCE);
        ArrayNode criteria = rubric.putArray("criteria");
        point(criteria, "Перебирает альтернативные планы выполнения запроса", "CORE", 3);
        point(criteria, "Выбирает план с наименьшей оценкой стоимости", "CORE", 3);
        point(criteria, "Оценка опирается на статистику таблиц", "DETAIL", 2);
        point(criteria, "Использует термины план, стоимость, статистика", "TERM", 1);
        rubric.putArray("misconceptions").add("Выполняет все планы и сравнивает время");
        rubric.putArray("acceptableTerms").add("planner");
        return rubric;
    }

    private static void point(ArrayNode criteria, String description, String tier, int weight) {
        criteria.addObject().put("criterionId", UUID.randomUUID().toString()).put("description", description)
                .put("tier", tier).put("weight", weight);
    }

    protected ObjectNode semanticExercise(Material material) {
        ObjectNode exercise = fixtures.freeResponse(material, blocks(text("Объясните, как работает оптимизатор PostgreSQL.")),
                blocks(text("Ориентир: " + REFERENCE)), "оптимизатор");
        ObjectNode policy = exercise.withObject("evaluatorPolicy");
        policy.put("id", "ai-semantic").put("version", "1");
        policy.set("rubric", rubric());
        return exercise;
    }

    /** A fresh learner whose semantic exercise is issued in a scheduled session. */
    protected Case issued() {
        Material material = fixtures.material();
        fixtures.publish(material, semanticExercise(material), "Оптимизатор запросов");
        Issued issued = fixtures.issueOne(material);
        assertThat(issued.json().path("evaluator").path("id").stringValue(null)).isEqualTo("ai-semantic");
        return new Case(material, issued);
    }

    /** A further semantic exercise (own objective) in the learner's deck, issued in a new session of the given mode. */
    protected Case another(Case first, String mode) {
        fixtures.publish(first.material(), semanticExercise(first.material()), "Ещё одна цель " + UUID.randomUUID());
        JsonNode session = fixtures.session(first.material(), mode, null);
        UUID id = UUID.fromString(session.path("sessionId").stringValue(null));
        for (JsonNode presentation : session.path("presentations")) {
            if (presentation.path("evaluator").path("id").stringValue(null).equals("ai-semantic")) {
                return new Case(first.material(), new Issued(id, presentation));
            }
        }
        throw new AssertionError("no semantic presentation was issued");
    }

    /** The next scheduled presentation of the learner's objective: it is made due again, as after its interval, and a new session issues it. */
    protected Case sameObjective(Case previous) {
        makeDue(previous);
        JsonNode session = fixtures.session(previous.material(), "SCHEDULED", null);
        UUID id = UUID.fromString(session.path("sessionId").stringValue(null));
        assertThat(session.path("presentations")).as("the due objective is issued again").hasSize(1);
        return new Case(previous.material(), new Issued(id, session.path("presentations").get(0)));
    }

    protected AttemptService.SubmitResult submit(Case learner, UUID attemptId, String answer) {
        return attempts.submit(learner.actor(), learner.deck(), learner.session(),
                StudyFixtures.attempt(attemptId, learner.issued(), StudyFixtures.textResponse(answer)));
    }

    /** Polls the attempt until it is no longer being assessed (or fails after ten seconds). */
    protected JsonNode settled(Case learner, UUID attemptId) {
        return eventually(() -> {
            JsonNode view = assessments.read(learner.actor(), learner.deck(), learner.session(), attemptId);
            return view.path("status").stringValue(null).equals("ASSESSING") ? null : view;
        }, "the attempt left ASSESSING");
    }

    protected <T> T eventually(Supplier<T> check, String what) {
        Instant limit = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(limit)) {
            T value = check.get();
            if (value != null) return value;
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
        throw new AssertionError("Timed out: " + what);
    }

    /** Results the service received for an answer that had left ASSESSING (self-check chosen, deadline passed): the hook late-result tests await. */
    protected double discarded() { return meters.counter("mnema_assessment_total", "outcome", "DISCARDED", "reason", "NONE").count(); }

    protected void awaitDiscarded(double before) {
        eventually(() -> discarded() > before ? Boolean.TRUE : null, "the late grade was discarded");
    }

    protected String state(UUID attemptId) {
        return jdbc.sql("SELECT state FROM app_learning.study_assessment WHERE attempt_id=:id").param("id", attemptId)
                .query(String.class).single();
    }

    protected String reason(UUID attemptId) {
        return jdbc.sql("SELECT reason FROM app_learning.study_assessment WHERE attempt_id=:id").param("id", attemptId)
                .query(String.class).optional().orElse(null);
    }

    protected long count(String table, UUID actor) {
        return jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE account_id=:actor").param("actor", actor)
                .query(Long.class).single();
    }

    /** Fair-use answer checks counted for the learner this month. */
    protected long checks(UUID actor) {
        return jdbc.sql("SELECT COALESCE(sum(used),0) FROM app_learning.usage_counter WHERE owner_id=:actor AND bucket='ASSESSMENT' "
                + "AND window_kind='MONTH'").param("actor", actor).query(Long.class).single();
    }

    /** The objective of the case's presentation is due again now (as after the interval), so a scheduled session issues it. */
    protected void makeDue(Case learner) {
        jdbc.sql("UPDATE app_learning.study_state SET next_due=now() - interval '1 minute' WHERE account_id=:actor AND next_due IS NOT NULL")
                .param("actor", learner.actor()).update();
    }

    /** Consumes the learner's whole daily fair-use allowance for answer checks (PLUS: 40 a day). */
    protected void exhaustFairUse(UUID actor) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        for (int index = 0; index < 40; index++) {
            int number = index;
            tx.executeWithoutResult(status -> ledger.consume(actor, Bucket.ASSESSMENT, 1, "exhaust:" + actor + ":" + number, null));
        }
    }

    protected static ExerciseCommand command(ObjectNode body) { return ExerciseCommand.readCreate(bytes(body)); }
}
