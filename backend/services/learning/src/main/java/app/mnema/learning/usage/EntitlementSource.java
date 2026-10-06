package app.mnema.learning.usage;

import java.time.Instant;
import java.util.UUID;

/**
 * The port through which usage learns an account's plan. Consumption lives in Learning; purchases, promo codes and
 * periods live in the billing context, which publishes snapshots to {@link EntitlementInbox}; {@link InboxEntitlementSource}
 * reads them. A browser return URL never changes an entitlement.
 */
public interface EntitlementSource {
    /** The entitlement of {@code owner} at {@code now}; never null (an account without a snapshot is on the default plan). */
    Entitlement current(UUID owner, Instant now);
}
