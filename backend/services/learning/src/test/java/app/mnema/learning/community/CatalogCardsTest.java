package app.mnema.learning.community;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure rules of a deck card: the description cut, the time at whole seconds, and the escaping of the sitemap. */
class CatalogCardsTest {
    @Test
    void aDescriptionOverThreeHundredCodePointsIsCutWithAnEllipsisWithinThreeHundred() {
        String exact = "я".repeat(300);
        assertThat(CatalogCards.describe(exact)).isEqualTo(exact);
        String cut = CatalogCards.describe("я".repeat(301));
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(300);
        assertThat(cut).endsWith("яя…");
        // code points, not UTF-16 units: a supplementary character is one
        String emoji = CatalogCards.describe("😀".repeat(400));
        assertThat(emoji.codePointCount(0, emoji.length())).isEqualTo(300);
        assertThat(emoji).endsWith("😀…");
        // trailing blanks before the ellipsis are dropped
        assertThat(CatalogCards.describe("а".repeat(298) + "   " + "б".repeat(50))).isEqualTo("а".repeat(298) + "…");
        assertThat(CatalogCards.describe("")).isEmpty();
    }

    @Test
    void theTimeIsWrittenAtWholeSeconds() {
        assertThat(CatalogCards.seconds(Instant.parse("2026-10-11T09:30:00.987654Z"))).isEqualTo("2026-10-11T09:30:00Z");
        assertThat(CatalogCards.seconds(Instant.parse("2026-10-11T09:30:00Z"))).isEqualTo("2026-10-11T09:30:00Z");
    }

    @Test
    void theSitemapEscapesWhatXmlReserves() {
        assertThat(CatalogService.escape("a&b<c>d\"e'f")).isEqualTo("a&amp;b&lt;c&gt;d&quot;e&apos;f");
        assertThat(CatalogService.escape("https://mnema.app/d/Kq7xT3mNpR/slug-1")).isEqualTo("https://mnema.app/d/Kq7xT3mNpR/slug-1");
    }
}
