package app.mnema.ai.vault;

public interface SecretVault {
    EncryptedSecret encrypt(byte[] plaintext);

    byte[] decrypt(EncryptedSecret secret);
}
