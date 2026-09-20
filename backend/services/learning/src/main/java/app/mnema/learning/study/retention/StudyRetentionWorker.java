package app.mnema.learning.study.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class StudyRetentionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(StudyRetentionWorker.class);
    private final StudyRetentionService service;

    StudyRetentionWorker(StudyRetentionService service) { this.service = service; }

    @Scheduled(initialDelayString = "${mnema.study.retention.initial-delay:PT1H}",
            fixedDelayString = "${mnema.study.retention.fixed-delay:PT1H}")
    void purge() {
        StudyRetentionService.PurgeResult result = service.purgeBatch();
        if (result.rawResponses() != 0 || result.compactOutcomes() != 0) {
            LOG.info("Study retention batch completed rawResponses={} compactOutcomes={}",
                    result.rawResponses(), result.compactOutcomes());
        }
    }
}
