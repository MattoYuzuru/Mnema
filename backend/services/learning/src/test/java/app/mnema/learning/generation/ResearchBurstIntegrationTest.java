package app.mnema.learning.generation;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.usage.ResearchBurstClock;
import app.mnema.learning.usage.ReservationScope;
import app.mnema.learning.usage.UsageLedger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real research admission, durable parking and execution after the Moscow usage day rolls over, without a wall-clock wait. */
@SpringBootTest(properties = {"learning.runtime.roles=all", "learning.ai.provider=stub", "learning.features.ai-generation.enabled=true",
        "learning.features.web-search.enabled=true", "learning.usage.entitlements.default-plan=PLUS",
        "learning.generation.worker.sweep-interval=PT0.2S", "spring.datasource.hikari.maximum-pool-size=4"})
@Import({GenerationTestConfiguration.class, ResearchBurstClock.class})
class ResearchBurstIntegrationTest extends PostgresIntegrationTest {
    @Autowired private JdbcClient jdbc;
    @Autowired private DeckService decks;
    @Autowired private SessionService sessions;
    @Autowired private UsageLedger ledger;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ResearchBurstClock.MutableClock clock;
    @Autowired private GenerationTestConfiguration.Scripted provider;
    @Autowired private GenerationTestConfiguration.ScriptedSearch search;

    @Test
    void researchBuysNothingWhenTheDailyBurstIsFullAndResumesOnTheNextUsageDay() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = UUID.fromString(decks.create(owner, new DeckCommand(UUID.randomUUID(), "Исследование", "Источники"))
                .acknowledgement().path("deck").path("deckId").stringValue());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var held = ledger.reserve(owner, ReservationScope.SESSION, UUID.randomUUID(), null, 125);
            ledger.settle(owner, new UsageLedger.Debit(held.reservationId(), "burst:" + owner, "MATERIAL_DETAILED", 125, null, null));
        });
        var today = ledger.dailyDebitRoom(owner).orElseThrow();
        assertThat(today.remainingTodayCredits()).isEqualTo(1);
        ObjectNode spec = GenerationIntegrationTest.spec("Планировщик запросов PostgreSQL");
        ((ObjectNode) spec.path("settings")).put("effort", "MEDIUM").put("factCheck", true);
        UUID session = UUID.fromString(sessions.create(owner, deck, GenerationIntegrationTest.body(UUID.randomUUID(), spec).toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .body().path("sessionId").stringValue());

        GenerationIntegrationTest.await("research parked at the Moscow day boundary", Duration.ofSeconds(15), () -> jdbc
                .sql("SELECT count(*) FROM app_learning.generation_step WHERE session_id=:id AND kind='RESEARCH' "
                        + "AND state='READY' AND next_attempt_at=:until AND attempts=0")
                .param("id", session).param("until", java.sql.Timestamp.from(today.resetsAt())).query(Integer.class).single() == 1);
        assertThat(provider.calls).isEmpty();
        assertThat(search.calls).isEmpty();
        assertThat(jdbc.sql("SELECT state FROM app_learning.generation_step WHERE session_id=:id AND kind='TEXT_DRAFT'")
                .param("id", session).query(String.class).single()).isEqualTo("WAITING_DEPENDENCIES");

        clock.set(today.resetsAt().plusSeconds(1));
        assertThat(ledger.dailyDebitRoom(owner).orElseThrow().remainingTodayCredits()).isEqualTo(126);
        // The dispatcher uses real database time; make only this parked fixture due as if that same boundary had arrived there too.
        jdbc.sql("UPDATE app_learning.generation_step SET next_attempt_at=CURRENT_TIMESTAMP WHERE session_id=:id AND kind='RESEARCH'")
                .param("id", session).update();
        GenerationIntegrationTest.await("researched material ready next day", Duration.ofSeconds(20), () -> jdbc
                .sql("SELECT state FROM app_learning.generation_session WHERE session_id=:id").param("id", session)
                .query(String.class).single().equals("REVIEW"));
        assertThat(search.calls).hasSize(1);
        assertThat(search.calls.getFirst().queries()).hasSize(2);
        assertThat(ledger.dailyDebitRoom(owner).orElseThrow().debitedTodayCredits()).isEqualTo(20);
        assertThat(jdbc.sql("SELECT requests FROM app_learning.generation_research WHERE session_id=:id").param("id", session)
                .query(Integer.class).single()).isEqualTo(2);
    }
}
