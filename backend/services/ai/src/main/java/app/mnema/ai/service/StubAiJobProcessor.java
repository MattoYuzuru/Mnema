package app.mnema.ai.service;

import app.mnema.ai.domain.entity.AiJobEntity;
import com.fasterxml.jackson.databind.node.NullNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "stub", matchIfMissing = true)
public class StubAiJobProcessor implements AiJobProcessor {

    @Override
    public AiJobProcessingResult process(AiJobEntity job) {
        return new AiJobProcessingResult(
                NullNode.getInstance(),
                "stub",
                "stub",
                0,
                0,
                BigDecimal.ZERO,
                job.getInputHash()
        );
    }
}
