package app.mnema.ai.provider.audit;

import app.mnema.ai.client.core.CoreApiClient.CoreTemplateResponse;
import app.mnema.ai.client.core.CoreApiClient.CoreUserCardResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditAnalyzerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void analyze_detectsMissingAndShortValues() {
        UUID cardId = UUID.randomUUID();
        ObjectNode content = objectMapper.createObjectNode();
        content.put("front", "A");
        content.put("back", "");
        CoreUserCardResponse card = new CoreUserCardResponse(cardId, content);

        CoreTemplateResponse template = new CoreTemplateResponse(
                UUID.randomUUID(),
                1,
                1,
                "Template",
                null,
                objectMapper.createObjectNode(),
                null,
                List.of()
        );

        AuditAnalyzer.AuditContext context = AuditAnalyzer.analyze(
                objectMapper,
                template,
                List.of(card),
                List.of("front", "back")
        );

        assertThat(context.summary().path("totalCards").asInt()).isEqualTo(1);
        assertThat(context.summary().path("fieldStats")).isNotNull();
        assertThat(context.issues().size()).isGreaterThan(0);
    }
}
