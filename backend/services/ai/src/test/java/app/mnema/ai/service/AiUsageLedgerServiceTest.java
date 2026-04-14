package app.mnema.ai.service;

import app.mnema.ai.domain.entity.AiUsageLedgerEntity;
import app.mnema.ai.repository.AiUsageLedgerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AiUsageLedgerServiceTest {

    @Mock
    private AiUsageLedgerRepository usageLedgerRepository;

    @Test
    void normalizesNullAndNegativeTokenCountsBeforeSaving() {
        AiUsageLedgerService service = new AiUsageLedgerService(usageLedgerRepository);
        UUID requestId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ObjectNode details = new ObjectMapper().createObjectNode().put("requests", 2);

        service.recordUsage(requestId, jobId, userId, null, -3, BigDecimal.ONE, "openai", "gpt-4o", "hash", details);

        ArgumentCaptor<AiUsageLedgerEntity> captor = ArgumentCaptor.forClass(AiUsageLedgerEntity.class);
        verify(usageLedgerRepository).save(captor.capture());
        AiUsageLedgerEntity saved = captor.getValue();
        assertThat(saved.getRequestId()).isEqualTo(requestId);
        assertThat(saved.getJobId()).isEqualTo(jobId);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getTokensIn()).isEqualTo(0);
        assertThat(saved.getTokensOut()).isEqualTo(0);
        assertThat(saved.getCostEstimate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(saved.getProvider()).isEqualTo("openai");
        assertThat(saved.getModel()).isEqualTo("gpt-4o");
        assertThat(saved.getPromptHash()).isEqualTo("hash");
        assertThat(saved.getDetails().path("requests").asInt()).isEqualTo(2);
        assertThat(saved.getCreatedAt()).isNotNull();
    }
}
