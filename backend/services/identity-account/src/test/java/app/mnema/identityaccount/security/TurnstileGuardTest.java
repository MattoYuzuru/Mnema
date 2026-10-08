package app.mnema.identityaccount.security;

import app.mnema.identityaccount.contract.AccountFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TurnstileGuardTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC);
    private final RateLimits limits = mock(RateLimits.class);
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final TurnstilePolicy required = policy("required", "prod", "0xFixtureSiteKey", "0xFixtureSecret");
    private final TurnstileGuard guard = new TurnstileGuard(required, builder.build(), clock, limits);

    private static TurnstilePolicy policy(String mode, String env, String site, String secret) {
        return new TurnstilePolicy(mode, env, site, secret,
                URI.create("https://mnema.app"), URI.create("https://auth.mnema.app"));
    }

    private void allow() { when(limits.allow("turnstile", "192.0.2.1", 30)).thenReturn(true); }

    private static String response(String host, String action, String time) {
        return "{\"success\":true,\"hostname\":\"" + host + "\",\"action\":\"" + action +
                "\",\"challenge_ts\":\"" + time + "\",\"error-codes\":[]}";
    }

    private void reject(int status, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(AccountFailure.class,
                failure -> assertThat(failure.status()).isEqualTo(status));
    }

    @Test
    void validatesBothApprovedHostsAndEveryActionWithFreshSingleUseRequest() {
        allow();
        for (var action : TurnstileGuard.Action.values()) {
            server.expect(requestTo(TurnstileGuard.SITEVERIFY)).andExpect(method(HttpMethod.POST))
                    .andExpect(content().json("{\"secret\":\"0xFixtureSecret\",\"response\":\"one-use-token\"}", org.springframework.test.json.JsonCompareMode.STRICT))
                    .andRespond(withSuccess(response(action == TurnstileGuard.Action.LOGIN ? "auth.mnema.app" : "mnema.app",
                            action.name().toLowerCase(java.util.Locale.ROOT), "2026-10-04T11:59:59Z"), MediaType.APPLICATION_JSON));
        }
        for (var action : TurnstileGuard.Action.values()) guard.verify("one-use-token", action, "192.0.2.1");
        server.verify();
        assertThat(guard.configuration()).isEqualTo(new TurnstileGuard.BrowserConfiguration("required", "0xFixtureSiteKey"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "missing"})
    void rejectsAbsentOrBlankTokenBeforeExternalCall(String token) {
        allow();
        reject(403, () -> guard.verify(token.equals("missing") ? null : token, TurnstileGuard.Action.LOGIN, "192.0.2.1"));
        server.verify();
    }

    @Test
    void boundsTokenAndUpstreamAttempts() {
        allow();
        reject(403, () -> guard.verify("x".repeat(2049), TurnstileGuard.Action.LOGIN, "192.0.2.1"));
        when(limits.allow("turnstile", "192.0.2.1", 30)).thenReturn(false);
        reject(429, () -> guard.verify("token", TurnstileGuard.Action.LOGIN, "192.0.2.1"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}",
            "{\"success\":\"true\"}", "{}", "[]", "null",
            "{\"success\":true,\"hostname\":7}",
            "wrong-host", "wrong-action", "expired", "future", "malformed-time"})
    void rejectsReplayExpiryHostActionAndMalformedMetadata(String value) {
        allow();
        String body = switch (value) {
            case "wrong-host" -> response("evil.test", "login", "2026-10-04T12:00:00Z");
            case "wrong-action" -> response("mnema.app", "register", "2026-10-04T12:00:00Z");
            case "expired" -> response("mnema.app", "login", "2026-10-04T11:54:59Z");
            case "future" -> response("mnema.app", "login", "2026-10-04T12:00:31Z");
            case "malformed-time" -> response("mnema.app", "login", "invalid");
            default -> value;
        };
        server.expect(requestTo(TurnstileGuard.SITEVERIFY)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        reject(403, () -> guard.verify("token", TurnstileGuard.Action.LOGIN, "192.0.2.1"));
        server.verify();
    }

    @Test
    void upstreamFailureDoesNotAuthorizeRetryOrExposeItsBody() {
        allow();
        server.expect(requestTo(TurnstileGuard.SITEVERIFY)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .body("sensitive upstream diagnostics"));
        assertThatThrownBy(() -> guard.verify("token", TurnstileGuard.Action.LOGIN, "192.0.2.1"))
                .isInstanceOf(AccountFailure.class).hasMessage("abuse_protection_unavailable");
        server.verify();
    }

    @Test
    void productionCannotDisableProtectionOrUseTestKeysAndBlockedIsTheKillSwitch() {
        for (String mode : new String[]{"disabled", "blocked"}) {
            var policy = policy(mode, "prod", "", "");
            var closed = new TurnstileGuard(policy, builder.build(), clock, limits);
            reject(503, () -> closed.verify(null, TurnstileGuard.Action.LOGIN, "192.0.2.1"));
            assertThat(closed.configuration()).isEqualTo(new TurnstileGuard.BrowserConfiguration("blocked", ""));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> policy("required", "prod",
                "1x00000000000000000000AA", "1x0000000000000000000000000000000AA"));
        assertThatIllegalArgumentException().isThrownBy(() -> policy("required", "prod",
                "0xFixtureSiteKey", "1x0000000000000000000000000000000AA"));
        assertThatIllegalArgumentException().isThrownBy(() -> policy("required", "prod", "", ""));
        assertThatIllegalArgumentException().isThrownBy(() -> policy("arbitrary", "dev", "", ""));
        assertThatIllegalArgumentException().isThrownBy(() -> policy("required", "prod", "valid-key123", "bad\nsecret"));
        assertThat(policy("required", "prod", "0xFixtureSiteKey", "0xFixtureSecret").mode)
                .isEqualTo(TurnstilePolicy.Mode.REQUIRED);
        server.verify();
    }

    @Test
    void onlyKnownDisposableLocalEnvironmentsCanDisableProtection() {
        for (String env : new String[]{"dev", "development", "test", "local", "local-blackbox", "local-browser-fixture", "local-full-stack"})
            assertThat(policy("disabled", env, "", "").mode).isEqualTo(TurnstilePolicy.Mode.DISABLED);
        for (String env : new String[]{"prod", "staging", "unknown"})
            assertThat(policy("disabled", env, "", "").mode).isEqualTo(TurnstilePolicy.Mode.BLOCKED);
        assertThat(policy("required", "test", "1x00000000000000000000AA",
                "1x0000000000000000000000000000000AA").mode).isEqualTo(TurnstilePolicy.Mode.REQUIRED);
    }

    @Test
    void localDisabledModeMakesNoExternalCallAndPublicConfigNeverIncludesSecret() {
        var local = new TurnstileGuard("disabled", "dev", "", "synthetic-unused-secret",
                URI.create("https://mnema.app"), URI.create("https://auth.mnema.app"), clock, limits);
        local.verify(null, TurnstileGuard.Action.LOGIN, "192.0.2.1");
        assertThat(local.configuration()).isEqualTo(new TurnstileGuard.BrowserConfiguration("disabled", ""));
        verifyNoInteractions(limits);
    }
}
