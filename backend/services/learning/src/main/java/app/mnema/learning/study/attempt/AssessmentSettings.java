package app.mnema.learning.study.attempt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code learning.ai.assess.*}: the AI assessment of free explanations (architecture §11).
 *
 * @param deadline how long an answer may stay {@code ASSESSING}: past it the sweeper makes it {@code UNAVAILABLE} and the learner
 *                 rates themselves (the owner's decision: 20 s, with a target of p50 3 s and p95 8 s)
 * @param sweepInterval how often overdue answers are looked for (also the worst delay after a crashed process)
 * @param concurrency answers graded at the same moment by one instance; further ones wait for a slot (the provider layer has its
 *                    own limit, {@code learning.ai.permits.assess}, per call and a strict-level answer makes two calls)
 * @param feedbackLanguage the language of the model's short notes ({@code <feedback_language>} of the prompt)
 */
@ConfigurationProperties("learning.ai.assess")
public record AssessmentSettings(@DefaultValue("PT20S") Duration deadline, @DefaultValue("PT2S") Duration sweepInterval,
                                 @DefaultValue("16") int concurrency, @DefaultValue("ru") String feedbackLanguage) {
    public AssessmentSettings {
        if (deadline.compareTo(Duration.ofSeconds(1)) < 0 || deadline.compareTo(Duration.ofMinutes(5)) > 0
                || sweepInterval.isNegative() || sweepInterval.isZero() || concurrency < 1 || concurrency > 256
                || feedbackLanguage == null || feedbackLanguage.isBlank() || feedbackLanguage.length() > 16) {
            throw new IllegalArgumentException("Invalid learning.ai.assess settings");
        }
        feedbackLanguage = feedbackLanguage.strip();
    }
}
