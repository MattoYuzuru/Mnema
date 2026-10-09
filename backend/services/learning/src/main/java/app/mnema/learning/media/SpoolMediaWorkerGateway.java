package app.mnema.learning.media;

import app.mnema.learning.platform.json.ContentJsonReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Hands one job to the root media runner through the job directory (the spool).
 *
 * <p>Protocol v1, inside the job directory Learning created (mode 0700, never opened to anyone): this class writes
 * {@code request.json} and an empty {@code output/}, then atomically renames {@code submitted.tmp} to
 * {@code submitted}. The trusted runner (a root host service) copies the source into its own scratch, runs the
 * one-shot FFmpeg CLI in a throw-away container with no network, validates what the container produced, copies it
 * into {@code output/} (owned by Learning) and only then atomically writes {@code status.json}: exit 0 is success,
 * exit 2 plus a code is terminal invalid media, anything else is retryable. The runner marks the start with a
 * {@code claimed} file; {@code cancel} asks it to stop. Learning accepts a verdict <strong>only from a file owned
 * by root</strong>: no unprivileged process, in particular no container, can forge one. Every file is still read
 * through {@link WorkerFiles} (no links, regular files, once).
 */
@Component
final class SpoolMediaWorkerGateway implements MediaWorkerGateway {
    static final long DISK_CAP_BYTES = 7L * 1024 * 1024 * 1024;
    private static final Duration WAIT_MARGIN = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofMillis(250);
    private static final Duration DISK_CHECK = Duration.ofSeconds(2);
    private static final int MAX_STATUS_BYTES = 4096;
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Set<String> STATUS_FIELDS = Set.of("formatVersion", "exitCode", "code");

    private final Duration wait;
    private final Duration poll;
    private final long diskCapBytes;
    private final int verdictUid;

    @Autowired
    SpoolMediaWorkerGateway(MediaProcessingSettings settings) {
        this(settings.workerTimeout.plus(WAIT_MARGIN), POLL, DISK_CAP_BYTES, settings.verdictUid);
    }

    /** Test seam: the wait limit, the poll interval, the disk cap and the uid that owns a genuine verdict (root) are otherwise fixed. */
    SpoolMediaWorkerGateway(Duration wait, Duration poll, long diskCapBytes, int verdictUid) {
        this.wait = wait;
        this.poll = poll;
        this.diskCapBytes = diskCapBytes;
        this.verdictUid = verdictUid;
    }

    @Override
    public void run(Path job, UUID assetId, long generation, String kind,
                    long length, String sha256, long maxDurationMs) {
        try {
            submit(job, assetId, generation, kind, length, sha256, maxDurationMs);
            await(job);
        } catch (MediaProcessingRejectedException rejected) {
            throw rejected;
        } catch (IOException | RuntimeException failure) {
            cancel(job);
            if (failure instanceof MediaStorageUnavailableException unavailable) throw unavailable;
            throw new MediaStorageUnavailableException();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancel(job);
            throw new MediaStorageUnavailableException();
        }
    }

    private static void submit(Path job, UUID assetId, long generation, String kind,
                               long length, String sha256, long maxDurationMs) throws IOException {
        Path output = job.resolve("output");
        Files.createDirectory(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        String duration = kind.equals("image") ? "null" : Long.toString(maxDurationMs);
        String manifest = "{\"formatVersion\":1,\"assetId\":\"" + assetId
                + "\",\"generation\":" + generation + ",\"kind\":\"" + kind
                + "\",\"expectedByteLength\":" + length + ",\"expectedSha256\":\""
                + sha256 + "\",\"maxDurationMs\":" + duration + "}";
        createNew(job.resolve("request.json"), manifest.getBytes(StandardCharsets.UTF_8));
        // The runner only looks at jobs that carry the marker, so everything above is complete by then.
        Path draft = job.resolve("submitted.tmp");
        createNew(draft, new byte[0]);
        Files.move(draft, job.resolve("submitted"), StandardCopyOption.ATOMIC_MOVE);
    }

    /** CREATE_NEW and NOFOLLOW_LINKS: never write through something that already exists in the job directory. */
    private static void createNew(Path file, byte[] bytes) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.write(ByteBuffer.wrap(bytes));
        }
    }

    private void await(Path job) throws IOException, InterruptedException {
        Path claimed = job.resolve("claimed");
        Path status = job.resolve("status.json");
        long deadline = System.nanoTime() + wait.toNanos();
        long nextDisk = System.nanoTime() + DISK_CHECK.toNanos();
        boolean started = false;
        while (true) {
            if (exists(status)) {
                interpret(status);   // a link, FIFO, oversized or non-root status is simply a retryable failure
                return;
            }
            long now = System.nanoTime();
            if (!started && exists(claimed)) {
                // Queue time behind another job does not count against this job's own budget.
                started = true;
                deadline = now + wait.toNanos();
            }
            if (now - deadline >= 0) throw new MediaStorageUnavailableException();
            if (now - nextDisk >= 0) {
                nextDisk = now + DISK_CHECK.toNanos();
                if (diskBytes(job) > diskCapBytes) throw new MediaStorageUnavailableException();
            }
            Thread.sleep(poll);
        }
    }

    /** Whether the entry exists at all, whatever it is; it is never followed. */
    private static boolean exists(Path entry) {
        return Files.exists(entry, LinkOption.NOFOLLOW_LINKS);
    }

    private void interpret(Path status) throws IOException {
        try {
            WorkerFiles.requireRegular(status);
            Object owner = Files.getAttribute(status, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            if (!(owner instanceof Integer uid) || uid != verdictUid) throw new MediaStorageUnavailableException();
            JsonNode root = new ContentJsonReader(MAX_STATUS_BYTES, 4, 16)
                    .read(WorkerFiles.readBounded(status, MAX_STATUS_BYTES));
            if (!root.isObject() || root.size() != STATUS_FIELDS.size()
                    || !root.properties().stream().allMatch(entry -> STATUS_FIELDS.contains(entry.getKey()))
                    || !root.path("formatVersion").isIntegralNumber() || root.path("formatVersion").intValue(0) != 1
                    || !root.path("exitCode").isIntegralNumber()) {
                throw new MediaStorageUnavailableException();
            }
            int exit = root.path("exitCode").intValue(-1);
            JsonNode code = root.path("code");
            if (exit == 0 && code.isNull()) return;
            if (exit == 2 && code.isString() && CODE.matcher(code.stringValue("")).matches()) {
                throw new MediaProcessingRejectedException(code.stringValue(""));
            }
            // Exit 3, an unknown exit code, a rejection without a valid code: retryable.
            throw new MediaStorageUnavailableException();
        } catch (IllegalArgumentException | JacksonException malformed) {
            throw new MediaStorageUnavailableException();
        }
    }

    private static void cancel(Path job) {
        try {
            createNew(job.resolve("cancel"), new byte[0]);
        } catch (FileAlreadyExistsException | NoSuchFileException ignored) {
            // already asked, or the job directory is gone: the worker stops when it notices that too
        } catch (IOException ignored) {
            // nothing more can be done from here
        }
    }

    /** Bytes of regular files below the job; entries that vanish or cannot be read count as nothing. */
    static long diskBytes(Path job) throws IOException {
        var total = new AtomicLong();
        Files.walkFileTree(job, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) total.addAndGet(attrs.size());
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) {
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) {
                return FileVisitResult.CONTINUE;
            }
        });
        return total.get();
    }
}
