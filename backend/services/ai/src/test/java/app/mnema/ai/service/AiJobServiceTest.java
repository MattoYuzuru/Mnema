package app.mnema.ai.service;

import app.mnema.ai.controller.dto.AiJobResponse;
import app.mnema.ai.controller.dto.AiJobPreflightResponse;
import app.mnema.ai.controller.dto.CreateAiJobRequest;
import app.mnema.ai.domain.entity.AiJobEntity;
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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    @Autowired
    private AiJobExecutionService executionService;

    @Autowired
    private AiJobCostEstimator costEstimator;

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
        seedQuota(userId, 5000);

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
        assertThat(jobRepository.findByRequestId(requestId))
                .isPresent()
                .get()
                .extracting(job -> job.getJobId())
                .isEqualTo(first.jobId());

        AiQuotaEntity quota = quotaRepository.findByUserIdAndPeriodStart(userId, currentPeriodStart(userId))
                .orElseThrow();
        assertThat(quota.getTokensUsed()).isEqualTo(estimateTokens(AiJobType.generic, NullNode.getInstance(), null, null));
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
        int estimate = estimateTokens(AiJobType.generic, NullNode.getInstance(), null, null);
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
        seedQuota(userId, 10000);

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
        seedQuota(userId, 10000);

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
        seedQuota(userId, 10000);

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

    @Test
    void getJobAndResultExposeExecutionSteps() {
        UUID userId = UUID.randomUUID();
        seedQuota(userId, 10000);

        AiJobResponse created = jobService.createJob(jwtFor(userId), "token", new CreateAiJobRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                AiJobType.generic,
                objectMapper.createObjectNode().put("mode", "generate_cards").put("provider", "openai").put("model", "gpt-4.1-mini"),
                null,
                10,
                null
        ));

        executionService.resetPlan(created.jobId(), java.util.List.of("prepare_context", "generate_content"));
        executionService.markProcessing(created.jobId(), "prepare_context");
        AiJobEntity processingJob = jobRepository.findByJobIdAndUserId(created.jobId(), userId).orElseThrow();
        processingJob.setStatus(AiJobStatus.processing);
        processingJob.setStartedAt(Instant.now());
        jobRepository.save(processingJob);

        AiJobResponse processing = jobService.getJob(jwtFor(userId), created.jobId());
        assertThat(processing.currentStep()).isEqualTo("prepare_context");
        assertThat(processing.completedSteps()).isEqualTo(0);
        assertThat(processing.totalSteps()).isEqualTo(2);
        assertThat(processing.cost()).isNotNull();
        assertThat(processing.cost().estimatedInputTokens()).isNotNull().isPositive();
        assertThat(processing.cost().estimatedCost()).isNotNull().isPositive();
        assertThat(processing.estimatedSecondsRemaining()).isNotNull().isPositive();
        assertThat(processing.estimatedCompletionAt()).isNotNull().isAfter(Instant.now().minusSeconds(1));
        assertThat(processing.queueAhead()).isNull();

        executionService.markCompleted(created.jobId(), "prepare_context");

        var result = jobService.getJobResult(jwtFor(userId), created.jobId());
        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps()).anySatisfy(step -> {
            assertThat(step.stepName()).isEqualTo("prepare_context");
            assertThat(step.status()).isEqualTo(app.mnema.ai.domain.type.AiJobStepStatus.completed);
        });
    }

    @Test
    void retryFailedJobCreatesTargetedRetryForGenerateCards() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        seedQuota(userId, 2000);

        UUID originalRequestId = UUID.randomUUID();
        AiJobResponse created = jobService.createJob(jwtFor(userId), "token", new CreateAiJobRequest(
                originalRequestId,
                deckId,
                AiJobType.enrich,
                objectMapper.createObjectNode()
                        .put("mode", "generate_cards")
                        .put("input", "Generate animals")
                        .put("provider", "openai"),
                null,
                10,
                null
        ));

        UUID failedCardId = UUID.randomUUID();
        AiJobEntity job = jobRepository.findByJobIdAndUserId(created.jobId(), userId).orElseThrow();
        job.setStatus(AiJobStatus.partial_success);
        job.setCompletedAt(Instant.now());
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", "generate_cards");
        ArrayNode fields = summary.putArray("fields");
        fields.add("front");
        fields.add("back");
        ArrayNode items = summary.putArray("items");
        ObjectNode failedItem = items.addObject();
        failedItem.put("cardId", failedCardId.toString());
        failedItem.put("status", "partial_success");
        failedItem.putArray("completedStages").add("text");
        job.setResultSummary(summary);
        jobRepository.save(job);

        AiJobResponse retry = jobService.retryFailedJob(jwtFor(userId), "token", created.jobId());
        AiJobEntity retryJob = jobRepository.findByJobIdAndUserId(retry.jobId(), userId).orElseThrow();

        assertThat(retryJob.getJobId()).isNotEqualTo(created.jobId());
        assertThat(retryJob.getType()).isEqualTo(AiJobType.enrich);
        assertThat(retryJob.getParamsJson().path("mode").asText()).isEqualTo("missing_fields");
        assertThat(retryJob.getParamsJson().path("retryOfJobId").asText()).isEqualTo(created.jobId().toString());
        assertThat(retryJob.getParamsJson().path("cardIds")).extracting(JsonNode::asText).containsExactly(failedCardId.toString());
        assertThat(retryJob.getParamsJson().path("fields")).extracting(JsonNode::asText).containsExactly("front", "back");
    }

    @Test
    void retryFailedJobRejectsJobsWithoutRetryableItems() {
        UUID userId = UUID.randomUUID();
        seedQuota(userId, 10000);

        AiJobResponse created = jobService.createJob(jwtFor(userId), "token", new CreateAiJobRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                AiJobType.generic,
                objectMapper.createObjectNode().put("mode", "missing_fields"),
                null,
                10,
                null
        ));

        AiJobEntity job = jobRepository.findByJobIdAndUserId(created.jobId(), userId).orElseThrow();
        job.setStatus(AiJobStatus.completed);
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mode", "missing_fields");
        ArrayNode items = summary.putArray("items");
        ObjectNode item = items.addObject();
        item.put("cardId", UUID.randomUUID().toString());
        item.put("status", "completed");
        job.setResultSummary(summary);
        jobRepository.save(job);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> jobService.retryFailedJob(jwtFor(userId), "token", created.jobId()));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void queuedJobsExposeQueueAheadAndEta() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        seedQuota(userId, 5000);

        AiJobResponse first = jobService.createJob(jwtFor(userId), "token", new CreateAiJobRequest(
                UUID.randomUUID(),
                deckId,
                AiJobType.enrich,
                objectMapper.createObjectNode().put("mode", "generate_cards").put("count", 4).put("provider", "openai"),
                null,
                10,
                null
        ));
        AiJobResponse second = jobService.createJob(jwtFor(userId), "token", new CreateAiJobRequest(
                UUID.randomUUID(),
                deckId,
                AiJobType.enrich,
                objectMapper.createObjectNode().put("mode", "generate_cards").put("count", 4).put("provider", "openai"),
                null,
                10,
                null
        ));
        AiJobResponse third = jobService.createJob(jwtFor(userId), "token", new CreateAiJobRequest(
                UUID.randomUUID(),
                deckId,
                AiJobType.enrich,
                objectMapper.createObjectNode().put("mode", "generate_cards").put("count", 4).put("provider", "openai"),
                null,
                10,
                null
        ));

        AiJobEntity firstJob = jobRepository.findByJobIdAndUserId(first.jobId(), userId).orElseThrow();
        firstJob.setStatus(AiJobStatus.processing);
        firstJob.setCreatedAt(Instant.now().minusSeconds(60));
        jobRepository.save(firstJob);

        AiJobEntity secondJob = jobRepository.findByJobIdAndUserId(second.jobId(), userId).orElseThrow();
        secondJob.setStatus(AiJobStatus.processing);
        secondJob.setCreatedAt(Instant.now().minusSeconds(30));
        jobRepository.save(secondJob);

        AiJobEntity thirdJob = jobRepository.findByJobIdAndUserId(third.jobId(), userId).orElseThrow();
        thirdJob.setStatus(AiJobStatus.queued);
        thirdJob.setCreatedAt(Instant.now());
        jobRepository.save(thirdJob);

        AiJobResponse queued = jobService.getJob(jwtFor(userId), third.jobId());

        assertThat(queued.queueAhead()).isEqualTo(2);
        assertThat(queued.estimatedSecondsRemaining()).isNotNull().isPositive();
        assertThat(queued.estimatedCompletionAt()).isNotNull().isAfter(Instant.now().minusSeconds(1));
    }

    @Test
    void preflightReturnsNormalizedPlanEtaAndCost() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        seedQuota(userId, 5000);

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

        ObjectNode params = objectMapper.createObjectNode();
        params.put("mode", " generate_cards ");
        params.put("providerCredentialId", credentialId.toString());
        params.put("provider", " openai ");
        params.put("model", " gpt-4.1-mini ");
        params.put("input", "  Generate fruits  ");
        params.put("count", 4);
        ArrayNode fields = params.putArray("fields");
        fields.add(" Front ");
        fields.add("Back");
        fields.add("Front");
        ObjectNode tts = params.putObject("tts");
        tts.put("enabled", true);
        tts.put("model", " gpt-4o-mini-tts ");

        AiJobPreflightResponse preflight = jobService.preflightJob(jwtFor(userId), new CreateAiJobRequest(
                UUID.randomUUID(),
                deckId,
                AiJobType.enrich,
                params,
                null,
                null,
                null
        ));

        assertThat(preflight.deckId()).isEqualTo(deckId);
        assertThat(preflight.providerCredentialId()).isEqualTo(credentialId);
        assertThat(preflight.providerAlias()).isEqualTo("Primary");
        assertThat(preflight.provider()).isEqualTo("openai");
        assertThat(preflight.model()).isEqualTo("gpt-4.1-mini");
        assertThat(preflight.mode()).isEqualTo("generate_cards");
        assertThat(preflight.normalizedParams().path("input").asText()).isEqualTo("Generate fruits");
        assertThat(preflight.normalizedParams().path("fields")).extracting(JsonNode::asText).containsExactly("Front", "Back", "Front");
        assertThat(preflight.fields()).containsExactly("Front", "Back");
        assertThat(preflight.plannedStages()).containsExactly("text", "audio");
        assertThat(preflight.items()).hasSize(4);
        assertThat(preflight.items()).allSatisfy(item -> {
            assertThat(item.itemType()).isEqualTo("new_card");
            assertThat(item.plannedStages()).containsExactly("text", "audio");
        });
        assertThat(preflight.cost()).isNotNull();
        assertThat(preflight.cost().estimatedInputTokens()).isNotNull().isPositive();
        assertThat(preflight.estimatedSecondsRemaining()).isNotNull().isPositive();
        assertThat(preflight.estimatedCompletionAt()).isNotNull().isAfter(Instant.now().minusSeconds(1));
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

    private int estimateTokens(AiJobType type, JsonNode params, String provider, String model) {
        AiJobCostEstimator.PlannedCost planned = costEstimator.estimatePlanned(type, params, provider, model);
        return Math.max(1,
                (planned.inputTokens() == null ? 0 : planned.inputTokens())
                        + (planned.outputTokens() == null ? 0 : planned.outputTokens()));
    }
}
