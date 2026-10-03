package app.mnema.learning.generation;

import app.mnema.learning.ai.AiProperties;
import app.mnema.learning.ai.OpaqueUserKey;
import app.mnema.learning.ai.UserKeys;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The opaque, non-personal user key a step sends to the provider (abuse isolation). A deployment without
 * {@code learning.ai.user-key.secret} may only run the Stub, which uses a fixed key that is not a secret.
 */
@Component
class ProviderKeys {
    private static final UserKeys STUB_KEYS = UserKeys.withSecret("stub-user-key-secret-not-for-production", "stub");

    private final UserKeys userKeys;
    private final AiProperties ai;

    ProviderKeys(UserKeys userKeys, AiProperties ai) {
        this.userKeys = userKeys;
        this.ai = ai;
    }

    /** @throws IllegalStateException no key secret is configured and the provider is not the Stub */
    OpaqueUserKey opaque(UUID owner) {
        if (userKeys.configured()) return userKeys.opaque(owner);
        if (!AiProperties.STUB.equals(ai.provider())) throw new IllegalStateException("learning.ai.user-key.secret is not configured");
        return STUB_KEYS.opaque(owner);
    }
}
