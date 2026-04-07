package app.mnema.ai.vault;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalSecretVaultTest {

    @Test
    void encryptDecryptRoundTripWithConfiguredHexKey() {
        LocalSecretVault vault = new LocalSecretVault(new VaultProps(
                "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
                "unit-test"
        ));

        EncryptedSecret encrypted = vault.encrypt("secret-value".getBytes(StandardCharsets.UTF_8));

        assertEquals("unit-test", encrypted.keyId());
        assertNotNull(encrypted.ciphertext());
        assertNotNull(encrypted.encryptedDataKey());
        assertArrayEquals("secret-value".getBytes(StandardCharsets.UTF_8), vault.decrypt(encrypted));
    }

    @Test
    void supportsBase64KeyMaterial() {
        String key = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        LocalSecretVault vault = new LocalSecretVault(new VaultProps(key, null));

        EncryptedSecret encrypted = vault.encrypt("hello".getBytes(StandardCharsets.UTF_8));

        assertEquals("local-master", encrypted.keyId());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), vault.decrypt(encrypted));
    }

    @Test
    void rejectsInvalidKeyEncoding() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new LocalSecretVault(new VaultProps("not-base64-or-hex!", "bad")));

        assertEquals("Invalid vault master key encoding: expected base64 or hex", ex.getMessage());
    }

    @Test
    void rejectsInvalidEncryptedDataKeyFormat() {
        LocalSecretVault vault = new LocalSecretVault(new VaultProps(
                "00112233445566778899aabbccddeeff",
                "short-key"
        ));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> vault.decrypt(new EncryptedSecret(new byte[]{1}, new byte[]{1}, "short-key", new byte[12], new byte[0])));

        assertEquals("Invalid encrypted data key format", ex.getMessage());
    }
}
