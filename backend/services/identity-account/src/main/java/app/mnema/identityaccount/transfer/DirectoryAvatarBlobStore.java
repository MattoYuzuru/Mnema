package app.mnema.identityaccount.transfer;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

final class DirectoryAvatarBlobStore implements AvatarBlobStore {
    private final Path root;

    DirectoryAvatarBlobStore(Path root) {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        AccountTransferBundle.require(Files.isDirectory(absoluteRoot, LinkOption.NOFOLLOW_LINKS),
                "avatar_root_missing");
        try {
            this.root = absoluteRoot.toRealPath();
        } catch (IOException exception) {
            throw new AccountTransferFailure("avatar_root_missing", exception);
        }
    }

    @Override
    public byte[] read(String key) {
        Path path = resolve(key);
        try {
            Path parent = safeParent(path, false);
            AccountTransferBundle.require(parent != null, "avatar_object_missing");
            path = parent.resolve(path.getFileName());
            AccountTransferBundle.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "avatar_object_missing");
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
            Path parent = safeParent(path, true);
            path = parent.resolve(path.getFileName());
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                AccountTransferBundle.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                && Files.size(path) <= 10 * 1024 * 1024
                                && MessageDigest.isEqual(Files.readAllBytes(path), bytes),
                        "avatar_object_conflict");
                return false;
            }
            temporary = Files.createTempFile(parent, ".mnema-avatar-", ".tmp");
            Files.write(temporary, bytes);
            requireSafeDirectory(parent);
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
            Path parent = safeParent(path, false);
            if (parent != null) Files.deleteIfExists(parent.resolve(path.getFileName()));
        } catch (AccountTransferFailure failure) {
            throw failure;
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

    private Path safeParent(Path path, boolean createMissing) throws IOException {
        Path parent = path.getParent();
        AccountTransferBundle.require(parent != null && parent.startsWith(root), "invalid_avatar_object_path");
        Path current = root;
        requireSafeDirectory(current);
        for (Path segment : root.relativize(parent)) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!createMissing) return null;
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException ignored) {
                    // Validate the entry that won the create race below.
                }
            }
            requireSafeDirectory(current);
        }
        return current;
    }

    private void requireSafeDirectory(Path directory) throws IOException {
        AccountTransferBundle.require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS),
                "invalid_avatar_object_path");
        AccountTransferBundle.require(directory.toRealPath().startsWith(root), "invalid_avatar_object_path");
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
