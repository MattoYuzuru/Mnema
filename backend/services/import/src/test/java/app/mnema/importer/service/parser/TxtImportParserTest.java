package app.mnema.importer.service.parser;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TxtImportParserTest {

    @Test
    void skipsBlankLinesAndReturnsTextRecords() throws Exception {
        TxtImportParser parser = new TxtImportParser();

        ImportPreview preview = parser.preview(
                new ByteArrayInputStream("\nfirst\n\nsecond\n".getBytes(StandardCharsets.UTF_8)),
                2
        );

        assertEquals(java.util.List.of("text"), preview.fields());
        assertEquals(2, preview.sample().size());
        assertEquals("first", preview.sample().getFirst().fields().get("text"));

        try (TxtImportParser.TxtImportStream stream = parser.openStream(
                new ByteArrayInputStream("\nfirst\n\nsecond\n".getBytes(StandardCharsets.UTF_8))
        )) {
            assertTrue(stream.hasNext());
            assertEquals("first", stream.next().fields().get("text"));
            assertEquals("second", stream.next().fields().get("text"));
            assertFalse(stream.hasNext());
        }
    }
}
