package app.mnema.learning.ai;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Which resolved addresses the safe image fetcher may connect to: public unicast only. Refused: loopback, any-local ({@code 0.0.0.0},
 * {@code ::}), site-local ({@code 10/8}, {@code 172.16/12}, {@code 192.168/16}), link-local ({@code 169.254/16} including the cloud
 * metadata address {@code 169.254.169.254}, {@code fe80::/10}), multicast, carrier-grade NAT ({@code 100.64/10}), unique-local
 * ({@code fc00::/7}), the benchmarking and documentation ranges, and an IPv4-mapped ({@code ::ffff:a.b.c.d}) or IPv4-compatible
 * ({@code ::a.b.c.d}) IPv6 address whose embedded IPv4 address is refused, and the tunnel and translation prefixes {@code 2002::/16} (6to4),
 * {@code 2001::/32} (Teredo), {@code 64:ff9b:1::/48} and {@code ::ffff:0:0:0/96} (SIIT). A name that resolves to several addresses is refused when
 * any one of them is.
 */
final class ImageAddressPolicy {
    /** Name resolution, replaceable in tests. */
    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;

        Resolver SYSTEM = InetAddress::getAllByName;
    }

    private ImageAddressPolicy() { }

    /** True when every address of {@code addresses} is public unicast (and there is at least one). */
    static boolean allPublic(InetAddress[] addresses) {
        if (addresses == null || addresses.length == 0) return false;
        for (InetAddress address : addresses) if (!isPublic(address)) return false;
        return true;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address) return publicV4(raw[0] & 0xff, raw[1] & 0xff, raw[2] & 0xff);
        if (address instanceof Inet6Address) {
            // unique local fc00::/7 (the JDK has no predicate for it)
            if ((raw[0] & 0xfe) == 0xfc) return false;
            boolean zeroPrefix = true;
            for (int index = 0; index < 10; index++) if (raw[index] != 0) zeroPrefix = false;
            if (zeroPrefix) {
                boolean mapped = (raw[10] & 0xff) == 0xff && (raw[11] & 0xff) == 0xff;
                boolean compatible = raw[10] == 0 && raw[11] == 0;
                // ::ffff:a.b.c.d and ::a.b.c.d (this also covers ::1 and :: which are refused above): judge the embedded IPv4 address
                if (mapped || compatible) return publicV4(raw[12] & 0xff, raw[13] & 0xff, raw[14] & 0xff) && !embeddedSpecial(raw);
            }
            // 64:ff9b::/96 (NAT64) embeds an IPv4 address as well
            boolean nat64 = raw[0] == 0 && raw[1] == 0x64 && (raw[2] & 0xff) == 0xff && (raw[3] & 0xff) == 0x9b;
            for (int index = 4; index < 12 && nat64; index++) if (raw[index] != 0) nat64 = false;
            if (nat64) return publicV4(raw[12] & 0xff, raw[13] & 0xff, raw[14] & 0xff);
            // 64:ff9b:1::/48 (local-use NAT64), 2002::/16 (6to4) and 2001::/32 (Teredo) embed or tunnel an IPv4 address that cannot be vouched
            // for, and ::ffff:0:0:0/96 (SIIT) is a translation prefix: none is a public unicast host
            if (raw[0] == 0 && raw[1] == 0x64 && (raw[2] & 0xff) == 0xff && (raw[3] & 0xff) == 0x9b && raw[4] == 0 && (raw[5] & 0xff) == 0x01) return false;
            if (raw[0] == 0x20 && raw[1] == 0x02) return false;
            if (raw[0] == 0x20 && raw[1] == 0x01 && raw[2] == 0 && raw[3] == 0) return false;
            boolean siit = true;
            for (int index = 0; index < 8; index++) if (raw[index] != 0) siit = false;
            if (siit && (raw[8] & 0xff) == 0xff && (raw[9] & 0xff) == 0xff && raw[10] == 0 && raw[11] == 0) return false;
            // 2001:db8::/32 documentation
            return !(raw[0] == 0x20 && raw[1] == 0x01 && raw[2] == 0x0d && (raw[3] & 0xff) == 0xb8);
        }
        return false;
    }

    private static boolean embeddedSpecial(byte[] raw) {
        // a.b.c.d with a == 0 is "this network" (covers :: itself)
        return (raw[12] & 0xff) == 0;
    }

    private static boolean publicV4(int a, int b, int c) {
        if (a == 0 || a == 10 || a == 127) return false;
        if (a == 100 && b >= 64 && b <= 127) return false;
        if (a == 169 && b == 254) return false;
        if (a == 172 && b >= 16 && b <= 31) return false;
        if (a == 192 && b == 168) return false;
        if (a == 192 && b == 0 && (c == 0 || c == 2)) return false;
        if (a == 198 && (b == 18 || b == 19)) return false;
        if (a == 198 && b == 51 && c == 100) return false;
        if (a == 203 && b == 0 && c == 113) return false;
        return a < 224;
    }
}
