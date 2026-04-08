package app.mnema.ai.service;

import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.entity.AiProviderCredentialEntity;
import app.mnema.ai.domain.type.AiProviderStatus;
import app.mnema.ai.repository.AiProviderCredentialRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiJobProcessorRouterTest {

    @Mock
    private AiProviderCredentialRepository credentialRepository;

    @Mock
    private AiProviderProcessor stubProcessor;

    @Mock
    private AiProviderProcessor openAiProcessor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiJobProcessorRouter router;

    @BeforeEach
    void setUp() {
        when(stubProcessor.provider()).thenReturn("stub");
        when(openAiProcessor.provider()).thenReturn("openai");
        router = new AiJobProcessorRouter(List.of(stubProcessor, openAiProcessor), credentialRepository, objectMapper, " local-openai ");
    }

    @Test
    void usesCredentialProviderWhenCredentialActive() {
        UUID credentialId = UUID.randomUUID();
        AiJobEntity job = job(params("providerCredentialId", credentialId.toString()));
        AiProviderCredentialEntity credential = new AiProviderCredentialEntity();
        credential.setProvider("openai");
        credential.setStatus(AiProviderStatus.active);
        when(credentialRepository.findByIdAndUserId(credentialId, job.getUserId())).thenReturn(Optional.of(credential));
        when(openAiProcessor.process(job)).thenReturn(new AiJobProcessingResult(null, "openai", "model", 1, 2, BigDecimal.ZERO, null));

        AiJobProcessingResult result = router.process(job);

        assertThat(result.provider()).isEqualTo("openai");
    }

    @Test
    void fallsBackToNormalizedProviderOrDefault() {
        AiJobEntity paramJob = job(params("provider", "ollama"));
        when(openAiProcessor.process(paramJob)).thenReturn(new AiJobProcessingResult(null, "openai", "model", null, null, BigDecimal.ZERO, null));

        assertThat(router.process(paramJob).provider()).isEqualTo("openai");

        AiJobEntity defaultJob = job(objectMapper.createObjectNode());
        when(openAiProcessor.process(defaultJob)).thenReturn(new AiJobProcessingResult(null, "openai", "model", null, null, BigDecimal.ZERO, null));

        assertThat(router.process(defaultJob).provider()).isEqualTo("openai");
    }

    @Test
    void rejectsInactiveCredentialAndInvalidUuid() {
        UUID credentialId = UUID.randomUUID();
        AiProviderCredentialEntity inactive = new AiProviderCredentialEntity();
        inactive.setStatus(AiProviderStatus.inactive);

        AiJobEntity inactiveJob = job(params("providerCredentialId", credentialId.toString()));
        when(credentialRepository.findByIdAndUserId(credentialId, inactiveJob.getUserId())).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> router.process(inactiveJob))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Provider credential is inactive");

        assertThatThrownBy(() -> router.process(job(params("providerCredentialId", "bad-uuid"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid providerCredentialId");
    }

    private AiJobEntity job(ObjectNode params) {
        AiJobEntity job = new AiJobEntity();
        job.setJobId(UUID.randomUUID());
        job.setUserId(UUID.randomUUID());
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        job.setParamsJson(params);
        return job;
    }

    private ObjectNode params(String key, String value) {
        return objectMapper.createObjectNode().put(key, value);
    }
}
