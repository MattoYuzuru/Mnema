package app.mnema.learning.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code learning.ai.*}: providers, routes, price table, resilience limits and the daily budget. Secrets arrive only
 * through {@code api-key}/{@code auth-key} (environment names in {@code application.properties}); validation messages
 * never include a value.
 *
 * @param provider {@code stub} forces the deterministic Stub on every text route (local/CI); empty uses the routes
 * @param providers by provider id: {@code deepseek}, {@code gigachat}, {@code openrouter}
 * @param models the price table; every route entry must name a listed model
 */
@ConfigurationProperties("learning.ai")
public record AiProperties(
        @DefaultValue("") String provider,
        @DefaultValue Routes routes,
        Map<String, Provider> providers,
        List<Model> models,
        @DefaultValue Transport transport,
        @DefaultValue Retry retry,
        @DefaultValue Breaker breaker,
        @DefaultValue Permits permits,
        @DefaultValue Budget budget,
        @DefaultValue UserKey userKey,
        @DefaultValue Prompt prompt) {

    public static final String STUB = "stub";

    public AiProperties {
        provider = provider == null ? "" : provider.strip().toLowerCase(Locale.ROOT);
        if (!provider.isEmpty() && !provider.equals(STUB)) throw new IllegalArgumentException("Unknown learning.ai.provider");
        providers = providers == null ? Map.of() : Map.copyOf(providers);
        models = models == null ? List.of() : List.copyOf(models);
    }

    /** Ordered {@code provider:model} lists; the first usable entry wins, the next ones are fallbacks. */
    public record Routes(@DefaultValue List<String> textFast, @DefaultValue List<String> textStrong,
                         @DefaultValue List<String> assess, @DefaultValue("PT8S") Duration assessAttemptCap,
                         @DefaultValue List<String> plan, @DefaultValue List<String> planStrong) {
        @ConstructorBinding
        public Routes {
            textFast = textFast == null ? List.of() : List.copyOf(textFast);
            textStrong = textStrong == null ? List.of() : List.copyOf(textStrong);
            assess = assess == null ? List.of() : List.copyOf(assess);
            plan = plan == null ? List.of() : List.copyOf(plan);
            planStrong = planStrong == null ? List.of() : List.copyOf(planStrong);
            if (assessAttemptCap == null || assessAttemptCap.isNegative() || assessAttemptCap.isZero()) {
                throw new IllegalArgumentException("Invalid assess attempt cap");
            }
        }

        public Routes(List<String> textFast, List<String> textStrong, List<String> assess, Duration assessAttemptCap) {
            this(textFast, textStrong, assess, assessAttemptCap, List.of(), List.of());
        }

        public Routes(List<String> textFast, List<String> textStrong, List<String> assess) {
            this(textFast, textStrong, assess, Duration.ofSeconds(8));
        }

        /**
         * The longest one provider attempt of {@code route} may take, or null for no cap beyond the deadline of the call. The grading route has
         * one so that a slow first provider leaves time for the fallback inside the 20 s deadline of an answer.
         */
        public Duration attemptCap(AiRoute route) { return route == AiRoute.ASSESS ? assessAttemptCap : null; }

        public List<String> of(AiRoute route) {
            return switch (route) {
                case TEXT_FAST -> textFast;
                case TEXT_STRONG -> textStrong;
                case ASSESS -> assess;
                case PLAN -> plan;
                case PLAN_STRONG -> planStrong;
            };
        }
    }

    /**
     * One provider. {@code enabled=false} is the per-provider kill switch. DeepSeek and OpenRouter use {@code apiKey};
     * GigaChat exchanges {@code authKey} for a short-lived token at {@code authUrl}.
     */
    public record Provider(@DefaultValue("true") boolean enabled, String baseUrl, String apiKey, String authUrl,
                           String authKey, @DefaultValue("GIGACHAT_API_PERS") String scope) {
        public Provider {
            baseUrl = baseUrl == null ? "" : baseUrl.strip();
            apiKey = apiKey == null ? "" : apiKey.strip();
            authUrl = authUrl == null ? "" : authUrl.strip();
            authKey = authKey == null ? "" : authKey.strip();
            scope = scope == null || scope.isBlank() ? "GIGACHAT_API_PERS" : scope.strip();
            requireEndpoint(baseUrl);
            requireEndpoint(authUrl);
        }

        /** Never prints a credential. */
        @Override
        public String toString() {
            return "Provider[enabled=" + enabled + ", baseUrl=" + baseUrl + ", apiKey=" + (apiKey.isEmpty() ? "unset" : "<redacted>")
                    + ", authKey=" + (authKey.isEmpty() ? "unset" : "<redacted>") + "]";
        }

        private static void requireEndpoint(String value) {
            if (value.isEmpty()) return;
            URI uri;
            try {
                uri = URI.create(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid provider URL");
            }
            boolean loopback = "http".equals(uri.getScheme()) && ("127.0.0.1".equals(uri.getHost())
                    || "localhost".equals(uri.getHost()) || "[::1]".equals(uri.getHost()));
            if (!"https".equals(uri.getScheme()) && !loopback || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("Provider URLs must be https (http only on loopback) without credentials");
            }
        }
    }

    /** Price of one model in micro-US-dollars per one million tokens. */
    public record Model(String provider, String id, long hitMicrosPerMillion, long missMicrosPerMillion,
                        long outputMicrosPerMillion) {
        public Model {
            if (provider == null || provider.isBlank() || id == null || id.isBlank()) {
                throw new IllegalArgumentException("A model needs a provider and an id");
            }
            if (hitMicrosPerMillion < 0 || missMicrosPerMillion < 0 || outputMicrosPerMillion < 0) {
                throw new IllegalArgumentException("Prices must not be negative");
            }
        }
    }

    /**
     * {@code firstByte} bounds the wait for the response headers (a silent provider must not eat the whole deadline, or
     * fallback could never happen); {@code idleStream} bounds silence inside a body. A non-streamed call receives its
     * headers when the provider starts answering, so raise {@code firstByte} if non-streamed answers take longer.
     */
    public record Transport(@DefaultValue("5s") Duration connectTimeout, @DefaultValue("60s") Duration idleStream,
                            @DefaultValue("4194304") int maxBodyBytes, @DefaultValue("60s") Duration firstByte) {
        public Transport {
            if (connectTimeout.isNegative() || connectTimeout.isZero() || idleStream.isNegative() || idleStream.isZero()
                    || firstByte.isNegative() || firstByte.isZero() || maxBodyBytes < 1_024 || maxBodyBytes > 64 * 1_024 * 1_024) {
                throw new IllegalArgumentException("Invalid transport limits");
            }
        }
    }

    /** Backoff uses full jitter: a random wait in {@code [0, min(cap, base * 2^n)]}, raised to {@code Retry-After}. */
    public record Retry(@DefaultValue("3") int transientAttempts, @DefaultValue("6") int rateLimitRetries,
                        @DefaultValue("500ms") Duration backoffBase, @DefaultValue("8s") Duration backoffCap,
                        @DefaultValue("30s") Duration rateLimitMaxWait) {
        public Retry {
            if (transientAttempts < 1 || transientAttempts > 10 || rateLimitRetries < 0 || rateLimitRetries > 10
                    || backoffBase.isNegative() || backoffCap.compareTo(backoffBase) < 0 || rateLimitMaxWait.isNegative()) {
                throw new IllegalArgumentException("Invalid retry policy");
            }
        }
    }

    public record Breaker(@DefaultValue("5") int failureThreshold, @DefaultValue("60s") Duration window,
                          @DefaultValue("30s") Duration openFor) {
        public Breaker {
            if (failureThreshold < 1 || window.isNegative() || window.isZero() || openFor.isNegative() || openFor.isZero()) {
                throw new IllegalArgumentException("Invalid breaker policy");
            }
        }
    }

    /**
     * Per-instance concurrent calls per capability; callers beyond {@code queueWait} get a rate-limit failure. {@code assess} counts calls,
     * and an answer at the strict levels makes two: keep it at least twice {@code learning.ai.assess.concurrency} (16), hence 32.
     */
    public record Permits(@DefaultValue("16") int text, @DefaultValue("4") int tts, @DefaultValue("2") int image,
                          @DefaultValue("1") int video, @DefaultValue("4") int search, @DefaultValue("32") int assess,
                          @DefaultValue("4") int imageSearch, @DefaultValue("2s") Duration queueWait) {
        public Permits {
            if (text < 1 || tts < 1 || image < 1 || video < 1 || search < 1 || assess < 1 || imageSearch < 1
                    || queueWait.isNegative()) {
                throw new IllegalArgumentException("Invalid permits");
            }
        }

        public int of(AiCapability capability) {
            return switch (capability) {
                case TEXT -> text;
                case TTS -> tts;
                case IMAGE -> image;
                case VIDEO -> video;
                case SEARCH -> search;
                case ASSESS -> assess;
                case IMAGE_SEARCH -> imageSearch;
            };
        }
    }

    /**
     * Global daily budget per capability in micro-US-dollars, summed over all instances from the call journal; zero
     * means no limit. The day starts in {@code zone}. A safety net, not accounting: instances check a value cached for
     * {@code cacheTtl}, so the last calls of a day can overshoot slightly.
     */
    public record Budget(@DefaultValue("Europe/Moscow") String zone, @DefaultValue("10000000") long textMicros,
                         @DefaultValue("5000000") long assessMicros, @DefaultValue("5000000") long ttsMicros,
                         @DefaultValue("5000000") long imageMicros, @DefaultValue("5000000") long videoMicros,
                         @DefaultValue("5000000") long searchMicros, @DefaultValue("5000000") long imageSearchMicros,
                         @DefaultValue("10s") Duration cacheTtl) {
        public Budget {
            try {
                ZoneId.of(zone);
            } catch (DateTimeException exception) {
                throw new IllegalArgumentException("Invalid budget zone");
            }
            if (textMicros < 0 || assessMicros < 0 || ttsMicros < 0 || imageMicros < 0 || videoMicros < 0
                    || searchMicros < 0 || imageSearchMicros < 0 || cacheTtl.isNegative()) {
                throw new IllegalArgumentException("Invalid budget");
            }
        }

        public ZoneId zoneId() { return ZoneId.of(zone); }

        public long of(AiCapability capability) {
            return switch (capability) {
                case TEXT -> textMicros;
                case ASSESS -> assessMicros;
                case TTS -> ttsMicros;
                case IMAGE -> imageMicros;
                case VIDEO -> videoMicros;
                case SEARCH -> searchMicros;
                case IMAGE_SEARCH -> imageSearchMicros;
            };
        }
    }

    /** HMAC secret of the opaque provider user key; required before a real provider is used. */
    public record UserKey(String secret, @DefaultValue("k1") String keyId) {
        public UserKey {
            secret = secret == null ? "" : secret;
            if (keyId == null || !keyId.matches("[A-Za-z0-9]{1,16}")) throw new IllegalArgumentException("Invalid key id");
        }

        public boolean configured() { return secret.length() >= 16; }

        /** Never prints the secret. */
        @Override
        public String toString() { return "UserKey[keyId=" + keyId + ", secret=" + (secret.isEmpty() ? "unset" : "<redacted>") + "]"; }
    }

    /** {@code learning.ai.prompt.*}: the active prompt version and the input ceilings (tokens, estimated). */
    public record Prompt(@DefaultValue("v1") String version, @DefaultValue("32000") int maxInputTokens,
                         @DefaultValue("25000") int workingInputTokens) {
        public Prompt {
            if (version == null || !version.matches("v[0-9]{1,3}") || workingInputTokens < 1
                    || maxInputTokens < workingInputTokens) {
                throw new IllegalArgumentException("Invalid prompt settings");
            }
        }
    }
}
