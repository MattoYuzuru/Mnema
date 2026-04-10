package app.mnema.ai.controller;

import app.mnema.ai.controller.dto.AiImportGenerateRequest;
import app.mnema.ai.controller.dto.AiImportPreviewRequest;
import app.mnema.ai.controller.dto.AiJobResponse;
import app.mnema.ai.controller.dto.AiJobResultResponse;
import app.mnema.ai.controller.dto.AiJobStepResponse;
import app.mnema.ai.controller.dto.AiRuntimeCapabilitiesResponse;
import app.mnema.ai.controller.dto.AiProviderResponse;
import app.mnema.ai.controller.dto.CreateAiJobRequest;
import app.mnema.ai.controller.dto.CreateAiProviderRequest;
import app.mnema.ai.controller.dto.UpdateAiProviderStatusRequest;
import app.mnema.ai.domain.type.AiJobStatus;
import app.mnema.ai.domain.type.AiJobStepStatus;
import app.mnema.ai.domain.type.AiJobType;
import app.mnema.ai.domain.type.AiProviderStatus;
import app.mnema.ai.service.AiImportService;
import app.mnema.ai.service.AiJobService;
import app.mnema.ai.service.AiProviderService;
import app.mnema.ai.service.AiRuntimeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AiJobService jobService;

    @Mock
    private AiProviderService providerService;

    @Mock
    private AiImportService importService;

    @Mock
    private AiRuntimeService runtimeService;

    @Mock
    private Jwt jwt;

    @Test
    void jobControllerDelegatesAllEndpoints() {
        AiJobController controller = new AiJobController(jobService);
        UUID jobId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        CreateAiJobRequest request = new CreateAiJobRequest(UUID.randomUUID(), deckId, AiJobType.generic, objectMapper.createObjectNode(), null, null, null);
        AiJobResponse response = jobResponse(deckId);
        AiJobResultResponse result = new AiJobResultResponse(
                jobId,
                AiJobStatus.completed,
                objectMapper.createObjectNode().put("ok", true),
                List.of(new AiJobStepResponse("generate_content", AiJobStepStatus.completed, Instant.now(), Instant.now(), null))
        );
        when(jwt.getTokenValue()).thenReturn("access-token");
        when(jobService.createJob(jwt, "access-token", request)).thenReturn(response);
        when(jobService.listJobs(jwt, deckId, 25)).thenReturn(List.of(response));
        when(jobService.getJob(jwt, jobId)).thenReturn(response);
        when(jobService.getJobResult(jwt, jobId)).thenReturn(result);
        when(jobService.cancelJob(jwt, jobId)).thenReturn(response);

        assertThat(controller.create(jwt, request)).isEqualTo(response);
        assertThat(controller.list(jwt, deckId, 25)).containsExactly(response);
        assertThat(controller.getJob(jwt, jobId)).isEqualTo(response);
        assertThat(controller.getResults(jwt, jobId)).isEqualTo(result);
        assertThat(controller.cancel(jwt, jobId)).isEqualTo(response);
    }

    @Test
    void providerImportAndRuntimeControllersDelegate() {
        AiProviderController providerController = new AiProviderController(providerService);
        AiImportController importController = new AiImportController(importService);
        AiRuntimeController runtimeController = new AiRuntimeController(runtimeService);
        UUID deckId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID sourceMediaId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        AiProviderResponse providerResponse = new AiProviderResponse(credentialId, "openai", "Primary", AiProviderStatus.active, Instant.now(), null, Instant.now());
        AiJobResponse jobResponse = jobResponse(deckId);
        AiRuntimeCapabilitiesResponse capabilities = new AiRuntimeCapabilitiesResponse(
                "hybrid",
                "openai",
                new AiRuntimeCapabilitiesResponse.OllamaRuntimeResponse(true, true, "http://localhost:11434", List.of(), List.of("alloy")),
                List.of(new AiRuntimeCapabilitiesResponse.AiProviderCapability("openai", "OpenAI", true, true, true, true, true, true, false))
        );
        CreateAiProviderRequest createProviderRequest = new CreateAiProviderRequest("openai", "Primary", "secret");
        UpdateAiProviderStatusRequest updateRequest = new UpdateAiProviderStatusRequest(true);
        AiImportPreviewRequest previewRequest = new AiImportPreviewRequest(requestId, deckId, sourceMediaId, providerId, "openai", "gpt", "pdf", "utf-8", "en", null, null);
        AiImportGenerateRequest generateRequest = new AiImportGenerateRequest(requestId, deckId, sourceMediaId, List.of("Front"), 3, providerId, "openai", "gpt", "pdf", "utf-8", "en", null, null, null, null, null);

        when(jwt.getTokenValue()).thenReturn("access-token");
        when(providerService.createCredential(jwt, createProviderRequest)).thenReturn(providerResponse);
        when(providerService.listActiveCredentials(jwt)).thenReturn(List.of(providerResponse));
        when(providerService.updateStatus(jwt, credentialId, updateRequest)).thenReturn(providerResponse);
        when(importService.createPreviewJob(jwt, "access-token", previewRequest)).thenReturn(jobResponse);
        when(importService.createGenerateJob(jwt, "access-token", generateRequest)).thenReturn(jobResponse);
        when(runtimeService.getCapabilities()).thenReturn(capabilities);

        assertThat(providerController.create(jwt, createProviderRequest)).isEqualTo(providerResponse);
        assertThat(providerController.list(jwt)).containsExactly(providerResponse);
        assertThat(providerController.updateStatus(jwt, credentialId, updateRequest)).isEqualTo(providerResponse);
        providerController.delete(jwt, credentialId);
        verify(providerService).deleteCredential(jwt, credentialId);

        assertThat(importController.preview(jwt, previewRequest)).isEqualTo(jobResponse);
        assertThat(importController.generate(jwt, generateRequest)).isEqualTo(jobResponse);
        assertThat(runtimeController.capabilities()).isEqualTo(capabilities);
    }

    private AiJobResponse jobResponse(UUID deckId) {
        return new AiJobResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                deckId,
                AiJobType.generic,
                AiJobStatus.queued,
                0,
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                null,
                "openai",
                null,
                "gpt-4o-mini",
                null,
                0,
                0,
                null,
                null,
                null,
                null
        );
    }
}
