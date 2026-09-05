package app.mnema.identityaccount.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * Resolves rate-limit identities without trusting caller-supplied forwarding headers.
 */
@Component
public final class ClientAddresses {
    private static final int MAX_FORWARDED_HOPS = 20;

    private final List<Network> trustedProxies;

    public ClientAddresses(@Value("${identity.trusted-proxy-cidrs}") String cidrs) {
        trustedProxies = Arrays.stream(cidrs.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(Network::parse)
                .toList();
        if (trustedProxies.isEmpty()) throw new IllegalArgumentException("At least one trusted proxy CIDR is required");
    }

    public String resolve(HttpServletRequest request) {
        InetAddress peer = address(request.getRemoteAddr());
        if (!trusted(peer)) return peer.getHostAddress();

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return peer.getHostAddress();
        String[] hops = forwarded.split(",", -1);
        if (hops.length > MAX_FORWARDED_HOPS) return peer.getHostAddress();
        for (int index = hops.length - 1; index >= 0; index--) {
            InetAddress candidate;
            try {
                candidate = address(hops[index].strip());
            } catch (IllegalArgumentException ignored) {
                return peer.getHostAddress();
            }
            if (!trusted(candidate)) return candidate.getHostAddress();
        }
        return address(hops[0].strip()).getHostAddress();
    }

    private boolean trusted(InetAddress address) {
        return trustedProxies.stream().anyMatch(network -> network.contains(address));
    }

    private static InetAddress address(String value) {
        if (value == null || value.isBlank() || (!value.contains(":") && !value.matches("[0-9.]+")))
            throw new IllegalArgumentException("Client address must be an IP literal");
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Client address must be an IP literal", e);
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
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Trusted proxy prefix is invalid", e);
            }
            if (prefix < 0 || prefix > address.length * 8)
                throw new IllegalArgumentException("Trusted proxy prefix is invalid");
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
