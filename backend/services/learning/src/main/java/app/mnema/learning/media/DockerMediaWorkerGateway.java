package app.mnema.learning.media;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Process boundary for the local GPL FFmpeg image; no shell, network, or inherited input. */
@Component
final class DockerMediaWorkerGateway implements MediaWorkerGateway {
    private static final Pattern CODE = Pattern.compile("\\\"code\\\"\\s*:\\s*\\\"([a-z][a-z0-9_]{0,63})\\\"");
    private final MediaProcessingSettings settings;

    DockerMediaWorkerGateway(MediaProcessingSettings settings) { this.settings = settings; }

    @Override
    public void run(Path job, UUID assetId, long generation, String kind,
                    long length, String sha256, long maxDurationMs) {
        Path output = job.resolve("output");
        Path cid = job.resolve("container.id");
        Process process = null;
        try {
            Files.createDirectory(output);
            // A private parent is inaccessible to other host users; UID 10001 writes this bind mount.
            Files.setPosixFilePermissions(output, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
            String duration = kind.equals("image") ? "null" : Long.toString(maxDurationMs);
            String manifest = "{\"formatVersion\":1,\"assetId\":\"" + assetId
                    + "\",\"generation\":" + generation + ",\"kind\":\"" + kind
                    + "\",\"expectedByteLength\":" + length + ",\"expectedSha256\":\""
                    + sha256 + "\",\"maxDurationMs\":" + duration + "}";
            Files.writeString(job.resolve("request.json"), manifest, StandardCharsets.UTF_8);
            var command = new ArrayList<>(List.of(settings.dockerBinary, "run", "--rm", "--network", "none",
                    "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                    "--pids-limit", "64", "--memory", "3g", "--memory-swap", "3g", "--cpus", "2",
                    "--tmpfs", "/tmp:rw,size=64m,mode=1777", "--cidfile", cid.toString(),
                    "--mount", bind(job.resolve("source"), "/work/source", true),
                    "--mount", bind(job.resolve("request.json"), "/work/request.json", true),
                    "--mount", bind(output, "/work/output", false), settings.image,
                    "--manifest", "/work/request.json", "--source", "/work/source", "--output", "/work/output"));
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process running = process;
            var log = new java.io.ByteArrayOutputStream();
            var overflow = new AtomicBoolean();
            Thread drain = Thread.startVirtualThread(() -> {
                try (var input = running.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (log.size() + count <= 65_536) log.write(buffer, 0, count);
                        else { overflow.set(true); running.destroyForcibly(); break; }
                    }
                } catch (IOException ignored) { overflow.set(true); }
            });
            long deadline = System.nanoTime() + settings.workerTimeout.toNanos();
            boolean finished = false;
            boolean diskExceeded = false;
            while (!finished && System.nanoTime() < deadline) {
                finished = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                if (!finished && diskBytes(job) > 7L * 1024 * 1024 * 1024) {
                    diskExceeded = true;
                    break;
                }
            }
            if (!finished) process.destroyForcibly();
            drain.join(Duration.ofSeconds(10));
            if (!finished || diskExceeded || overflow.get() || drain.isAlive())
                throw new MediaStorageUnavailableException();
            if (process.exitValue() == 0) return;
            String safeCode = code(log.toString(StandardCharsets.UTF_8));
            if (process.exitValue() == 2 && safeCode != null) throw new MediaProcessingRejectedException(safeCode);
            throw new MediaStorageUnavailableException();
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new MediaStorageUnavailableException();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            removeContainer(cid);
        }
    }

    private static String bind(Path host, String target, boolean readOnly) {
        return "type=bind,src=" + host.toAbsolutePath() + ",dst=" + target + (readOnly ? ",readonly" : "");
    }

    private static String code(String log) {
        var match = CODE.matcher(log);
        return match.find() ? match.group(1) : null;
    }

    private static long diskBytes(Path job) throws IOException {
        try (var paths = Files.walk(job)) {
            long total = 0;
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                total = Math.addExact(total, Files.size(path));
            }
            return total;
        }
    }

    private void removeContainer(Path cid) {
        try {
            if (!Files.isRegularFile(cid)) return;
            String id = Files.readString(cid).strip();
            if (!id.matches("[0-9a-f]{64}")) return;
            Process cleanup = new ProcessBuilder(settings.dockerBinary, "rm", "-f", id)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!cleanup.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) cleanup.destroyForcibly();
        } catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }
}
