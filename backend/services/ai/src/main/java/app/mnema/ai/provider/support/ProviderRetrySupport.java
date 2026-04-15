package app.mnema.ai.provider.support;

import org.slf4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProviderRetrySupport {

    private static final Pattern RETRY_IN_PATTERN = Pattern.compile("retry in\\s*([0-9]+(?:\\.[0-9]+)?)s", Pattern.CASE_INSENSITIVE);
    private static final int DEFAULT_TEXT_MAX_RETRIES = 4;
    private static final long DEFAULT_TEXT_RETRY_INITIAL_DELAY_MS = 2_000L;
    private static final long DEFAULT_TEXT_RETRY_MAX_DELAY_MS = 30_000L;
    private static final double MIN_JITTER_RATIO = 0.85d;
    private static final double MAX_JITTER_RATIO = 1.15d;

    private ProviderRetrySupport() {
    }

    public static <T> T executeTextRequest(String provider,
                                           Logger logger,
                                           RetriableSupplier<T> supplier) {
        return executeTextRequest(
                provider,
                logger,
                DEFAULT_TEXT_MAX_RETRIES,
                DEFAULT_TEXT_RETRY_INITIAL_DELAY_MS,
                DEFAULT_TEXT_RETRY_MAX_DELAY_MS,
                ProviderRetrySupport::sleepQuietly,
                () -> ThreadLocalRandom.current().nextDouble(MIN_JITTER_RATIO, MAX_JITTER_RATIO),
                supplier
        );
    }

    public static <T> T executeTextRequest(String provider,
                                           Logger logger,
                                           int maxRetries,
                                           RetriableSupplier<T> supplier) {
        return executeTextRequest(
                provider,
                logger,
                maxRetries,
                DEFAULT_TEXT_RETRY_INITIAL_DELAY_MS,
                DEFAULT_TEXT_RETRY_MAX_DELAY_MS,
                ProviderRetrySupport::sleepQuietly,
                () -> ThreadLocalRandom.current().nextDouble(MIN_JITTER_RATIO, MAX_JITTER_RATIO),
                supplier
        );
    }

    static <T> T executeTextRequest(String provider,
                                    Logger logger,
                                    int maxRetries,
                                    long initialDelayMs,
                                    long maxDelayMs,
                                    Sleeper sleeper,
                                    Jitter jitter,
                                    RetriableSupplier<T> supplier) {
        String safeProvider = provider == null || provider.isBlank() ? "AI provider" : provider.trim();
        int safeRetries = Math.max(0, maxRetries);
        long nextDelayMs = normalizeInitialDelay(initialDelayMs);
        long safeMaxDelayMs = Math.max(nextDelayMs, maxDelayMs);
        int attempts = 0;
        while (true) {
            try {
                return supplier.get();
            } catch (RestClientResponseException ex) {
                if (!isRetryableStatus(ex.getRawStatusCode()) || attempts >= safeRetries) {
                    throw ex;
                }
                long waitMs = resolveRetryAfterMs(ex);
                if (waitMs <= 0L) {
                    waitMs = jitterDelay(nextDelayMs, jitter);
                    nextDelayMs = Math.min(nextDelayMs * 2L, safeMaxDelayMs);
                } else {
                    nextDelayMs = Math.min(Math.max(nextDelayMs, waitMs), safeMaxDelayMs);
                }
                logRetry(logger, safeProvider, "response", attempts + 1, waitMs, ex.getRawStatusCode(), summarizeError(ex));
                if (!sleeper.sleep(waitMs)) {
                    throw new IllegalStateException(safeProvider + " retry interrupted");
                }
                attempts++;
            } catch (RestClientException ex) {
                if (!isRetryableTransportFailure(ex) || attempts >= safeRetries) {
                    throw ex;
                }
                long waitMs = jitterDelay(nextDelayMs, jitter);
                nextDelayMs = Math.min(nextDelayMs * 2L, safeMaxDelayMs);
                logRetry(logger, safeProvider, "transport", attempts + 1, waitMs, null, summarizeError(ex));
                if (!sleeper.sleep(waitMs)) {
                    throw new IllegalStateException(safeProvider + " retry interrupted");
                }
                attempts++;
            }
        }
    }

    public static boolean isRetryableStatus(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    public static long resolveRetryAfterMs(RestClientResponseException ex) {
        if (ex == null) {
            return 0L;
        }
        Long headerMs = parseRetryAfterHeader(ex.getResponseHeaders());
        if (headerMs != null) {
            return headerMs;
        }
        Long bodyMs = parseRetryAfterMessage(ex.getResponseBodyAsString());
        if (bodyMs != null) {
            return bodyMs;
        }
        Long messageMs = parseRetryAfterMessage(ex.getMessage());
        return messageMs == null ? 0L : messageMs;
    }

    public static boolean isRetryableTransportFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof NoRouteToHostException
                    || current instanceof UnknownHostException) {
                return true;
            }
            if (current instanceof IOException && !(current instanceof InterruptedIOException)) {
                return true;
            }
            if (current instanceof SocketException socketException) {
                String message = socketException.getMessage();
                if (message != null && message.toLowerCase(Locale.ROOT).contains("reset")) {
                    return true;
                }
            }
            if (current instanceof ResourceAccessException resourceAccessException) {
                String message = resourceAccessException.getMessage();
                if (message != null) {
                    String lower = message.toLowerCase(Locale.ROOT);
                    if (lower.contains("timed out")
                            || lower.contains("timeout")
                            || lower.contains("connection reset")
                            || lower.contains("connection refused")
                            || lower.contains("premature eof")) {
                        return true;
                    }
                }
            }
            current = current.getCause();
        }
        return false;
    }

    static Long parseRetryAfterHeader(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String value = headers.getFirst("Retry-After");
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            if (seconds >= 0) {
                return seconds * 1000L;
            }
        } catch (NumberFormatException ignored) {
        }
        try {
            Instant retryAt = DateTimeFormatter.RFC_1123_DATE_TIME.parse(trimmed, Instant::from);
            return Math.max(0L, retryAt.toEpochMilli() - System.currentTimeMillis());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    static Long parseRetryAfterMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher matcher = RETRY_IN_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        try {
            double seconds = Double.parseDouble(matcher.group(1));
            if (seconds < 0d) {
                return null;
            }
            return Math.round(seconds * 1000d);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static long normalizeInitialDelay(long initialDelayMs) {
        return initialDelayMs <= 0L ? DEFAULT_TEXT_RETRY_INITIAL_DELAY_MS : initialDelayMs;
    }

    private static long jitterDelay(long waitMs, Jitter jitter) {
        long safeWaitMs = Math.max(1L, waitMs);
        double ratio = jitter == null ? 1.0d : jitter.nextRatio();
        return Math.max(1L, Math.round(safeWaitMs * ratio));
    }

    private static void logRetry(Logger logger,
                                 String provider,
                                 String kind,
                                 int attempt,
                                 long waitMs,
                                 Integer status,
                                 String message) {
        if (logger == null) {
            return;
        }
        if (status == null) {
            logger.warn("{} {} retry attempt={} waitMs={} message={}", provider, kind, attempt, waitMs, message);
            return;
        }
        logger.warn("{} {} retry attempt={} waitMs={} status={} message={}",
                provider,
                kind,
                attempt,
                waitMs,
                status,
                message);
    }

    private static String summarizeError(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null) {
            return null;
        }
        String message = throwable.getMessage().replaceAll("[\\r\\n]+", " ").trim();
        return message.length() > 300 ? message.substring(0, 300) + "..." : message;
    }

    private static boolean sleepQuietly(long waitMs) {
        if (waitMs <= 0L) {
            return true;
        }
        try {
            Thread.sleep(waitMs);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @FunctionalInterface
    public interface RetriableSupplier<T> {
        T get() throws RestClientException;
    }

    @FunctionalInterface
    interface Sleeper {
        boolean sleep(long waitMs);
    }

    @FunctionalInterface
    interface Jitter {
        double nextRatio();
    }
}
