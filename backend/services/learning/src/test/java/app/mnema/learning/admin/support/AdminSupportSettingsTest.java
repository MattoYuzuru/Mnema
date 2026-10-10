package app.mnema.learning.admin.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminSupportSettingsTest {
    private static final String SECRET = "support-machine-credential-32-characters";

    @Test void emptyConfigurationDisablesBridgeAndOneSidedConfigurationFailsClosed() {
        assertThat(settings("", "", false).enabled()).isFalse();
        assertThatThrownBy(() -> settings("https://support.example.test/internal/support", "", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings("", SECRET, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void validatesFixedHttpsEndpointAndDistinctCredential() {
        assertThat(settings("https://support.example.test/internal/support", SECRET, false).enabled()).isTrue();
        for (String endpoint : new String[]{"http://support.example.test/internal/support", "https://user:password@support.example.test/internal/support",
                "https://support.example.test/internal/support?secret=a", "https://support.example.test/internal/support#token",
                "https://support.example.test/internal/support/", "https://support.example.test:65536/internal/support",
                "https://support.example.test:0/internal/support", "https://support.example.test/internal/%73upport", "garbage"}) {
            assertThatThrownBy(() -> settings(endpoint, SECRET, true)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> settings("https://support.example.test/internal/support", "bot-token", false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void plainHttpRequiresExplicitLiteralLoopbackAndLimitsAreBounded() {
        assertThatThrownBy(() -> settings("http://127.0.0.1:18123/internal/support", SECRET, false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(settings("http://127.0.0.1:18123/internal/support", SECRET, true).enabled()).isTrue();
        assertThat(settings("http://[::1]:18123/internal/support", SECRET, true).enabled()).isTrue();
        assertThatThrownBy(() -> settings("http://localhost:18123/internal/support", SECRET, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminSupportSettings("", "", false, Duration.ofMillis(999), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminSupportSettings("", "", false, Duration.ofSeconds(11), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminSupportSettings("", "", false, Duration.ofSeconds(6), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminSupportSettings("", "", false, Duration.ofSeconds(6), 9)).isInstanceOf(IllegalArgumentException.class);
    }

    private static AdminSupportSettings settings(String endpoint, String secret, boolean loopback) {
        return new AdminSupportSettings(endpoint, secret, loopback, Duration.ofSeconds(6), 4);
    }
}
