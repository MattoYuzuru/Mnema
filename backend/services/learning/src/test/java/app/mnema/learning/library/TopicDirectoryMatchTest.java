package app.mnema.learning.library;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TopicDirectoryMatchTest {
    @Test
    void anAliasMatchesAtWordBoundariesOnly() {
        assertThat(TopicDirectory.occurs("английский для начинающих", "английский")).isTrue();
        assertThat(TopicDirectory.occurs("курс js для всех", "js")).isTrue();
        assertThat(TopicDirectory.occurs("json и xml", "js")).isFalse();
        assertThat(TopicDirectory.occurs("jsx", "js")).isFalse();
        assertThat(TopicDirectory.occurs("sql и данные", "sql")).isTrue();
        assertThat(TopicDirectory.occurs("the data", "data")).isTrue();
        assertThat(TopicDirectory.occurs("database", "data")).isFalse();
    }

    @Test
    void scriptsWrittenWithoutSpacesMatchInsideARun() {
        assertThat(TopicDirectory.occurs("日本語の単語", "日本語")).isTrue();
        assertThat(TopicDirectory.occurs("word日本語word", "日本語")).isTrue();
        assertThat(TopicDirectory.occurs("한국어를 배우자", "한국어")).isTrue();
        assertThat(TopicDirectory.occurs("кандзи n5", "кандзи")).isTrue();
    }

    @Test
    void aPhraseMatchesAsAWhole() {
        assertThat(TopicDirectory.occurs("русский язык для школы", "русский язык")).isTrue();
        assertThat(TopicDirectory.occurs("русский языки", "русский язык")).isFalse();
        assertThat(TopicDirectory.occurs("", "js")).isFalse();
    }
}
