package app.mnema.ai.service;

import app.mnema.ai.client.media.MediaApiClient;
import app.mnema.ai.client.media.MediaResolved;
import app.mnema.ai.support.ImportPdfExtractor;
import app.mnema.ai.support.ImportTextDecoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AiImportContentService {

    private static final long KB = 1024L;
    private static final long MB = 1024L * KB;
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_PREVIEW_CARDS = 500;

    private final MediaApiClient mediaApiClient;
    private final HttpClient httpClient;
    private final long maxBytes;
    private final long maxPdfBytes;
    private final int maxChars;
    private final Duration timeout;

    public AiImportContentService(MediaApiClient mediaApiClient,
                                  @Value("${app.ai.import.max-bytes:10485760}") long maxBytes,
                                  @Value("${app.ai.import.pdf-max-bytes:31457280}") long maxPdfBytes,
                                  @Value("${app.ai.import.max-chars:200000}") int maxChars,
                                  @Value("${app.ai.import.download-timeout-seconds:30}") long timeoutSeconds) {
        this.mediaApiClient = mediaApiClient;
        this.maxBytes = Math.max(maxBytes, MB);
        this.maxPdfBytes = Math.max(maxPdfBytes, MB);
        this.maxChars = Math.max(maxChars, 1000);
        this.timeout = Duration.ofSeconds(Math.max(timeoutSeconds, 5));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public ImportTextPayload loadText(UUID mediaId, String encoding, String accessToken) {
        if (mediaId == null) {
            throw new IllegalStateException("sourceMediaId is required");
        }
        MediaResolved resolved = resolveMedia(mediaId, accessToken);
        String url = resolved.url();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Source media URL is missing");
        }
        String mimeType = normalizeMimeType(resolved.mimeType());
        if (mimeType == null || !(mimeType.startsWith("text/") || "application/pdf".equals(mimeType))) {
            throw new IllegalStateException("Unsupported source media type: " + resolved.mimeType());
        }
        long declaredSize = resolved.sizeBytes() == null ? -1 : resolved.sizeBytes();
        boolean isPdf = "application/pdf".equals(mimeType);
        DownloadResult download = downloadLimited(url, resolveMaxBytes(mimeType));
        if (isPdf) {
            if (download.truncated()) {
                throw new IllegalStateException("PDF is too large to parse. Please upload a smaller file.");
            }
            ImportPdfExtractor.PdfText pdfText = ImportPdfExtractor.extract(download.bytes(), maxChars);
            String text = normalizeText(pdfText.text());
            boolean truncated = pdfText.truncated();
            if (text.isBlank()) {
                throw new IllegalStateException("PDF text is empty");
            }
            return new ImportTextPayload(
                    text,
                    mimeType,
                    declaredSize,
                    truncated,
                    text.length(),
                    "pdf",
                    MAX_PREVIEW_CARDS
            );
        }
        ImportTextDecoder.DecodedText decoded = ImportTextDecoder.decode(download.bytes(), encoding);
        String text = normalizeText(decoded.text());
        boolean truncated = download.truncated();
        if (text.isBlank()) {
            throw new IllegalStateException("Source text is empty");
        }
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars);
            truncated = true;
        }
        return new ImportTextPayload(
                text,
                mimeType,
                declaredSize,
                truncated,
                text.length(),
                decoded.charset().name(),
                MAX_PREVIEW_CARDS
        );
    }

    private MediaResolved resolveMedia(UUID mediaId, String accessToken) {
        List<MediaResolved> resolved = mediaApiClient.resolve(List.of(mediaId), accessToken);
        if (resolved == null || resolved.isEmpty()) {
            throw new IllegalStateException("Source media not found");
        }
        MediaResolved item = resolved.get(0);
        if (item == null || item.mediaId() == null) {
            throw new IllegalStateException("Source media not found");
        }
        if (item.kind() != null && !"ai_import".equalsIgnoreCase(item.kind())) {
            throw new IllegalStateException("Source media kind is not supported");
        }
        return item;
    }

    private DownloadResult downloadLimited(String url, long limitBytes) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Failed to download source media");
            }
            try (InputStream inputStream = response.body()) {
                return readLimited(inputStream, limitBytes);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to download source media", ex);
        }
    }

    private DownloadResult readLimited(InputStream inputStream, long limitBytes) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(limitBytes, 64 * KB));
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0L;
        boolean truncated = false;
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            if (total + read > limitBytes) {
                int allowed = (int) Math.max(0, limitBytes - total);
                if (allowed > 0) {
                    output.write(buffer, 0, allowed);
                    total += allowed;
                }
                truncated = true;
                break;
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return new DownloadResult(output.toByteArray(), truncated);
    }


    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\u0000', ' ').trim();
        return normalized;
    }

    private String normalizeMimeType(String mimeType) {
        if (mimeType == null) {
            return null;
        }
        String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
        int idx = normalized.indexOf(';');
        if (idx > 0) {
            normalized = normalized.substring(0, idx).trim();
        }
        return normalized.isBlank() ? null : normalized;
    }

    private long resolveMaxBytes(String mimeType) {
        if ("application/pdf".equals(mimeType)) {
            return maxPdfBytes;
        }
        return maxBytes;
    }

    public record ImportTextPayload(
            String text,
            String mimeType,
            long sizeBytes,
            boolean truncated,
            int charCount,
            String detectedCharset,
            int maxRecommendedCards
    ) {
    }

    private record DownloadResult(byte[] bytes, boolean truncated) {
    }

}
