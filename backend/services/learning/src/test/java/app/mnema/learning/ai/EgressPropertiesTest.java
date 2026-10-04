package app.mnema.learning.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code learning.ai.egress.*} and {@code providers.<id>.egress}: validation never echoes a value; nothing prints a secret. */
class EgressPropertiesTest {
    private static final String PASSWORD = "pw-CANARY-123456";

    private static AiProperties.Egress egress(String url) { return new AiProperties.Egress(url, "", "", true); }

    @Test
    void aBareHttpHostAndPortIsAccepted() {
        assertThat(egress("http://proxy.example.net:3128").active()).isTrue();
        assertThat(egress("http://203.0.113.7:3128/").active()).isTrue();
        assertThat(egress("http://[2001:db8::1]:3128").active()).isTrue();
        assertThat(egress("  ").active()).as("unset").isFalse();
        assertThat(new AiProperties.Egress("http://proxy.example.net:3128", "", "", false).active()).as("kill switch").isFalse();
        assertThat(new AiProperties.Egress(null, null, null, true).proxyUrl()).isEmpty();
    }

    @Test
    void badProxyUrlsAreRejectedWithoutEchoingTheValue() {
        for (String bad : new String[] {"https://secret-host-canary.example:3128", "socks5://secret-host-canary.example:1080",
                "http://secret-host-canary.example", "http://user:pw@secret-host-canary.example:3128", "http://secret-host-canary.example:3128/path",
                "http://secret-host-canary.example:3128?x=1", "http://secret-host-canary.example:3128#f", "secret-host-canary.example:3128",
                "http://secret host canary:3128", "http://:3128"}) {
            assertThatThrownBy(() -> egress(bad)).as(bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("proxy-url").hasMessageNotContaining("canary").hasMessageNotContaining("pw");
        }
    }

    @Test
    void userAndPasswordComeTogetherOrNotAtAll() {
        assertThatThrownBy(() -> new AiProperties.Egress("http://p.example:3128", "someone", "", true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("someone");
        assertThatThrownBy(() -> new AiProperties.Egress("http://p.example:3128", "", PASSWORD, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(PASSWORD);
        assertThatThrownBy(() -> new AiProperties.Egress("http://p.example:3128", "a:b", PASSWORD, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(PASSWORD);
        assertThat(new AiProperties.Egress("http://p.example:3128", "someone", PASSWORD, true).hasCredentials()).isTrue();
        assertThat(egress("http://p.example:3128").hasCredentials()).isFalse();
    }

    @Test
    void toStringRedactsTheProxyAndItsCredentials() {
        String text = new AiProperties.Egress("http://secret-host-canary.example:3128", "user-CANARY", PASSWORD, true).toString();
        assertThat(text).doesNotContain("secret-host-canary").doesNotContain("user-CANARY").doesNotContain(PASSWORD).contains("<redacted>");
        assertThat(egress("").toString()).contains("proxyUrl=unset");
        assertThat(new AiProperties.Provider(true, "https://x.example", "k", "", "", "", AiProperties.EgressMode.PROXY).toString())
                .contains("egress=proxy");
    }

    @Test
    void springBindsTheEgressSettingsAndTheProviderMode() {
        var source = new MapConfigurationPropertySource(Map.of(
                "learning.ai.egress.proxy-url", "http://203.0.113.7:3128", "learning.ai.egress.user", "u",
                "learning.ai.egress.password", "p", "learning.ai.providers.openrouter.base-url", "https://openrouter.ai/api/v1",
                "learning.ai.providers.openrouter.egress", "proxy", "learning.ai.providers.deepseek.base-url", "https://api.deepseek.com"));
        AiProperties bound = new Binder(source).bind("learning.ai", AiProperties.class).get();
        assertThat(bound.egress().active()).isTrue();
        assertThat(bound.egress().user()).isEqualTo("u");
        assertThat(bound.providers().get("openrouter").egress()).isEqualTo(AiProperties.EgressMode.PROXY);
        assertThat(bound.providers().get("deepseek").egress()).as("default").isEqualTo(AiProperties.EgressMode.DIRECT);
        AiProperties defaults = new Binder(new MapConfigurationPropertySource(Map.of())).bind("learning.ai", AiProperties.class)
                .orElseGet(() -> new AiProperties(null, null, null, null, null, null, null, null, null, null, null));
        assertThat(defaults.egress().active()).isFalse();
        assertThat(defaults.egress().enabled()).isTrue();
        assertThat(AiProperties.EgressMode.PROXY.label()).isEqualTo("proxy");
    }
}
