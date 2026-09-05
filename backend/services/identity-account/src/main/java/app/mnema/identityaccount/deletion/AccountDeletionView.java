package app.mnema.identityaccount.deletion;

import java.time.Instant;
import java.util.UUID;

public record AccountDeletionView(String context, UUID operationId, String state, Instant deletionRequestedAt,
                                  Instant recoverableUntil, Instant purgeAfter, String completionScope) {
    static AccountDeletionView pending(AccountDeletions.Operation operation) {
        return new AccountDeletionView("ACCOUNT_RECOVERY", operation.operationId(), "PENDING_DELETION",
                operation.deletionRequestedAt().toInstant(), operation.recoverableUntil().toInstant(),
                operation.purgeAfter().toInstant(), "IDENTITY_ONLY");
    }
}
