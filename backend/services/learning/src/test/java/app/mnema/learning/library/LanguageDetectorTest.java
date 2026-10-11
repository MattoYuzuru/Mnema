package app.mnema.learning.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageDetectorTest {
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            // Cyrillic: Russian by default, the letters of one language decide the others
            "Японский язык для начинающих. Слова и фразы.|ru",
            "Вода|ru",
            "Українська мова: слова та вирази|uk",
            "Беларуская мова: словы і выразы ў дзень|be",
            "Српски језик: речи и изрази ђаци|",
            // scripts
            "ひらがなとカタカナ|ja",
            "日本語の単語 N5|ja",
            "汉语词汇表 HSK 词汇|zh",
            "한국어 기초 단어 모음 TOPIK|ko",
            "Ελληνικά για αρχάριους|el",
            "עברית למתחילים|he",
            "ภาษาไทยสำหรับผู้เริ่มต้น|th",
            "اللغة العربية|",
            "हिंदी शब्द|",
            // Latin: stopwords with weights, letters unique to one language
            "The basics of the English language and how to learn it|en",
            "Los verbos de la lengua española y para qué sirven|es",
            "Les verbes de la langue française et leurs usages dans la vie|fr",
            "Die Verben der deutschen Sprache und ihre Verwendung|de",
            "I verbi della lingua italiana e come si usano nella vita|it",
            "Os verbos da língua portuguesa e como se usam no dia a dia|pt",
            "Straße, Übung, Köln, Tür|de",
            "¿Qué es esto? ¡Mañana!|es",
            // too short, too shared or not covered: unsure
            "Python basics|",
            "Irregular verbs|",
            "Merhaba dünya, nasılsın bugün|",
            "Zażółć gęślą jaźń, the and of|",
            "123 456 -- ??|",
            "|"
    })
    void table(String text, String expected) {
        assertThat(LanguageDetector.detect(text)).isEqualTo(Optional.ofNullable(expected == null || expected.isBlank() ? null : expected));
    }

    @Test
    void aMixedTextIsDecidedByWhatDominatesAndASplitTextIsUnsure() {
        // a Russian title around a few English words is Russian
        assertThat(LanguageDetector.detect("Английский: apple, banana, cherry — слова для начинающих и не только")).contains("ru");
        // half and half: no script holds 60 percent of the letters
        assertThat(LanguageDetector.detect("абвгд abcde")).isEmpty();
    }

    @Test
    void kanaDecidesJapaneseOnlyWithHanAndHanAloneIsChinese() {
        assertThat(LanguageDetector.detect("漢字")).contains("zh");
        assertThat(LanguageDetector.detect("漢字と仮名")).contains("ja");
    }

    @Test
    void isDeterministicAndHandlesNothing() {
        assertThat(LanguageDetector.detect(null)).isEmpty();
        assertThat(LanguageDetector.detect("   ")).isEmpty();
        String text = "The basics of the English language and how to learn it";
        for (int i = 0; i < 20; i++) assertThat(LanguageDetector.detect(text)).contains("en");
    }
}
