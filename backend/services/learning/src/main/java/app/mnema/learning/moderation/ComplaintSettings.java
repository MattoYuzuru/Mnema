package app.mnema.learning.moderation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The switch and the limit of complaint filing ({@code learning.community.complaints.*}).
 *
 * <p>{@code enabled} defaults to {@code true}, unlike the other community switches: the deck route is also a child of
 * {@code learning.community.public-routes.enabled} (off by default), so a deployment that opens the public routes has the complaint button working, which
 * the product rules require before the first public deck («Правила и ПДн»). Switched off, both filing routes answer 404 as if they did not exist and
 * write nothing; the console and the enforcement of decisions already taken are not affected. {@code reporter-per-day} is the number of NEW complaints
 * one account may file in a rolling 24 hours (a silent repeat counts nothing). The Identity profile lookups of profile complaints are a pool of the shared
 * {@link app.mnema.learning.platform.security.ProfileLookupBudget} ({@code learning.community.profile-lookups.*}), so that complaints never starve the grants by login.
 */
@Component
public final class ComplaintSettings {
    final boolean enabled;
    final boolean publicRoutes;
    final int reporterPerDay;

    ComplaintSettings(@Value("${learning.community.complaints.enabled:true}") boolean enabled,
                      @Value("${learning.community.public-routes.enabled:false}") boolean publicRoutes,
                      @Value("${learning.community.complaints.reporter-per-day:10}") int reporterPerDay) {
        if (reporterPerDay < 1 || reporterPerDay > 1_000) {
            throw new IllegalArgumentException("Invalid complaint settings");
        }
        this.enabled = enabled;
        this.publicRoutes = publicRoutes;
        this.reporterPerDay = reporterPerDay;
    }

    /** Whether the deck filing route exists: both its own switch and the public routes' switch are on. */
    boolean deckFiling() { return enabled && publicRoutes; }

    /** Whether the profile filing route exists. */
    boolean profileFiling() { return enabled; }
}
