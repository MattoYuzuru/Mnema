package app.mnema.learning.community;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The knobs of the catalogue routes are bounded and fail at startup; the shipped defaults are the documented ones. */
class CatalogRouteSettingsTest {
    private static CatalogRouteSettings settings(boolean enabled, double threshold, String facets, String sitemap, String timeout, String origin) {
        return settings(enabled, threshold, facets, sitemap, timeout, 2, origin);
    }

    private static CatalogRouteSettings settings(boolean enabled, double threshold, String facets, String sitemap, String timeout, int searches, String origin) {
        return new CatalogRouteSettings(enabled, threshold, Duration.parse(facets), Duration.parse(sitemap), Duration.parse(timeout), searches, origin);
    }

    @Test
    void theDefaultsAreOffHalfAThresholdFiveMinutesAnHourAndTheProductionOrigin() {
        CatalogRouteSettings defaults = settings(false, 0.5, "PT5M", "PT1H", "PT3S", "");
        assertThat(defaults.enabled).isFalse();
        assertThat(defaults.similarityThreshold).isEqualTo(0.5);
        assertThat(defaults.facetsTtl).isEqualTo(Duration.ofMinutes(5));
        assertThat(defaults.sitemapTtl).isEqualTo(Duration.ofHours(1));
        assertThat(defaults.maxConcurrentSearches).isEqualTo(2);
        assertThat(defaults.origin).isEqualTo("https://mnema.app");
        assertThat(settings(true, 1.0, "PT1S", "PT1M", "PT30S", 64, "https://example.org").origin).isEqualTo("https://example.org");
    }

    @Test
    void outOfRangeValuesFailAtStartup() {
        for (Runnable invalid : new Runnable[] {() -> settings(true, 0.09, "PT5M", "PT1H", "PT3S", ""), () -> settings(true, 1.01, "PT5M", "PT1H", "PT3S", ""),
                () -> settings(true, Double.NaN, "PT5M", "PT1H", "PT3S", ""), () -> settings(true, 0.5, "PT6M", "PT1H", "PT3S", ""), () -> settings(true, 0.5, "PT0S", "PT1H", "PT3S", ""),
                () -> settings(true, 0.5, "PT5M", "PT30S", "PT3S", ""), () -> settings(true, 0.5, "PT5M", "PT61M", "PT3S", ""), () -> settings(true, 0.5, "PT5M", "PT1H", "PT3S", 0, ""), () -> settings(true, 0.5, "PT5M", "PT1H", "PT3S", 65, ""), () -> settings(true, 0.5, "PT5M", "PT1H", "PT0.5S", ""),
                () -> settings(true, 0.5, "PT5M", "PT1H", "PT31S", ""), () -> settings(true, 0.5, "PT5M", "PT1H", "PT3S", "http://mnema.app"), () -> settings(true, 0.5, "PT5M", "PT1H", "PT3S", "https://mnema.app/x")}) {
            assertThatThrownBy(invalid::run).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
