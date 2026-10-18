package app.mnema.learning.community;

import app.mnema.learning.fork.ForkSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Whether a copy from the community catalogue is possible and worth showing someone: its kill switch {@code learning.community.catalog.enabled} is on, so are the switches of
 * making a copy ({@code learning.community.forks.enabled} and {@code learning.community.public-routes.enabled}, {@link ForkSettings#creation()}), and it lists at least one deck. The onboarding path
 * asks it to hide the «Скопировать колоду сообщества» milestone in front of an empty or switched-off catalogue (OB-301). The answer of the database is one
 * {@code EXISTS} over the partial index of listed decks and is kept per instance for the facets TTL ({@code learning.community.catalog.facets-ttl}, at most five minutes), so a
 * read of the path never costs a catalogue query more often than the facets do. A switched-off catalogue or copy is answered without a query.
 */
@Component
public final class CatalogAvailability {
    private record Known(Instant loadedAt, boolean listed) { }

    private final CatalogRouteSettings settings;
    private final ForkSettings forks;
    private final CatalogQueries queries;
    private final Clock clock;
    private volatile Known known;

    @Autowired
    CatalogAvailability(CatalogRouteSettings settings, ForkSettings forks, CatalogQueries queries) {
        this(settings, forks, queries, Clock.systemUTC());
    }

    CatalogAvailability(CatalogRouteSettings settings, ForkSettings forks, CatalogQueries queries, Clock clock) {
        this.settings = settings;
        this.forks = forks;
        this.queries = queries;
        this.clock = clock;
    }

    /** @return whether the catalogue is switched on and lists at least one deck (as of at most one facets TTL ago) */
    public boolean listsDecks() {
        if (!settings.enabled || !forks.creation()) return false;
        Instant now = clock.instant();
        Known current = known;
        if (current != null && now.isBefore(current.loadedAt().plus(settings.facetsTtl))) return current.listed();
        Known fresh = new Known(now, queries.anyListed());
        known = fresh;
        return fresh.listed();
    }

    /** Forgets the cached answer (tests). */
    void invalidate() {
        known = null;
    }
}
