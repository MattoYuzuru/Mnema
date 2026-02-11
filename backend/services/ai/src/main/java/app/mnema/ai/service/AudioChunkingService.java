package app.mnema.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
public class AudioChunkingService {

    private static final Logger log = LoggerFactory.getLogger(AudioChunkingService.class);

    private final int maxSeconds;
    private final int chunkSeconds;
    private final int maxChunks;
    private final Duration ffmpegTimeout;
    private final String ffmpegPath;
    private final String ffprobePath;

    public AudioChunkingService(@Value("${app.ai.import.audio-max-seconds:300}") int maxSeconds,
                                @Value("${app.ai.import.audio-chunk-seconds:60}") int chunkSeconds,
                                @Value("${app.ai.import.audio-max-chunks:8}") int maxChunks,
                                @Value("${app.ai.import.audio-ffmpeg-timeout-seconds:30}") long timeoutSeconds,
                                @Value("${app.ai.import.ffmpeg-path:ffmpeg}") String ffmpegPath,
                                @Value("${app.ai.import.ffprobe-path:ffprobe}") String ffprobePath) {
        this.maxSeconds = Math.max(maxSeconds, 1);
        this.chunkSeconds = Math.max(chunkSeconds, 1);
        this.maxChunks = Math.max(maxChunks, 1);
        this.ffmpegTimeout = Duration.ofSeconds(Math.max(timeoutSeconds, 5));
        this.ffmpegPath = ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath.trim();
        this.ffprobePath = ffprobePath == null || ffprobePath.isBlank() ? "ffprobe" : ffprobePath.trim();
    }

    public AudioChunkingResult prepareChunks(byte[] bytes, String mimeType) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("Audio is empty");
        }
        String normalizedMimeType = normalizeMimeType(mimeType);
        Path input = null;
        Path outputDir = null;
        try {
            String extension = resolveExtension(normalizedMimeType);
            input = Files.createTempFile("mnema-audio-", extension);
            Files.write(input, bytes);

            int durationSeconds = (int) Math.ceil(probeDurationSeconds(input));
            if (durationSeconds <= 0) {
                throw new IllegalStateException("Audio duration is missing");
            }
            if (durationSeconds > maxSeconds) {
                throw new IllegalStateException("Audio is too long. Please upload a shorter file.");
            }
            if (durationSeconds <= chunkSeconds) {
                List<AudioChunk> chunks = List.of(new AudioChunk(bytes, normalizedMimeType, false));
                return new AudioChunkingResult(chunks, durationSeconds, chunks.size());
            }

            outputDir = Files.createTempDirectory("mnema-audio-chunks-");
            String outputPattern = outputDir.resolve("chunk-%03d.wav").toString();
            runCommand(List.of(
                    ffmpegPath,
                    "-hide_banner",
                    "-loglevel", "error",
                    "-i", input.toString(),
                    "-ac", "1",
                    "-ar", "16000",
                    "-f", "segment",
                    "-segment_time", Integer.toString(chunkSeconds),
                    "-reset_timestamps", "1",
                    outputPattern
            ));
            List<Path> files;
            try (var stream = Files.list(outputDir)) {
                files = stream
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wav"))
                        .sorted(Comparator.naturalOrder())
                        .toList();
            }
            if (files.isEmpty()) {
                throw new IllegalStateException("Audio chunking failed");
            }
            if (files.size() > maxChunks) {
                throw new IllegalStateException("Audio is too long. Please upload a shorter file.");
            }
            List<AudioChunk> chunks = new ArrayList<>();
            for (Path file : files) {
                chunks.add(new AudioChunk(Files.readAllBytes(file), "audio/wav", true));
            }
            return new AudioChunkingResult(chunks, durationSeconds, chunks.size());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to process audio", ex);
        } finally {
            cleanupPath(input);
            cleanupDirectory(outputDir);
        }
    }

    private double probeDurationSeconds(Path input) throws IOException {
        String output = runCommand(List.of(
                ffprobePath,
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                input.toString()
        ));
        try {
            return Double.parseDouble(output.trim());
        } catch (NumberFormatException ex) {
            log.warn("Failed to parse ffprobe duration output: {}", output.trim());
            return 0;
        }
    }

    private String runCommand(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean finished;
        try {
            finished = process.waitFor(ffmpegTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("Audio processing interrupted", ex);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Audio processing timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Audio processing failed: " + summarizeOutput(output));
        }
        return output;
    }

    private String resolveExtension(String mimeType) {
        String normalized = normalizeMimeType(mimeType);
        if (normalized == null) {
            return ".bin";
        }
        return switch (normalized) {
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/mp4", "audio/x-m4a" -> ".m4a";
            case "audio/ogg" -> ".ogg";
            case "audio/wav", "audio/x-wav" -> ".wav";
            case "audio/webm" -> ".webm";
            case "audio/flac" -> ".flac";
            default -> ".bin";
        };
    }

    private String normalizeMimeType(String mimeType) {
        if (mimeType == null) {
            return null;
        }
        String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf(';');
        if (separator >= 0) {
            normalized = normalized.substring(0, separator).trim();
        }
        return normalized;
    }

    private String summarizeOutput(String output) {
        if (output == null) {
            return "";
        }
        String trimmed = output.trim();
        if (trimmed.length() <= 300) {
            return trimmed;
        }
        return trimmed.substring(0, 300) + "...";
    }

    private void cleanupPath(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private void cleanupDirectory(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            try (var stream = Files.list(dir)) {
                stream.forEach(this::cleanupPath);
            }
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
        }
    }

    public record AudioChunk(byte[] bytes, String mimeType, boolean reencoded) {
    }

    public record AudioChunkingResult(List<AudioChunk> chunks, int durationSeconds, int chunkCount) {
    }
}
