package app.mnema.ai.provider.gemini;

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
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GeminiJobProcessorTest {

    @Test
    void parseRetryAfterMessageReadsSeconds() {
        String message = "Please retry in 37.588078075s.";

        Long parsed = GeminiJobProcessor.parseRetryAfterMessage(message);

        assertThat(parsed).isEqualTo(37588L);
    }

    @Test
    void parseRetryAfterMessageReturnsNullWhenMissing() {
        assertThat(GeminiJobProcessor.parseRetryAfterMessage("No retry hint")).isNull();
        assertThat(GeminiJobProcessor.parseRetryAfterMessage("")).isNull();
        assertThat(GeminiJobProcessor.parseRetryAfterMessage(null)).isNull();
    }

    @Test
    void normalizeLegacyTtsModelAliasConvertsNonPreviewModel() {
        String normalized = GeminiJobProcessor.normalizeLegacyTtsModelAlias("gemini-2.5-flash-tts");

        assertThat(normalized).isEqualTo("gemini-2.5-flash-preview-tts");
    }

    @Test
    void normalizeLegacyTtsModelAliasKeepsPreviewModel() {
        String normalized = GeminiJobProcessor.normalizeLegacyTtsModelAlias("gemini-2.5-flash-preview-tts");

        assertThat(normalized).isEqualTo("gemini-2.5-flash-preview-tts");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveTtsMappingsDefaultsToAllAudioFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        GeminiJobProcessor processor = createProcessor(mapper);

        CoreApiClient.CoreTemplateResponse template = new CoreApiClient.CoreTemplateResponse(
                UUID.randomUUID(),
                1,
                1,
                "template",
                "desc",
                NullNode.getInstance(),
                NullNode.getInstance(),
                List.of(
                        new CoreApiClient.CoreFieldTemplate(UUID.randomUUID(), "front", "Front", "text", true, true, 1),
                        new CoreApiClient.CoreFieldTemplate(UUID.randomUUID(), "audio1", "Audio 1", "audio", false, false, 2),
                        new CoreApiClient.CoreFieldTemplate(UUID.randomUUID(), "audio2", "Audio 2", "audio", false, false, 3)
                )
        );

        Method resolveMappings = GeminiJobProcessor.class.getDeclaredMethod(
                "resolveTtsMappings",
                com.fasterxml.jackson.databind.JsonNode.class,
                List.class,
                List.class,
                CoreApiClient.CoreTemplateResponse.class
        );
        resolveMappings.setAccessible(true);
        List<Object> mappings = (List<Object>) resolveMappings.invoke(
                processor,
                mapper.createObjectNode(),
                List.of("front"),
                List.of("audio1", "audio2"),
                template
        );

        Method sourceField = mappings.getFirst().getClass().getDeclaredMethod("sourceField");
        Method targetField = mappings.getFirst().getClass().getDeclaredMethod("targetField");
        sourceField.setAccessible(true);
        targetField.setAccessible(true);

        List<String> sources = mappings.stream().map(mapping -> invokeString(sourceField, mapping)).toList();
        List<String> targets = mappings.stream().map(mapping -> invokeString(targetField, mapping)).toList();

        assertThat(mappings).hasSize(2);
        assertThat(sources).containsOnly("front");
        assertThat(targets).containsExactly("audio1", "audio2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveExecutionPlanForGenerateCardsAppliesChangesLast() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        GeminiJobProcessor processor = createProcessor(mapper);
        AiJobEntity job = createJob(mapper.createObjectNode().put("mode", "generate_cards"), AiJobType.generic);

        Method resolveExecutionPlan = GeminiJobProcessor.class.getDeclaredMethod("resolveExecutionPlan", AiJobEntity.class);
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
    void resolveExecutionPlanForMissingFieldsCollapsesToSingleApplyStage() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        GeminiJobProcessor processor = createProcessor(mapper);
        AiJobEntity job = createJob(mapper.createObjectNode().put("mode", "missing_fields"), AiJobType.generic);

        Method resolveExecutionPlan = GeminiJobProcessor.class.getDeclaredMethod("resolveExecutionPlan", AiJobEntity.class);
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
        ObjectMapper mapper = new ObjectMapper();
        GeminiJobProcessor processor = createProcessor(mapper);
        UUID cardId = UUID.randomUUID();
        CoreApiClient.CoreUserCardResponse card = new CoreApiClient.CoreUserCardResponse(
                cardId,
                null,
                false,
                mapper.createObjectNode().put("front", "Hola")
        );

        Object mediaResult = createInnerRecord(
                GeminiJobProcessor.class,
                "MediaApplyResult",
                new Class<?>[]{int.class, Set.class, int.class},
                new Object[]{1, Set.of(cardId), 1}
        );
        Object ttsResult = createInnerRecord(
                GeminiJobProcessor.class,
                "TtsApplyResult",
                new Class<?>[]{int.class, int.class, int.class, Set.class, String.class, String.class},
                new Object[]{1, 1, 12, Set.of(cardId), "gemini-tts", null}
        );

        Method buildEnhanceItems = GeminiJobProcessor.class.getDeclaredMethod("buildEnhanceItems", List.class, mediaResult.getClass(), ttsResult.getClass());
        buildEnhanceItems.setAccessible(true);
        ArrayNode items = (ArrayNode) buildEnhanceItems.invoke(processor, List.of(card), mediaResult, ttsResult);

        assertThat(items).hasSize(1);
        JsonNode item = items.get(0);
        assertThat(item.path("cardId").asText()).isEqualTo(cardId.toString());
        assertThat(item.path("preview").asText()).isEqualTo("Hola");
        assertThat(item.path("status").asText()).isEqualTo("completed");
        assertThat(item.path("completedStages")).extracting(JsonNode::asText).containsExactly("content", "tts");
    }

    private static GeminiJobProcessor createProcessor(ObjectMapper mapper) {
        return new GeminiJobProcessor(
                mock(GeminiClient.class),
                new GeminiProps(
                        "https://generativelanguage.googleapis.com",
                        "gemini-2.0-flash",
                        "gemini-2.5-flash-preview-tts",
                        "Kore",
                        "audio/wav",
                        "gemini-2.0-flash",
                        "gemini-2.5-flash-image",
                        10,
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
                mapper,
                mock(AiJobExecutionService.class),
                200_000
        );
    }

    private static AiJobEntity createJob(com.fasterxml.jackson.databind.JsonNode params, AiJobType type) {
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

    private static String invokeString(Method method, Object target) {
        try {
            return (String) method.invoke(target);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
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
