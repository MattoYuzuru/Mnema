package app.mnema.ai.provider.claude;

import app.mnema.ai.client.core.CoreApiClient;
import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.type.AiJobType;
import app.mnema.ai.repository.AiProviderCredentialRepository;
import app.mnema.ai.service.AiJobExecutionService;
import app.mnema.ai.service.CardNoveltyService;
import app.mnema.ai.vault.SecretVault;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ClaudeJobProcessorTest {

    @Test
    void buildUpdatedItemsUsesCanonicalItemContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClaudeJobProcessor processor = createProcessor(mapper);
        UUID cardId = UUID.randomUUID();
        CoreApiClient.CoreUserCardResponse card = new CoreApiClient.CoreUserCardResponse(
                cardId,
                null,
                false,
                mapper.createObjectNode().put("front", "Bonjour")
        );

        Method buildUpdatedItems = ClaudeJobProcessor.class.getDeclaredMethod("buildUpdatedItems", List.class, Set.class);
        buildUpdatedItems.setAccessible(true);
        ArrayNode items = (ArrayNode) buildUpdatedItems.invoke(processor, List.of(card), Set.of(cardId));

        assertThat(items).hasSize(1);
        JsonNode item = items.get(0);
        assertThat(item.path("cardId").asText()).isEqualTo(cardId.toString());
        assertThat(item.path("preview").asText()).isEqualTo("Bonjour");
        assertThat(item.path("status").asText()).isEqualTo("completed");
        assertThat(item.path("completedStages")).extracting(JsonNode::asText).containsExactly("content");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveExecutionPlanForMissingFieldsUsesApplyStage() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClaudeJobProcessor processor = createProcessor(mapper);
        AiJobEntity job = new AiJobEntity();
        job.setParamsJson(mapper.createObjectNode().put("mode", "missing_fields"));
        job.setType(AiJobType.generic);

        Method resolveExecutionPlan = ClaudeJobProcessor.class.getDeclaredMethod("resolveExecutionPlan", AiJobEntity.class);
        resolveExecutionPlan.setAccessible(true);
        List<String> plan = (List<String>) resolveExecutionPlan.invoke(processor, job);

        assertThat(plan).containsExactly("prepare_context", "generate_content", "apply_changes");
    }

    private static ClaudeJobProcessor createProcessor(ObjectMapper mapper) {
        return new ClaudeJobProcessor(
                mock(ClaudeClient.class),
                new ClaudeProps("https://api.anthropic.com", "2023-06-01", "claude-sonnet-4-5", 8192),
                mock(SecretVault.class),
                mock(AiProviderCredentialRepository.class),
                mock(CoreApiClient.class),
                mock(CardNoveltyService.class),
                mapper,
                mock(AiJobExecutionService.class)
        );
    }
}
