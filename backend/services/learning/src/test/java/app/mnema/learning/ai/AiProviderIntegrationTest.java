package app.mnema.learning.ai;

import app.mnema.learning.capability.LearningCapabilities;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The provider layer on the real Spring context and PostgreSQL, on the Stub: no network, no key. */
@SpringBootTest(properties = {"learning.ai.provider=stub", "learning.features.ai-generation.enabled=true",
        "learning.features.image-search.enabled=true", "learning.ai.budget.text-micros=1000",
        // small pool: every distinct test context keeps its own pool and the shared test PostgreSQL has a connection cap
        "spring.datasource.hikari.maximum-pool-size=2"})
class AiProviderIntegrationTest extends PostgresIntegrationTest {
    @Autowired private TextGeneration text;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private LearningCapabilities capabilities;
    @Autowired private AiProperties properties;
    @Autowired private JdbcCallJournal journal;
    @Autowired private AiCallRetentionWorker retention;
    @Autowired private UserKeys userKeys;

    private void cleanJournal() { jdbc.sql("DELETE FROM app_learning.ai_provider_call").update(); }

    @Test
    void aStubCallIsJournaledWithIntentAndOutcomeAndNoText() {
        cleanJournal();
        UUID step = UUID.randomUUID();
        var request = new TextRequest(AiRoute.TEXT_FAST, List.of(TextRequest.Segment.system("СЕКРЕТНЫЙ-ПРОМПТ", true),
                TextRequest.Segment.user("задача", false)), OutputContract.MBM_TEXT, 500, 0.5, Duration.ofSeconds(5),
                AiTestSupport.KEY, null, step, 2);
        assertThat(text.generate(request)).isInstanceOf(AiResult.Ok.class);

        var rows = jdbc.sql("SELECT * FROM app_learning.ai_provider_call").query().listOfRows();
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row).containsEntry("outcome", "OK").containsEntry("capability", "TEXT").containsEntry("provider", "stub")
                .containsEntry("model", "stub").containsEntry("step_id", step).containsEntry("attempt", 2);
        assertThat((String) row.get("request_hash")).isEqualTo(request.fingerprint());
        assertThat(((Number) row.get("latency_ms")).intValue()).isGreaterThanOrEqualTo(0);
        assertThat((int) row.get("prompt_tokens")).isEqualTo((int) row.get("cache_hit_tokens") + (int) row.get("cache_miss_tokens"));
        assertThat(row.toString()).doesNotContain("СЕКРЕТНЫЙ-ПРОМПТ").doesNotContain("задача").doesNotContain(AiTestSupport.USER_KEY);
        // the schema has no column that could hold a prompt or a response
        assertThat(jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema='app_learning' "
                + "AND table_name='ai_provider_call'").query(String.class).list())
                .noneMatch(name -> name.contains("prompt_text") || name.contains("response") || name.equals("text") || name.contains("body"));
    }

    @Test
    void noDatabaseTransactionIsOpenDuringTheProviderCallAndTheCallRefusesToRunInsideOne() {
        var observed = new ArrayList<Boolean>();
        StreamListener probe = delta -> observed.add(TransactionSynchronizationManager.isActualTransactionActive());
        assertThat(text.generate(AiTestSupport.request(probe))).isInstanceOf(AiResult.Ok.class);
        assertThat(observed).isNotEmpty().containsOnly(false);

        var inside = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> inside.executeWithoutResult(status -> text.generate(AiTestSupport.request())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theJournalIsAppendOnlyExceptForTheSingleOutcomeTransition() {
        cleanJournal();
        UUID id = journal.begin(new CallJournal.Intent(null, 1, AiCapability.TEXT, "stub", "stub", "a".repeat(64)));
        assertThat(jdbc.sql("SELECT outcome FROM app_learning.ai_provider_call WHERE call_id=:id").param("id", id).query(String.class).single())
                .isEqualTo("PENDING");
        journal.finish(id, new CallJournal.Outcome("OK", new Usage(10, 4, 6, 2), 7, "req", 12));
        var row = jdbc.sql("SELECT * FROM app_learning.ai_provider_call WHERE call_id=:id").param("id", id).query().singleRow();
        assertThat(row).containsEntry("outcome", "OK").containsEntry("cost_micros", 7L).containsEntry("provider_request_id", "req");

        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.ai_provider_call SET outcome='TRANSIENT' WHERE call_id=:id")
                .param("id", id).update()).isInstanceOf(DataAccessException.class);
        UUID second = journal.begin(new CallJournal.Intent(null, 1, AiCapability.TEXT, "stub", "stub", "b".repeat(64)));
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.ai_provider_call SET model='other' WHERE call_id=:id")
                .param("id", second).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.ai_provider_call SET capability='TTS' WHERE call_id=:id")
                .param("id", second).update()).isInstanceOf(DataAccessException.class);
        // the single allowed update must leave PENDING: usage cannot be edited in place on a pending row
        UUID pending = journal.begin(new CallJournal.Intent(null, 1, AiCapability.TEXT, "stub", "stub", "e".repeat(64)));
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.ai_provider_call SET cost_micros=5 WHERE call_id=:id")
                .param("id", pending).update()).isInstanceOf(DataAccessException.class);
        // a failed finish never throws into the caller: an unknown outcome violates the CHECK and is logged
        journal.finish(second, new CallJournal.Outcome("MADE_UP", Usage.ZERO, 0, null, 1));
        assertThat(jdbc.sql("SELECT outcome FROM app_learning.ai_provider_call WHERE call_id=:id").param("id", second)
                .query(String.class).single()).isEqualTo("PENDING");
    }

    @Test
    void retentionDeletesOnlyRowsOlderThanNinetyDays() {
        cleanJournal();
        UUID old = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        for (var entry : new Object[][] {{old, 91}, {recent, 89}}) {
            jdbc.sql("INSERT INTO app_learning.ai_provider_call(call_id,capability,provider,model,request_hash,outcome,created_at) "
                            + "VALUES (:id,'TEXT','stub','stub',:hash,'OK',:at)")
                    .param("id", entry[0]).param("hash", "c".repeat(64))
                    .param("at", OffsetDateTime.now(ZoneOffset.UTC).minusDays((Integer) entry[1])).update();
        }
        retention.purge();
        assertThat(jdbc.sql("SELECT call_id FROM app_learning.ai_provider_call").query(UUID.class).list()).containsExactly(recent);
    }

    @Test
    void theDailyBudgetSumsTheJournalAndTurnsTheCapabilityTemporary() {
        cleanJournal();
        assertThat(journal.spentMicros(AiCapability.TEXT, Instant.now().minus(Duration.ofHours(1)))).isZero();
        assertThat(capabilities.aiGeneration()).isEqualTo(new LearningCapabilities.Status(true, null));
        assertThat(text.generate(AiTestSupport.request())).isInstanceOf(AiResult.Ok.class);

        jdbc.sql("INSERT INTO app_learning.ai_provider_call(call_id,capability,provider,model,request_hash,outcome,cost_micros) "
                + "VALUES (:id,'TEXT','deepseek','deepseek-flash',:hash,'OK',1500)").param("id", UUID.randomUUID())
                .param("hash", "d".repeat(64)).update();
        assertThat(journal.spentMicros(AiCapability.TEXT, Instant.now().minus(Duration.ofHours(1)))).isEqualTo(1500);
        assertThat(journal.spentMicros(AiCapability.ASSESS, Instant.now().minus(Duration.ofHours(1)))).isZero();
        // a fresh view of the journal (the bean caches its sum for 10 s) sees the limit of 1000 micros reached
        var fresh = new AiBudget(properties.budget(), journal, java.time.Clock.systemUTC());
        assertThat(fresh.exhausted(AiCapability.TEXT)).isTrue();
        assertThat(fresh.exhausted(AiCapability.ASSESS)).isFalse();
    }

    @Test
    void theStubIsAvailableWithoutAUserKeyAndTheOtherCapabilitiesKeepTheirReasons() {
        assertThat(capabilities.aiGeneration()).isEqualTo(new LearningCapabilities.Status(true, null));
        assertThat(capabilities.imageSearch().reason()).isEqualTo(LearningCapabilities.Reason.PROVIDER_NOT_CONFIGURED);
        assertThat(capabilities.videoGeneration().reason()).isEqualTo(LearningCapabilities.Reason.DISABLED);
        assertThat(userKeys.configured()).as("the Stub needs no user-key secret").isFalse();
    }
}
