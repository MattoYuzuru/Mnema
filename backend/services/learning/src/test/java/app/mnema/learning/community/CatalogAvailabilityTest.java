package app.mnema.learning.community;

import app.mnema.learning.fork.ForkSettings;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Whether the catalogue is worth showing: switched on and listing a deck, asked of the database at most once per facets TTL, never when it is off. */
class CatalogAvailabilityTest {
    private final CatalogQueries queries = mock(CatalogQueries.class);
    private final ForkSettings forks = mock(ForkSettings.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-20T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    private static CatalogRouteSettings settings(boolean enabled) {
        return new CatalogRouteSettings(enabled, 0.5, Duration.ofMinutes(5), Duration.ofHours(1), Duration.ofSeconds(3), 2, "");
    }

    @Test
    void withoutTheSwitchesOfMakingACopyTheCatalogueIsNotShownEitherAndIsNotAsked() {
        when(queries.anyListed()).thenReturn(true);
        when(forks.creation()).thenReturn(false);
        CatalogAvailability availability = new CatalogAvailability(settings(true), forks, queries, clock);
        assertThat(availability.listsDecks()).as("forks or public routes off: a copy from the catalogue is not possible").isFalse();
        verify(queries, never()).anyListed();
        when(forks.creation()).thenReturn(true);
        assertThat(availability.listsDecks()).isTrue();
    }

    @Test
    void aSwitchedOffCatalogueIsNotShownAndIsNeverAsked() {
        CatalogAvailability availability = new CatalogAvailability(settings(false), forks, queries, clock);
        assertThat(availability.listsDecks()).isFalse();
        verify(queries, never()).anyListed();
    }

    @Test
    void anEmptyCatalogueIsNotShownAndTheAnswerIsKeptForTheFacetsTtlOnly() {
        when(forks.creation()).thenReturn(true);
        when(queries.anyListed()).thenReturn(false);
        CatalogAvailability availability = new CatalogAvailability(settings(true), forks, queries, clock);
        assertThat(availability.listsDecks()).isFalse();
        now.set(now.get().plus(Duration.ofMinutes(4)));
        assertThat(availability.listsDecks()).isFalse();
        verify(queries, times(1)).anyListed();

        // a deck is listed meanwhile: the next look after the TTL sees it
        when(queries.anyListed()).thenReturn(true);
        assertThat(availability.listsDecks()).as("still the cached answer").isFalse();
        now.set(now.get().plus(Duration.ofMinutes(2)));
        assertThat(availability.listsDecks()).isTrue();
        verify(queries, times(2)).anyListed();
        availability.invalidate();
        assertThat(availability.listsDecks()).isTrue();
        verify(queries, times(3)).anyListed();
    }
}
