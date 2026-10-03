package app.mnema.learning.generation;

import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in live run of the real worker path against DeepSeek: a session from the prompt «20 глаголов движения» must reach
 * REVIEW with a valid native-v1 revision within the six minutes of the step deadline, and the ledger must carry the debit
 * with the measured cost of the provider calls. Skipped unless {@code MNEMA_AI_LIVE=true} and a key is in the environment:
 * <pre>
 * MNEMA_AI_LIVE=true MNEMA_AI_DEEPSEEK_API_KEY=... ./gradlew :services:learning:cleanTest :services:learning:test --tests '*GenerationLiveProviderTest*'
 * </pre>
 * CI never runs it (no variable, no network). It never prints the key, a prompt or the model's text; the database is the
 * disposable Testcontainers one.
 */
@SpringBootTest(properties = {
        "learning.runtime.roles=all",
        "learning.features.ai-generation.enabled=true",
        "learning.ai.user-key.secret=live-test-user-key-secret-0123456789",
        "learning.usage.entitlements.default-plan=PLUS",
        "spring.datasource.hikari.maximum-pool-size=6"})
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MNEMA_AI_LIVE", matches = "true")
class GenerationLiveProviderTest extends PostgresIntegrationTest {
    private static final Duration STEP_DEADLINE = Duration.ofMinutes(6);

    @Autowired private SessionService sessions;
    @Autowired private DeckService decks;
    @Autowired private JdbcClient jdbc;
    @Autowired private GenerationSettings settings;

    @Test
    void twentyVerbsOfMotionComeBackAsAValidMaterialWithinSixMinutesAndTheLedgerCarriesTheDebit() throws Exception {
        String key = System.getenv("MNEMA_AI_DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(), "MNEMA_AI_DEEPSEEK_API_KEY is not set");
        UUID owner = UUID.randomUUID();
        UUID deck = UUID.fromString(decks.create(owner, new DeckCommand(UUID.randomUUID(), "Русский язык", "Глаголы и их формы"))
                .acknowledgement().path("deck").path("deckId").stringValue(null));
        ObjectNode spec = GenerationIntegrationTest.JSON.createObjectNode().put("kind", "MATERIALS").put("outputLanguage", "ru")
                .put("prompt", "20 глаголов движения");
        spec.putObject("settings").put("effort", "MEDIUM");
        ObjectNode body = GenerationIntegrationTest.JSON.createObjectNode().put("commandId", UUID.randomUUID().toString());
        body.set("spec", spec);

        Instant started = Instant.now();
        JsonNode created = sessions.create(owner, deck, body.toString().getBytes(StandardCharsets.UTF_8)).body();
        UUID session = UUID.fromString(created.path("sessionId").stringValue(null));
        String state = "RUNNING";
        while (state.equals("RUNNING") && Duration.between(started, Instant.now()).compareTo(STEP_DEADLINE.plusSeconds(30)) < 0) {
            Thread.sleep(500);
            state = jdbc.sql("SELECT state FROM app_learning.generation_session WHERE session_id=:id").param("id", session)
                    .query(String.class).single();
        }
        Duration took = Duration.between(started, Instant.now());

        assertThat(state).as("the session must leave RUNNING within the step deadline").isEqualTo("REVIEW");
        assertThat(took).isLessThanOrEqualTo(STEP_DEADLINE);
        JsonNode detail = sessions.read(owner, deck, session);
        assertThat(detail.path("artifacts").get(0).path("state").stringValue(null)).isEqualTo("PROPOSED");
        assertThat(detail.path("usage").path("spentCredits").intValue()).isEqualTo(10);
        UUID artifact = UUID.fromString(detail.path("artifacts").get(0).path("artifactId").stringValue(null));
        JsonNode full = sessions.artifact(owner, deck, session, artifact, null);
        new NativeDocumentReader().read(full.path("revision").path("payload").path("document").toString().getBytes(StandardCharsets.UTF_8));
        assertThat(full.path("title").stringValue(null)).isNotBlank();

        // the ledger: one DEBIT of the rate-card weight, with the measured cost of every successful call of the step
        var debit = jdbc.sql("SELECT credits,cost_micros FROM app_learning.usage_ledger_entry WHERE owner_id=:owner AND kind='DEBIT'")
                .param("owner", owner).query((row, ignored) -> new long[] {row.getLong("credits"), row.getLong("cost_micros")}).single();
        assertThat(debit[0]).isEqualTo(-10);
        long dollars = jdbc.sql("SELECT COALESCE(sum(c.cost_micros),0) FROM app_learning.ai_provider_call c JOIN app_learning.generation_step s "
                        + "ON s.step_id=c.step_id WHERE s.session_id=:id AND c.outcome='OK' AND c.provider='deepseek'")
                .param("id", session).query(Long.class).single();
        assertThat(dollars).isPositive();
        assertThat(debit[1]).isEqualTo(BigDecimal.valueOf(dollars).multiply(settings.usdRubRate()).setScale(0, RoundingMode.CEILING).longValueExact());
        assertThat(jdbc.sql("SELECT min(prompt_tokens) FROM app_learning.ai_provider_call WHERE step_id=:step AND outcome='OK'")
                .param("step", jdbc.sql("SELECT step_id FROM app_learning.generation_step WHERE session_id=:id").param("id", session)
                        .query(UUID.class).single()).query(Integer.class).single()).isPositive();
    }
}
