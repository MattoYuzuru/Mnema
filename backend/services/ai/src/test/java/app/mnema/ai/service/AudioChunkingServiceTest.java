package app.mnema.ai.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioChunkingServiceTest {

    @Test
    void helperMethodsNormalizeExtensionsAndSummaries() throws Exception {
        AudioChunkingService service = new AudioChunkingService(300, 60, 8, 30, "ffmpeg", "ffprobe");

        assertThat(invoke(service, "normalizeMimeType", new Class[]{String.class}, " audio/mpeg; charset=utf-8 ")).isEqualTo("audio/mpeg");
        assertThat(invoke(service, "resolveExtension", new Class[]{String.class}, "audio/x-m4a")).isEqualTo(".m4a");
        assertThat(invoke(service, "resolveExtension", new Class[]{String.class}, "application/octet-stream")).isEqualTo(".bin");
        assertThat(invoke(service, "summarizeOutput", new Class[]{String.class}, "x".repeat(320))).isEqualTo("x".repeat(300) + "...");
        assertThatThrownBy(() -> service.prepareChunks(new byte[0], "audio/mpeg"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Audio is empty");
    }

    @Test
    void runCommandAndCleanupHandleSuccessFailureAndFilesystem() throws Exception {
        AudioChunkingService service = new AudioChunkingService(300, 60, 8, 30, "ffmpeg", "ffprobe");

        assertThat(invoke(service, "runCommand", new Class[]{List.class}, List.of("bash", "-lc", "printf ok"))).isEqualTo("ok");
        assertThatThrownBy(() -> invoke(service, "runCommand", new Class[]{List.class}, List.of("bash", "-lc", "echo failure && exit 2")))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessage("Audio processing failed: failure");

        Path file = Files.createTempFile("mnema-audio-test", ".tmp");
        Path dir = Files.createTempDirectory("mnema-audio-dir");
        Files.writeString(dir.resolve("chunk.wav"), "wav");
        invoke(service, "cleanupPath", new Class[]{Path.class}, file);
        invoke(service, "cleanupDirectory", new Class[]{Path.class}, dir);

        assertThat(Files.exists(file)).isFalse();
        assertThat(Files.exists(dir)).isFalse();
    }

    private Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}
