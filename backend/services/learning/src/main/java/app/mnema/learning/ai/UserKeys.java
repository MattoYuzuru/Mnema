package app.mnema.learning.ai;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque per-account key sent to providers as the user id: {@code <keyId>.<base64url(HMAC-SHA256(accountId))>}. The
 * provider can tell requests of one account apart (abuse isolation) but cannot link them to a person; rotating the
 * secret and its {@code keyId} severs old links. The secret ({@code MNEMA_AI_USER_KEY_SECRET}) must be configured: there is
 * no per-process default, because a key that changes on restart would defeat isolation.
 */
@Component
public final class UserKeys {
    private final String keyId;
    private final SecretKeySpec key;

    UserKeys(AiProperties properties) {
        AiProperties.UserKey config = properties.userKey();
        this.keyId = config.keyId();
        this.key = config.configured() ? new SecretKeySpec(config.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256") : null;
    }

    public boolean configured() { return key != null; }

    public String opaque(UUID accountId) {
        if (key == null) throw new IllegalStateException("learning.ai.user-key.secret is not configured");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] digest = mac.doFinal(accountId.toString().getBytes(StandardCharsets.UTF_8));
            return keyId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }
}
