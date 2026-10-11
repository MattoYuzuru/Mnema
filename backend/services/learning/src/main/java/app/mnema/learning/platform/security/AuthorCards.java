package app.mnema.learning.platform.security;

import java.util.Optional;
import java.util.UUID;

/**
 * The public author card of an account, as Identity publishes it ({@code GET /api/accounts/profiles/...}, contract {@code contracts/identity/public-profile.json}):
 * a card exists only for an account with an enabled consent and a login, and only the fields the owner agreed to show are present. Learning keeps no copy.
 * Link previews ask here; every failure is an empty answer, never an error, so a preview is never blocked by Identity.
 * There is deliberately no lookup by login: Identity's by-username endpoint has one 15-minute allowance per caller address, shared by the grants by login and the
 * profile complaints ({@link ProfileLookupBudget}), and a guest-reachable route must never spend it (the profile preview waits for the author page, epic 3).
 */
public interface AuthorCards {
    /** The fields of a card a link preview may show: the login, and the display name and bio, each absent unless the owner agreed to show it. */
    record Card(String username, String displayName, String bio) { }

    /** @return the card of the account, or empty when it has none (no consent, no login, unknown) or Identity did not answer in time */
    Optional<Card> byAccount(UUID accountId);
}
