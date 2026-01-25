package app.mnema.ai.service;

import app.mnema.ai.domain.entity.AiUsageLedgerEntity;
import app.mnema.ai.repository.AiUsageLedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
public class AiUsageLedgerService {

    private final AiUsageLedgerRepository usageLedgerRepository;

    public AiUsageLedgerService(AiUsageLedgerRepository usageLedgerRepository) {
        this.usageLedgerRepository = usageLedgerRepository;
    }

    @Transactional
    public void recordUsage(UUID requestId,
                            UUID jobId,
                            UUID userId,
                            Integer tokensIn,
                            Integer tokensOut,
                            BigDecimal costEstimate,
                            String provider,
                            String model,
                            String promptHash) {
        AiUsageLedgerEntity entry = new AiUsageLedgerEntity();
        entry.setRequestId(requestId);
        entry.setJobId(jobId);
        entry.setUserId(userId);
        entry.setTokensIn(tokensIn);
        entry.setTokensOut(tokensOut);
        entry.setCostEstimate(costEstimate);
        entry.setProvider(provider);
        entry.setModel(model);
        entry.setPromptHash(promptHash);
        entry.setCreatedAt(Instant.now());
        usageLedgerRepository.save(entry);
    }
}
