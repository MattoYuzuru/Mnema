package app.mnema.ai.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImportItemExtractorTest {

    @Test
    void extractItemsReturnsEmptyWhenNotListLike() {
        String text = "This is a paragraph with more than six words.\nAnother sentence follows here.";

        List<String> items = ImportItemExtractor.extractItems(text);

        assertThat(items).isEmpty();
    }

    @Test
    void extractItemsStripsPrefixesAndDeduplicates() {
        String text = "1. Apple\n2) Banana\n- apple\n* Carrot\n5. Date";

        List<String> items = ImportItemExtractor.extractItems(text);

        assertThat(items).containsExactly("Apple", "Banana", "Carrot", "Date");
    }

    @Test
    void extractItemsHandlesShortLinesList() {
        String text = "one\ntwo\nthree\nfour\nfive\nsix";

        List<String> items = ImportItemExtractor.extractItems(text);

        assertThat(items).containsExactly("one", "two", "three", "four", "five", "six");
    }
}
