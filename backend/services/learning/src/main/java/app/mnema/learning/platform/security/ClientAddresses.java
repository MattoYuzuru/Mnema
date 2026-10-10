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
        return client(request).map(InetAddress::getHostAddress);
    }

    /**
     * The network of the client for abuse limits: an IPv4 address as is, an IPv6 address reduced to its /64 (one subscriber usually owns a whole /64, so
     * the host part is free for them to rotate and must not count as another client), written {@code 2001:db8:0:1::/64}.
     *
     * @return the network, or empty when the request carries no usable peer
     */
    public Optional<String> resolveNetwork(HttpServletRequest request) {
        return client(request).map(ClientAddresses::network);
    }

    /**
     * The coarse network for flood limits: an IPv6 address reduced to its /48 (a site or a rented block: rotating /64s inside it must not mint fresh
     * budgets), written {@code 2001:db8:0::/48}.
     *
     * @return the /48, or empty for an IPv4 address (its address is already the finest and only network) and for a request without a usable peer
     */
    public Optional<String> resolveCoarseNetwork(HttpServletRequest request) {
        return client(request).filter(address -> address.getAddress().length == 16).map(address -> {
            byte[] bytes = address.getAddress();
            return String.format("%x:%x:%x::/48", ((bytes[0] & 0xff) << 8) | (bytes[1] & 0xff), ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff),
                    ((bytes[4] & 0xff) << 8) | (bytes[5] & 0xff));
        });
    }

    private static String network(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 16) return address.getHostAddress();
        return String.format("%x:%x:%x:%x::/64", ((bytes[0] & 0xff) << 8) | (bytes[1] & 0xff), ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff),
                ((bytes[4] & 0xff) << 8) | (bytes[5] & 0xff), ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff));
    }

    private Optional<InetAddress> client(HttpServletRequest request) {
        InetAddress peer;
        try {
            peer = address(request.getRemoteAddr());
        } catch (IllegalArgumentException failure) {
            return Optional.empty();
        }
        if (!trusted(peer)) return Optional.of(peer);
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return Optional.of(peer);
        String[] hops = forwarded.split(",", -1);
        if (hops.length > MAX_FORWARDED_HOPS) return Optional.of(peer);
        try {
            for (int index = hops.length - 1; index >= 0; index--) {
                InetAddress candidate = address(hops[index].strip());
                if (!trusted(candidate)) return Optional.of(candidate);
            }
            return Optional.of(address(hops[0].strip()));
        } catch (IllegalArgumentException failure) {
            return Optional.of(peer);
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
