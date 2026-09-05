package app.mnema.identityaccount.transfer;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

final class DirectoryAvatarBlobStore implements AvatarBlobStore {
    private final Path root;

    DirectoryAvatarBlobStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        AccountTransferBundle.require(Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS), "avatar_root_missing");
    }

    @Override
    public byte[] read(String key) {
        Path path = resolve(key);
        try {
            AccountTransferBundle.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "avatar_object_missing");
            AccountTransferBundle.require(path.toRealPath().startsWith(root.toRealPath()), "invalid_avatar_object_path");
            AccountTransferBundle.require(Files.size(path) <= 10 * 1024 * 1024, "avatar_too_large");
            byte[] bytes = Files.readAllBytes(path);
            return bytes;
        } catch (AccountTransferFailure failure) {
            throw failure;
        } catch (IOException exception) {
            throw new AccountTransferFailure("avatar_read_failed", exception);
        }
    }

    @Override
    public boolean putExact(String key, byte[] bytes) {
        Path path = resolve(key);
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            AccountTransferBundle.require(path.getParent().toRealPath().startsWith(root.toRealPath()),
                    "invalid_avatar_object_path");
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                AccountTransferBundle.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                && Files.size(path) <= 10 * 1024 * 1024
                                && MessageDigest.isEqual(Files.readAllBytes(path), bytes),
                        "avatar_object_conflict");
                return false;
            }
            temporary = Files.createTempFile(path.getParent(), ".mnema-avatar-", ".tmp");
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path);
            }
            return true;
        } catch (AccountTransferFailure failure) {
            tryDelete(temporary);
            throw failure;
        } catch (IOException exception) {
            tryDelete(temporary);
            throw new AccountTransferFailure("avatar_write_failed", exception);
        }
    }

    @Override
    public void deleteExact(String key) {
        Path path = resolve(key);
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new AccountTransferFailure("avatar_cleanup_failed", exception);
        }
    }

    private Path resolve(String key) {
        AccountTransferBundle.require(key != null && !key.isBlank() && !key.startsWith("/")
                && !key.contains("\\") && !key.contains("\u0000"), "invalid_avatar_object_key");
        Path path = root.resolve(key).normalize();
        AccountTransferBundle.require(path.startsWith(root) && !path.equals(root), "invalid_avatar_object_key");
        return path;
    }

    private static void tryDelete(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The caller receives the primary operation failure.
        }
    }
}
