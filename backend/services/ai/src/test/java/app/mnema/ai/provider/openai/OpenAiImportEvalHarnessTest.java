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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiImportEvalHarnessTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarioCases")
    void importGenerateHandlesEvalScenarios(ScenarioCase scenario) throws Exception {
        OpenAiClient openAiClient = mock(OpenAiClient.class);
        CoreApiClient coreApiClient = mock(CoreApiClient.class);
        AiImportContentService importContentService = mock(AiImportContentService.class);
        AudioChunkingService audioChunkingService = mock(AudioChunkingService.class);
        CardNoveltyService noveltyService = new CardNoveltyService(coreApiClient);
        OpenAiJobProcessor processor = new OpenAiJobProcessor(
                openAiClient,
                new OpenAiProps(
                        "https://api.openai.com/v1",
                        "",
                        "qwen3:4b",
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
                        12,
                        5,
                        2_000L,
                        30_000L,
                        10_000L,
                        600_000L
                ),
                mock(SecretVault.class),
                mock(AiProviderCredentialRepository.class),
                mock(MediaApiClient.class),
                importContentService,
                audioChunkingService,
                coreApiClient,
                noveltyService,
                OBJECT_MAPPER,
                mock(AiJobExecutionService.class),
                200_000
        );

        UUID deckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        UUID sourceMediaId = UUID.randomUUID();
        ObjectNode params = OBJECT_MAPPER.createObjectNode();
        params.put("__skipStepTracking", true);
        params.put("provider", scenario.provider());
        params.put("model", scenario.model());
        params.put("mode", "import_generate");
        params.put("count", scenario.items().size());
        params.put("sourceMediaId", sourceMediaId.toString());
        params.putObject("tts").put("enabled", false);
        params.putArray("fields").add("term").add("translation").add("example");

        AiJobEntity job = createJob(params, AiJobType.generic);
        job.setDeckId(deckId);
        job.setUserAccessToken("token");

        when(importContentService.loadSource(any(), any()))
                .thenReturn(new AiImportContentService.ImportSourcePayload(new byte[] {1, 2, 3}, scenario.mimeType(), 64, false));

        if ("audio_stt".equals(scenario.sourceKind())) {
            when(audioChunkingService.prepareChunks(any(), eq(scenario.mimeType())))
                    .thenReturn(new AudioChunkingService.AudioChunkingResult(
                            List.of(
                                    new AudioChunkingService.AudioChunk(new byte[] {1}, "audio/wav", true),
                                    new AudioChunkingService.AudioChunk(new byte[] {2}, "audio/wav", true)
                            ),
                            42,
                            2
                    ));
            List<String> transcriptChunks = splitTranscriptChunks(buildSourceText(scenario.items()), 2);
            AtomicInteger transcriptCall = new AtomicInteger();
            when(openAiClient.createTranscription(any(), any())).thenAnswer(invocation ->
                    transcriptChunks.get(Math.min(transcriptCall.getAndIncrement(), transcriptChunks.size() - 1))
            );
        } else {
            when(importContentService.extractText(any(), any(), any()))
                    .thenReturn(new AiImportContentService.ImportTextPayload(
                            buildSourceText(scenario.items()),
                            scenario.mimeType(),
                            64,
                            false,
                            256,
                            "text/plain".equals(scenario.mimeType()) ? "utf-8" : null,
                            500,
                            scenario.extraction(),
                            "application/pdf".equals(scenario.mimeType()) ? 1 : null,
                            "ocr".equals(scenario.extraction()) ? 1 : null,
                            null,
                            null
                    ));
        }

        when(coreApiClient.getUserDeck(deckId, "token"))
                .thenReturn(new CoreApiClient.CoreUserDeckResponse(deckId, publicDeckId, 1, 1));
        when(coreApiClient.getPublicDeck(publicDeckId, 1))
                .thenReturn(new CoreApiClient.CorePublicDeckResponse(publicDeckId, 1, UUID.randomUUID(), "Legal", "legal english", "en", templateId, 1));
        when(coreApiClient.getTemplate(templateId, 1, "token"))
                .thenReturn(new CoreApiClient.CoreTemplateResponse(
                        templateId,
                        1,
                        1,
                        "Basic",
                        "",
                        null,
                        null,
                        List.of(
                                new CoreApiClient.CoreFieldTemplate(UUID.randomUUID(), "term", "Term", "text", true, true, 0),
                                new CoreApiClient.CoreFieldTemplate(UUID.randomUUID(), "translation", "Translation", "text", true, false, 1),
                                new CoreApiClient.CoreFieldTemplate(UUID.randomUUID(), "example", "Example", "text", true, false, 2)
                        )
                ));
        when(coreApiClient.getUserCards(deckId, 1, 200, "token"))
                .thenReturn(new CoreApiClient.CoreUserCardPage(List.of()));

        AtomicInteger auditCalls = new AtomicInteger();
        when(openAiClient.createResponse(any(), any())).thenAnswer(invocation -> {
            OpenAiResponseRequest request = invocation.getArgument(1);
            String formatName = request.responseFormat().path("name").asText();
            JsonNode body;
            if ("mnema_source_normalization".equals(formatName)) {
                ObjectNode json = OBJECT_MAPPER.createObjectNode();
                var items = json.putArray("items");
                for (int i = 0; i < scenario.items().size(); i++) {
                    ScenarioItem fixture = scenario.items().get(i);
                    ObjectNode item = items.addObject();
                    item.put("sourceIndex", i + 1);
                    item.put("normalizedText", fixture.expectedTerm());
                }
                body = json;
            } else if ("mnema_cards".equals(formatName)) {
                ObjectNode json = OBJECT_MAPPER.createObjectNode();
                var cards = json.putArray("cards");
                for (int i = 0; i < scenario.items().size(); i++) {
                    ScenarioItem fixture = scenario.items().get(i);
                    ObjectNode card = cards.addObject();
                    card.put("sourceIndex", i + 1);
                    card.put("sourceText", scenario.expectSourceNormalization() ? fixture.expectedTerm() : fixture.sourceText());
                    ObjectNode fields = card.putObject("fields");
                    fields.put("term", scenario.expectSourceNormalization() ? fixture.expectedTerm() : fixture.sourceText());
                    fields.put("translation", fixture.badTranslation());
                    fields.put("example", "Example " + (i + 1));
                }
                body = json;
            } else if ("mnema_draft_audit".equals(formatName)) {
                ObjectNode json = OBJECT_MAPPER.createObjectNode();
                var items = json.putArray("items");
                boolean repairedPass = auditCalls.getAndIncrement() > 0;
                for (int i = 0; i < scenario.items().size(); i++) {
                    ScenarioItem fixture = scenario.items().get(i);
                    ObjectNode item = items.addObject();
                    item.put("draftIndex", i);
                    if (repairedPass) {
                        item.put("decision", "accept");
                        item.put("summary", "fixed");
                        item.putArray("issues");
                        item.putArray("focusFields");
                    } else {
                        item.put("decision", "repair");
                        item.put("summary", fixture.issue());
                        item.putArray("issues").add(fixture.issue());
                        item.putArray("focusFields").add("translation");
                    }
                }
                body = json;
            } else if ("mnema_draft_repair".equals(formatName)) {
                ObjectNode json = OBJECT_MAPPER.createObjectNode();
                var repairs = json.putArray("repairs");
                for (int i = 0; i < scenario.items().size(); i++) {
                    ScenarioItem fixture = scenario.items().get(i);
                    ObjectNode repair = repairs.addObject();
                    repair.put("draftIndex", i);
                    ObjectNode fields = repair.putObject("fields");
                    fields.put("term", fixture.expectedTerm());
                    fields.put("translation", fixture.goodTranslation());
                    fields.put("example", "Example " + (i + 1));
                }
                body = json;
            } else {
                throw new AssertionError("Unexpected response format: " + formatName);
            }
            return new OpenAiResponseResult(body.toString(), scenario.model(), 120, 60, buildUsageRaw(120, 60, 12, 6));
        });

        List<CoreApiClient.CreateCardRequestPayload> capturedRequests = new ArrayList<>();
        when(coreApiClient.addCards(any(), any(), any(), any())).thenAnswer(invocation -> {
            List<CoreApiClient.CreateCardRequestPayload> requests = invocation.getArgument(1);
            capturedRequests.addAll(requests);
            return requests.stream()
                    .map(request -> new CoreApiClient.CoreUserCardResponse(UUID.randomUUID(), null, true, request.content()))
                    .toList();
        });

        Method handleImportGenerate = OpenAiJobProcessor.class.getDeclaredMethod(
                "handleImportGenerate",
                AiJobEntity.class,
                String.class,
                JsonNode.class
        );
        handleImportGenerate.setAccessible(true);
        app.mnema.ai.service.AiJobProcessingResult result =
                (app.mnema.ai.service.AiJobProcessingResult) handleImportGenerate.invoke(processor, job, "", params);

        assertThat(capturedRequests).hasSize(scenario.items().size());
        for (int i = 0; i < scenario.items().size(); i++) {
            assertThat(capturedRequests.get(i).content().path("term").asText()).isEqualTo(scenario.items().get(i).expectedTerm());
            assertThat(capturedRequests.get(i).content().path("translation").asText()).isEqualTo(scenario.items().get(i).goodTranslation());
        }
        assertThat(result.resultSummary().path("sourceCoverage").path("sourceItemsTotal").asInt()).isEqualTo(scenario.items().size());
        assertThat(result.resultSummary().path("sourceCoverage").path("sourceItemsUsed").asInt()).isEqualTo(scenario.items().size());
        assertThat(result.resultSummary().path("qualityGate").path("repairedDrafts").asInt()).isEqualTo(scenario.items().size());
        assertThat(result.resultSummary().path("usage").path("draftRepair").path("requests").asInt()).isEqualTo(1);

        if (scenario.expectSourceNormalization()) {
            assertThat(result.resultSummary().path("sourceNormalization").path("reviewedItems").asInt()).isEqualTo(scenario.items().size());
            assertThat(result.resultSummary().path("sourceNormalization").path("normalizedItems").asInt()).isEqualTo(scenario.items().size());
            assertThat(result.resultSummary().path("sourceNormalization").path("extraction").asText()).isEqualTo(scenario.extraction());
            assertThat(result.resultSummary().path("usage").path("sourceNormalization").path("requests").asInt()).isEqualTo(1);
        } else {
            assertThat(result.resultSummary().has("sourceNormalization")).isFalse();
            assertThat(result.resultSummary().path("usage").has("sourceNormalization")).isFalse();
        }

        if ("audio_stt".equals(scenario.sourceKind())) {
            verify(openAiClient, times(2)).createTranscription(any(), any());
        } else {
            verify(importContentService).extractText(any(), any(), any());
        }
    }

    private static Stream<ScenarioCase> scenarioCases() {
        return loadScenarios().stream();
    }

    private static List<ScenarioCase> loadScenarios() {
        try (InputStream input = OpenAiImportEvalHarnessTest.class.getResourceAsStream("/app/mnema/ai/provider/openai/import-eval-scenarios.json")) {
            assertThat(input).isNotNull();
            return OBJECT_MAPPER.readValue(input, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load import eval scenarios", ex);
        }
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
        job.setCreatedAt(Instant.now());
        return job;
    }

    private static ObjectNode buildUsageRaw(int inputTokens,
                                            int outputTokens,
                                            int cachedInputTokens,
                                            int reasoningTokens) {
        ObjectNode raw = OBJECT_MAPPER.createObjectNode();
        ObjectNode usage = raw.putObject("usage");
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", outputTokens);
        usage.putObject("input_tokens_details").put("cached_tokens", cachedInputTokens);
        usage.putObject("output_tokens_details").put("reasoning_tokens", reasoningTokens);
        return raw;
    }

    private static String buildSourceText(List<ScenarioItem> items) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            builder.append(i + 1).append(". ").append(items.get(i).sourceText()).append("\n");
        }
        return builder.toString();
    }

    private static List<String> splitTranscriptChunks(String transcript, int chunks) {
        String[] lines = transcript.split("\\R");
        List<String> nonEmpty = new ArrayList<>();
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                nonEmpty.add(line);
            }
        }
        int perChunk = Math.max(1, (int) Math.ceil(nonEmpty.size() / (double) chunks));
        List<String> result = new ArrayList<>();
        for (int offset = 0; offset < nonEmpty.size(); offset += perChunk) {
            result.add(String.join("\n", nonEmpty.subList(offset, Math.min(nonEmpty.size(), offset + perChunk))));
        }
        while (result.size() < chunks) {
            result.add("");
        }
        return result;
    }

    private record ScenarioCase(String name,
                                String provider,
                                String model,
                                String mimeType,
                                String extraction,
                                String sourceKind,
                                Boolean expectSourceNormalization,
                                List<ScenarioItem> items) {
        private ScenarioCase {
            if (expectSourceNormalization == null) {
                expectSourceNormalization = false;
            }
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private record ScenarioItem(String sourceText,
                                String expectedTerm,
                                String badTranslation,
                                String goodTranslation,
                                String issue) {
        private ScenarioItem {
            if (expectedTerm == null || expectedTerm.isBlank()) {
                expectedTerm = sourceText;
            }
        }
    }
}
