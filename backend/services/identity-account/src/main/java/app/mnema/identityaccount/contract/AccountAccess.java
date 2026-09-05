package app.mnema.identityaccount.contract;

import java.util.UUID;

/**
 * A generation-bound capability, never inferred from an email or mutable public username.
 */
public record AccountAccess(UUID accountId, long generation) {
}
