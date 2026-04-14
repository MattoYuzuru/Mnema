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
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiDraftQualityFixtureTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void qualityGateRepairsFixtureBackedImportCases() throws Exception {
        List<FixtureCase> fixtures = loadFixtures();
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
        params.put("count", fixtures.size());
        params.put("sourceMediaId", sourceMediaId.toString());
        params.putObject("tts").put("enabled", false);
        params.putArray("fields").add("term").add("translation").add("example");
        AiJobEntity job = createJob(params, AiJobType.generic);
        job.setDeckId(deckId);
        job.setUserAccessToken("token");

        when(importContentService.loadSource(any(), any()))
                .thenReturn(new AiImportContentService.ImportSourcePayload(new byte[0], "text/plain", 64, false));
        when(importContentService.extractText(any(), any(), any()))
                .thenReturn(new AiImportContentService.ImportTextPayload(
                        buildSourceText(fixtures),
                        "text/plain",
                        64,
                        false,
                        128,
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
            JsonNode body;
            if ("mnema_cards".equals(formatName)) {
                ObjectNode json = OBJECT_MAPPER.createObjectNode();
                var cards = json.putArray("cards");
                for (int i = 0; i < fixtures.size(); i++) {
                    FixtureCase fixture = fixtures.get(i);
                    int sourceIndex = i + 1;
                    ObjectNode card = cards.addObject();
                    card.put("sourceIndex", sourceIndex);
                    card.put("sourceText", fixture.sourceText());
                    ObjectNode fields = card.putObject("fields");
                    fields.put("term", fixture.sourceText());
                    fields.put("translation", fixture.badTranslation());
                    fields.put("example", "Example " + sourceIndex);
                }
                body = json;
            } else if ("mnema_draft_audit".equals(formatName)) {
                ObjectNode json = OBJECT_MAPPER.createObjectNode();
                var items = json.putArray("items");
                boolean repairedPass = draftAuditCalls.getAndIncrement() > 0;
                for (int i = 0; i < fixtures.size(); i++) {
                    FixtureCase fixture = fixtures.get(i);
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
                for (int i = 0; i < fixtures.size(); i++) {
                    FixtureCase fixture = fixtures.get(i);
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
            ObjectNode raw = OBJECT_MAPPER.createObjectNode();
            ObjectNode usage = raw.putObject("usage");
            usage.put("input_tokens", 100);
            usage.put("output_tokens", 50);
            usage.putObject("input_tokens_details").put("cached_tokens", 10);
            usage.putObject("output_tokens_details").put("reasoning_tokens", 5);
            return new OpenAiResponseResult(body.toString(), "qwen3:4b", 100, 50, raw);
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

        assertThat(capturedRequests).hasSize(fixtures.size());
        for (int i = 0; i < fixtures.size(); i++) {
            assertThat(capturedRequests.get(i).content().path("term").asText()).isEqualTo(fixtures.get(i).expectedTerm());
            assertThat(capturedRequests.get(i).content().path("translation").asText()).isEqualTo(fixtures.get(i).goodTranslation());
        }
        assertThat(result.resultSummary().path("qualityGate").path("repairRequested").asInt()).isEqualTo(fixtures.size());
        assertThat(result.resultSummary().path("qualityGate").path("repairedDrafts").asInt()).isEqualTo(fixtures.size());
        assertThat(result.resultSummary().path("qualityGate").path("finalFlaggedDrafts").asInt()).isZero();
        assertThat(result.resultSummary().path("usage").path("draftRepair").path("requests").asInt()).isEqualTo(1);
        assertThat(result.resultSummary().path("usage").path("draftFinalAudit").path("requests").asInt()).isEqualTo(1);
    }

    private static List<FixtureCase> loadFixtures() throws Exception {
        try (InputStream input = OpenAiDraftQualityFixtureTest.class.getResourceAsStream("/app/mnema/ai/provider/openai/draft-quality-fixtures.json")) {
            assertThat(input).isNotNull();
            return OBJECT_MAPPER.readValue(input, new TypeReference<>() {});
        }
    }

    private static String buildSourceText(List<FixtureCase> fixtures) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < fixtures.size(); i++) {
            builder.append(i + 1).append(". ").append(fixtures.get(i).sourceText()).append("\n");
        }
        return builder.toString();
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

    private record FixtureCase(String sourceText,
                               String expectedTerm,
                               String badTranslation,
                               String goodTranslation,
                               String issue) {
        private FixtureCase {
            if (expectedTerm == null || expectedTerm.isBlank()) {
                expectedTerm = sourceText;
            }
        }
    }
}
