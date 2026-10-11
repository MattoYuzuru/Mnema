package app.mnema.learning.library;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The switch and the abuse limits of the public media route ({@code learning.community.public-media.*}, Share/9). Media has limits of its own, separate from
 * the reads of a deck (one page of a deck shows many media, one request each). The route ships disabled: with {@code enabled=false} (the default) it answers 404
 * as if it did not exist; it also needs {@code learning.community.public-routes.enabled}, because it lives under the same prefix. Limits are per instance; invalid
 * values fail at startup. A limit counts REQUESTS FOR SIGNED LINKS, not bytes: the bytes are served by the object store after the link is handed out, so the
 * limits bound how fast links are minted, not the egress itself.
 * <ul>
 *   <li>{@code guest-per-minute}: a guest per client network (IPv4 address, IPv6 /64); {@code coarse-per-minute}: all guests of one IPv6 /48 together (rotating
 *       /64s inside a site must not mint fresh budgets); a request must pass both.</li>
 *   <li>{@code deck-per-minute}: all guests together for one deck. Signed-in viewers are limited per account by the public chain and are not counted here, so
 *       guests never starve them.</li>
 *   <li>{@code max-tracked}: the size bound of each table; a new key that finds its table full shares the overflow bucket of that table
 *       ({@code overflow-per-minute}).</li>
 * </ul>
 */
@Component
final class PublicMediaSettings {
    final boolean enabled;
    final int guestPerMinute;
    final int coarsePerMinute;
    final int deckPerMinute;
    final int overflowPerMinute;
    final int maxTracked;

    PublicMediaSettings(@Value("${learning.community.public-media.enabled:false}") boolean enabled,
                        @Value("${learning.community.public-media.guest-per-minute:240}") int guestPerMinute,
                        @Value("${learning.community.public-media.coarse-per-minute:1200}") int coarsePerMinute,
                        @Value("${learning.community.public-media.deck-per-minute:3000}") int deckPerMinute,
                        @Value("${learning.community.public-media.overflow-per-minute:600}") int overflowPerMinute,
                        @Value("${learning.community.public-media.max-tracked:50000}") int maxTracked) {
        if (outside(guestPerMinute, 1, 100_000) || outside(coarsePerMinute, 1, 1_000_000) || outside(deckPerMinute, 1, 1_000_000)
                || outside(overflowPerMinute, 1, 1_000_000) || outside(maxTracked, 100, 1_000_000)) {
            throw new IllegalArgumentException("Invalid public media settings");
        }
        this.enabled = enabled;
        this.guestPerMinute = guestPerMinute;
        this.coarsePerMinute = coarsePerMinute;
        this.deckPerMinute = deckPerMinute;
        this.overflowPerMinute = overflowPerMinute;
        this.maxTracked = maxTracked;
    }

    private static boolean outside(int value, int min, int max) { return value < min || value > max; }
}
