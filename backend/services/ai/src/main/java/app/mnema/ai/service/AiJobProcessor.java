package app.mnema.ai.service;

import app.mnema.ai.domain.entity.AiJobEntity;

public interface AiJobProcessor {
    AiJobProcessingResult process(AiJobEntity job);
}
