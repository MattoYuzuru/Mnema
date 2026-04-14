package app.mnema.ai.domain;

import app.mnema.ai.domain.composite.AiJobStepId;
import app.mnema.ai.domain.composite.AiQuotaId;
import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.entity.AiJobStepEntity;
import app.mnema.ai.domain.entity.AiProviderCredentialEntity;
import app.mnema.ai.domain.entity.AiQuotaEntity;
import app.mnema.ai.domain.entity.AiUsageLedgerEntity;
import app.mnema.ai.domain.entity.SubscriptionEntity;
import app.mnema.ai.domain.type.AiJobStatus;
import app.mnema.ai.domain.type.AiJobStepStatus;
import app.mnema.ai.domain.type.AiJobType;
import app.mnema.ai.domain.type.AiProviderStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiDomainModelTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void entityAndCompositeAccessorsRoundTrip() {
        UUID userId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        Instant now = Instant.now();
        AiJobEntity job = new AiJobEntity(
                jobId,
                requestId,
                userId,
                deckId,
                AiJobType.generic,
                AiJobStatus.processing,
                25,
                objectMapper.createObjectNode().put("mode", "audit"),
                "hash",
                objectMapper.createObjectNode().put("ok", true),
                2,
                now.plusSeconds(60),
                now,
                "worker-1",
                now.minusSeconds(10),
                now.plusSeconds(10),
                "boom",
                now.minusSeconds(20),
                now
        );
        job.setUserAccessToken("token");
        assertThat(job.getJobId()).isEqualTo(jobId);
        assertThat(job.getRequestId()).isEqualTo(requestId);
        assertThat(job.getUserId()).isEqualTo(userId);
        assertThat(job.getDeckId()).isEqualTo(deckId);
        assertThat(job.getUserAccessToken()).isEqualTo("token");
        assertThat(job.getType()).isEqualTo(AiJobType.generic);
        assertThat(job.getStatus()).isEqualTo(AiJobStatus.processing);
        assertThat(job.getProgress()).isEqualTo(25);
        assertThat(job.getParamsJson().path("mode").asText()).isEqualTo("audit");
        assertThat(job.getInputHash()).isEqualTo("hash");
        assertThat(job.getResultSummary().path("ok").asBoolean()).isTrue();
        assertThat(job.getAttempts()).isEqualTo(2);
        assertThat(job.getNextRunAt()).isNotNull();
        assertThat(job.getLockedAt()).isNotNull();
        assertThat(job.getLockedBy()).isEqualTo("worker-1");
        assertThat(job.getStartedAt()).isNotNull();
        assertThat(job.getCompletedAt()).isNotNull();
        assertThat(job.getErrorMessage()).isEqualTo("boom");
        assertThat(job.getCreatedAt()).isNotNull();
        assertThat(job.getUpdatedAt()).isNotNull();

        AiProviderCredentialEntity credential = new AiProviderCredentialEntity(
                UUID.randomUUID(), userId, "openai", "Primary", new byte[]{1}, new byte[]{2}, "key", new byte[]{3}, new byte[]{4},
                AiProviderStatus.active, now, now.plusSeconds(5), now.plusSeconds(10)
        );
        assertThat(credential.getProvider()).isEqualTo("openai");
        credential.setAlias("Fallback");
        assertThat(credential.getAlias()).isEqualTo("Fallback");

        AiQuotaEntity quota = new AiQuotaEntity(userId, LocalDate.now(), LocalDate.now().plusMonths(1), 1000, 250, BigDecimal.TEN, now);
        assertThat(quota.getTokensUsed()).isEqualTo(250);
        quota.setCostLimit(BigDecimal.ONE);
        assertThat(quota.getCostLimit()).isEqualByComparingTo(BigDecimal.ONE);

        AiUsageLedgerEntity ledger = new AiUsageLedgerEntity(1L, requestId, jobId, userId, "openai", "gpt", 12, 34, BigDecimal.ONE, "hash", objectMapper.createObjectNode().put("requests", 1), now);
        assertThat(ledger.getId()).isEqualTo(1L);
        assertThat(ledger.getRequestId()).isEqualTo(requestId);
        assertThat(ledger.getJobId()).isEqualTo(jobId);
        assertThat(ledger.getUserId()).isEqualTo(userId);
        assertThat(ledger.getProvider()).isEqualTo("openai");
        assertThat(ledger.getModel()).isEqualTo("gpt");
        assertThat(ledger.getTokensIn()).isEqualTo(12);
        assertThat(ledger.getTokensOut()).isEqualTo(34);
        assertThat(ledger.getCostEstimate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(ledger.getPromptHash()).isEqualTo("hash");
        assertThat(ledger.getDetails().path("requests").asInt()).isEqualTo(1);
        assertThat(ledger.getCreatedAt()).isEqualTo(now);
        ledger.setId(2L);
        ledger.setRequestId(UUID.randomUUID());
        ledger.setJobId(UUID.randomUUID());
        ledger.setUserId(UUID.randomUUID());
        ledger.setProvider("gemini");
        ledger.setModel("gemini-2.5");
        ledger.setTokensIn(1);
        ledger.setTokensOut(2);
        ledger.setCostEstimate(BigDecimal.TEN);
        ledger.setPromptHash("hash-2");
        ledger.setDetails(objectMapper.createObjectNode().put("requests", 2));
        ledger.setCreatedAt(now.plusSeconds(1));
        assertThat(ledger.getId()).isEqualTo(2L);
        assertThat(ledger.getProvider()).isEqualTo("gemini");
        assertThat(ledger.getModel()).isEqualTo("gemini-2.5");
        assertThat(ledger.getTokensIn()).isEqualTo(1);
        assertThat(ledger.getTokensOut()).isEqualTo(2);
        assertThat(ledger.getCostEstimate()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(ledger.getPromptHash()).isEqualTo("hash-2");
        assertThat(ledger.getDetails().path("requests").asInt()).isEqualTo(2);
        assertThat(ledger.getCreatedAt()).isEqualTo(now.plusSeconds(1));

        AiJobStepEntity step = new AiJobStepEntity(jobId, "generate", AiJobStepStatus.completed, now, now.plusSeconds(5), null);
        assertThat(step.getJobId()).isEqualTo(jobId);
        assertThat(step.getStepName()).isEqualTo("generate");
        assertThat(step.getStatus()).isEqualTo(AiJobStepStatus.completed);
        assertThat(step.getStartedAt()).isEqualTo(now);
        assertThat(step.getEndedAt()).isEqualTo(now.plusSeconds(5));
        step.setJobId(UUID.randomUUID());
        step.setStepName("audit");
        step.setStatus(AiJobStepStatus.failed);
        step.setStartedAt(now.plusSeconds(1));
        step.setEndedAt(now.plusSeconds(2));
        step.setErrorSummary("warn");
        assertThat(step.getStepName()).isEqualTo("audit");
        assertThat(step.getStatus()).isEqualTo(AiJobStepStatus.failed);
        assertThat(step.getStartedAt()).isEqualTo(now.plusSeconds(1));
        assertThat(step.getEndedAt()).isEqualTo(now.plusSeconds(2));
        assertThat(step.getErrorSummary()).isEqualTo("warn");

        SubscriptionEntity subscription = new SubscriptionEntity(userId, "pro", "active", now.plusSeconds(3600), 15);
        assertThat(subscription.getUserId()).isEqualTo(userId);
        assertThat(subscription.getPlanId()).isEqualTo("pro");
        assertThat(subscription.getSubscriptionStatus()).isEqualTo("active");
        assertThat(subscription.getPeriodEnd()).isEqualTo(now.plusSeconds(3600));
        subscription.setUserId(UUID.randomUUID());
        subscription.setPlanId("team");
        subscription.setSubscriptionStatus("trialing");
        subscription.setPeriodEnd(now.plusSeconds(7200));
        subscription.setBillingAnchor(1);
        assertThat(subscription.getPlanId()).isEqualTo("team");
        assertThat(subscription.getSubscriptionStatus()).isEqualTo("trialing");
        assertThat(subscription.getPeriodEnd()).isEqualTo(now.plusSeconds(7200));
        assertThat(subscription.getBillingAnchor()).isEqualTo(1);

        AiQuotaId quotaId = new AiQuotaId(userId, LocalDate.now());
        AiQuotaId sameQuotaId = new AiQuotaId(userId, quotaId.getPeriodStart());
        AiJobStepId stepId = new AiJobStepId(jobId, "generate");
        AiJobStepId sameStepId = new AiJobStepId(jobId, "generate");
        quotaId.setUserId(userId);
        quotaId.setPeriodStart(quotaId.getPeriodStart());
        stepId.setJobId(jobId);
        stepId.setStepName("generate");
        assertThat(quotaId.getUserId()).isEqualTo(userId);
        assertThat(quotaId.getPeriodStart()).isNotNull();
        assertThat(stepId.getJobId()).isEqualTo(jobId);
        assertThat(stepId.getStepName()).isEqualTo("generate");
        assertThat(quotaId).isEqualTo(sameQuotaId).hasSameHashCodeAs(sameQuotaId);
        assertThat(stepId).isEqualTo(sameStepId).hasSameHashCodeAs(sameStepId);
    }
}
