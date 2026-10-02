package app.mnema.learning.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserKeysTest {
    private static UserKeys keys(String secret, String keyId) {
        AiProperties base = AiTestSupport.properties("", AiTestSupport.routes(List.of(), List.of(), List.of()), Map.of());
        return new UserKeys(new AiProperties(base.provider(), base.routes(), base.providers(), base.models(), base.transport(),
                base.retry(), base.breaker(), base.permits(), base.budget(), new AiProperties.UserKey(secret, keyId), base.prompt()));
    }

    @Test
    void theKeyIsStableOpaqueAndAcceptedAsARequestUserKey() {
        UserKeys keys = keys("0123456789abcdef0123456789abcdef", "k1");
        UUID account = UUID.fromString("018f1d98-5c10-7abc-8abc-012345678921");
        String key = keys.opaque(account);

        assertThat(keys.configured()).isTrue();
        assertThat(key).startsWith("k1.").doesNotContain(account.toString()).matches("[A-Za-z0-9._-]{1,64}");
        assertThat(keys.opaque(account)).isEqualTo(key);
        assertThat(keys.opaque(UUID.randomUUID())).isNotEqualTo(key);
        // the key id and the secret both matter: rotation severs old links
        assertThat(keys("0123456789abcdef0123456789abcdee", "k1").opaque(account).substring(3)).isNotEqualTo(key.substring(3));
        assertThat(keys("0123456789abcdef0123456789abcdef", "k2").opaque(account)).startsWith("k2.");
        // known-answer for HMAC-SHA256(secret, accountId), so a refactor cannot silently change every user key
        assertThat(key).isEqualTo("k1.fVwg51GlEYceihQWXn5sWcQh09X8-K4pSJvz6PbljNM");
    }

    @Test
    void withoutASecretNoKeyIsEverMade() {
        UserKeys none = keys("", "k1");
        assertThat(none.configured()).isFalse();
        assertThatThrownBy(() -> none.opaque(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        assertThat(keys("short", "k1").configured()).isFalse();
        assertThatThrownBy(() -> new AiProperties.UserKey("0123456789abcdef", "bad id!")).isInstanceOf(IllegalArgumentException.class);
    }
}
