package app.mnema.learning.catalog.authoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Owner-facing recovery and account capacity policies; native document limits remain versioned contracts. */
@Component
final class AuthoringSettings {
    private final Duration draftRecoveryWindow;
    private final int maxActiveDrafts;
    private final long maxDraftBytesPerAccount;
    private final int maxActiveCaptureNotes;
    private final long maxCaptureBytesPerAccount;

    AuthoringSettings(
            @Value("${learning.authoring.draft.recovery-window:P30D}") Duration draftRecoveryWindow,
            @Value("${learning.authoring.draft.max-active-per-account:200}") int maxActiveDrafts,
            @Value("${learning.authoring.draft.max-total-bytes-per-account:20971520}") long maxDraftBytesPerAccount,
            @Value("${learning.authoring.capture.max-active-per-account:10000}") int maxActiveCaptureNotes,
            @Value("${learning.authoring.capture.max-total-bytes-per-account:67108864}") long maxCaptureBytesPerAccount
    ) {
        if (draftRecoveryWindow == null || draftRecoveryWindow.compareTo(Duration.ofDays(1)) < 0
                || draftRecoveryWindow.compareTo(Duration.ofDays(90)) > 0
                || maxActiveDrafts < 1 || maxActiveDrafts > 1_000
                || maxDraftBytesPerAccount < 1_048_576 || maxDraftBytesPerAccount > 1_073_741_824
                || maxActiveCaptureNotes < 1 || maxActiveCaptureNotes > 100_000
                || maxCaptureBytesPerAccount < 1_048_576 || maxCaptureBytesPerAccount > 1_073_741_824) {
            throw new IllegalArgumentException("Invalid authoring policy");
        }
        this.draftRecoveryWindow = draftRecoveryWindow;
        this.maxActiveDrafts = maxActiveDrafts;
        this.maxDraftBytesPerAccount = maxDraftBytesPerAccount;
        this.maxActiveCaptureNotes = maxActiveCaptureNotes;
        this.maxCaptureBytesPerAccount = maxCaptureBytesPerAccount;
    }

    Duration draftRecoveryWindow() { return draftRecoveryWindow; }
    int maxActiveDrafts() { return maxActiveDrafts; }
    long maxDraftBytesPerAccount() { return maxDraftBytesPerAccount; }
    int maxActiveCaptureNotes() { return maxActiveCaptureNotes; }
    long maxCaptureBytesPerAccount() { return maxCaptureBytesPerAccount; }
}
