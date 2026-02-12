package app.mnema.ai.service;

import app.mnema.ai.support.OcrEngine;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;

@Service
public class PdfOcrService {

    private static final Logger log = LoggerFactory.getLogger(PdfOcrService.class);

    private final OcrEngine ocrEngine;
    private final int maxPages;
    private final int dpi;
    private final int parallelism;
    private final int queueCapacity;
    private final Duration pageTimeout;
    private final ExecutorService executor;

    public PdfOcrService(OcrEngine ocrEngine,
                         @Value("${app.ai.import.pdf-ocr-max-pages:10}") int maxPages,
                         @Value("${app.ai.import.pdf-ocr-dpi:200}") int dpi,
                         @Value("${app.ai.import.pdf-ocr-parallelism:2}") int parallelism,
                         @Value("${app.ai.import.pdf-ocr-queue-capacity:64}") int queueCapacity,
                         @Value("${app.ai.import.pdf-ocr-page-timeout-seconds:20}") long pageTimeoutSeconds) {
        this.ocrEngine = ocrEngine;
        this.maxPages = Math.max(maxPages, 1);
        this.dpi = Math.max(dpi, 72);
        this.parallelism = Math.max(parallelism, 1);
        int resolvedQueue = queueCapacity > 0 ? queueCapacity : this.parallelism * 4;
        this.queueCapacity = Math.max(resolvedQueue, this.parallelism);
        this.pageTimeout = Duration.ofSeconds(Math.max(pageTimeoutSeconds, 5));
        this.executor = new ThreadPoolExecutor(
                this.parallelism,
                this.parallelism,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(this.queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "pdf-ocr-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public PdfOcrResult extract(byte[] pdfBytes, int maxChars, String language) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return new PdfOcrResult("", false, 0, 0);
        }
        int limit = Math.max(maxChars, 1);
        MemoryUsageSetting memory = MemoryUsageSetting.setupTempFileOnly();
        try (PDDocument document = Loader.loadPDF(pdfBytes, null, null, null, memory.streamCache)) {
            if (document.isEncrypted()) {
                throw new IllegalStateException("Encrypted PDF is not supported");
            }
            int pageCount = document.getNumberOfPages();
            int pagesToProcess = Math.min(pageCount, maxPages);
            PDFRenderer renderer = new PDFRenderer(document);
            StringBuilder combined = new StringBuilder();
            boolean truncated = false;
            List<Future<PageResult>> pending = new ArrayList<>();

            for (int pageIndex = 0; pageIndex < pagesToProcess; pageIndex++) {
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
                int currentIndex = pageIndex;
                pending.add(executor.submit(() -> new PageResult(currentIndex, ocrEngine.extractText(image, language, dpi))));

                if (pending.size() >= parallelism || pageIndex == pagesToProcess - 1) {
                    List<PageResult> results = collectBatch(pending);
                    pending.clear();
                    results.sort(Comparator.comparingInt(PageResult::index));
                    for (PageResult result : results) {
                        if (result.text() != null && !result.text().isBlank()) {
                            if (!combined.isEmpty()) {
                                combined.append("\n\n");
                            }
                            combined.append(result.text().trim());
                        }
                        if (combined.length() >= limit) {
                            truncated = true;
                            combined.setLength(limit);
                            break;
                        }
                    }
                    if (truncated) {
                        break;
                    }
                }
            }

            return new PdfOcrResult(combined.toString(), truncated, pageCount, pagesToProcess);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to run OCR on PDF", ex);
        }
    }

    private List<PageResult> collectBatch(List<Future<PageResult>> futures) {
        List<PageResult> results = new ArrayList<>();
        for (Future<PageResult> future : futures) {
            try {
                results.add(future.get(pageTimeout.toMillis(), TimeUnit.MILLISECONDS));
            } catch (TimeoutException ex) {
                future.cancel(true);
                log.warn("OCR page timed out after {}s", pageTimeout.toSeconds());
            } catch (Exception ex) {
                log.warn("OCR page failed: {}", ex.getClass().getSimpleName());
            }
        }
        return results;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private record PageResult(int index, String text) {
    }

    public record PdfOcrResult(String text, boolean truncated, int pageCount, int pagesProcessed) {
    }
}
