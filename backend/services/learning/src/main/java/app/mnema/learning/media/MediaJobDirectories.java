package app.mnema.learning.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Lifecycle of the directories inside Learning's job spool. Nothing but Learning and the root runner can reach
 * it, but deletion still walks open directory handles with no-follow semantics, so a link can never redirect a
 * delete.
 */
final class MediaJobDirectories {
    static final String PREFIX = "media-";
    /** Learning-only scratch inside the work root (private copies of verified worker output); never opened to the worker. */
    static final String PRIVATE = ".private";
    private static final Set<String> KEEP = Set.of(PRIVATE, "lost+found");
    private static final Logger log = LoggerFactory.getLogger(MediaJobDirectories.class);

    private MediaJobDirectories() { }

    /** Creates the private scratch directory (owner only) and a fresh subdirectory in it. */
    static Path newPrivateDirectory(Path root) throws IOException {
        var owner = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
        Path scratch = root.resolve(PRIVATE);
        try {
            Files.createDirectory(scratch, owner);   // created with its final mode, never opened up and narrowed later
        } catch (FileAlreadyExistsException existing) {
            WorkerFiles.requireDirectory(scratch);   // a link or file in its place is refused
        }
        return Files.createTempDirectory(scratch, "v-", owner);
    }

    /** Deletes a job (or private) directory with all that is in it; failures are logged and left for the sweep. */
    static void delete(Path directory) {
        try {
            deleteTree(directory);
        } catch (IOException failure) {
            log.warn("media_processing_job_cleanup_failed error_type={}", failure.getClass().getSimpleName());
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        Path parent = directory.toAbsolutePath().getParent();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            if (stream instanceof SecureDirectoryStream<Path> secure) {
                deleteEntry(secure, directory.getFileName());
                return;
            }
        }
        // No secure directory streams on this platform: refuse links and delete bottom-up without following them.
        WorkerFiles.requireDirectory(directory);
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void deleteEntry(SecureDirectoryStream<Path> parent, Path name) throws IOException {
        BasicFileAttributes attributes = parent.getFileAttributeView(name, BasicFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS).readAttributes();
        if (!attributes.isDirectory()) {
            parent.deleteFile(name);   // a link is unlinked, never followed
            return;
        }
        IOException first = null;
        try (SecureDirectoryStream<Path> child = parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
            List<Path> names = new ArrayList<>();
            for (Path entry : child) names.add(entry.getFileName());
            for (Path entry : names) {
                try {
                    deleteEntry(child, entry);
                } catch (IOException failure) {
                    if (first == null) first = failure;   // keep going: delete what can be deleted
                }
            }
        }
        if (first != null) throw first;
        parent.deleteDirectory(name);
    }

    /**
     * Removes entries nobody owns any more (a crash or a killed process left them) older than
     * {@code maxAge}: stale {@code media-*} jobs not run by this process, stale private copies, and any
     * other entry a misbehaving writer left in the root. Links are unlinked, never followed, and the
     * private scratch directory itself and {@code lost+found} stay.
     */
    static int sweepStale(Path root, Duration maxAge, Set<Path> active, Instant now) {
        return sweep(root, now.minus(maxAge), active, true);
    }

    /** At startup no job of this single Learning instance can be running: remove every leftover job and private copy. */
    static int sweepAll(Path root) {
        return sweep(root, Instant.MAX, Set.of(), false);
    }

    private static int sweep(Path root, Instant cutoff, Set<Path> active, boolean everything) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return 0;
        int removed = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (active.contains(entry)) continue;
                if (name.equals(PRIVATE)) {
                    removed += sweep(entry, cutoff, Set.of(), true);
                    continue;
                }
                if (KEEP.contains(name) || (!everything && !name.startsWith(PREFIX))) continue;
                if (Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) {
                    delete(entry);
                    removed++;
                }
            }
        } catch (IOException failure) {
            log.warn("media_processing_job_sweep_failed error_type={}", failure.getClass().getSimpleName());
        }
        return removed;
    }
}
