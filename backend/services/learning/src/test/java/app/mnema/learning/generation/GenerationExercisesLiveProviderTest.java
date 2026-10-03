package app.mnema.learning.generation;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in live run of the exercise path against DeepSeek: one material about the PostgreSQL planner, five exercises with
 * {@code AUTO} mechanics. The batch must reach REVIEW within the step deadline with at least three proposals of at least
 * three mechanics, each already validated by the server pipeline. Prints only counts, mechanics and timings (never a
 * prompt, the model's text or the key). Skipped unless {@code MNEMA_AI_LIVE=true} and a key is in the environment:
 * <pre>
 * MNEMA_AI_LIVE=true MNEMA_AI_DEEPSEEK_API_KEY=... ./gradlew :services:learning:cleanTest :services:learning:test --tests '*GenerationExercisesLiveProviderTest*'
 * </pre>
 */
@SpringBootTest(properties = {
        "learning.runtime.roles=all",
        "learning.features.ai-generation.enabled=true",
        "learning.ai.user-key.secret=live-test-user-key-secret-0123456789",
        "learning.usage.entitlements.default-plan=PLUS",
        "spring.datasource.hikari.maximum-pool-size=6"})
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MNEMA_AI_LIVE", matches = "true")
class GenerationExercisesLiveProviderTest extends PostgresIntegrationTest {
    private static final Duration STEP_DEADLINE = Duration.ofMinutes(6);

    @Autowired private SessionService sessions;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService studySessions;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;

    @Test
    void fiveAutoExercisesForOneMaterialComeBackValidatedWithAtLeastThreeMechanics() throws Exception {
        String key = System.getenv("MNEMA_AI_DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(), "MNEMA_AI_DEEPSEEK_API_KEY is not set");
        UUID owner = UUID.randomUUID();
        UUID deck = UUID.fromString(decks.create(owner, new DeckCommand(UUID.randomUUID(), "PostgreSQL", "Как работает СУБД"))
                .acknowledgement().path("deck").path("deckId").stringValue(null));
        StudyFixtures fixtures = new StudyFixtures(decks, items, exercises, studySessions, media, jdbc);
        StudyFixtures.Material material = fixtures.addMaterial(owner, deck,
                "Планировщик PostgreSQL перебирает альтернативные планы выполнения запроса и выбирает план с наименьшей "
                        + "оценкой стоимости. Он сравнивает последовательное сканирование (Seq Scan), сканирование по индексу "
                        + "(Index Scan) и способы соединения таблиц: Nested Loop, Hash Join и Merge Join.",
                "Оценка стоимости опирается на статистику таблиц, которую собирает команда ANALYZE: число строк, долю NULL "
                        + "и самые частые значения. Если статистика устарела, планировщик может выбрать медленный план.");

        ObjectNode spec = GenerationIntegrationTest.JSON.createObjectNode().put("kind", "EXERCISES").put("outputLanguage", "ru");
        spec.putArray("targets").addObject().put("memberKey", material.member().toString())
                .put("itemRevisionId", material.itemRevision().toString());
        ObjectNode settings = spec.putObject("settings").put("mechanics", "AUTO").put("priority", "UNCOVERED_FIRST");
        settings.putObject("quantity").put("mode", "EXACT").put("perTarget", 5);
        ObjectNode body = GenerationIntegrationTest.JSON.createObjectNode().put("commandId", UUID.randomUUID().toString());
        body.set("spec", spec);

        Instant started = Instant.now();
        UUID session = UUID.fromString(sessions.create(owner, deck, body.toString().getBytes(StandardCharsets.UTF_8)).body()
                .path("sessionId").stringValue(null));
        String state = "RUNNING";
        while (state.equals("RUNNING") && Duration.between(started, Instant.now()).compareTo(STEP_DEADLINE.plusSeconds(30)) < 0) {
            Thread.sleep(500);
            state = jdbc.sql("SELECT state FROM app_learning.generation_session WHERE session_id=:id").param("id", session)
                    .query(String.class).single();
        }
        Duration took = Duration.between(started, Instant.now());
        assertThat(state).as("the session must leave RUNNING within the step deadline").isEqualTo("REVIEW");

        JsonNode detail = sessions.read(owner, deck, session);
        Set<String> mechanics = new HashSet<>();
        int proposed = 0;
        int failed = 0;
        for (JsonNode artifact : detail.path("artifacts")) {
            String artifactState = artifact.path("state").stringValue("");
            if (artifactState.equals("FAILED")) failed++;
            if (!artifactState.equals("PROPOSED")) continue;
            proposed++;
            JsonNode full = sessions.artifact(owner, deck, session,
                    UUID.fromString(artifact.path("artifactId").stringValue(null)), null);
            mechanics.add(full.path("display").path("mechanic").stringValue(""));
        }
        long calls = jdbc.sql("SELECT count(*) FROM app_learning.ai_provider_call c JOIN app_learning.generation_step s "
                + "ON s.step_id=c.step_id WHERE s.session_id=:id").param("id", session).query(Long.class).single();
        long costMicros = jdbc.sql("SELECT COALESCE(sum(c.cost_micros),0) FROM app_learning.ai_provider_call c JOIN "
                + "app_learning.generation_step s ON s.step_id=c.step_id WHERE s.session_id=:id AND c.outcome='OK'")
                .param("id", session).query(Long.class).single();
        System.out.printf("live_exercises took_ms=%d proposed=%d failed=%d mechanics=%s provider_calls=%d cost_micro_usd=%d%n",
                took.toMillis(), proposed, failed, mechanics, calls, costMicros);

        assertThat(proposed).isGreaterThanOrEqualTo(3);
        assertThat(mechanics).hasSizeGreaterThanOrEqualTo(3);
        assertThat(took).isLessThanOrEqualTo(STEP_DEADLINE);
    }
}
