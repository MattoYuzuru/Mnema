package app.mnema.ai.provider.openai;

import app.mnema.ai.client.core.CoreApiClient;
import app.mnema.ai.client.media.MediaApiClient;
import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.type.AiJobStatus;
import app.mnema.ai.domain.type.AiJobType;
import app.mnema.ai.repository.AiProviderCredentialRepository;
import app.mnema.ai.service.AiImportContentService;
import app.mnema.ai.service.AiJobExecutionService;
import app.mnema.ai.service.AudioChunkingService;
import app.mnema.ai.service.CardNoveltyService;
import app.mnema.ai.vault.SecretVault;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OpenAiJobProcessorTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void parseRetryAfterMessageReadsSeconds() {
        String message = "Please retry in 12.5s.";

        Long parsed = OpenAiJobProcessor.parseRetryAfterMessage(message);

        assertThat(parsed).isEqualTo(12500L);
    }

    @Test
    void parseRetryAfterMessageReturnsNullWhenMissing() {
        assertThat(OpenAiJobProcessor.parseRetryAfterMessage("No retry hint")).isNull();
        assertThat(OpenAiJobProcessor.parseRetryAfterMessage("")).isNull();
        assertThat(OpenAiJobProcessor.parseRetryAfterMessage(null)).isNull();
    }

    @Test
    void resolveEnhanceModeUsesMissingFieldsForVisualAndTextActions() {
        ObjectNode params = OBJECT_MAPPER.createObjectNode();
        params.putArray("actions").add("image");

        String mode = OpenAiJobProcessor.resolveEnhanceMode(params);

        assertThat(mode).isEqualTo("missing_fields");
    }

    @Test
    void resolveEnhanceModeUsesMissingAudioForAudioActions() {
        ObjectNode params = OBJECT_MAPPER.createObjectNode();
        params.putArray("actions").add("missing_audio");

        String mode = OpenAiJobProcessor.resolveEnhanceMode(params);

        assertThat(mode).isEqualTo("missing_audio");
    }

    @Test
    void resolveEnhanceModeFallsBackToMissingFieldsWhenActionsEmpty() {
        ObjectNode params = OBJECT_MAPPER.createObjectNode();

        String mode = OpenAiJobProcessor.resolveEnhanceMode(params);

        assertThat(mode).isEqualTo("missing_fields");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveExecutionPlanForGenerateCardsAppliesChangesLast() throws Exception {
        OpenAiJobProcessor processor = createProcessor();
        AiJobEntity job = createJob(OBJECT_MAPPER.createObjectNode().put("mode", "generate_cards"), AiJobType.generic);

        Method resolveExecutionPlan = OpenAiJobProcessor.class.getDeclaredMethod("resolveExecutionPlan", AiJobEntity.class);
        resolveExecutionPlan.setAccessible(true);
        List<String> plan = (List<String>) resolveExecutionPlan.invoke(processor, job);

        assertThat(plan).containsExactly(
                "prepare_context",
                "generate_content",
                "generate_media",
                "generate_audio",
                "apply_changes"
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveExecutionPlanForMissingFieldsUsesSingleApplyStage() throws Exception {
        OpenAiJobProcessor processor = createProcessor();
        AiJobEntity job = createJob(OBJECT_MAPPER.createObjectNode().put("mode", "missing_fields"), AiJobType.generic);

        Method resolveExecutionPlan = OpenAiJobProcessor.class.getDeclaredMethod("resolveExecutionPlan", AiJobEntity.class);
        resolveExecutionPlan.setAccessible(true);
        List<String> plan = (List<String>) resolveExecutionPlan.invoke(processor, job);

        assertThat(plan).containsExactly(
                "prepare_context",
                "generate_content",
                "apply_changes"
        );
    }

    private static OpenAiJobProcessor createProcessor() {
        return new OpenAiJobProcessor(
                mock(OpenAiClient.class),
                new OpenAiProps(
                        "https://api.openai.com/v1",
                        "",
                        "gpt-4.1-mini",
                        "gpt-4o-mini-tts",
                        "alloy",
                        "mp3",
                        "gpt-4o-mini-transcribe",
                        "gpt-image-1-mini",
                        "1024x1024",
                        "low",
                        "natural",
                        "png",
                        "sora-2",
                        5,
                        "720p",
                        60,
                        5,
                        2_000L,
                        30_000L
                ),
                mock(SecretVault.class),
                mock(AiProviderCredentialRepository.class),
                mock(MediaApiClient.class),
                mock(AiImportContentService.class),
                mock(AudioChunkingService.class),
                mock(CoreApiClient.class),
                mock(CardNoveltyService.class),
                OBJECT_MAPPER,
                mock(AiJobExecutionService.class),
                200_000
        );
    }

    private static AiJobEntity createJob(JsonNode params, AiJobType type) {
        AiJobEntity job = new AiJobEntity();
        job.setJobId(UUID.randomUUID());
        job.setRequestId(UUID.randomUUID());
        job.setUserId(UUID.randomUUID());
        job.setType(type);
        job.setStatus(AiJobStatus.queued);
        job.setProgress(0);
        job.setParamsJson(params);
        return job;
    }
}
