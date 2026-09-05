package app.mnema.identityaccount.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientAddressesTest {
    private final ClientAddresses addresses = new ClientAddresses("10.42.0.0/16,fd42::/64");

    @Test
    void trustedProxyUsesRightMostUntrustedForwardedAddress() {
        var first = request("10.42.0.5", "198.51.100.11, 10.42.0.8");
        var second = request("10.42.0.5", "198.51.100.12, 10.42.0.8");

        assertThat(addresses.resolve(first)).isEqualTo("198.51.100.11");
        assertThat(addresses.resolve(second)).isEqualTo("198.51.100.12");
    }

    @Test
    void untrustedPeerCannotSpoofForwardedAddress() {
        assertThat(addresses.resolve(request("203.0.113.9", "198.51.100.10"))).isEqualTo("203.0.113.9");
    }

    @Test
    void malformedOrOversizedForwardingChainFallsBackToPeer() {
        assertThat(addresses.resolve(request("10.42.0.5", "client.example"))).isEqualTo("10.42.0.5");
        assertThat(addresses.resolve(request("10.42.0.5", "198.51.100.1,".repeat(20) + "198.51.100.2")))
                .isEqualTo("10.42.0.5");
    }

    @Test
    void configurationRejectsEmptyOrInvalidNetworks() {
        assertThatThrownBy(() -> new ClientAddresses(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientAddresses("10.42.0.0/33")).isInstanceOf(IllegalArgumentException.class);
    }

    private static MockHttpServletRequest request(String peer, String forwarded) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        request.addHeader("X-Forwarded-For", forwarded);
        return request;
    }
}
