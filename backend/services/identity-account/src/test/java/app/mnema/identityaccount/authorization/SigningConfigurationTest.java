package app.mnema.identityaccount.authorization;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningConfigurationTest {
    @TempDir
    Path directory;

    @Test
    void explicitRotationKeepsOnlyActivePrivateKeyAndSurvivesReload() throws Exception {
        RSAKey active = new RSAKeyGenerator(2048).keyID("new-active").generate();
        RSAKey previous = new RSAKeyGenerator(2048).keyID("prior-verification").generate();
        Path file = directory.resolve("synthetic-keys.json");
        Files.writeString(file, new JWKSet(List.of(active, previous)).toString(false));
        var configuration = new AuthorizationConfiguration();
        var keys = configuration.signingKeys(file.toString(), "new-active");
        assertThat(keys.getKeyByKeyId("new-active").isPrivate()).isTrue();
        assertThat(keys.getKeyByKeyId("prior-verification").isPrivate()).isFalse();
        assertThat(configuration.signingKeys(file.toString(), "new-active").getKeyByKeyId("new-active")
                .computeThumbprint())
                .isEqualTo(active.computeThumbprint());
        assertThatThrownBy(() -> configuration.signingKeys(file.toString(), "absent")).isInstanceOf(
                IllegalArgumentException.class);
        Files.writeString(file, new JWKSet(active.toPublicJWK()).toString());
        assertThatThrownBy(() -> configuration.signingKeys(file.toString(), "new-active")).isInstanceOf(
                IllegalArgumentException.class);
        Files.writeString(file, new JWKSet(List.of(active, active)).toString(false));
        assertThatThrownBy(() -> configuration.signingKeys(file.toString(), "new-active")).isInstanceOf(
                IllegalArgumentException.class);
    }
}
