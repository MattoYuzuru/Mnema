package app.mnema.ai.provider.openai;

import app.mnema.ai.client.core.CoreApiClient;
import app.mnema.ai.client.media.MediaApiClient;
import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.entity.AiProviderCredentialEntity;
import app.mnema.ai.domain.type.AiJobStatus;
import app.mnema.ai.domain.type.AiJobType;
import app.mnema.ai.domain.type.AiProviderStatus;
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
import org.springframework.web.client.ResourceAccessException;

import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                "analyze_content",
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

    @Test
    void isLocalOllamaRequestRecognizesLocalProviderAliases() throws Exception {
        OpenAiJobProcessor processor = createProcessor();
        Method isLocalOllamaRequest = OpenAiJobProcessor.class.getDeclaredMethod("isLocalOllamaRequest", JsonNode.class);
        isLocalOllamaRequest.setAccessible(true);

        boolean ollama = (boolean) isLocalOllamaRequest.invoke(processor, OBJECT_MAPPER.createObjectNode().put("provider", "ollama"));
        boolean localOpenAi = (boolean) isLocalOllamaRequest.invoke(processor, OBJECT_MAPPER.createObjectNode().put("provider", "local-openai"));
        boolean openAi = (boolean) isLocalOllamaRequest.invoke(processor, OBJECT_MAPPER.createObjectNode().put("provider", "openai"));

        assertThat(ollama).isTrue();
        assertThat(localOpenAi).isTrue();
        assertThat(openAi).isFalse();
    }

    @Test
    void createSpeechWithRetryRetriesTransportFailuresForLocalProvider() throws Exception {
        OpenAiClient client = mock(OpenAiClient.class);
        when(client.createSpeech(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out")))
                .thenReturn(new byte[]{1, 2, 3});

        OpenAiJobProcessor processor = createProcessor(client);
        ObjectNode params = OBJECT_MAPPER.createObjectNode();
        params.put("provider", "ollama");
        params.put("mode", "missing_audio");
        params.putObject("tts")
                .put("enabled", true)
                .put("model", "kokoro-tts");
        AiJobEntity job = createJob(params, AiJobType.tts);

        Method createSpeechWithRetry = OpenAiJobProcessor.class.getDeclaredMethod(
                "createSpeechWithRetry",
                AiJobEntity.class,
                String.class,
                OpenAiSpeechRequest.class,
                UUID.class,
                String.class
        );
        createSpeechWithRetry.setAccessible(true);

        byte[] result = (byte[]) createSpeechWithRetry.invoke(
                processor,
                job,
                "",
                new OpenAiSpeechRequest("kokoro-tts", "hola", "alloy", "mp3"),
                UUID.randomUUID(),
                "audio"
        );

        assertThat(result).containsExactly(1, 2, 3);
        verify(client, times(2)).createSpeech(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void resolveLocalGenerateBatchSizeKeepsLargeOllamaRequestsSmall() throws Exception {
        OpenAiJobProcessor processor = createProcessor();
        ObjectNode params = OBJECT_MAPPER.createObjectNode();
        params.putArray("fields")
                .add("markdown")
                .add("markdown_3")
                .add("markdown_2")
                .add("markdown_4")
                .add("field")
                .add("field_2");

        Method resolveLocalGenerateBatchSize = OpenAiJobProcessor.class.getDeclaredMethod("resolveLocalGenerateBatchSize", JsonNode.class);
        resolveLocalGenerateBatchSize.setAccessible(true);
        int batchSize = (int) resolveLocalGenerateBatchSize.invoke(processor, params);

        assertThat(batchSize).isEqualTo(5);
    }

    @Test
    void resolveImportBatchSizeKeepsLocalOllamaImportsSmall() throws Exception {
        OpenAiJobProcessor processor = createProcessor();
        ObjectNode params = OBJECT_MAPPER.createObjectNode();
        params.put("provider", "ollama");
        params.putArray("fields")
                .add("front")
                .add("back")
                .add("hint")
                .add("note");

        Method resolveImportBatchSize = OpenAiJobProcessor.class.getDeclaredMethod("resolveImportBatchSize", JsonNode.class);
        resolveImportBatchSize.setAccessible(true);
        int batchSize = (int) resolveImportBatchSize.invoke(processor, params);

        assertThat(batchSize).isEqualTo(6);
    }

    @Test
    void resolveCandidateCountKeepsLocalOllamaSchemaSmall() throws Exception {
        OpenAiJobProcessor processor = createProcessor();

        Method resolveCandidateCount = OpenAiJobProcessor.class.getDeclaredMethod("resolveCandidateCount", int.class, int.class, boolean.class);
        resolveCandidateCount.setAccessible(true);
        int localCandidates = (int) resolveCandidateCount.invoke(processor, 5, 0, true);
        int remoteCandidates = (int) resolveCandidateCount.invoke(processor, 5, 0, false);

        assertThat(localCandidates).isEqualTo(7);
        assertThat(remoteCandidates).isEqualTo(15);
    }

    @Test
    void sanitizeTtsTextStripsMarkdownAndUrls() throws Exception {
        OpenAiJobProcessor processor = createProcessor();
        Method sanitizeTtsText = OpenAiJobProcessor.class.getDeclaredMethod("sanitizeTtsText", String.class);
        sanitizeTtsText.setAccessible(true);

        String sanitized = (String) sanitizeTtsText.invoke(processor, "**subpoena** [link](https://example.test) `note`");

        assertThat(sanitized).isEqualTo("subpoena link note");
    }

    @Test
    void resolveCredentialSkipsUserOpenAiKeysForLocalOllamaRequests() throws Exception {
        AiProviderCredentialRepository credentialRepository = mock(AiProviderCredentialRepository.class);
        UUID userId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        AiProviderCredentialEntity credential = new AiProviderCredentialEntity(
                credentialId,
                userId,
                "openai",
                "remote",
                new byte[]{1},
                null,
                null,
                null,
                null,
                AiProviderStatus.active,
                Instant.now(),
                null,
                Instant.now()
        );
        when(credentialRepository.findByIdAndUserId(credentialId, userId)).thenReturn(Optional.of(credential));

        OpenAiJobProcessor processor = new OpenAiJobProcessor(
                mock(OpenAiClient.class),
                new OpenAiProps(
                        "https://api.openai.com/v1",
                        "system-key",
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
                        12,
                        5,
                        2_000L,
                        30_000L,
                        10_000L,
                        600_000L
                ),
                mock(SecretVault.class),
                credentialRepository,
                mock(MediaApiClient.class),
                mock(AiImportContentService.class),
                mock(AudioChunkingService.class),
                mock(CoreApiClient.class),
                mock(CardNoveltyService.class),
                OBJECT_MAPPER,
                mock(AiJobExecutionService.class),
                200_000
        );
        ObjectNode params = OBJECT_MAPPER.createObjectNode();
        params.put("provider", "ollama");
        params.put("providerCredentialId", credentialId.toString());
        AiJobEntity job = createJob(params, AiJobType.generic);
        job.setUserId(userId);

        Method resolveCredential = OpenAiJobProcessor.class.getDeclaredMethod("resolveCredential", AiJobEntity.class);
        resolveCredential.setAccessible(true);
        Object credentialSelection = resolveCredential.invoke(processor, job);
        Method credentialMethod = credentialSelection.getClass().getDeclaredMethod("credential");
        Method apiKeyMethod = credentialSelection.getClass().getDeclaredMethod("apiKey");
        credentialMethod.setAccessible(true);
        apiKeyMethod.setAccessible(true);

        assertThat(credentialMethod.invoke(credentialSelection)).isNull();
        assertThat(apiKeyMethod.invoke(credentialSelection)).isEqualTo("");
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleGenerateCardsMaybeBatchedAddsCardsOnceForLocalAtomicBatching() throws Exception {
        OpenAiClient openAiClient = mock(OpenAiClient.class);
        CoreApiClient coreApiClient = mock(CoreApiClient.class);
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
                mock(AiImportContentService.class),
                mock(AudioChunkingService.class),
                coreApiClient,
                noveltyService,
                OBJECT_MAPPER,
                mock(AiJobExecutionService.class),
                200_000
        );

        UUID deckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        ObjectNode params = OBJECT_MAPPER.createObjectNode();
        params.put("__skipStepTracking", true);
        params.put("provider", "ollama");
        params.put("mode", "generate_cards");
        params.put("count", 9);
        params.putArray("fields").add("front").add("back");
        params.put("input", "Generate atomic cards");
        AiJobEntity job = createJob(params, AiJobType.generic);
        job.setDeckId(deckId);
        job.setUserAccessToken("token");

        when(coreApiClient.getUserDeck(deckId, "token"))
                .thenReturn(new CoreApiClient.CoreUserDeckResponse(deckId, publicDeckId, 1, 1));
        when(coreApiClient.getPublicDeck(publicDeckId, 1))
                .thenReturn(new CoreApiClient.CorePublicDeckResponse(
                        publicDeckId,
                        1,
                        UUID.randomUUID(),
                        "Deck",
                        "Desc",
                        "en",
                        templateId,
                        1
                ));
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
                                new CoreApiClient.CoreFieldTemplate(UUID.randomUUID(), "front", "Front", "text", true, true, 0),
                                new CoreApiClient.CoreFieldTemplate(UUID.randomUUID(), "back", "Back", "text", true, false, 1)
                        )
                ));
        when(coreApiClient.getUserCards(deckId, 1, 3, "token"))
                .thenReturn(new CoreApiClient.CoreUserCardPage(List.of()));
        when(coreApiClient.getUserCards(deckId, 1, 200, "token"))
                .thenReturn(new CoreApiClient.CoreUserCardPage(List.of()));

        AtomicInteger sequence = new AtomicInteger(1);
        when(openAiClient.createResponse(any(), any())).thenAnswer(invocation -> {
            OpenAiResponseRequest request = invocation.getArgument(1);
            String formatName = request.responseFormat().path("name").asText();
            StringBuilder json = new StringBuilder();
            if ("mnema_cards".equals(formatName)) {
                int count = request.responseFormat()
                        .path("schema")
                        .path("properties")
                        .path("cards")
                        .path("minItems")
                        .asInt();
                json.append("{\"cards\":[");
                for (int i = 0; i < count; i++) {
                    if (i > 0) {
                        json.append(',');
                    }
                    int id = sequence.getAndIncrement();
                    json.append("{\"fields\":{\"front\":\"Q").append(id).append("\",\"back\":\"A").append(id).append("\"}}");
                }
                json.append("]}");
            } else if ("mnema_draft_audit".equals(formatName)) {
                int count = request.input().split("draftIndex=").length - 1;
                json.append("{\"items\":[");
                for (int i = 0; i < count; i++) {
                    if (i > 0) {
                        json.append(',');
                    }
                    json.append("{\"draftIndex\":").append(i)
                            .append(",\"decision\":\"accept\",\"summary\":\"ok\",\"issues\":[],\"focusFields\":[]}");
                }
                json.append("]}");
            }
            ObjectNode raw = OBJECT_MAPPER.createObjectNode();
            ObjectNode usage = raw.putObject("usage");
            usage.put("input_tokens", 100);
            usage.put("output_tokens", 50);
            usage.putObject("input_tokens_details").put("cached_tokens", 10);
            usage.putObject("output_tokens_details").put("reasoning_tokens", 5);
            return new OpenAiResponseResult(json.toString(), "qwen3:4b", 100, 50, raw);
        });
        when(coreApiClient.addCards(any(), any(), any(), any())).thenAnswer(invocation -> {
            List<CoreApiClient.CreateCardRequestPayload> requests = invocation.getArgument(1);
            return requests.stream()
                    .map(request -> new CoreApiClient.CoreUserCardResponse(UUID.randomUUID(), null, true, request.content()))
                    .toList();
        });

        Method handleGenerateCardsMaybeBatched = OpenAiJobProcessor.class.getDeclaredMethod(
                "handleGenerateCardsMaybeBatched",
                AiJobEntity.class,
                String.class,
                JsonNode.class
        );
        handleGenerateCardsMaybeBatched.setAccessible(true);
        Object result = handleGenerateCardsMaybeBatched.invoke(processor, job, "", params);
        assertThat(result).isInstanceOf(app.mnema.ai.service.AiJobProcessingResult.class);

        verify(coreApiClient, times(1)).addCards(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleImportGenerateUsesExactItemBatchCountForLocalOllama() throws Exception {
        OpenAiClient openAiClient = mock(OpenAiClient.class);
        CoreApiClient coreApiClient = mock(CoreApiClient.class);
        AiImportContentService importContentService = mock(AiImportContentService.class);
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
                mock(AudioChunkingService.class),
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
        params.put("provider", "ollama");
        params.put("mode", "import_generate");
        params.put("count", 8);
        params.put("sourceMediaId", sourceMediaId.toString());
        params.putObject("tts").put("enabled", false);
        params.putArray("fields")
                .add("markdown")
                .add("markdown_2")
                .add("markdown_3")
                .add("markdown_4")
                .add("field");
        AiJobEntity job = createJob(params, AiJobType.generic);
        job.setDeckId(deckId);
        job.setUserAccessToken("token");

        AiImportContentService.ImportSourcePayload source = new AiImportContentService.ImportSourcePayload(
                new byte[0],
                "text/plain",
                64,
                false
        );
        when(importContentService.loadSource(any(), any())).thenReturn(source);
        when(importContentService.extractText(any(), any(), any()))
                .thenReturn(new AiImportContentService.ImportTextPayload(
                        """
                        1. subpoena
                        2. plaintiff
                        3. bailiff
                        4. deposition
                        5. affidavit
                        6. injunction
                        7. paralegal
                        8. pro bono
                        """,
                        "text/plain",
                        64,
                        false,
                        96,
                        "utf-8",
                        500,
                        "text",
                        null,
                        null,
                        null,
                        null
                ));
        when(coreApiClient.getUserDeck(deckId, "token"))
                .thenReturn(new CoreApiClient.CoreUserDeckResponse(deckId, publicDeckId, 1, 1));
        when(coreApiClient.getPublicDeck(publicDeckId, 1))
                .thenReturn(new CoreApiClient.CorePublicDeckResponse(
                        publicDeckId,
                        1,
                        UUID.randomUUID(),
                        "Deck",
                        "Desc",
                        "en",
                        templateId,
                        1
                ));
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
                                new CoreApiClient.CoreFieldTemplate(UUID.randomUUID(), "markdown", "Markdown", "text", true, true, 0),
                                new CoreApiClient.CoreFieldTemplate(UUID.randomUUID(), "markdown_2", "Markdown 2", "text", true, false, 1),
                                new CoreApiClient.CoreFieldTemplate(UUID.randomUUID(), "markdown_3", "Markdown 3", "text", true, false, 2),
                                new CoreApiClient.CoreFieldTemplate(UUID.randomUUID(), "markdown_4", "Markdown 4", "text", true, false, 3),
                                new CoreApiClient.CoreFieldTemplate(UUID.randomUUID(), "field", "Field", "text", true, false, 4)
                        )
                ));
        when(coreApiClient.getUserCards(deckId, 1, 200, "token"))
                .thenReturn(new CoreApiClient.CoreUserCardPage(List.of()));

        List<Integer> schemaCounts = new ArrayList<>();
        List<Boolean> schemaHasSourceTracking = new ArrayList<>();
        String[] sourceTerms = {
                "subpoena",
                "plaintiff",
                "bailiff",
                "deposition",
                "affidavit",
                "injunction",
                "paralegal",
                "pro bono"
        };
        AtomicInteger sequence = new AtomicInteger(1);
        when(openAiClient.createResponse(any(), any())).thenAnswer(invocation -> {
            OpenAiResponseRequest request = invocation.getArgument(1);
            String formatName = request.responseFormat().path("name").asText();
            StringBuilder json = new StringBuilder();
            if ("mnema_cards".equals(formatName)) {
                int count = request.responseFormat()
                        .path("schema")
                        .path("properties")
                        .path("cards")
                        .path("minItems")
                        .asInt();
                schemaCounts.add(count);
                schemaHasSourceTracking.add(request.responseFormat()
                        .path("schema")
                        .path("properties")
                        .path("cards")
                        .path("items")
                        .path("properties")
                        .has("sourceIndex"));
                json.append("{\"cards\":[");
                for (int i = 0; i < count; i++) {
                    if (i > 0) {
                        json.append(',');
                    }
                    int id = sequence.getAndIncrement();
                    String sourceText = sourceTerms[id - 1];
                    json.append("{")
                            .append("\"sourceIndex\":").append(id).append(",")
                            .append("\"sourceText\":\"").append(sourceText).append("\",")
                            .append("\"fields\":{")
                            .append("\"markdown\":\"").append(sourceText).append("\",")
                            .append("\"markdown_2\":\"translation ").append(id).append("\",")
                            .append("\"markdown_3\":\"example ").append(id).append("\",")
                            .append("\"markdown_4\":\"note ").append(id).append("\",")
                            .append("\"field\":\"audio text ").append(id).append("\"")
                            .append("}}");
                }
                json.append("]}");
            } else if ("mnema_draft_audit".equals(formatName)) {
                json.append("{\"items\":[");
                for (int i = 0; i < 8; i++) {
                    if (i > 0) {
                        json.append(',');
                    }
                    json.append("{\"draftIndex\":").append(i)
                            .append(",\"decision\":\"accept\",\"summary\":\"ok\",\"issues\":[],\"focusFields\":[]}");
                }
                json.append("]}");
            }
            ObjectNode raw = OBJECT_MAPPER.createObjectNode();
            ObjectNode usage = raw.putObject("usage");
            usage.put("input_tokens", 100);
            usage.put("output_tokens", 50);
            usage.putObject("input_tokens_details").put("cached_tokens", 10);
            usage.putObject("output_tokens_details").put("reasoning_tokens", 5);
            return new OpenAiResponseResult(json.toString(), "qwen3:4b", 100, 50, raw);
        });
        when(coreApiClient.addCards(any(), any(), any(), any())).thenAnswer(invocation -> {
            List<CoreApiClient.CreateCardRequestPayload> requests = invocation.getArgument(1);
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
        Object result = handleImportGenerate.invoke(processor, job, "", params);
        assertThat(result).isInstanceOf(app.mnema.ai.service.AiJobProcessingResult.class);

        assertThat(schemaCounts).containsExactly(4, 4);
        assertThat(schemaHasSourceTracking).containsExactly(true, true);
        app.mnema.ai.service.AiJobProcessingResult processingResult = (app.mnema.ai.service.AiJobProcessingResult) result;
        assertThat(processingResult.resultSummary().path("sourceCoverage").path("sourceItemsTotal").asInt()).isEqualTo(8);
        assertThat(processingResult.resultSummary().path("sourceCoverage").path("sourceItemsUsed").asInt()).isEqualTo(8);
        assertThat(processingResult.resultSummary().path("usage").path("textGeneration").path("requests").asInt()).isEqualTo(2);
        assertThat(processingResult.resultSummary().path("usage").path("textGeneration").path("inputTokens").asInt()).isEqualTo(200);
        assertThat(processingResult.resultSummary().path("usage").path("draftAudit").path("requests").asInt()).isEqualTo(1);
        assertThat(processingResult.resultSummary().path("usage").path("draftFinalAudit").path("requests").asInt()).isZero();
        assertThat(processingResult.resultSummary().path("qualityGate").path("qualityScore").asInt()).isEqualTo(100);
        assertThat(processingResult.resultSummary().path("usage").path("textGeneration").path("calls").get(0).path("cachedInputTokens").asInt()).isEqualTo(10);
        assertThat(processingResult.usageDetails().path("textGeneration").path("calls").get(0).path("reasoningOutputTokens").asInt()).isEqualTo(5);
        verify(coreApiClient, times(1)).addCards(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleImportGenerateRepairsFlaggedDraftsBeforeApply() throws Exception {
        OpenAiClient openAiClient = mock(OpenAiClient.class);
        CoreApiClient coreApiClient = mock(CoreApiClient.class);
        AiImportContentService importContentService = mock(AiImportContentService.class);
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
                mock(AudioChunkingService.class),
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
        params.put("provider", "ollama");
        params.put("mode", "import_generate");
        params.put("count", 2);
        params.put("sourceMediaId", sourceMediaId.toString());
        params.putObject("tts").put("enabled", false);
        params.putArray("fields").add("term").add("translation").add("example");
        AiJobEntity job = createJob(params, AiJobType.generic);
        job.setDeckId(deckId);
        job.setUserAccessToken("token");

        when(importContentService.loadSource(any(), any())).thenReturn(new AiImportContentService.ImportSourcePayload(new byte[0], "text/plain", 32, false));
        when(importContentService.extractText(any(), any(), any()))
                .thenReturn(new AiImportContentService.ImportTextPayload(
                        """
                        1. fishing expedition
                        2. affidavit
                        """,
                        "text/plain",
                        32,
                        false,
                        64,
                        "utf-8",
                        500,
                        "text",
                        null,
                        null,
                        null,
                        null
                ));
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

        AtomicInteger draftAuditCalls = new AtomicInteger();
        when(openAiClient.createResponse(any(), any())).thenAnswer(invocation -> {
            OpenAiResponseRequest request = invocation.getArgument(1);
            String formatName = request.responseFormat().path("name").asText();
            String body;
            if ("mnema_cards".equals(formatName)) {
                body = """
                        {"cards":[
                          {"sourceIndex":1,"sourceText":"fishing expedition","fields":{"term":"fishing expedition","translation":"рыбная экспедиция","example":"The lawyer called it a fishing expedition."}},
                          {"sourceIndex":2,"sourceText":"affidavit","fields":{"term":"affidavit","translation":"аффидевит","example":"She signed an affidavit before filing the motion."}}
                        ]}
                        """;
            } else if ("mnema_draft_audit".equals(formatName)) {
                if (draftAuditCalls.getAndIncrement() > 0) {
                    body = """
                            {"items":[
                              {"draftIndex":0,"decision":"accept","summary":"fixed","issues":[],"focusFields":[]},
                              {"draftIndex":1,"decision":"accept","summary":"ok","issues":[],"focusFields":[]}
                            ]}
                            """;
                } else {
                    body = """
                            {"items":[
                              {"draftIndex":0,"decision":"repair","summary":"Literal mistranslation","issues":["Translation is too literal for legal context"],"focusFields":["translation"]},
                              {"draftIndex":1,"decision":"accept","summary":"ok","issues":[],"focusFields":[]}
                            ]}
                            """;
                }
            } else if ("mnema_draft_repair".equals(formatName)) {
                body = """
                        {"repairs":[
                          {"draftIndex":0,"fields":{"term":"fishing expedition","translation":"поиск компромата","example":"The lawyer called it a fishing expedition."}}
                        ]}
                        """;
            } else {
                throw new AssertionError("Unexpected response format: " + formatName);
            }
            ObjectNode raw = OBJECT_MAPPER.createObjectNode();
            ObjectNode usage = raw.putObject("usage");
            usage.put("input_tokens", 100);
            usage.put("output_tokens", 50);
            usage.putObject("input_tokens_details").put("cached_tokens", 10);
            usage.putObject("output_tokens_details").put("reasoning_tokens", 5);
            return new OpenAiResponseResult(body, "qwen3:4b", 100, 50, raw);
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

        app.mnema.ai.service.AiJobProcessingResult result = (app.mnema.ai.service.AiJobProcessingResult) handleImportGenerate.invoke(processor, job, "", params);

        assertThat(capturedRequests).hasSize(2);
        assertThat(capturedRequests.getFirst().content().path("translation").asText()).isEqualTo("поиск компромата");
        assertThat(result.resultSummary().path("qualityGate").path("repairRequested").asInt()).isEqualTo(1);
        assertThat(result.resultSummary().path("qualityGate").path("repairedDrafts").asInt()).isEqualTo(1);
        assertThat(result.resultSummary().path("qualityGate").path("finalFlaggedDrafts").asInt()).isZero();
        assertThat(result.resultSummary().path("qualityGate").path("qualityScore").asInt()).isGreaterThanOrEqualTo(90);
        assertThat(result.resultSummary().path("usage").path("draftRepair").path("requests").asInt()).isEqualTo(1);
        assertThat(result.resultSummary().path("usage").path("draftFinalAudit").path("requests").asInt()).isEqualTo(1);
    }

    @Test
    void detectAudioQualityIssueRejectsBrokenHeadersAndAcceptsValidWav() throws Exception {
        OpenAiJobProcessor processor = createProcessor();
        Method detectAudioQualityIssue = OpenAiJobProcessor.class.getDeclaredMethod(
                "detectAudioQualityIssue",
                byte[].class,
                String.class,
                String.class
        );
        detectAudioQualityIssue.setAccessible(true);

        byte[] invalidMp3 = new byte[200];
        for (int i = 0; i < invalidMp3.length; i++) {
            invalidMp3[i] = (byte) (i % 17);
        }
        byte[] wav = new byte[256];
        wav[0] = 'R';
        wav[1] = 'I';
        wav[2] = 'F';
        wav[3] = 'F';
        wav[8] = 'W';
        wav[9] = 'A';
        wav[10] = 'V';
        wav[11] = 'E';
        for (int i = 12; i < wav.length; i++) {
            wav[i] = (byte) (i % 23);
        }

        String invalidIssue = (String) detectAudioQualityIssue.invoke(processor, invalidMp3, "audio/mpeg", "Long enough text for validation");
        String validIssue = (String) detectAudioQualityIssue.invoke(processor, wav, "audio/wav", "Long enough text for validation");

        assertThat(invalidIssue).isEqualTo("mp3 header is invalid");
        assertThat(validIssue).isNull();
    }

    private static OpenAiJobProcessor createProcessor() {
        return createProcessor(mock(OpenAiClient.class));
    }

    private static OpenAiJobProcessor createProcessor(OpenAiClient openAiClient) {
        return new OpenAiJobProcessor(
                openAiClient,
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
