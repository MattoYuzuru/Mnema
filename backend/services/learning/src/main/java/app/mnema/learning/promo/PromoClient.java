package app.mnema.learning.promo;

import app.mnema.learning.platform.security.ClientAddresses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * What abuse limits know about a caller: a keyed hash of the network address, never the value; absent when the address is unknown. There is no device
 * hash: a User-Agent is shared by millions of devices, so it adds no usable signal to the address and would only be stored for nothing.
 */
public record PromoClient(byte[] ipHash) {
    static final PromoClient UNKNOWN = new PromoClient(null);

    /** Derives the {@link PromoClient} of a request: address through the trusted-proxy rule (IPv6 reduced to its /64), then HMAC-SHA256 with the promo secret. */
    @Component
    static final class Resolver {
        private final ClientAddresses addresses;
        private final PromoSettings settings;

        Resolver(ClientAddresses addresses, PromoSettings settings) {
            this.addresses = addresses;
            this.settings = settings;
        }

        PromoClient of(HttpServletRequest request) {
            return new PromoClient(addresses.resolveNetwork(request).map(value -> hmac("ip:" + value)).orElse(null));
        }

        private byte[] hmac(String value) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(settings.hashSecret, "HmacSHA256"));
                return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            } catch (GeneralSecurityException failure) {
                throw new IllegalStateException("HMAC-SHA256 is unavailable", failure);
            }
        }
    }
}
