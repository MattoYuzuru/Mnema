package app.mnema.ai.service;

import app.mnema.ai.domain.entity.AiUsageLedgerEntity;
import app.mnema.ai.repository.AiUsageLedgerRepository;
import com.fasterxml.jackson.databind.JsonNode;
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
        recordUsage(requestId, jobId, userId, tokensIn, tokensOut, costEstimate, provider, model, promptHash, null);
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
                            String promptHash,
                            JsonNode details) {
        int normalizedIn = tokensIn == null ? 0 : Math.max(tokensIn, 0);
        int normalizedOut = tokensOut == null ? 0 : Math.max(tokensOut, 0);
        AiUsageLedgerEntity entry = new AiUsageLedgerEntity();
        entry.setRequestId(requestId);
        entry.setJobId(jobId);
        entry.setUserId(userId);
        entry.setTokensIn(normalizedIn);
        entry.setTokensOut(normalizedOut);
        entry.setCostEstimate(costEstimate);
        entry.setProvider(provider);
        entry.setModel(model);
        entry.setPromptHash(promptHash);
        entry.setDetails(details);
        entry.setCreatedAt(Instant.now());
        usageLedgerRepository.save(entry);
    }
}
