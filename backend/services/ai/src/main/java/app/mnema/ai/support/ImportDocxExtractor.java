package app.mnema.ai.support;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;

public final class ImportDocxExtractor {

    private ImportDocxExtractor() {
    }

    public static DocxText extract(byte[] bytes, int maxChars) {
        if (bytes == null || bytes.length == 0) {
            return new DocxText("", false);
        }
        int limit = Math.max(maxChars, 1);
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes);
             XWPFDocument document = new XWPFDocument(input);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String text = extractor.getText();
            if (text == null) {
                return new DocxText("", false);
            }
            boolean truncated = false;
            if (text.length() > limit) {
                text = text.substring(0, limit);
                truncated = true;
            }
            return new DocxText(text, truncated);
        } catch (EncryptedDocumentException ex) {
            throw new IllegalStateException("Encrypted DOCX is not supported", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse DOCX", ex);
        }
    }

    public record DocxText(String text, boolean truncated) {
    }
}
