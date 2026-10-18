package app.mnema.learning.community;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Whether the community catalogue is worth showing someone: its kill switch {@code learning.community.catalog.enabled} is on and it lists at least one deck. The onboarding path
 * asks it to hide the «Скопировать колоду сообщества» milestone in front of an empty or switched-off catalogue (OB-301). The answer of the database is one
 * {@code EXISTS} over the partial index of listed decks and is kept per instance for the facets TTL ({@code learning.community.catalog.facets-ttl}, at most five minutes), so a
 * read of the path never costs a catalogue query more often than the facets do. A switched-off catalogue is answered without a query.
 */
@Component
public final class CatalogAvailability {
    private record Known(Instant loadedAt, boolean listed) { }

    private final CatalogRouteSettings settings;
    private final CatalogQueries queries;
    private final Clock clock;
    private volatile Known known;

    @Autowired
    CatalogAvailability(CatalogRouteSettings settings, CatalogQueries queries) {
        this(settings, queries, Clock.systemUTC());
    }

    CatalogAvailability(CatalogRouteSettings settings, CatalogQueries queries, Clock clock) {
        this.settings = settings;
        this.queries = queries;
        this.clock = clock;
    }

    /** @return whether the catalogue is switched on and lists at least one deck (as of at most one facets TTL ago) */
    public boolean listsDecks() {
        if (!settings.enabled) return false;
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
