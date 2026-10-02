package app.mnema.learning.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** Builders for the provider layer's configuration in tests. */
final class AiTestSupport {
    static final AiProperties.Model FLASH = new AiProperties.Model("deepseek", "deepseek-flash", 6_000, 300_000, 1_200_000);
    static final AiProperties.Model PRO = new AiProperties.Model("deepseek", "deepseek-v4-pro", 44_000, 1_320_000, 3_960_000);
    static final AiProperties.Model GIGA = new AiProperties.Model("gigachat", "GigaChat-2", 765_000, 765_000, 765_000);
    static final String USER_KEY = "k1.abcdefghijklmnopqrstuvwxyz";

    private AiTestSupport() { }

    static AiProperties properties(String provider, AiProperties.Routes routes, Map<String, AiProperties.Provider> providers) {
        return new AiProperties(provider, routes, providers, List.of(FLASH, PRO, GIGA),
                new AiProperties.Transport(Duration.ofSeconds(2), Duration.ofMillis(400), 1 << 20),
                new AiProperties.Retry(3, 6, Duration.ofMillis(10), Duration.ofMillis(100), Duration.ofSeconds(30)),
                new AiProperties.Breaker(5, Duration.ofSeconds(60), Duration.ofSeconds(30)),
                new AiProperties.Permits(16, 4, 2, 1, 4, 16, 4, Duration.ofMillis(50)),
                new AiProperties.Budget("Europe/Moscow", 10_000_000, 5_000_000, 5_000_000, 5_000_000, 5_000_000,
                        5_000_000, 5_000_000, Duration.ofSeconds(10)),
                new AiProperties.UserKey("0123456789abcdef0123456789abcdef", "k1"),
                new AiProperties.Prompt("v1", 32_000, 25_000));
    }

    static AiProperties.Routes routes(List<String> fast, List<String> strong, List<String> assess) {
        return new AiProperties.Routes(fast, strong, assess);
    }

    static AiProperties.Provider provider(String baseUrl, String apiKey) {
        return new AiProperties.Provider(true, baseUrl, apiKey, "", "", "GIGACHAT_API_PERS");
    }

    static TextRequest request() { return request(null); }

    static TextRequest request(StreamListener listener) {
        return new TextRequest(AiRoute.TEXT_FAST, List.of(TextRequest.Segment.system("СИСТЕМА", true),
                TextRequest.Segment.user("БРИФ", true), TextRequest.Segment.user("ЗАДАЧА", false)),
                OutputContract.MBM_TEXT, 1_000, 0.7, Duration.ofSeconds(20), USER_KEY, listener, null, 1);
    }

    static TextRequest jsonRequest() {
        return new TextRequest(AiRoute.TEXT_FAST, List.of(TextRequest.Segment.user("верни json", false)),
                OutputContract.JSON, 1_000, 0.3, Duration.ofSeconds(20), USER_KEY, null, null, 1);
    }

    /** A clock that only moves when told to. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) { this.now = start; }

        void advance(Duration duration) { now = now.plus(duration); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return now; }
    }
}
