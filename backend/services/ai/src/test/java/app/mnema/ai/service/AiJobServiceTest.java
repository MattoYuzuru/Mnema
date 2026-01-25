package app.mnema.ai.service;

import app.mnema.ai.controller.dto.AiJobResponse;
import app.mnema.ai.controller.dto.CreateAiJobRequest;
import app.mnema.ai.domain.entity.AiQuotaEntity;
import app.mnema.ai.domain.type.AiJobType;
import app.mnema.ai.repository.AiJobRepository;
import app.mnema.ai.repository.AiQuotaRepository;
import app.mnema.ai.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
class AiJobServiceTest extends PostgresIntegrationTest {

    @Autowired
    private AiJobService jobService;

    @Autowired
    private AiQuotaRepository quotaRepository;

    @Autowired
    private AiJobRepository jobRepository;

    @Autowired
    private AiQuotaService quotaService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createJobIsIdempotent() {
        UUID userId = UUID.randomUUID();
        seedQuota(userId, 100);

        UUID requestId = UUID.randomUUID();
        CreateAiJobRequest request = new CreateAiJobRequest(
                requestId,
                null,
                AiJobType.generic,
                NullNode.getInstance(),
                null,
                10,
                null
        );

        AiJobResponse first = jobService.createJob(jwtFor(userId), request);
        AiJobResponse second = jobService.createJob(jwtFor(userId), request);

        assertThat(first.jobId()).isEqualTo(second.jobId());
        assertThat(jobRepository.count()).isEqualTo(1);

        AiQuotaEntity quota = quotaRepository.findByUserIdAndPeriodStart(userId, currentPeriodStart(userId))
                .orElseThrow();
        assertThat(quota.getTokensUsed()).isEqualTo(estimateTokens(AiJobType.generic, null, NullNode.getInstance()));
    }

    @Test
    void createJobEnforcesQuota() {
        UUID userId = UUID.randomUUID();
        CreateAiJobRequest request = new CreateAiJobRequest(
                UUID.randomUUID(),
                null,
                AiJobType.generic,
                NullNode.getInstance(),
                null,
                10,
                null
        );
        int estimate = estimateTokens(AiJobType.generic, null, NullNode.getInstance());
        seedQuota(userId, Math.max(estimate - 1, 0));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> jobService.createJob(jwtFor(userId), request));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(jobRepository.count()).isEqualTo(0);

        AiQuotaEntity quota = quotaRepository.findByUserIdAndPeriodStart(userId, currentPeriodStart(userId))
                .orElseThrow();
        assertThat(quota.getTokensUsed()).isEqualTo(0);
    }

    private void seedQuota(UUID userId, int tokensLimit) {
        LocalDate periodStart = currentPeriodStart(userId);
        LocalDate periodEnd = quotaService.currentPeriodEnd(userId);
        AiQuotaEntity quota = new AiQuotaEntity();
        quota.setUserId(userId);
        quota.setPeriodStart(periodStart);
        quota.setPeriodEnd(periodEnd);
        quota.setTokensLimit(tokensLimit);
        quota.setTokensUsed(0);
        quota.setUpdatedAt(Instant.now());
        quotaRepository.save(quota);
    }

    private LocalDate currentPeriodStart(UUID userId) {
        return quotaService.currentPeriodStart(userId);
    }

    private Jwt jwtFor(UUID userId) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test")
                .header("alg", "none")
                .claim("user_id", userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build();
    }

    private int estimateTokens(AiJobType type, UUID deckId, JsonNode params) {
        try {
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("type", type);
            payload.put("deckId", deckId);
            payload.put("params", params);
            byte[] bytes = objectMapper.writeValueAsBytes(payload);
            int estimated = (int) Math.ceil(bytes.length / 4.0);
            return Math.max(1, estimated);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
