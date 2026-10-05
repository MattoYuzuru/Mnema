package app.mnema.learning.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The client address of a request: a forwarded header counts only from a trusted proxy, and only its right-most untrusted hop. */
class ClientAddressesTest {
    private final ClientAddresses addresses = new ClientAddresses("127.0.0.0/8, ::1/128, 10.1.0.0/16");

    private static MockHttpServletRequest request(String peer, String forwarded) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (forwarded != null) request.addHeader("X-Forwarded-For", forwarded);
        return request;
    }

    @Test
    void anUntrustedPeerIsItselfAndItsForwardedHeaderIsIgnored() {
        assertThat(addresses.resolve(request("198.51.100.7", "203.0.113.9"))).contains("198.51.100.7");
    }

    @Test
    void aTrustedProxyNamesTheRightMostUntrustedHop() {
        assertThat(addresses.resolve(request("127.0.0.1", "203.0.113.9"))).contains("203.0.113.9");
        assertThat(addresses.resolve(request("127.0.0.1", "6.6.6.6, 203.0.113.9, 10.1.2.3"))).contains("203.0.113.9");
        assertThat(addresses.resolve(request("10.1.0.5", "2001:db8::1"))).contains("2001:db8:0:0:0:0:0:1");
        assertThat(addresses.resolve(request("127.0.0.1", "10.1.0.9, 127.0.0.2"))).contains("10.1.0.9");
    }

    @Test
    void aMissingOrBrokenForwardedHeaderFallsBackToThePeer() {
        assertThat(addresses.resolve(request("127.0.0.1", null))).contains("127.0.0.1");
        assertThat(addresses.resolve(request("127.0.0.1", " "))).contains("127.0.0.1");
        assertThat(addresses.resolve(request("127.0.0.1", "not-an-ip"))).contains("127.0.0.1");
        assertThat(addresses.resolve(request("127.0.0.1", "1.1.1.1,".repeat(25)))).contains("127.0.0.1");
    }

    @Test
    void anUnusablePeerIsEmpty() {
        assertThat(addresses.resolve(request("", null))).isEmpty();
        assertThat(addresses.resolve(request("host.example", null))).isEmpty();
    }

    @Test
    void trustedProxyConfigurationMustBeCidr() {
        assertThatThrownBy(() -> new ClientAddresses("127.0.0.1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientAddresses("127.0.0.1/40")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientAddresses("127.0.0.1/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientAddresses("name/8")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ClientAddresses("").resolve(request("127.0.0.1", "1.1.1.1"))).contains("127.0.0.1");
        assertThat(new ClientAddresses("192.168.1.0/20").resolve(request("192.168.1.5", "203.0.113.9"))).contains("203.0.113.9");
    }
}
