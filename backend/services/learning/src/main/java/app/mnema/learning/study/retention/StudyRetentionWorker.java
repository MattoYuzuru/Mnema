package app.mnema.learning.study.retention;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The schedule of the Study retention. Like the other workers it exists only for the {@code worker} and {@code all} roles
 * ({@code learning.runtime.roles}); an {@code api} process never purges on a timer. {@code learning.study.retention.enabled=false} is the
 * kill switch. Passes that overlap across processes are safe ({@link StudySnapshotRetention}).
 */
@Component
@ConditionalOnExpression("'${learning.runtime.roles:all}'.trim().toLowerCase() == 'worker' or "
        + "'${learning.runtime.roles:all}'.trim().toLowerCase() == 'all'")
class StudyRetentionWorker {
    private final StudyRetentionPass pass;
    private final StudyRetentionSettings settings;

    StudyRetentionWorker(StudyRetentionPass pass, StudyRetentionSettings settings) {
        this.pass = pass;
        this.settings = settings;
    }

    @Scheduled(initialDelayString = "${learning.study.retention.initial-delay:PT2M}",
            fixedDelayString = "${learning.study.retention.interval:PT5M}")
    void purge() {
        if (!settings.enabled()) return;
        try {
            pass.run();
        } catch (RuntimeException failure) {
            pass.failed(failure);
        }
    }
}
