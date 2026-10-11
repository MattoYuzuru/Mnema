package app.mnema.learning.library;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AliasNormalizerTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Español|espanol",
            "FRANÇAIS|francais",
            "  Русский   язык |русский язык",
            "Ёжик|ежик",
            "Türkçe|turkce",
            "ＪＡＶＡＳＣＲＩＰＴ|javascript",
            "Ελληνικά|ελληνικα",
            "Straße|straße",
            "ÇA va|ca va"
    })
    void lowersFoldsAndStripsMarksOfLatinAndGreekLetters(String raw, String expected) {
        assertThat(AliasNormalizer.normalize(raw)).isEqualTo(expected);
    }

    @Test
    void keepsTheMarksThatAreLettersOfTheirScript() {
        // й is not и, and the voicing marks of kana and the jamo of Hangul decide the word.
        assertThat(AliasNormalizer.normalize("Японский")).isEqualTo("японский").contains("й");
        assertThat(AliasNormalizer.normalize("がっこう")).isEqualTo("がっこう");
        assertThat(AliasNormalizer.normalize("한국어")).isEqualTo("한국어");
        assertThat(AliasNormalizer.normalize("日本語")).isEqualTo("日本語");
    }

    @Test
    void stripsArabicVowelMarksAndCollapsesAnyWhiteSpace() {
        assertThat(AliasNormalizer.normalize("اَلْعَرَبِيَّة")).isEqualTo("العربية");
        assertThat(AliasNormalizer.normalize("a \t b\n c")).isEqualTo("a b c");
        assertThat(AliasNormalizer.normalize("   ")).isEmpty();
        assertThat(AliasNormalizer.normalize(null)).isEmpty();
    }

    @Test
    void isIdempotent() {
        for (String text : new String[] {"Ёлка Ñandú", "ＡＢＣ  ｄｅｆ", "한국어  を", "ÀÉÎÕÜ"}) {
            String once = AliasNormalizer.normalize(text);
            assertThat(AliasNormalizer.normalize(once)).isEqualTo(once);
        }
    }
}
