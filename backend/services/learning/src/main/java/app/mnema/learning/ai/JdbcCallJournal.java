package app.mnema.learning.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** PostgreSQL journal in {@code app_learning.ai_provider_call}; also the spend source of {@link AiBudget}. */
@Component
class JdbcCallJournal implements CallJournal, AiBudget.SpendSource {
    private static final Logger LOG = LoggerFactory.getLogger(JdbcCallJournal.class);
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    JdbcCallJournal(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(5);
    }

    @Override
    public UUID begin(Intent intent) {
        UUID id = UUID.randomUUID();
        transaction.executeWithoutResult(status -> jdbc.sql("INSERT INTO app_learning.ai_provider_call(call_id,step_id,attempt,"
                        + "capability,provider,model,request_hash) VALUES (:id,:step,:attempt,:capability,:provider,:model,:hash)")
                .param("id", id).param("step", intent.stepId()).param("attempt", intent.attempt())
                .param("capability", intent.capability().name()).param("provider", intent.provider())
                .param("model", intent.model()).param("hash", intent.requestHash()).update());
        return id;
    }

    @Override
    public void finish(UUID callId, Outcome outcome) {
        try {
            transaction.executeWithoutResult(status -> jdbc.sql("UPDATE app_learning.ai_provider_call SET outcome=:outcome,"
                            + "prompt_tokens=:prompt,cache_hit_tokens=:hit,cache_miss_tokens=:miss,completion_tokens=:out,"
                            + "cost_micros=:cost,provider_request_id=:request,latency_ms=:latency WHERE call_id=:id")
                    .param("outcome", outcome.outcome()).param("prompt", outcome.usage().promptTokens())
                    .param("hit", outcome.usage().cacheHitTokens()).param("miss", outcome.usage().cacheMissTokens())
                    .param("out", outcome.usage().completionTokens()).param("cost", outcome.costMicros())
                    .param("request", outcome.providerRequestId())
                    .param("latency", (int) Math.min(Integer.MAX_VALUE, outcome.latencyMillis())).param("id", callId).update());
        } catch (RuntimeException exception) {
            // The call already happened; its row stays PENDING, which is visible in the journal.
            LOG.error("ai_call_journal_finish_failed call_id={} error={}", callId, exception.getClass().getSimpleName());
        }
    }

    @Override
    public long spentMicros(AiCapability capability, Instant since) {
        return jdbc.sql("SELECT COALESCE(SUM(cost_micros),0) FROM app_learning.ai_provider_call "
                        + "WHERE capability=:capability AND created_at>=:since")
                .param("capability", capability.name()).param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .query(Long.class).single();
    }

    /** One bounded retention batch; returns the number of deleted rows. */
    int purgeBatch(int batch, java.time.Duration retention) {
        return transaction.execute(status -> jdbc.sql("DELETE FROM app_learning.ai_provider_call WHERE call_id IN "
                        + "(SELECT call_id FROM app_learning.ai_provider_call WHERE created_at < CURRENT_TIMESTAMP - "
                        + "(:seconds * interval '1 second') ORDER BY created_at LIMIT :batch)")
                .param("seconds", retention.toSeconds()).param("batch", batch).update());
    }
}
