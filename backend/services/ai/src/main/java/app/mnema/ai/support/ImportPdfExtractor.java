package app.mnema.ai.support;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

public final class ImportPdfExtractor {

    private ImportPdfExtractor() {
    }

    public static PdfText extract(byte[] bytes, int maxChars) {
        if (bytes == null || bytes.length == 0) {
            return new PdfText("", false);
        }
        int limit = Math.max(maxChars, 1);
        try (PDDocument document = PDDocument.load(bytes, MemoryUsageSetting.setupTempFileOnly())) {
            if (document.isEncrypted()) {
                throw new IllegalStateException("Encrypted PDF is not supported");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            if (text == null) {
                return new PdfText("", false);
            }
            boolean truncated = false;
            if (text.length() > limit) {
                text = text.substring(0, limit);
                truncated = true;
            }
            return new PdfText(text, truncated);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse PDF", ex);
        }
    }

    public record PdfText(String text, boolean truncated) {
    }
}
