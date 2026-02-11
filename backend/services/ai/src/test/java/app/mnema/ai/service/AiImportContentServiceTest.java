package app.mnema.ai.service;

import app.mnema.ai.client.media.MediaApiClient;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiImportContentServiceTest {

    @Test
    void fallsBackToOcrWhenPdfTextIsEmpty() throws Exception {
        byte[] pdfBytes = createPdfWithPages(2);
        PdfOcrService pdfOcrService = mock(PdfOcrService.class);
        when(pdfOcrService.extract(eq(pdfBytes), anyInt(), eq("eng")))
                .thenReturn(new PdfOcrService.PdfOcrResult("OCR text", false, 2, 2));
        AiImportContentService service = new AiImportContentService(
                mock(MediaApiClient.class),
                pdfOcrService,
                10_485_760L,
                31_457_280L,
                20_971_520L,
                52_428_800L,
                1000,
                10,
                30
        );

        AiImportContentService.ImportTextPayload payload = service.extractText(
                new AiImportContentService.ImportSourcePayload(pdfBytes, "application/pdf", pdfBytes.length, false),
                null,
                "en"
        );

        assertEquals("ocr", payload.extraction());
        assertEquals("OCR text", payload.text());
        assertEquals(2, payload.sourcePages());
        assertEquals(2, payload.ocrPages());
        assertFalse(payload.truncated());
        ArgumentCaptor<String> languageCaptor = ArgumentCaptor.forClass(String.class);
        verify(pdfOcrService).extract(eq(pdfBytes), anyInt(), languageCaptor.capture());
        assertEquals("eng", languageCaptor.getValue());
    }

    @Test
    void truncatesTextSourcesAboveMaxChars() {
        PdfOcrService pdfOcrService = mock(PdfOcrService.class);
        AiImportContentService service = new AiImportContentService(
                mock(MediaApiClient.class),
                pdfOcrService,
                10_485_760L,
                31_457_280L,
                20_971_520L,
                52_428_800L,
                1000,
                50,
                30
        );
        String longText = "x".repeat(1200);
        byte[] bytes = longText.getBytes(StandardCharsets.UTF_8);

        AiImportContentService.ImportTextPayload payload = service.extractText(
                new AiImportContentService.ImportSourcePayload(bytes, "text/plain", bytes.length, false),
                "utf-8",
                null
        );

        assertTrue(payload.truncated());
        assertEquals(1000, payload.charCount());
        assertNotNull(payload.text());
        assertEquals(1000, payload.text().length());
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
}
