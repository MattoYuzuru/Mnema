package app.mnema.learning.library;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewTextTest {
    @Test
    void plainMakesOneLineOfAnyInput() {
        assertThat(PreviewText.plain("  Первая\n\nстрока\t\tи   вторая\u0000\u0007 ")).isEqualTo("Первая строка и вторая");
        assertThat(PreviewText.plain("a b c d")).isEqualTo("a b c d");
        assertThat(PreviewText.plain(null)).isEmpty();
        assertThat(PreviewText.plain(" \n\t ")).isEmpty();
        // markup is not interpreted here, only escaped later
        assertThat(PreviewText.plain("<b>x</b>")).isEqualTo("<b>x</b>");
    }

    @Test
    void bidirectionalFormattingCharactersAreDropped() {
        // an override or an isolate could reorder the text around it in a messenger card
        assertThat(PreviewText.plain("\u202Eevil\u202C text\u2066\u2069 \u200Eok\u200F\u061C")).isEqualTo("evil text ok");
        assertThat(PreviewText.plain("a\u202Ab")).isEqualTo("ab");
        assertThat(PreviewText.plain("\u202E\u2066")).isEmpty();
        for (int point : new int[] {0x200E, 0x200F, 0x061C, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069}) {
            assertThat(PreviewText.isBidiFormat(point)).as(Integer.toHexString(point)).isTrue();
        }
        assertThat(PreviewText.isBidiFormat('a')).isFalse();
        assertThat(PreviewText.isBidiFormat(0x200B)).isFalse();
    }

    @Test
    void textThatFitsIsReturnedAsItIs() {
        assertThat(PreviewText.limit("короткий текст", 200)).isEqualTo("короткий текст");
        assertThat(PreviewText.limit("12345", 5)).isEqualTo("12345");
    }

    @Test
    void longTextIsCutOnAWordBoundaryWithAnEllipsis() {
        String text = "слово ".repeat(60).strip();
        String cut = PreviewText.limit(text, 200);
        assertThat(cut).endsWith("слово…").doesNotContain("  ").hasSizeLessThanOrEqualTo(201);
        assertThat(cut.substring(0, cut.length() - 1)).isEqualTo(text.substring(0, cut.length() - 1));
        // the allowance ends exactly between two words
        assertThat(PreviewText.limit("aaaa bbbb cccc", 9)).isEqualTo("aaaa bbbb…");
    }

    @Test
    void aTextWithoutSpacesIsCutHardAndNeverInsideASurrogatePair() {
        String text = "😀".repeat(300);
        String cut = PreviewText.limit(text, 200);
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(201);
        assertThat(cut).endsWith("😀…");
        assertThat(PreviewText.limit("a".repeat(500), 200)).isEqualTo("a".repeat(200) + "…");
    }

    @Test
    void aWordBoundaryTooFarBackIsNotWorthShorteningFor() {
        // the only space is in the first tenth: keep the 200 characters instead of a tiny head
        String text = "ab " + "c".repeat(400);
        assertThat(PreviewText.limit(text, 200)).isEqualTo(("ab " + "c".repeat(197)) + "…");
    }

    @Test
    void trailingPunctuationBeforeTheEllipsisIsDropped() {
        assertThat(PreviewText.limit("первое, второе, третье, четвёртое", 15)).isEqualTo("первое, второе…");
    }
}
