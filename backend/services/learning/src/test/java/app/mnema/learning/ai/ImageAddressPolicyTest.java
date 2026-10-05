package app.mnema.learning.ai;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

class ImageAddressPolicyTest {
    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    @Test
    void publicUnicastAddressesAreAllowed() throws Exception {
        for (String literal : new String[] {"93.184.216.34", "8.8.8.8", "151.101.1.69", "2606:2800:220:1:248:1893:25c8:1946", "2a00:1450:4001:81a::200e"}) {
            assertThat(ImageAddressPolicy.isPublic(ip(literal))).as(literal).isTrue();
        }
    }

    @Test
    void loopbackPrivateLinkLocalMetadataCgnatMulticastAndUniqueLocalAreRefused() throws Exception {
        for (String literal : new String[] {"127.0.0.1", "127.1.2.3", "0.0.0.0", "10.0.0.1", "172.16.5.4", "172.31.255.255", "192.168.0.10",
                "169.254.169.254", "169.254.1.1", "100.64.0.1", "100.127.255.254", "224.0.0.1", "239.1.1.1", "255.255.255.255", "198.18.0.1",
                "192.0.2.5", "198.51.100.7", "203.0.113.9", "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1", "2001:db8::1"}) {
            assertThat(ImageAddressPolicy.isPublic(ip(literal))).as(literal).isFalse();
        }
    }

    @Test
    void anIPv4MappedOrCompatibleIPv6AddressIsJudgedByItsEmbeddedIPv4Address() throws Exception {
        for (String literal : new String[] {"::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254", "::ffff:192.168.1.1", "::ffff:100.64.0.1",
                "::127.0.0.1", "::10.1.2.3", "::169.254.169.254", "64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe"}) {
            assertThat(ImageAddressPolicy.isPublic(ip(literal))).as(literal).isFalse();
        }
        assertThat(ImageAddressPolicy.isPublic(ip("::ffff:8.8.8.8"))).isTrue();
        assertThat(ImageAddressPolicy.isPublic(ip("64:ff9b::808:808"))).isTrue();
    }

    @Test
    void tunnelAndTranslationPrefixesAreRefused() throws Exception {
        for (String literal : new String[] {"2002:808:808::1", "2002:7f00:1::1", "2001:0:4136:e378:8000:63bf:3fff:fdd2", "2001::1", "64:ff9b:1::808:808",
                "64:ff9b:1:ffff::1", "::ffff:0:808:808"}) {
            assertThat(ImageAddressPolicy.isPublic(ip(literal))).as(literal).isFalse();
        }
        // neighbouring public space stays reachable
        assertThat(ImageAddressPolicy.isPublic(ip("2001:4860:4860::8888"))).isTrue();
    }

    @Test
    void aNameIsRefusedWhenAnyOfItsAddressesIsAndWhenItHasNone() throws Exception {
        assertThat(ImageAddressPolicy.allPublic(new InetAddress[] {ip("93.184.216.34"), ip("2606:2800:220:1::1")})).isTrue();
        assertThat(ImageAddressPolicy.allPublic(new InetAddress[] {ip("93.184.216.34"), ip("127.0.0.1")})).isFalse();
        assertThat(ImageAddressPolicy.allPublic(new InetAddress[0])).isFalse();
        assertThat(ImageAddressPolicy.allPublic(null)).isFalse();
    }
}
