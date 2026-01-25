package app.mnema.ai.vault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Service
public class LocalSecretVault implements SecretVault {

    private static final Logger log = LoggerFactory.getLogger(LocalSecretVault.class);
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKey masterKey;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    public LocalSecretVault(VaultProps props) {
        String appEnv = System.getenv("APP_ENV");
        boolean isProd = appEnv != null
                && (appEnv.equalsIgnoreCase("prod") || appEnv.equalsIgnoreCase("production"));
        String key = props == null ? null : props.masterKey();
        if (key == null || key.isBlank()) {
            if (isProd) {
                throw new IllegalStateException("AI vault master key is required in prod");
            }
            byte[] generated = new byte[32];
            random.nextBytes(generated);
            this.masterKey = new SecretKeySpec(generated, "AES");
            this.keyId = props == null || props.keyId() == null || props.keyId().isBlank()
                    ? "local-ephemeral"
                    : props.keyId();
            log.warn("AI vault master key is not configured; using ephemeral key");
        } else {
            byte[] decoded = Base64.getDecoder().decode(key);
            if (decoded.length != 16 && decoded.length != 24 && decoded.length != 32) {
                throw new IllegalStateException("Invalid vault master key length");
            }
            this.masterKey = new SecretKeySpec(decoded, "AES");
            this.keyId = props.keyId() == null || props.keyId().isBlank() ? "local-master" : props.keyId();
        }
    }

    @Override
    public EncryptedSecret encrypt(byte[] plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext is required");
        }
        byte[] aad = new byte[0];
        byte[] dataKey = new byte[32];
        random.nextBytes(dataKey);
        byte[] nonce = randomNonce();
        byte[] ciphertext = encryptAesGcm(plaintext, dataKey, nonce, aad);

        byte[] dataKeyNonce = randomNonce();
        byte[] encryptedDataKey = encryptAesGcm(dataKey, masterKey.getEncoded(), dataKeyNonce, aad);
        byte[] packedDataKey = new byte[dataKeyNonce.length + encryptedDataKey.length];
        System.arraycopy(dataKeyNonce, 0, packedDataKey, 0, dataKeyNonce.length);
        System.arraycopy(encryptedDataKey, 0, packedDataKey, dataKeyNonce.length, encryptedDataKey.length);

        return new EncryptedSecret(ciphertext, packedDataKey, keyId, nonce, aad);
    }

    @Override
    public byte[] decrypt(EncryptedSecret secret) {
        if (secret == null) {
            throw new IllegalArgumentException("secret is required");
        }
        byte[] aad = secret.aad() == null ? new byte[0] : secret.aad();
        byte[] dataKey = resolveDataKey(secret.encryptedDataKey(), aad);
        return decryptAesGcm(secret.ciphertext(), dataKey, secret.nonce(), aad);
    }

    private byte[] resolveDataKey(byte[] encryptedDataKey, byte[] aad) {
        if (encryptedDataKey == null || encryptedDataKey.length == 0) {
            return masterKey.getEncoded();
        }
        if (encryptedDataKey.length <= NONCE_LENGTH) {
            throw new IllegalArgumentException("Invalid encrypted data key format");
        }
        byte[] dataKeyNonce = Arrays.copyOfRange(encryptedDataKey, 0, NONCE_LENGTH);
        byte[] cipher = Arrays.copyOfRange(encryptedDataKey, NONCE_LENGTH, encryptedDataKey.length);
        return decryptAesGcm(cipher, masterKey.getEncoded(), dataKeyNonce, aad);
    }

    private byte[] randomNonce() {
        byte[] nonce = new byte[NONCE_LENGTH];
        random.nextBytes(nonce);
        return nonce;
    }

    private byte[] encryptAesGcm(byte[] plaintext, byte[] key, byte[] nonce, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(plaintext);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encrypt secret", ex);
        }
    }

    private byte[] decryptAesGcm(byte[] ciphertext, byte[] key, byte[] nonce, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(ciphertext);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to decrypt secret", ex);
        }
    }
}
