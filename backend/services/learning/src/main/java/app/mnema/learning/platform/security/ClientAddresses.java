package app.mnema.learning.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the client address behind the platform's reverse proxy without trusting a caller-supplied header: {@code X-Forwarded-For} counts only
 * when the socket peer belongs to {@code learning.trusted-proxy-cidrs} (the same rule as Identity's {@code ClientAddresses}), and the right-most
 * hop that is not itself a trusted proxy is the client. The address is used for abuse limits only and is never stored: callers hash it.
 */
@Component
public final class ClientAddresses {
    private static final int MAX_FORWARDED_HOPS = 20;

    private final List<Network> trustedProxies;

    public ClientAddresses(@Value("${learning.trusted-proxy-cidrs:127.0.0.0/8,::1/128}") String cidrs) {
        trustedProxies = Arrays.stream(cidrs.split(",")).map(String::strip).filter(value -> !value.isEmpty())
                .map(Network::parse).toList();
    }

    /** @return the client address, or empty when the request carries no usable peer */
    public Optional<String> resolve(HttpServletRequest request) {
        InetAddress peer;
        try {
            peer = address(request.getRemoteAddr());
        } catch (IllegalArgumentException failure) {
            return Optional.empty();
        }
        if (!trusted(peer)) return Optional.of(peer.getHostAddress());
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return Optional.of(peer.getHostAddress());
        String[] hops = forwarded.split(",", -1);
        if (hops.length > MAX_FORWARDED_HOPS) return Optional.of(peer.getHostAddress());
        try {
            for (int index = hops.length - 1; index >= 0; index--) {
                InetAddress candidate = address(hops[index].strip());
                if (!trusted(candidate)) return Optional.of(candidate.getHostAddress());
            }
            return Optional.of(address(hops[0].strip()).getHostAddress());
        } catch (IllegalArgumentException failure) {
            return Optional.of(peer.getHostAddress());
        }
    }

    private boolean trusted(InetAddress address) {
        return trustedProxies.stream().anyMatch(network -> network.contains(address));
    }

    private static InetAddress address(String value) {
        if (value == null || value.isBlank() || (!value.contains(":") && !value.matches("[0-9.]+"))) {
            throw new IllegalArgumentException("Client address must be an IP literal");
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException failure) {
            throw new IllegalArgumentException("Client address must be an IP literal", failure);
        }
    }

    private record Network(byte[] address, int prefix) {
        static Network parse(String cidr) {
            String[] parts = cidr.split("/", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Trusted proxy must use CIDR notation");
            byte[] address = ClientAddresses.address(parts[0]).getAddress();
            int prefix;
            try {
                prefix = Integer.parseInt(parts[1]);
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("Trusted proxy prefix is invalid", failure);
            }
            if (prefix < 0 || prefix > address.length * 8) throw new IllegalArgumentException("Trusted proxy prefix is invalid");
            return new Network(address, prefix);
        }

        boolean contains(InetAddress candidate) {
            byte[] value = candidate.getAddress();
            if (value.length != address.length) return false;
            int bytes = prefix / 8;
            int bits = prefix % 8;
            for (int index = 0; index < bytes; index++) if (value[index] != address[index]) return false;
            if (bits == 0) return true;
            int mask = 0xff << (8 - bits);
            return (value[bytes] & mask) == (address[bytes] & mask);
        }
    }
}
