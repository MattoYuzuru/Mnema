package app.mnema.ai.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportPdfExtractorTest {

    @Test
    void extractsTextFromPdf() throws Exception {
        byte[] pdfBytes = createPdf("Hello PDF");
        ImportPdfExtractor.PdfText result = ImportPdfExtractor.extract(pdfBytes, 1000);
        assertTrue(result.text().contains("Hello PDF"));
        assertFalse(result.truncated());
    }

    @Test
    void truncatesLongPdfText() throws Exception {
        byte[] pdfBytes = createPdf("Truncate this");
        ImportPdfExtractor.PdfText result = ImportPdfExtractor.extract(pdfBytes, 4);
        assertTrue(result.text().length() <= 4);
        assertTrue(result.truncated());
    }

    private byte[] createPdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(72, 720);
                content.showText(text);
                content.endText();
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                doc.save(out);
                return out.toByteArray();
            }
        }
    }
}
