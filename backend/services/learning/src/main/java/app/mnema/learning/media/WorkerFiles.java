package app.mnema.learning.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Every access to a file the media worker can write. The worker runs FFmpeg on hostile input and is
 * treated as compromised, so a path under a job directory is never trusted: no call here follows a
 * symbolic link, only a regular file is opened (a FIFO or device would block or lie), each file is
 * opened exactly once, and sizes come from the bytes actually read, never from a second look at the path.
 * The primary defence is elsewhere (the worker's output only reaches Learning as validated, Learning-owned
 * copies made by the root runner after the container is gone); this is the layer that must still hold when it does not.
 */
final class WorkerFiles {
    private WorkerFiles() { }

    /** The file exists as a regular file that is not a link; anything else (link, FIFO, device, directory) is refused. */
    static void requireRegular(Path file) throws IOException {
        if (!Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
            throw new IOException("not a regular file");
        }
    }

    static void requireDirectory(Path directory) throws IOException {
        if (!Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()) {
            throw new IOException("not a directory");
        }
    }

    /** At most {@code cap} bytes of a regular file; a longer file is refused, not truncated. */
    static byte[] readBounded(Path file, int cap) throws IOException {
        requireRegular(file);
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             InputStream input = Channels.newInputStream(channel)) {
            byte[] bytes = input.readNBytes(cap + 1);
            if (bytes.length > cap) throw new IOException("too large");
            return bytes;
        }
    }

    /**
     * Copies exactly {@code length} bytes of a worker file into a Learning-private file in one pass,
     * hashing what is copied. A longer file, a shorter file or a different digest is refused.
     */
    static void copyVerified(Path source, Path target, long length, String sha256) throws IOException {
        requireRegular(source);
        try (SeekableByteChannel in = Files.newByteChannel(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             InputStream input = Channels.newInputStream(in);
             var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer, 0, (int) Math.min(buffer.length, length + 1 - total))) > 0) {
                total += count;
                if (total > length) throw new IOException("longer than declared");
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
            if (total != length) throw new IOException("shorter than declared");
            if (!HexFormat.of().formatHex(digest.digest()).equals(sha256)) throw new IOException("digest mismatch");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
