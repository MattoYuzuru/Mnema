package app.mnema.ai.service;

import app.mnema.ai.domain.entity.AiJobEntity;

public interface AiProviderProcessor {
    String provider();

    AiJobProcessingResult process(AiJobEntity job);
}
