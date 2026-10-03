package app.mnema.learning.study.attempt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ends what no grader ended: answers still ASSESSING past {@code learning.ai.assess.deadline} (a provider that never answered, a
 * crashed process) become UNAVAILABLE every {@code learning.ai.assess.sweep-interval}. Any instance may run it: due rows are
 * claimed with {@code SKIP LOCKED}. The learner then rates themselves; nothing is lost or penalized.
 */
@Component
class AssessmentSweeper {
    private static final Logger LOG = LoggerFactory.getLogger(AssessmentSweeper.class);
    private final AssessmentService service;

    AssessmentSweeper(AssessmentService service) { this.service = service; }

    @Scheduled(initialDelayString = "${learning.ai.assess.sweep-interval:PT2S}",
            fixedDelayString = "${learning.ai.assess.sweep-interval:PT2S}")
    void sweep() {
        try {
            service.expireOverdue();
        } catch (RuntimeException failure) {
            LOG.warn("assessment_sweep_failed error_type={}", failure.getClass().getSimpleName());
        }
    }
}
