package app.mnema.ai.service;

import app.mnema.ai.controller.dto.AiJobResponse;
import app.mnema.ai.controller.dto.CreateAiJobRequest;
import app.mnema.ai.domain.entity.AiQuotaEntity;
import app.mnema.ai.domain.entity.AiProviderCredentialEntity;
import app.mnema.ai.domain.type.AiJobStatus;
import app.mnema.ai.domain.type.AiJobType;
import app.mnema.ai.repository.AiJobRepository;
import app.mnema.ai.repository.AiProviderCredentialRepository;
import app.mnema.ai.repository.AiQuotaRepository;
import app.mnema.ai.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.BeforeEach;
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
    private AiProviderCredentialRepository credentialRepository;

    @Autowired
    private AiQuotaService quotaService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanState() {
        jobRepository.deleteAll();
        credentialRepository.deleteAll();
        quotaRepository.deleteAll();
    }

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

        AiJobResponse first = jobService.createJob(jwtFor(userId), "token", request);
        AiJobResponse second = jobService.createJob(jwtFor(userId), "token", request);

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
                () -> jobService.createJob(jwtFor(userId), "token", request));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(jobRepository.count()).isEqualTo(0);

        AiQuotaEntity quota = quotaRepository.findByUserIdAndPeriodStart(userId, currentPeriodStart(userId))
                .orElseThrow();
        assertThat(quota.getTokensUsed()).isEqualTo(0);
    }

    @Test
    void listGetResultAndCancelReturnPersistedJobDetails() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        seedQuota(userId, 1000);

        AiProviderCredentialEntity credential = new AiProviderCredentialEntity();
        credential.setId(credentialId);
        credential.setUserId(userId);
        credential.setProvider("openai");
        credential.setAlias("Primary");
        credential.setStatus(app.mnema.ai.domain.type.AiProviderStatus.active);
        credential.setEncryptedSecret(new byte[]{1});
        credential.setEncryptedDataKey(new byte[]{2});
        credential.setKeyId("key-1");
        credential.setNonce(new byte[]{3});
        credential.setAad(new byte[]{4});
        credential.setCreatedAt(Instant.now());
        credential.setUpdatedAt(Instant.now());
        credentialRepository.save(credential);

        var params = objectMapper.createObjectNode();
        params.put("providerCredentialId", credentialId.toString());
        params.put("provider", "openai");
        params.put("model", "gpt-4o-mini");

        AiJobResponse created = jobService.createJob(jwtFor(userId), "token", new CreateAiJobRequest(
                requestId,
                deckId,
                AiJobType.generic,
                params,
                null,
                10,
                null
        ));

        assertThat(jobService.listJobs(jwtFor(userId), deckId, 100)).singleElement().satisfies(job -> {
            assertThat(job.providerCredentialId()).isEqualTo(credentialId);
            assertThat(job.provider()).isEqualTo("openai");
            assertThat(job.providerAlias()).isEqualTo("Primary");
            assertThat(job.model()).isEqualTo("gpt-4o-mini");
        });
        assertThat(jobService.getJob(jwtFor(userId), created.jobId()).jobId()).isEqualTo(created.jobId());
        assertThat(jobService.getJobResult(jwtFor(userId), created.jobId()).status()).isEqualTo(AiJobStatus.queued);

        AiJobResponse canceled = jobService.cancelJob(jwtFor(userId), created.jobId());
        assertThat(canceled.status()).isEqualTo(AiJobStatus.canceled);
        assertThat(canceled.completedAt()).isNotNull();
    }

    @Test
    void listAndCancelValidateInputAndJobOwnership() {
        UUID userId = UUID.randomUUID();
        UUID anotherUser = UUID.randomUUID();
        seedQuota(userId, 1000);

        ResponseStatusException badDeck = assertThrows(ResponseStatusException.class,
                () -> jobService.listJobs(jwtFor(userId), null, 20));
        assertThat(badDeck.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        CreateAiJobRequest request = new CreateAiJobRequest(
                UUID.randomUUID(),
                null,
                AiJobType.generic,
                NullNode.getInstance(),
                null,
                10,
                null
        );
        AiJobResponse created = jobService.createJob(jwtFor(userId), "token", request);

        ResponseStatusException notFound = assertThrows(ResponseStatusException.class,
                () -> jobService.getJob(jwtFor(anotherUser), created.jobId()));
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createJobRejectsConflictingRequestReuseAndFinishedCancel() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        seedQuota(userId, 1000);

        jobService.createJob(jwtFor(userId), "token", new CreateAiJobRequest(
                requestId,
                null,
                AiJobType.generic,
                objectMapper.createObjectNode().put("input", "first"),
                null,
                10,
                null
        ));

        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
                () -> jobService.createJob(jwtFor(userId), "token", new CreateAiJobRequest(
                        requestId,
                        null,
                        AiJobType.generic,
                        objectMapper.createObjectNode().put("input", "second"),
                        null,
                        10,
                        null
                )));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var job = jobRepository.findByRequestId(requestId).orElseThrow();
        job.setStatus(AiJobStatus.completed);
        job.setCompletedAt(Instant.now());
        jobRepository.save(job);

        ResponseStatusException finished = assertThrows(ResponseStatusException.class,
                () -> jobService.cancelJob(jwtFor(userId), job.getJobId()));
        assertThat(finished.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
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
