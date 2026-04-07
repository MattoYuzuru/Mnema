package app.mnema.importer.service.parser;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvImportParserTest {

    @Test
    void previewUsesHeaderRowWhenItLooksLikeFieldNames() throws Exception {
        CsvImportParser parser = new CsvImportParser(',');

        ImportPreview preview = parser.preview(
                new ByteArrayInputStream("Front,Back\nQuestion,Answer\n".getBytes(StandardCharsets.UTF_8)),
                1
        );

        assertEquals(java.util.List.of("Front", "Back"), preview.fields());
        assertEquals("Question", preview.sample().getFirst().fields().get("Front"));
    }

    @Test
    void openStreamFallsBackToDefaultHeadersWhenFirstRowLooksLikeData() throws Exception {
        CsvImportParser parser = new CsvImportParser('\t');

        try (CsvImportParser.CsvImportStream stream = parser.openStream(
                new ByteArrayInputStream("hello?\tworld!\nsecond\trow\n".getBytes(StandardCharsets.UTF_8))
        )) {
            assertEquals(java.util.List.of("front", "back"), stream.fields());
            assertTrue(stream.hasNext());

            ImportRecord first = stream.next();
            assertEquals("hello?", first.fields().get("front"));
            assertEquals("world!", first.fields().get("back"));

            ImportRecord second = stream.next();
            assertEquals("second", second.fields().get("front"));
            assertEquals("row", second.fields().get("back"));
            assertFalse(stream.hasNext());
        }
    }
}
