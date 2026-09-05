package app.mnema.identityaccount.transfer;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class AccountTransferCodec {
    private static final String ACCOUNTS_ENTRY = "accounts.json";
    private static final byte[] MAGIC = "MNEMA-ACCOUNT-XFER-1\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int NONCE_BYTES = 12;
    private static final int MAX_ACCOUNTS_BYTES = 64 * 1024 * 1024;
    private static final int MAX_AVATAR_BYTES = 10 * 1024 * 1024;
    private final SecretKeySpec encryptionKey;
    private final ObjectMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .findAndAddModules()
            .build();

    AccountTransferCodec(byte[] encryptionKey) {
        AccountTransferBundle.require(encryptionKey != null && encryptionKey.length == 32,
                "invalid_encryption_key");
        this.encryptionKey = new SecretKeySpec(encryptionKey.clone(), "AES");
    }

    void write(Path output, AccountTransferArtifact artifact) {
        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        AccountTransferBundle.require(parent != null && Files.isDirectory(parent), "invalid_artifact_parent");
        AccountTransferBundle.require(!Files.exists(absolute), "artifact_exists");
        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, ".mnema-account-transfer-", ".zip");
            setPrivatePermissions(temporary);
            byte[] nonce = new byte[NONCE_BYTES];
            new SecureRandom().nextBytes(nonce);
            OutputStream outputStream = Files.newOutputStream(temporary);
            outputStream.write(MAGIC);
            outputStream.write(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce);
            try (CipherOutputStream encrypted = new CipherOutputStream(outputStream, cipher);
                 ZipOutputStream zip = new ZipOutputStream(encrypted)) {
                writeEntry(zip, ACCOUNTS_ENTRY, json.writeValueAsBytes(artifact.bundle()));
                artifact.bundle().accounts().stream()
                        .filter(account -> account.avatar() != null)
                        .sorted(java.util.Comparator.comparing(account -> account.avatar().assetId()))
                        .forEach(account -> writeEntryUnchecked(zip, avatarEntry(account.avatar().assetId()),
                                artifact.avatar(account.avatar().assetId())));
            }
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute);
            }
        } catch (AccountTransferFailure failure) {
            if (temporary != null) tryDelete(temporary);
            throw failure;
        } catch (IOException | RuntimeException exception) {
            if (temporary != null) tryDelete(temporary);
            throw new AccountTransferFailure("artifact_write_failed", exception);
        }
    }

    AccountTransferArtifact read(Path input) {
        Path absolute = input.toAbsolutePath().normalize();
        AccountTransferBundle.require(Files.isRegularFile(absolute), "artifact_missing");
        byte[] accounts = null;
        Map<UUID, byte[]> avatars = new HashMap<>();
        Set<String> names = new HashSet<>();
        try (InputStream inputStream = Files.newInputStream(absolute)) {
            AccountTransferBundle.require(java.util.Arrays.equals(inputStream.readNBytes(MAGIC.length), MAGIC),
                    "invalid_artifact_header");
            byte[] nonce = inputStream.readNBytes(NONCE_BYTES);
            AccountTransferBundle.require(nonce.length == NONCE_BYTES, "invalid_artifact_header");
            byte[] plaintext = decryptAuthenticated(inputStream, nonce);
            try {
                try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(plaintext))) {
                    for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                        AccountTransferBundle.require(!entry.isDirectory() && names.add(entry.getName()),
                                "invalid_archive_entry");
                        if (ACCOUNTS_ENTRY.equals(entry.getName())) {
                            accounts = readBounded(zip, MAX_ACCOUNTS_BYTES, "accounts_too_large");
                        } else {
                            UUID assetId = parseAvatarEntry(entry.getName());
                            AccountTransferBundle.require(!avatars.containsKey(assetId), "duplicate_avatar_blob");
                            avatars.put(assetId, readBounded(zip, MAX_AVATAR_BYTES, "avatar_too_large"));
                        }
                        zip.closeEntry();
                    }
                    AccountTransferBundle.require(accounts != null, "missing_accounts_projection");
                    AccountTransferBundle bundle = json.readValue(accounts, AccountTransferBundle.class);
                    Set<String> expected = new HashSet<>();
                    expected.add(ACCOUNTS_ENTRY);
                    bundle.accounts().stream().filter(account -> account.avatar() != null)
                            .forEach(account -> expected.add(avatarEntry(account.avatar().assetId())));
                    AccountTransferBundle.require(names.equals(expected), "archive_entry_mismatch");
                    return new AccountTransferArtifact(bundle, avatars);
                }
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (AccountTransferFailure failure) {
            throw failure;
        } catch (IOException | RuntimeException exception) {
            throw new AccountTransferFailure("artifact_read_failed", exception);
        }
    }

    private byte[] decryptAuthenticated(InputStream input, byte[] nonce) throws IOException {
        try {
            return cipher(Cipher.DECRYPT_MODE, nonce).doFinal(input.readAllBytes());
        } catch (GeneralSecurityException exception) {
            throw new AccountTransferFailure("artifact_authentication_failed", exception);
        }
    }

    private Cipher cipher(int mode, byte[] nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, encryptionKey, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(MAGIC);
            cipher.updateAAD(nonce);
            return cipher;
        } catch (GeneralSecurityException exception) {
            throw new AccountTransferFailure("artifact_crypto_failed", exception);
        }
    }

    byte[] canonicalProjection(AccountTransferBundle bundle) {
        try {
            return json.writeValueAsBytes(bundle);
        } catch (IOException exception) {
            throw new AccountTransferFailure("projection_encode_failed", exception);
        }
    }

    byte[] evidence(AccountTransferEvidence evidence) {
        try {
            return json.writeValueAsBytes(evidence);
        } catch (IOException exception) {
            throw new AccountTransferFailure("evidence_encode_failed", exception);
        }
    }

    private static byte[] readBounded(InputStream input, int maximum, String code) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int read; (read = input.read(buffer)) != -1; ) {
            total += read;
            AccountTransferBundle.require(total <= maximum, code);
            result.write(buffer, 0, read);
        }
        return result.toByteArray();
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void writeEntryUnchecked(ZipOutputStream zip, String name, byte[] bytes) {
        try {
            writeEntry(zip, name, bytes);
        } catch (IOException exception) {
            throw new AccountTransferFailure("artifact_write_failed", exception);
        }
    }

    private static String avatarEntry(UUID assetId) {
        return "avatars/" + assetId + ".blob";
    }

    private static UUID parseAvatarEntry(String name) {
        AccountTransferBundle.require(name.startsWith("avatars/") && name.endsWith(".blob")
                && name.length() == "avatars/".length() + 36 + ".blob".length(), "unexpected_archive_entry");
        try {
            return UUID.fromString(name.substring("avatars/".length(), name.length() - ".blob".length()));
        } catch (IllegalArgumentException exception) {
            throw new AccountTransferFailure("unexpected_archive_entry", exception);
        }
    }

    private static void setPrivatePermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows has no POSIX mode; the parent ACL remains authoritative there.
        } catch (IOException exception) {
            throw new AccountTransferFailure("artifact_permissions_failed", exception);
        }
    }

    private static void tryDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The original failure is more useful; the random private file is reported by its owning process.
        }
    }
}
