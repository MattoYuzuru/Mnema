package app.mnema.ai.provider.support;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ProviderRetrySupportTest {

    private final Logger logger = mock(Logger.class);

    @Test
    void executeTextRequestRetriesOnRateLimitResponses() {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> waits = new ArrayList<>();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "1");

        String value = ProviderRetrySupport.executeTextRequest(
                "Gemini",
                logger,
                2,
                100L,
                500L,
                waitMs -> {
                    waits.add(waitMs);
                    return true;
                },
                () -> 1.0d,
                () -> {
                    if (attempts.getAndIncrement() == 0) {
                        throw HttpClientErrorException.create(
                                HttpStatus.TOO_MANY_REQUESTS,
                                "Too Many Requests",
                                headers,
                                new byte[0],
                                StandardCharsets.UTF_8
                        );
                    }
                    return "ok";
                }
        );

        assertThat(value).isEqualTo("ok");
        assertThat(attempts).hasValue(2);
        assertThat(waits).containsExactly(1000L);
    }

    @Test
    void executeTextRequestRetriesOnTransportTimeouts() {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> waits = new ArrayList<>();

        String value = ProviderRetrySupport.executeTextRequest(
                "OpenAI",
                logger,
                2,
                120L,
                500L,
                waitMs -> {
                    waits.add(waitMs);
                    return true;
                },
                () -> 1.0d,
                () -> {
                    if (attempts.getAndIncrement() == 0) {
                        throw new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out"));
                    }
                    return "ok";
                }
        );

        assertThat(value).isEqualTo("ok");
        assertThat(attempts).hasValue(2);
        assertThat(waits).containsExactly(120L);
    }

    @Test
    void executeTextRequestDoesNotRetryOnNonRetryableClientErrors() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> ProviderRetrySupport.executeTextRequest(
                "Claude",
                logger,
                2,
                100L,
                500L,
                waitMs -> true,
                () -> 1.0d,
                () -> {
                    attempts.incrementAndGet();
                    throw HttpClientErrorException.create(
                            HttpStatus.BAD_REQUEST,
                            "Bad Request",
                            HttpHeaders.EMPTY,
                            new byte[0],
                            StandardCharsets.UTF_8
                    );
                }
        )).isInstanceOf(HttpClientErrorException.BadRequest.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void executeTextRequestFailsWhenRetrySleepIsInterrupted() {
        assertThatThrownBy(() -> ProviderRetrySupport.executeTextRequest(
                " ",
                null,
                1,
                0L,
                100L,
                waitMs -> false,
                () -> 1.0d,
                () -> {
                    throw HttpClientErrorException.create(
                            HttpStatus.TOO_MANY_REQUESTS,
                            "Too Many Requests",
                            HttpHeaders.EMPTY,
                            new byte[0],
                            StandardCharsets.UTF_8
                    );
                }
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("AI provider retry interrupted");
    }

    @Test
    void resolveRetryAfterUsesHeaderAndMessageFallbacks() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "2");
        RestClientResponseException withHeader = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                headers,
                new byte[0],
                StandardCharsets.UTF_8
        );
        RestClientResponseException withMessage = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                HttpHeaders.EMPTY,
                "retry in 1.5s".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );

        assertThat(ProviderRetrySupport.resolveRetryAfterMs(withHeader)).isEqualTo(2000L);
        assertThat(ProviderRetrySupport.resolveRetryAfterMs(withMessage)).isEqualTo(1500L);
        assertThat(ProviderRetrySupport.parseRetryAfterMessage("wait please")).isNull();
    }

    @Test
    void parseRetryAfterHeaderSupportsRfc1123Dates() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(Instant.now().plus(3, ChronoUnit.SECONDS), ZoneOffset.UTC)));

        Long waitMs = ProviderRetrySupport.parseRetryAfterHeader(headers);

        assertThat(waitMs).isNotNull();
        assertThat(waitMs).isBetween(1L, 4000L);
        assertThat(ProviderRetrySupport.parseRetryAfterHeader(HttpHeaders.EMPTY)).isNull();
    }

    @Test
    void detectsRetryableTransportFailures() {
        assertThat(ProviderRetrySupport.isRetryableTransportFailure(new ConnectException("connection refused"))).isTrue();
        assertThat(ProviderRetrySupport.isRetryableTransportFailure(new ResourceAccessException("Premature EOF"))).isTrue();
        assertThat(ProviderRetrySupport.isRetryableTransportFailure(new InterruptedIOException("interrupted"))).isFalse();
    }
}
