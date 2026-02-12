package app.mnema.ai.service;

import app.mnema.ai.support.OcrEngine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfOcrServiceTest {

    @Test
    void limitsPagesForOcr() throws Exception {
        byte[] pdfBytes = createPdfWithPages(2);
        PdfOcrService service = new PdfOcrService(new StubOcrEngine("Page"), 1, 200, 1, 4, 5);
        try {
            PdfOcrService.PdfOcrResult result = service.extract(pdfBytes, 5000, "eng");
            assertEquals(2, result.pageCount());
            assertEquals(1, result.pagesProcessed());
            assertFalse(result.truncated());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void truncatesOcrText() throws Exception {
        byte[] pdfBytes = createPdfWithPages(1);
        PdfOcrService service = new PdfOcrService(new StubOcrEngine("abcdef"), 1, 200, 1, 4, 5);
        try {
            PdfOcrService.PdfOcrResult result = service.extract(pdfBytes, 4, "eng");
            assertTrue(result.truncated());
            assertEquals(4, result.text().length());
        } finally {
            service.shutdown();
        }
    }

    private byte[] createPdfWithPages(int pages) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                doc.save(out);
                return out.toByteArray();
            }
        }
    }

    private static final class StubOcrEngine implements OcrEngine {
        private final String text;

        private StubOcrEngine(String text) {
            this.text = text;
        }

        @Override
        public String extractText(java.awt.image.BufferedImage image, String language, int dpi) {
            return text;
        }
    }
}
