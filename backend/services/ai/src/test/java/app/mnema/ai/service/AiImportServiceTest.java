package app.mnema.ai.service;

import app.mnema.ai.controller.dto.AiImportGenerateRequest;
import app.mnema.ai.controller.dto.AiImportPreviewRequest;
import app.mnema.ai.controller.dto.AiJobResponse;
import app.mnema.ai.controller.dto.CreateAiJobRequest;
import app.mnema.ai.domain.type.AiJobStatus;
import app.mnema.ai.domain.type.AiJobType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiImportServiceTest {

    @Mock
    private AiJobService jobService;

    @Mock
    private Jwt jwt;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiImportService service;

    @BeforeEach
    void setUp() {
        service = new AiImportService(jobService, objectMapper);
    }

    @Test
    void createPreviewJobBuildsTrimmedParams() {
        UUID requestId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID sourceMediaId = UUID.randomUUID();
        UUID providerCredentialId = UUID.randomUUID();
        ObjectNode stt = objectMapper.createObjectNode().put("enabled", true);
        AiJobResponse response = new AiJobResponse(
                UUID.randomUUID(), requestId, deckId, AiJobType.generic, AiJobStatus.queued, 0,
                Instant.now(), Instant.now(), null, null, null, providerCredentialId, "openai", null, "gpt", null, 0, 0
        );
        when(jobService.createJob(eq(jwt), eq("access-token"), org.mockito.ArgumentMatchers.any(CreateAiJobRequest.class)))
                .thenReturn(response);

        var actual = service.createPreviewJob(jwt, "access-token", new AiImportPreviewRequest(
                requestId,
                deckId,
                sourceMediaId,
                providerCredentialId,
                " openai ",
                " gpt-4o-mini ",
                " pdf ",
                " utf-8 ",
                " en ",
                " summarize ",
                stt
        ));

        assertThat(actual).isEqualTo(response);
        ArgumentCaptor<CreateAiJobRequest> captor = ArgumentCaptor.forClass(CreateAiJobRequest.class);
        verify(jobService).createJob(eq(jwt), eq("access-token"), captor.capture());
        CreateAiJobRequest request = captor.getValue();
        assertThat(request.type()).isEqualTo(AiJobType.generic);
        assertThat(request.params().path("mode").asText()).isEqualTo("import_preview");
        assertThat(request.params().path("sourceMediaId").asText()).isEqualTo(sourceMediaId.toString());
        assertThat(request.params().path("providerCredentialId").asText()).isEqualTo(providerCredentialId.toString());
        assertThat(request.params().path("provider").asText()).isEqualTo("openai");
        assertThat(request.params().path("model").asText()).isEqualTo("gpt-4o-mini");
        assertThat(request.params().path("sourceType").asText()).isEqualTo("pdf");
        assertThat(request.params().path("encoding").asText()).isEqualTo("utf-8");
        assertThat(request.params().path("language").asText()).isEqualTo("en");
        assertThat(request.params().path("instructions").asText()).isEqualTo("summarize");
        assertThat(request.params().path("stt").path("enabled").asBoolean()).isTrue();
    }

    @Test
    void createGenerateJobFiltersFieldsAndAddsOptionalNodes() {
        UUID requestId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID sourceMediaId = UUID.randomUUID();
        ObjectNode tts = objectMapper.createObjectNode().put("enabled", true);
        ObjectNode image = objectMapper.createObjectNode().put("enabled", true);
        ObjectNode video = objectMapper.createObjectNode().put("enabled", false);
        AiJobResponse response = new AiJobResponse(
                UUID.randomUUID(), requestId, deckId, AiJobType.generic, AiJobStatus.queued, 0,
                Instant.now(), Instant.now(), null, null, null, null, "gemini", null, "model", null, 0, 0
        );
        when(jobService.createJob(eq(jwt), eq("access-token"), org.mockito.ArgumentMatchers.any(CreateAiJobRequest.class)))
                .thenReturn(response);

        service.createGenerateJob(jwt, "access-token", new AiImportGenerateRequest(
                requestId,
                deckId,
                sourceMediaId,
                Arrays.asList(" Front ", null, "Back", "Front", " "),
                12,
                null,
                "gemini",
                null,
                null,
                null,
                null,
                null,
                null,
                tts,
                image,
                video
        ));

        ArgumentCaptor<CreateAiJobRequest> captor = ArgumentCaptor.forClass(CreateAiJobRequest.class);
        verify(jobService).createJob(eq(jwt), eq("access-token"), captor.capture());
        CreateAiJobRequest request = captor.getValue();
        assertThat(request.params().path("mode").asText()).isEqualTo("import_generate");
        assertThat(request.params().path("count").asInt()).isEqualTo(12);
        assertThat(request.params().path("fields")).extracting(node -> node.asText()).containsExactly("Front", "Back");
        assertThat(request.params().path("tts").path("enabled").asBoolean()).isTrue();
        assertThat(request.params().path("image").path("enabled").asBoolean()).isTrue();
        assertThat(request.params().path("video").path("enabled").asBoolean()).isFalse();
    }
}
