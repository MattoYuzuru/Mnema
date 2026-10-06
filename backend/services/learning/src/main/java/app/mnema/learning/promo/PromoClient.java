package app.mnema.learning.promo;

import app.mnema.learning.platform.security.ClientAddresses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/** What abuse limits know about a caller: keyed hashes of the network address and the User-Agent, never the values. Either hash may be absent. */
public record PromoClient(byte[] ipHash, byte[] deviceHash) {
    static final PromoClient UNKNOWN = new PromoClient(null, null);

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
            byte[] ip = addresses.resolveNetwork(request).map(value -> hmac("ip:" + value)).orElse(null);
            String agent = request.getHeader("User-Agent");
            byte[] device = agent == null || agent.isBlank() ? null
                    : hmac("ua:" + (agent.length() > 256 ? agent.substring(0, 256) : agent));
            return new PromoClient(ip, device);
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
