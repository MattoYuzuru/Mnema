package app.mnema.learning.ai;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiRoutingTest {
    private static AiProperties properties(String provider, List<String> fast, Map<String, AiProperties.Provider> providers) {
        return AiTestSupport.properties(provider, AiTestSupport.routes(fast, List.of("deepseek:deepseek-v4-pro"), List.of()), providers);
    }

    @Test
    void routeEntriesWithoutUsableAdaptersAreSkippedInOrder() {
        var deepseek = new ScriptedAdapter("deepseek");
        var gigachat = new ScriptedAdapter("gigachat");
        var routing = new AiRouting(properties("", List.of("deepseek:deepseek-flash", "gigachat:GigaChat-2"), Map.of()),
                Map.of("deepseek", deepseek, "gigachat", gigachat));
        assertThat(routing.candidates(AiRoute.TEXT_FAST)).extracting(AiRouting.Candidate::key)
                .containsExactly("deepseek:deepseek-flash", "gigachat:GigaChat-2");
        assertThat(routing.candidates(AiRoute.ASSESS)).isEmpty();

        deepseek.unconfigured();
        assertThat(routing.candidates(AiRoute.TEXT_FAST)).extracting(AiRouting.Candidate::provider).containsExactly("gigachat");
        // a provider with no adapter at all (switched off) is skipped as well
        var onlyGiga = new AiRouting(properties("", List.of("deepseek:deepseek-flash", "gigachat:GigaChat-2"), Map.of()),
                Map.of("gigachat", gigachat));
        assertThat(onlyGiga.candidates(AiRoute.TEXT_FAST)).extracting(AiRouting.Candidate::provider).containsExactly("gigachat");
    }

    @Test
    void thePlannerRoutesAreTheirOwnListsOfTheTextCapability() {
        var routes = new AiProperties.Routes(List.of("deepseek:deepseek-flash"), List.of("deepseek:deepseek-v4-pro"), List.of(), Duration.ofSeconds(8),
                List.of("deepseek:deepseek-flash", "gigachat:GigaChat-2"), List.of("deepseek:deepseek-v4-pro"));
        assertThat(routes.of(AiRoute.PLAN)).containsExactly("deepseek:deepseek-flash", "gigachat:GigaChat-2");
        assertThat(routes.of(AiRoute.PLAN_STRONG)).containsExactly("deepseek:deepseek-v4-pro");
        var routing = new AiRouting(AiTestSupport.properties("", routes, Map.of()),
                Map.of("deepseek", new ScriptedAdapter("deepseek"), "gigachat", new ScriptedAdapter("gigachat")));
        assertThat(routing.candidates(AiRoute.PLAN)).extracting(AiRouting.Candidate::key).containsExactly("deepseek:deepseek-flash", "gigachat:GigaChat-2");
        assertThat(routing.candidates(AiRoute.PLAN_STRONG)).extracting(AiRouting.Candidate::key).containsExactly("deepseek:deepseek-v4-pro");
        // a route that was not configured has no candidate: the planner fails closed instead of using another route
        assertThat(new AiRouting(properties("", List.of("deepseek:deepseek-flash"), Map.of()), Map.of("deepseek", new ScriptedAdapter("deepseek")))
                .candidates(AiRoute.PLAN)).isEmpty();
    }

    @Test
    void theStubOverridesEveryRoute() {
        var routing = new AiRouting(properties("stub", List.of("deepseek:deepseek-flash"), Map.of()),
                Map.of("stub", new StubTextAdapter(), "deepseek", new ScriptedAdapter("deepseek")));
        for (AiRoute route : AiRoute.values()) {
            assertThat(routing.candidates(route)).extracting(AiRouting.Candidate::key).containsExactly("stub:stub");
        }
    }

    @Test
    void aMalformedRouteFailsAtStartupAndNeverAtCallTime() {
        for (String bad : List.of("deepseek", ":model", "deepseek:", "mystery:model", "deepseek:unpriced-model", "stub:stub",
                "deepseek:deepseek-flash,stub:stub")) {
            assertThatThrownBy(() -> new AiRouting(properties("", List.of(bad.split(",")), Map.of()), Map.of()))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theStubIsRegisteredOnlyByTheExplicitSettingAndAnnouncedWithAWarning() {
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AiConfiguration.class);
        appender.start();
        logger.addAppender(appender);
        try (var http = new ChatHttp(properties("stub", List.of(), Map.of()).transport())) {
            assertThat(AiConfiguration.adapters(properties("", List.of(), Map.of()), http, Clock.systemUTC())).isEmpty();
            assertThat(appender.list).as("no warning without the Stub").isEmpty();
            assertThat(AiConfiguration.adapters(properties("stub", List.of(), Map.of()), http, Clock.systemUTC())).containsOnlyKeys("stub");
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(event.getFormattedMessage()).startsWith("ai_stub_active");
        });
    }

    @Test
    void secretsNeverAppearInToString() {
        var provider = new AiProperties.Provider(true, "https://api.example.com", "sk-SECRET-KEY", "https://auth.example.com", "AUTH-SECRET", "S");
        assertThat(provider.toString()).doesNotContain("sk-SECRET-KEY").doesNotContain("AUTH-SECRET").contains("<redacted>");
        assertThat(new AiProperties.Provider(true, "", "", "", "", "").toString()).contains("apiKey=unset");
        assertThat(new AiProperties.UserKey("0123456789abcdef-SECRET", "k1").toString()).doesNotContain("SECRET").contains("<redacted>");
        assertThat(new AiProperties.UserKey("", "k1").toString()).contains("secret=unset");
        AiProperties all = AiTestSupport.properties("", AiTestSupport.routes(List.of(), List.of(), List.of()), Map.of("deepseek", provider));
        assertThat(all.toString()).doesNotContain("sk-SECRET-KEY").doesNotContain("0123456789abcdef0123456789abcdef");
    }

    @Test
    void propertiesRejectUnsafeOrNonsensicalValues() {
        assertThatThrownBy(() -> AiTestSupport.properties("openai", AiTestSupport.routes(List.of(), List.of(), List.of()), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        for (String url : List.of("http://api.example.com", "https://user:pass@api.example.com", "ftp://x", "not a url", "https://")) {
            assertThatThrownBy(() -> AiTestSupport.provider(url, "k")).as(url).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(AiTestSupport.provider("http://127.0.0.1:8080", "k").baseUrl()).isEqualTo("http://127.0.0.1:8080");
        assertThat(AiTestSupport.provider("https://api.deepseek.com", " key ").apiKey()).isEqualTo("key");
        assertThatThrownBy(() -> new AiProperties.Model("deepseek", "m", -1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiProperties.Model("", "m", 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiProperties.Retry(0, 6, Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiProperties.Breaker(0, Duration.ofSeconds(1), Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiProperties.Transport(Duration.ofSeconds(1), Duration.ZERO, 4096, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiProperties.Permits(0, 1, 1, 1, 1, 1, 1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiProperties.Budget("Mars/Base", 1, 1, 1, 1, 1, 1, 1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiProperties.Budget("UTC", -1, 1, 1, 1, 1, 1, 1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiProperties.Prompt("1", 10, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiProperties.Prompt("v1", 5, 10)).isInstanceOf(IllegalArgumentException.class);
        AiProperties.Permits permits = AiTestSupport.properties("", AiTestSupport.routes(List.of(), List.of(), List.of()), Map.of()).permits();
        for (AiCapability capability : AiCapability.values()) assertThat(permits.of(capability)).isPositive();
        AiProperties.Budget budget = AiTestSupport.properties("", AiTestSupport.routes(List.of(), List.of(), List.of()), Map.of()).budget();
        for (AiCapability capability : AiCapability.values()) assertThat(budget.of(capability)).isPositive();
        assertThat(budget.zoneId().getId()).isEqualTo("Europe/Moscow");
        assertThat(new AiProperties.Provider(true, null, null, null, null, null).scope()).isEqualTo("GIGACHAT_API_PERS");
        assertThat(new AiProperties.Routes(null, null, null).of(AiRoute.TEXT_FAST)).isEmpty();
        assertThat(new AiProperties(null, null, null, null, null, null, null, null, null, null, null)).isNotNull();
    }

    @Test
    void theConfigurationBuildsRealAdaptersOnlyForEnabledProvidersWithAUrl() {
        var providers = Map.of("deepseek", AiTestSupport.provider("https://api.deepseek.com", "key"),
                "gigachat", new AiProperties.Provider(true, "https://gigachat.example/api/v1", "", "https://auth.example/oauth", "authkey", "GIGACHAT_API_PERS"),
                "openrouter", new AiProperties.Provider(false, "https://openrouter.ai/api/v1", "key", "", "", ""));
        AiProperties properties = properties("", List.of("deepseek:deepseek-flash"), providers);
        try (var http = new ChatHttp(properties.transport())) {
            Map<String, TextAdapter> adapters = AiConfiguration.adapters(properties, http, Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC));
            assertThat(adapters).as("the Stub is never registered without learning.ai.provider=stub").containsOnlyKeys("deepseek", "gigachat");
            assertThat(adapters.get("deepseek").configured()).isTrue();
            assertThat(adapters.get("gigachat").configured()).isTrue();
            var keyless = properties("", List.of("deepseek:deepseek-flash"), Map.of("deepseek", AiTestSupport.provider("https://api.deepseek.com", "")));
            assertThat(AiConfiguration.adapters(keyless, http, Clock.systemUTC()).get("deepseek").configured()).isFalse();
            assertThat(AiConfiguration.adapters(properties("", List.of(), Map.of()), http, Clock.systemUTC())).isEmpty();
        }
    }
}
