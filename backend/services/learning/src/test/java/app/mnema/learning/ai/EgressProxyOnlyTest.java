package app.mnema.learning.ai;

import org.junit.jupiter.api.Test;

import java.net.Authenticator;
import java.net.InetAddress;
import java.net.PasswordAuthentication;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/** The proxy credentials answer only the proxy challenge of the configured host and port, whatever form the JDK names the host in. */
class EgressProxyOnlyTest {
    private static PasswordAuthentication ask(Authenticator authenticator, String host, int port, Authenticator.RequestorType type) throws Exception {
        return Authenticator.requestPasswordAuthentication(authenticator, host, null, port, "http", "realm", "basic",
                URI.create("https://generativelanguage.googleapis.com/").toURL(), type);
    }

    @Test
    void onlyTheConfiguredProxyHostAndPortGetTheCredentials() throws Exception {
        var authenticator = new EgressClients.ProxyOnly("Proxy.Example.net", 3128, "mnema", "pw");
        assertThat(ask(authenticator, "proxy.example.net", 3128, Authenticator.RequestorType.PROXY)).isNotNull();
        assertThat(ask(authenticator, "proxy.example.net", 3129, Authenticator.RequestorType.PROXY)).as("other port").isNull();
        assertThat(ask(authenticator, "other.example.net", 3128, Authenticator.RequestorType.PROXY)).as("other host").isNull();
        assertThat(ask(authenticator, "proxy.example.net", 3128, Authenticator.RequestorType.SERVER)).as("provider challenge").isNull();
    }

    @Test
    void anIpv6LiteralProxyMatchesTheExpandedFormTheJdkUses() throws Exception {
        var authenticator = new EgressClients.ProxyOnly("[2001:db8::1]", 3128, "mnema", "pw");
        PasswordAuthentication answer = ask(authenticator, InetAddress.ofLiteral("2001:db8::1").getHostAddress(), 3128,
                Authenticator.RequestorType.PROXY);
        assertThat(answer).isNotNull();
        assertThat(answer.getUserName()).isEqualTo("mnema");
        assertThat(ask(authenticator, "2001:db8::2", 3128, Authenticator.RequestorType.PROXY)).isNull();
    }
}
