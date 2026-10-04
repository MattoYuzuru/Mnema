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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in live run of the planner against DeepSeek (thinking on the {@code plan} route, JSON output): three materials, an
 * EXERCISES spec with «Сначала показать план». The session must reach PLAN_READY within the planner deadline with a valid
 * plan over the spec's targets. Prints only counts and timings (never a prompt, the model's text or the key). Skipped unless
 * {@code MNEMA_AI_LIVE=true} and a key is in the environment:
 * <pre>
 * MNEMA_AI_LIVE=true MNEMA_AI_DEEPSEEK_API_KEY=... ./gradlew :services:learning:cleanTest :services:learning:test --tests '*GenerationPlanLiveProviderTest*'
 * </pre>
 */
@SpringBootTest(properties = {
        "learning.runtime.roles=all",
        "learning.features.ai-generation.enabled=true",
        "learning.ai.user-key.secret=live-test-user-key-secret-0123456789",
        "learning.usage.entitlements.default-plan=MAX",
        "spring.datasource.hikari.maximum-pool-size=6"})
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MNEMA_AI_LIVE", matches = "true")
class GenerationPlanLiveProviderTest extends PostgresIntegrationTest {
    private static final Duration PLAN_DEADLINE = Duration.ofMinutes(4);

    @Autowired private SessionService sessions;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService studySessions;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;

    @Test
    void aThinkingPlanForThreeMaterialsComesBackValidWithinThePlannerDeadline() throws Exception {
        String key = System.getenv("MNEMA_AI_DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(), "MNEMA_AI_DEEPSEEK_API_KEY is not set");
        UUID owner = UUID.randomUUID();
        UUID deck = UUID.fromString(decks.create(owner, new DeckCommand(UUID.randomUUID(), "PostgreSQL", "Как работает СУБД"))
                .acknowledgement().path("deck").path("deckId").stringValue(null));
        StudyFixtures fixtures = new StudyFixtures(decks, items, exercises, studySessions, media, jdbc);
        StudyFixtures.Material planner = fixtures.addMaterial(owner, deck,
                "Планировщик PostgreSQL перебирает планы выполнения и выбирает план с наименьшей оценкой стоимости.",
                "Он сравнивает Seq Scan, Index Scan и способы соединения: Nested Loop, Hash Join, Merge Join.");
        StudyFixtures.Material statistics = fixtures.addMaterial(owner, deck,
                "Команда ANALYZE собирает статистику: число строк, долю NULL и самые частые значения.",
                "Устаревшая статистика ведёт к медленным планам.");
        StudyFixtures.Material indexes = fixtures.addMaterial(owner, deck,
                "B-tree индекс ускоряет поиск по равенству и диапазону, но замедляет вставку.",
                "Частичный индекс хранит только строки, подходящие под условие.");

        ObjectNode spec = GenerationIntegrationTest.JSON.createObjectNode().put("kind", "EXERCISES").put("outputLanguage", "ru");
        ArrayNode targets = spec.putArray("targets");
        for (StudyFixtures.Material material : new StudyFixtures.Material[] {planner, statistics, indexes}) {
            targets.addObject().put("memberKey", material.member().toString()).put("itemRevisionId", material.itemRevision().toString());
        }
        ObjectNode settings = spec.putObject("settings").put("mechanics", "AUTO").put("priority", "UNCOVERED_FIRST").put("planFirst", true);
        settings.putObject("quantity").put("mode", "EXACT").put("perTarget", 3);
        ObjectNode body = GenerationIntegrationTest.JSON.createObjectNode().put("commandId", UUID.randomUUID().toString());
        body.set("spec", spec);

        Instant started = Instant.now();
        UUID session = UUID.fromString(sessions.create(owner, deck, body.toString().getBytes(StandardCharsets.UTF_8)).body()
                .path("sessionId").stringValue(null));
        String state = "PLANNING";
        while (state.equals("PLANNING") && Duration.between(started, Instant.now()).compareTo(PLAN_DEADLINE.plusSeconds(30)) < 0) {
            Thread.sleep(500);
            state = jdbc.sql("SELECT state FROM app_learning.generation_session WHERE session_id=:id").param("id", session)
                    .query(String.class).single();
        }
        Duration took = Duration.between(started, Instant.now());
        JsonNode detail = sessions.read(owner, deck, session);
        JsonNode plan = detail.path("plan");
        int planned = 0;
        for (JsonNode item : plan.path("items")) planned += item.path("count").asInt(0);
        long calls = jdbc.sql("SELECT count(*) FROM app_learning.ai_provider_call c JOIN app_learning.generation_step s "
                + "ON s.step_id=c.step_id WHERE s.session_id=:id").param("id", session).query(Long.class).single();
        long costMicros = jdbc.sql("SELECT COALESCE(sum(c.cost_micros),0) FROM app_learning.ai_provider_call c JOIN "
                + "app_learning.generation_step s ON s.step_id=c.step_id WHERE s.session_id=:id AND c.outcome='OK'")
                .param("id", session).query(Long.class).single();
        System.out.printf("live_plan state=%s took_ms=%d items=%d planned_exercises=%d provider_calls=%d cost_micro_usd=%d end_reason=%s%n",
                state, took.toMillis(), plan.path("items").size(), planned, calls, costMicros, detail.path("endReason").stringValue(""));

        assertThat(state).as("the plan must be ready within the planner deadline").isEqualTo("PLAN_READY");
        assertThat(plan.path("items").size()).isBetween(1, 3);
        assertThat(planned).isBetween(1, 30);
        assertThat(took).isLessThanOrEqualTo(PLAN_DEADLINE);
    }
}
