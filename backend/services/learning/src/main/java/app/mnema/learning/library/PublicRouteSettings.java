package app.mnema.learning.library;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The switches and abuse limits of the public read routes ({@code learning.community.public-routes.*}). New public features ship disabled: with
 * {@code enabled=false} (the default) the routes answer 404 as if they did not exist. Limits are per instance; invalid values fail at startup.
 * <ul>
 *   <li>{@code guest-per-minute}: a guest per client network (IPv4 address, IPv6 /64); {@code coarse-per-minute}: all guests of one IPv6 /48 together
 *       (rotating /64s inside a site must not mint fresh budgets); {@code overflow-per-minute}: the one shared budget of every guest key that finds the
 *       guest table full; {@code account-per-minute}: a signed-in viewer.</li>
 *   <li>{@code max-tracked}: the size bound of each limiter table (guests, /48 networks, accounts are separate tables).</li>
 *   <li>{@code max-concurrent}: reads served at the same time by the instance; the next one is refused at once with 503 instead of queueing for a connection.</li>
 * </ul>
 */
@Component
final class PublicRouteSettings {
    final boolean enabled;
    final int guestPerMinute;
    final int coarsePerMinute;
    final int overflowPerMinute;
    final int accountPerMinute;
    final int maxTracked;
    final int maxConcurrent;

    PublicRouteSettings(@Value("${learning.community.public-routes.enabled:false}") boolean enabled,
                        @Value("${learning.community.public-routes.guest-per-minute:120}") int guestPerMinute,
                        @Value("${learning.community.public-routes.coarse-per-minute:600}") int coarsePerMinute,
                        @Value("${learning.community.public-routes.overflow-per-minute:600}") int overflowPerMinute,
                        @Value("${learning.community.public-routes.account-per-minute:600}") int accountPerMinute,
                        @Value("${learning.community.public-routes.max-tracked:50000}") int maxTracked,
                        @Value("${learning.community.public-routes.max-concurrent:4}") int maxConcurrent) {
        if (outside(guestPerMinute, 1, 100_000) || outside(coarsePerMinute, 1, 1_000_000) || outside(overflowPerMinute, 1, 1_000_000)
                || outside(accountPerMinute, 1, 100_000) || outside(maxTracked, 100, 1_000_000) || outside(maxConcurrent, 1, 64)) {
            throw new IllegalArgumentException("Invalid public route settings");
        }
        this.enabled = enabled;
        this.guestPerMinute = guestPerMinute;
        this.coarsePerMinute = coarsePerMinute;
        this.overflowPerMinute = overflowPerMinute;
        this.accountPerMinute = accountPerMinute;
        this.maxTracked = maxTracked;
        this.maxConcurrent = maxConcurrent;
    }

    private static boolean outside(int value, int min, int max) { return value < min || value > max; }
}
