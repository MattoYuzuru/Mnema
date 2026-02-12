package app.mnema.ai.support;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportDocxExtractorTest {

    @Test
    void extractsDocxText() throws Exception {
        byte[] docx = createDocx("Hello DOCX");
        ImportDocxExtractor.DocxText result = ImportDocxExtractor.extract(docx, 1000);
        assertTrue(result.text().contains("Hello DOCX"));
        assertFalse(result.truncated());
    }

    @Test
    void truncatesDocxText() throws Exception {
        byte[] docx = createDocx("abcdef");
        ImportDocxExtractor.DocxText result = ImportDocxExtractor.extract(docx, 4);
        assertEquals(4, result.text().length());
        assertTrue(result.truncated());
    }

    private byte[] createDocx(String text) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFRun run = document.createParagraph().createRun();
            run.setText(text);
            document.write(out);
            return out.toByteArray();
        }
    }
}
