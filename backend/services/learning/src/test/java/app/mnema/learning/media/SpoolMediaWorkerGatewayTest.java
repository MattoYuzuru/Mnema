package app.mnema.learning.media;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Learning half of the spool protocol against a Java stand-in for the worker (no FFmpeg, no Docker). */
class SpoolMediaWorkerGatewayTest {
    private static final UUID ASSET = UUID.fromString("3cbb01c5-a3c0-467d-8e87-ac3a4e7e19ba");
    private static final String SHA = "a".repeat(64);

    @TempDir Path root;
    private Path job;
    private Thread worker;
    private final AtomicBoolean sawCompleteJob = new AtomicBoolean();
    private volatile String jobModeForWorker;
    private volatile String outputModeForWorker;

    @BeforeEach
    void createJob() throws IOException {
        job = Files.createDirectory(root.resolve("media-1"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.writeString(job.resolve("source"), "bytes");
    }

    @AfterEach
    void stopWorker() throws InterruptedException {
        if (worker != null) {
            worker.interrupt();
            worker.join(5_000);
        }
    }

    /** The test process cannot create root-owned files, so it plays the runner under its own uid. */
    private static final int RUNNER_UID = currentUid();

    private static int currentUid() {
        try {
            return (Integer) Files.getAttribute(Path.of(System.getProperty("java.io.tmpdir")), "unix:uid");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private SpoolMediaWorkerGateway gateway(Duration wait) {
        return new SpoolMediaWorkerGateway(wait, Duration.ofMillis(10), SpoolMediaWorkerGateway.DISK_CAP_BYTES, RUNNER_UID);
    }

    private void run(SpoolMediaWorkerGateway gateway, String kind) {
        gateway.run(job, ASSET, 3, kind, 5, SHA, kind.equals("image") ? 0 : 60_000);
    }

    /** Behaves like the real runner: acts only once {@code submitted} exists, then lets the test decide. */
    private void worker(Consumer<Path> behaviour) {
        worker = Thread.startVirtualThread(() -> {
            try {
                while (!Files.exists(job.resolve("submitted"))) Thread.sleep(5);
                sawCompleteJob.set(Files.isRegularFile(job.resolve("request.json"))
                        && Files.isDirectory(job.resolve("output")) && !Files.exists(job.resolve("submitted.tmp")));
                jobModeForWorker = mode(job);
                outputModeForWorker = mode(job.resolve("output"));
                Files.createFile(job.resolve("claimed"));
                behaviour.accept(job);
            } catch (InterruptedException | IOException stopped) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static String mode(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    private static void status(Path job, String json) {
        try {
            Files.writeString(job.resolve("status.json.tmp"), json, StandardCharsets.UTF_8);
            Files.move(job.resolve("status.json.tmp"), job.resolve("status.json"), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void successReturnsAfterTheWorkerPublishesExitZero() throws Exception {
        worker(job -> status(job, "{\"formatVersion\":1,\"exitCode\":0,\"code\":null}"));
        run(gateway(Duration.ofSeconds(10)), "audio");
        assertThat(sawCompleteJob).isTrue();
        assertThat(Files.readString(job.resolve("request.json"))).isEqualTo(
                "{\"formatVersion\":1,\"assetId\":\"" + ASSET + "\",\"generation\":3,\"kind\":\"audio\","
                        + "\"expectedByteLength\":5,\"expectedSha256\":\"" + SHA + "\",\"maxDurationMs\":60000}");
        assertThat(job.resolve("cancel")).doesNotExist();
        // nothing is ever opened to a group or to others: the runner is root and needs no access of its own
        assertThat(jobModeForWorker).isEqualTo("rwx------");
        assertThat(outputModeForWorker).isEqualTo("rwx------");
        assertThat(mode(job)).isEqualTo("rwx------");
    }

    @Test
    void imagesCarryNoDuration() throws IOException {
        worker(job -> status(job, "{\"formatVersion\":1,\"exitCode\":0,\"code\":null}"));
        run(gateway(Duration.ofSeconds(10)), "image");
        assertThat(Files.readString(job.resolve("request.json"))).endsWith("\"maxDurationMs\":null}");
    }

    @Test
    void exitTwoWithACodeIsATerminalRejection() {
        worker(job -> status(job, "{\"formatVersion\":1,\"exitCode\":2,\"code\":\"unsupported_audio\"}"));
        assertThatThrownBy(() -> run(gateway(Duration.ofSeconds(10)), "audio"))
                .isInstanceOfSatisfying(MediaProcessingRejectedException.class,
                        failure -> assertThat(failure.code()).isEqualTo("unsupported_audio"));
        assertThat(job.resolve("cancel")).doesNotExist();
    }

    @Test
    void everythingElseIsRetryable() {
        for (String status : new String[] {
                "{\"formatVersion\":1,\"exitCode\":3,\"code\":\"codec_timeout\"}",
                "{\"formatVersion\":1,\"exitCode\":2,\"code\":null}",
                "{\"formatVersion\":1,\"exitCode\":2,\"code\":\"Bad Code\"}",
                "{\"formatVersion\":1,\"exitCode\":0,\"code\":\"x\"}",
                "{\"formatVersion\":1,\"exitCode\":7,\"code\":null}",
                "{\"formatVersion\":2,\"exitCode\":0,\"code\":null}",
                "{\"formatVersion\":1,\"exitCode\":0,\"code\":null,\"extra\":1}",
                "{\"formatVersion\":1,\"exitCode\":\"0\",\"code\":null}",
                "{\"formatVersion\":1,\"exitCode\":0,\"exitCode\":0,\"code\":null}",
                "[]", "not json", ""}) {
            tearDownJob();
            worker(job -> status(job, status));
            assertThatThrownBy(() -> run(gateway(Duration.ofSeconds(10)), "audio"))
                    .as(status).isInstanceOf(MediaStorageUnavailableException.class);
        }
    }

    @Test
    void aVerdictNotWrittenByRootIsNeverAccepted() {
        // a perfectly valid success, but written by an unprivileged process (here: this one, as if a container had forged it)
        worker(job -> status(job, "{\"formatVersion\":1,\"exitCode\":0,\"code\":null}"));
        var strict = new SpoolMediaWorkerGateway(Duration.ofSeconds(10), Duration.ofMillis(10),
                SpoolMediaWorkerGateway.DISK_CAP_BYTES, RUNNER_UID + 1);
        assertThatThrownBy(() -> run(strict, "audio")).isInstanceOf(MediaStorageUnavailableException.class);
        tearDownJob();
        worker(job -> status(job, "{\"formatVersion\":1,\"exitCode\":2,\"code\":\"unsupported_audio\"}"));
        assertThatThrownBy(() -> run(strict, "audio")).isInstanceOf(MediaStorageUnavailableException.class);   // not even a rejection
    }

    @Test
    void theProductionGatewayDemandsARootOwnedVerdict() throws Exception {
        var field = SpoolMediaWorkerGateway.class.getDeclaredField("verdictUid");
        field.setAccessible(true);
        var settings = new MediaProcessingSettings(true, root.toString(), Duration.ofMinutes(30), Duration.ofHours(2), 0,
                Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(30), 5, 1,
                Duration.ofHours(1), Duration.ofMinutes(5));
        assertThat(field.getInt(new SpoolMediaWorkerGateway(settings))).isZero();
    }

    @Test
    void aNegativeVerdictOwnerIsAnInvalidPolicy() {
        assertThatThrownBy(() -> new MediaProcessingSettings(true, root.toString(), Duration.ofMinutes(30), Duration.ofHours(2), -1,
                Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(30), 5, 1,
                Duration.ofHours(1), Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOversizedStatusIsRetryable() {
        worker(job -> status(job, "{\"formatVersion\":1,\"exitCode\":0,\"code\":null}" + " ".repeat(5000)));
        assertThatThrownBy(() -> run(gateway(Duration.ofSeconds(10)), "audio"))
                .isInstanceOf(MediaStorageUnavailableException.class);
    }

    @Test
    void aWorkerThatNeverAnswersIsCancelledAndRetryable() throws IOException {
        assertThatThrownBy(() -> run(gateway(Duration.ofMillis(300)), "audio"))
                .isInstanceOf(MediaStorageUnavailableException.class);
        assertThat(job.resolve("cancel")).exists();
        assertThat(mode(job)).isEqualTo("rwx------");
    }

    @Test
    void timeWaitingInTheQueueDoesNotCountAgainstTheJob() throws InterruptedException {
        // claimed after 400 ms, answered 400 ms later: the 600 ms budget restarts at the claim
        worker = Thread.startVirtualThread(() -> {
            try {
                while (!Files.exists(job.resolve("submitted"))) Thread.sleep(5);
                Thread.sleep(400);
                Files.createDirectory(job.resolve("claimed"));
                Thread.sleep(400);
                status(job, "{\"formatVersion\":1,\"exitCode\":0,\"code\":null}");
            } catch (InterruptedException | IOException stopped) {
                Thread.currentThread().interrupt();
            }
        });
        run(gateway(Duration.ofMillis(600)), "audio");
        assertThat(job.resolve("cancel")).doesNotExist();
    }

    @Test
    void aJobOverTheDiskCapIsCancelled() throws IOException {
        Files.write(job.resolve("source"), new byte[4096]);
        var small = new SpoolMediaWorkerGateway(Duration.ofSeconds(10), Duration.ofMillis(10), 1024, RUNNER_UID);
        // the disk check runs every two seconds, so the job must outlive the first check
        assertThatThrownBy(() -> run(small, "audio")).isInstanceOf(MediaStorageUnavailableException.class);
        assertThat(job.resolve("cancel")).exists();
    }

    @Test
    void interruptionCancelsTheJobAndKeepsTheInterruptFlag() throws InterruptedException {
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        Thread caller = Thread.startVirtualThread(() -> {
            try {
                run(gateway(Duration.ofSeconds(30)), "audio");
            } catch (RuntimeException error) {
                failure.set(error);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        while (!Files.exists(job.resolve("submitted"))) Thread.sleep(5);
        caller.interrupt();
        caller.join(5_000);
        assertThat(failure.get()).isInstanceOf(MediaStorageUnavailableException.class);
        assertThat(interrupted).isTrue();
        assertThat(job.resolve("cancel")).exists();
    }

    @Test
    void aMissingJobDirectoryIsRetryable() throws IOException {
        Files.delete(job.resolve("source"));
        Files.delete(job);
        assertThatThrownBy(() -> run(gateway(Duration.ofSeconds(1)), "audio"))
                .isInstanceOf(MediaStorageUnavailableException.class);
    }

    @Test
    void aStatusThatIsALinkOrAFifoIsRetryableAndNeverFollowedOrAwaited() throws Exception {
        Path secret = Files.writeString(root.resolve("secret.json"), "{\"formatVersion\":1,\"exitCode\":0,\"code\":null}");
        worker(job -> {
            try {
                Files.createSymbolicLink(job.resolve("status.json"), secret);   // a perfectly valid verdict, reached through a link
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertThatThrownBy(() -> run(gateway(Duration.ofSeconds(10)), "audio")).isInstanceOf(MediaStorageUnavailableException.class);
        tearDownJob();
        MediaWorkerResultTest.mkfifo(root.resolve("fifo-source"));
        worker(job -> {
            try {
                Files.move(root.resolve("fifo-source"), job.resolve("status.json"));
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
        long started = System.nanoTime();
        assertThatThrownBy(() -> run(gateway(Duration.ofSeconds(10)), "audio")).isInstanceOf(MediaStorageUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));   // refused, not blocked on the pipe
    }

    @Test
    void existingEntriesAreNeverWrittenThroughWhenTheJobIsPrepared() throws IOException {
        Path victim = Files.writeString(root.resolve("victim"), "keep");
        Files.createSymbolicLink(job.resolve("request.json"), victim);
        assertThatThrownBy(() -> run(gateway(Duration.ofSeconds(1)), "audio")).isInstanceOf(MediaStorageUnavailableException.class);
        assertThat(Files.readString(victim)).isEqualTo("keep");
    }

    @Test
    void aCancelMarkerIsNeverWrittenThroughAnExistingLink() throws IOException {
        Path victim = Files.writeString(root.resolve("victim"), "keep");
        Files.createSymbolicLink(job.resolve("cancel"), victim);
        assertThatThrownBy(() -> run(gateway(Duration.ofMillis(200)), "audio")).isInstanceOf(MediaStorageUnavailableException.class);
        assertThat(Files.readString(victim)).isEqualTo("keep");
    }

    @Test
    void diskAccountingToleratesEntriesThatVanishOrCannotBeRead() throws IOException {
        Files.write(job.resolve("a"), new byte[100]);
        Path closed = Files.createDirectory(job.resolve("closed"));
        Files.write(closed.resolve("b"), new byte[50]);
        Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("---------"));
        try {
            assertThat(SpoolMediaWorkerGateway.diskBytes(job)).isGreaterThanOrEqualTo(105);   // "source" (5) + "a" (100)
        } finally {
            Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private void tearDownJob() {
        try {
            if (worker != null) {
                worker.interrupt();
                worker.join(5_000);
            }
            try (var paths = Files.walk(job)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
            job = Files.createDirectory(root.resolve("media-1"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Files.writeString(job.resolve("source"), "bytes");
        } catch (IOException | InterruptedException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
