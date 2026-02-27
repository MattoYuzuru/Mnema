package app.mnema.ai.provider.gemini;

import app.mnema.ai.client.core.CoreApiClient;
import app.mnema.ai.client.media.MediaApiClient;
import app.mnema.ai.repository.AiProviderCredentialRepository;
import app.mnema.ai.service.AiImportContentService;
import app.mnema.ai.service.AudioChunkingService;
import app.mnema.ai.service.CardNoveltyService;
import app.mnema.ai.vault.SecretVault;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
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
        GeminiJobProcessor processor = new GeminiJobProcessor(
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
                200_000
        );

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

    private static String invokeString(Method method, Object target) {
        try {
            return (String) method.invoke(target);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
