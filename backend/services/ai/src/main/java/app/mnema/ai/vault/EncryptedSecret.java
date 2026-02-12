package app.mnema.ai.vault;

public record EncryptedSecret(
        byte[] ciphertext,
        byte[] encryptedDataKey,
        String keyId,
        byte[] nonce,
        byte[] aad
) {
}
