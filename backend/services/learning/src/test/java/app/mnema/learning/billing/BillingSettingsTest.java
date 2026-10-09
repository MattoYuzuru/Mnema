package app.mnema.learning.billing;

import app.mnema.learning.platform.api.CapabilityUnavailableException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code learning.billing.*}: who may check out, what stops the start and what only makes billing not configured. */
class BillingSettingsTest {
    private static final UUID TESTER = UUID.randomUUID();

    private static BillingSettings settings(String checkout, String testers, String publicUrl, String bankUrl, String key, String password) {
        return new BillingSettings(checkout, testers, publicUrl, bankUrl, key, password, Duration.ofHours(1), Duration.ofSeconds(10), 10,
                Duration.ofMinutes(2), Duration.ofSeconds(5), Duration.ofSeconds(15));
    }

    private static BillingSettings configured(String checkout, String testers) {
        return settings(checkout, testers, "https://mnema.app", "https://securepay.tinkoff.ru/v2", BillingFixtures.TERMINAL, BillingFixtures.PASSWORD_BASE64);
    }

    @Test
    void theModeDecidesWhoIsLetThrough() {
        UUID other = UUID.randomUUID();

        assertThat(configured("OFF", TESTER.toString()).available(TESTER)).isFalse();
        assertThat(configured("TESTERS", " " + TESTER + " , ").available(TESTER)).isTrue();
        assertThat(configured("TESTERS", TESTER.toString()).available(other)).isFalse();
        assertThat(configured("on", "").available(other)).isTrue();
        assertThat(configured("", "").available(other)).as("a blank mode is OFF").isFalse();
        assertThat(settings("ON", "", "https://mnema.app", "https://securepay.tinkoff.ru/v2", "", "").available(other)).isFalse();
    }

    @Test
    void checkoutIsRefusedWithTheReasonTheContractNames() {
        assertThatThrownBy(() -> configured("OFF", "").requireAvailable(TESTER)).isInstanceOfSatisfying(CapabilityUnavailableException.class,
                failure -> assertThat(failure.extension().members()).containsEntry("capability", "billing").containsEntry("reason", "DISABLED"));
        assertThatThrownBy(() -> configured("TESTERS", "").requireAvailable(TESTER)).isInstanceOfSatisfying(CapabilityUnavailableException.class,
                failure -> assertThat(failure.extension().members()).containsEntry("reason", "DISABLED"));
        assertThatThrownBy(() -> settings("ON", "", "", "https://securepay.tinkoff.ru/v2", "", "").requireAvailable(TESTER))
                .isInstanceOfSatisfying(CapabilityUnavailableException.class,
                        failure -> assertThat(failure.extension().members()).containsEntry("reason", "NOT_CONFIGURED"));
        configured("ON", "").requireAvailable(TESTER);
    }

    @Test
    void unusableCredentialsMakeBillingNotConfiguredInsteadOfStoppingTheStart() {
        assertThat(settings("ON", "", "https://mnema.app", "https://securepay.tinkoff.ru/v2", BillingFixtures.TERMINAL, "%%%not-base64").configured()).isFalse();
        assertThat(settings("ON", "", "https://mnema.app", "https://securepay.tinkoff.ru/v2", "bad key!", BillingFixtures.PASSWORD_BASE64).configured()).isFalse();
        assertThat(settings("ON", "", "https://mnema.app", "https://securepay.tinkoff.ru/v2", BillingFixtures.TERMINAL, "").configured()).isFalse();
        assertThat(settings("OFF", "", "", "https://securepay.tinkoff.ru/v2", "", "").configured()).isFalse();
        assertThat(settings("ON", "", "https://mnema.app", "https://securepay.tinkoff.ru/v2", "", BillingFixtures.PASSWORD_BASE64).isOwnTerminal("x")).isFalse();
        assertThat(configured("ON", "").configured()).isTrue();
    }

    @Test
    void thePasswordIsDecodedAndNeverPrinted() {
        BillingSettings settings = configured("ON", "");

        assertThat(settings.password()).isEqualTo(BillingFixtures.PASSWORD);
        assertThat(settings.toString()).doesNotContain(BillingFixtures.PASSWORD).doesNotContain(BillingFixtures.PASSWORD_BASE64)
                .doesNotContain(BillingFixtures.TERMINAL);
        assertThat(settings.isOwnTerminal(BillingFixtures.TERMINAL)).isTrue();
        assertThat(settings.isOwnTerminal(BillingFixtures.TERMINAL + "x")).isFalse();
        assertThat(settings.isOwnTerminal(null)).isFalse();
    }

    @Test
    void urlsAreOriginsOverHttpsAndLoopbackMayUseHttp() {
        assertThat(configured("ON", "").publicBaseUrl).isEqualTo("https://mnema.app");
        assertThat(settings("ON", "", " https://MNEMA.app:8443 ", "https://securepay.tinkoff.ru/v2/", "k", "cA==").publicBaseUrl).isEqualTo("https://mnema.app:8443");
        assertThat(settings("ON", "", "http://localhost:4200", "http://127.0.0.1:9/v2", "k", "cA==").publicBaseUrl).isEqualTo("http://localhost:4200");
        assertThat(settings("ON", "", "http://[::1]:4200", "http://[::1]:9/v2/", "k", "cA==").bankBaseUrl).isEqualTo("http://[::1]:9/v2");
        assertThat(configured("ON", "").bankBaseUrl).isEqualTo("https://securepay.tinkoff.ru/v2");
        for (String bad : new String[] {"http://mnema.app", "https://mnema.app/path", "https://user@mnema.app", "https://mnema.app?x=1", "mnema.app", "ftp://x.test", "https://mnema.app#f"}) {
            assertThatThrownBy(() -> settings("ON", "", bad, "https://securepay.tinkoff.ru/v2", "k", "cA==")).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        for (String bad : new String[] {"http://securepay.tinkoff.ru/v2", "https://securepay.tinkoff.ru/v2?x", "", "not a url"}) {
            assertThatThrownBy(() -> settings("ON", "", "https://mnema.app", bad, "k", "cA==")).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void invalidModesTestersAndLimitsStopTheStart() {
        assertThatThrownBy(() -> configured("MAYBE", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configured("TESTERS", "not-a-uuid")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configured("TESTERS", "00000000-0000-0000-0000-000000000000")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BillingSettings("ON", "", "", "https://securepay.tinkoff.ru/v2", "", "", Duration.ofSeconds(1), Duration.ofSeconds(10), 10,
                Duration.ofMinutes(2), Duration.ofSeconds(5), Duration.ofSeconds(15))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BillingSettings("ON", "", "", "https://securepay.tinkoff.ru/v2", "", "", Duration.ofHours(1), Duration.ofSeconds(10), 0,
                Duration.ofMinutes(2), Duration.ofSeconds(5), Duration.ofSeconds(15))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BillingSettings("ON", "", "", "https://securepay.tinkoff.ru/v2", "", "", Duration.ofHours(1), Duration.ofSeconds(10), 10,
                Duration.ofMinutes(2), Duration.ofSeconds(5), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BillingSettings("ON", "", "", "https://securepay.tinkoff.ru/v2", "", "", Duration.ofHours(1), Duration.ofSeconds(10), 10,
                Duration.ofMinutes(2), Duration.ofSeconds(5), Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
    }
}
