package app.mnema.learning.billing;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code learning.billing.npd.*}: OFF by default, and ON without usable credentials stops the start without printing a value. */
class NpdSettingsTest {
    private static NpdSettings settings(String mode, String inn, String password, String baseUrl) {
        return new NpdSettings(mode, inn, password, baseUrl, Duration.ofMinutes(1), Duration.ofSeconds(5), Duration.ofSeconds(20));
    }

    private static NpdSettings on() {
        return settings("ON", FakeMyTax.INN, FakeMyTax.PASSWORD_BASE64, "https://lknpd.nalog.ru/api/v1");
    }

    @Test
    void blankOrOffNeverSends() {
        assertThat(settings("", "", "", "https://lknpd.nalog.ru/api/v1").sending()).isFalse();
        assertThat(settings(null, null, null, "https://lknpd.nalog.ru/api/v1").mode).isEqualTo(NpdSettings.Mode.OFF);
        assertThat(settings("off", FakeMyTax.INN, FakeMyTax.PASSWORD_BASE64, "https://lknpd.nalog.ru/api/v1").sending()).as("credentials alone do not send").isFalse();
        assertThat(on().sending()).isTrue();
        assertThat(settings(" on ", FakeMyTax.INN, FakeMyTax.PASSWORD_BASE64, "https://lknpd.nalog.ru/api/v1").sending()).isTrue();
    }

    @Test
    void onWithoutAnInnOrAPasswordFailsFastNamingTheSettingsButNoValue() {
        for (String[] bad : new String[][] {{"", ""}, {FakeMyTax.INN, ""}, {"", FakeMyTax.PASSWORD_BASE64}}) {
            assertThatThrownBy(() -> settings("ON", bad[0], bad[1], "https://lknpd.nalog.ru/api/v1")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MNEMA_NPD_RECEIPTS=ON").hasMessageContaining("MNEMA_NPD_INN").hasMessageContaining("MNEMA_NPD_PASSWORD_BASE64")
                    .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(FakeMyTax.INN, FakeMyTax.PASSWORD_BASE64));
        }
    }

    @Test
    void anInnThatIsNotTwelveDigitsAndAPasswordThatIsNotBase64AreRefusedInAnyMode() {
        for (String inn : new String[] {"123", "77012345678", "7701234567890", "77012345678x", "abcdefghijkl"}) {
            assertThatThrownBy(() -> settings("OFF", inn, "", "https://lknpd.nalog.ru/api/v1")).as(inn).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(inn);
        }
        assertThatThrownBy(() -> settings("OFF", "", "%%%not-base64", "https://lknpd.nalog.ru/api/v1")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("not-base64");
        assertThatThrownBy(() -> settings("MAYBE", "", "", "https://lknpd.nalog.ru/api/v1")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OFF or ON");
    }

    @Test
    void theBaseUrlIsHttpsOrLoopbackAndHasNoQueryOrCredentials() {
        assertThat(settings("OFF", "", "", " https://LKNPD.nalog.ru/api/v1/ ").baseUrl).isEqualTo("https://lknpd.nalog.ru/api/v1");
        assertThat(settings("OFF", "", "", "http://127.0.0.1:9/api/v1").baseUrl).isEqualTo("http://127.0.0.1:9/api/v1");
        assertThat(settings("OFF", "", "", "http://[::1]:9").baseUrl).isEqualTo("http://[::1]:9");
        for (String bad : new String[] {"http://lknpd.nalog.ru/api/v1", "https://u@lknpd.nalog.ru", "https://lknpd.nalog.ru?x=1", "https://lknpd.nalog.ru#f", "lknpd", "", "ftp://x.test", "https://exa mple"}) {
            assertThatThrownBy(() -> settings("OFF", "", "", bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void durationsAreBounded() {
        assertThatThrownBy(() -> new NpdSettings("OFF", "", "", "https://lknpd.nalog.ru/api/v1", Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(20)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NpdSettings("OFF", "", "", "https://lknpd.nalog.ru/api/v1", Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(20)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NpdSettings("OFF", "", "", "https://lknpd.nalog.ru/api/v1", Duration.ofMinutes(1), Duration.ofSeconds(5), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDeviceIdIsStableDerivedFromTheInnAndRevealsNothing() {
        NpdSettings first = on();
        NpdSettings second = on();
        NpdSettings other = settings("ON", "500100732259", FakeMyTax.PASSWORD_BASE64, "https://lknpd.nalog.ru/api/v1");

        assertThat(first.deviceId()).isEqualTo(second.deviceId()).isNotEqualTo(other.deviceId()).hasSize(21).startsWith("mnema").doesNotContain(FakeMyTax.INN);
        assertThat(first.password()).isEqualTo(FakeMyTax.PASSWORD);
        assertThat(first.toString()).doesNotContain(FakeMyTax.INN, FakeMyTax.PASSWORD, FakeMyTax.PASSWORD_BASE64);
    }
}
