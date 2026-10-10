package app.mnema.learning.library;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeckSlugTest {
    @Test
    void russianTitlesAreTransliterated() {
        assertThat(DeckSlug.of("Испанский язык: базовые глаголы")).isEqualTo("ispanskiy-yazyk-bazovye-glagoly");
        assertThat(DeckSlug.of("Щука, ёж и объём")).isEqualTo("shchuka-ezh-i-obem");
        assertThat(DeckSlug.of("Съезд Ёлки")).isEqualTo("sezd-elki");
        assertThat(DeckSlug.of("Жизнь хороша — ЧТО Ж")).isEqualTo("zhizn-khorosha-chto-zh");
    }

    @Test
    void ukrainianAndOtherLatinScriptsLoseTheirMarks() {
        assertThat(DeckSlug.of("Їжак і ґанок")).isEqualTo("yizhak-i-ganok");
        assertThat(DeckSlug.of("Crème brûlée & Straße")).isEqualTo("creme-brulee-strasse");
        assertThat(DeckSlug.of("Łódź Øre Þing")).isEqualTo("lodz-ore-thing");
    }

    @Test
    void separatorsCollapseAndNeverLeadOrTrail() {
        assertThat(DeckSlug.of("  --Hello,   World!!  ")).isEqualTo("hello-world");
        assertThat(DeckSlug.of("a/b\\c?d#e")).isEqualTo("a-b-c-d-e");
        assertThat(DeckSlug.of("Deck 2026")).isEqualTo("deck-2026");
    }

    @Test
    void aTitleWithNothingToTransliterateGetsTheFallback() {
        assertThat(DeckSlug.of("日本語")).isEqualTo("deck");
        assertThat(DeckSlug.of("😀😀")).isEqualTo("deck");
        assertThat(DeckSlug.of("   ")).isEqualTo("deck");
        assertThat(DeckSlug.of("")).isEqualTo("deck");
        assertThat(DeckSlug.of(null)).isEqualTo("deck");
    }

    @Test
    void theSlugIsAtMostEightyCharactersAndNeverEndsWithAHyphen() {
        String slug = DeckSlug.of("слово ".repeat(40));
        assertThat(slug.length()).isLessThanOrEqualTo(DeckSlug.MAX_LENGTH);
        assertThat(slug).doesNotEndWith("-").startsWith("slovo-slovo");
        // 79 letters, a separator at 80: the cut must not leave a dangling hyphen
        String edge = DeckSlug.of("a".repeat(79) + " b");
        assertThat(edge).isEqualTo("a".repeat(79));
        assertThat(DeckSlug.of("a".repeat(200))).hasSize(80);
    }
}
