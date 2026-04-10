package app.mnema.ai.provider.grok;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GrokJobProcessorTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void resolveExecutionPlanForGenerateCardsAppliesChangesLast() throws Exception {
        GrokJobProcessor processor = createProcessor();
        AiJobEntity job = createJob(OBJECT_MAPPER.createObjectNode().put("mode", "generate_cards"), AiJobType.generic);

        Method resolveExecutionPlan = GrokJobProcessor.class.getDeclaredMethod("resolveExecutionPlan", AiJobEntity.class);
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
        GrokJobProcessor processor = createProcessor();
        AiJobEntity job = createJob(OBJECT_MAPPER.createObjectNode().put("mode", "missing_fields"), AiJobType.generic);

        Method resolveExecutionPlan = GrokJobProcessor.class.getDeclaredMethod("resolveExecutionPlan", AiJobEntity.class);
        resolveExecutionPlan.setAccessible(true);
        List<String> plan = (List<String>) resolveExecutionPlan.invoke(processor, job);

        assertThat(plan).containsExactly(
                "prepare_context",
                "generate_content",
                "apply_changes"
        );
    }

    @Test
    void buildEnhanceItemsUsesCanonicalItemContract() throws Exception {
        GrokJobProcessor processor = createProcessor();
        UUID cardId = UUID.randomUUID();
        CoreApiClient.CoreUserCardResponse card = new CoreApiClient.CoreUserCardResponse(
                cardId,
                null,
                false,
                OBJECT_MAPPER.createObjectNode().put("front", "Hola")
        );

        Object mediaResult = createInnerRecord(
                GrokJobProcessor.class,
                "MediaApplyResult",
                new Class<?>[]{int.class, Set.class, int.class, int.class},
                new Object[]{1, Set.of(cardId), 1, 0}
        );
        Object ttsResult = createInnerRecord(
                GrokJobProcessor.class,
                "TtsApplyResult",
                new Class<?>[]{int.class, int.class, int.class, Set.class, String.class, String.class},
                new Object[]{1, 1, 12, Set.of(cardId), "grok-voice-mini", null}
        );

        Method buildEnhanceItems = GrokJobProcessor.class.getDeclaredMethod("buildEnhanceItems", List.class, mediaResult.getClass(), ttsResult.getClass());
        buildEnhanceItems.setAccessible(true);
        ArrayNode items = (ArrayNode) buildEnhanceItems.invoke(processor, List.of(card), mediaResult, ttsResult);

        assertThat(items).hasSize(1);
        JsonNode item = items.get(0);
        assertThat(item.path("cardId").asText()).isEqualTo(cardId.toString());
        assertThat(item.path("preview").asText()).isEqualTo("Hola");
        assertThat(item.path("status").asText()).isEqualTo("completed");
        assertThat(item.path("completedStages")).extracting(JsonNode::asText).containsExactly("content", "tts");
    }

    private static GrokJobProcessor createProcessor() {
        return new GrokJobProcessor(
                mock(GrokClient.class),
                new GrokProps(
                        "https://api.x.ai/v1",
                        "grok-4-fast-non-reasoning",
                        "grok-voice-mini",
                        "alloy",
                        "mp3",
                        "grok-transcribe",
                        "grok-image-1",
                        "1024x1024",
                        "medium",
                        "natural",
                        "png",
                        "grok-video-1",
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

    private static Object createInnerRecord(Class<?> owner,
                                            String simpleName,
                                            Class<?>[] parameterTypes,
                                            Object[] args) throws Exception {
        Class<?> type = findInnerClass(owner, simpleName);
        Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    private static Class<?> findInnerClass(Class<?> owner, String simpleName) {
        for (Class<?> candidate : owner.getDeclaredClasses()) {
            if (candidate.getSimpleName().equals(simpleName)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Inner class not found: " + simpleName);
    }
}
