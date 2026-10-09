package app.mnema.learning.media;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Yandex Object Storage does not document the default CRC32 trailer of AWS SDK v2.30+, so the client must send and
 * presign plain requests. The stub records what actually goes on the wire.
 */
class MediaObjectStoreWireTest {
    private record Seen(String method, String uri, Map<String, String> headers) { }

    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private MediaObjectStore store;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            var headers = new java.util.HashMap<String, String>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.getFirst()));
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().toString(), headers));
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("ETag", "\"d41d8cd98f00b204e9800998ecf8427e\"");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        var settings = new MediaUploadSettings(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "ru-central1", "synthetic-bucket", "synthetic-access", "synthetic-secret-key", true,
                67_108_864, 536_870_912, 4_294_967_296L, 8_589_934_592L, 3, 5_242_880, 5_242_880,
                Duration.ofMinutes(15), Duration.ofHours(24), Duration.ofMinutes(10), Duration.ofMinutes(15),
                Duration.ofMinutes(15));
        store = new MediaObjectStore(settings);
    }

    @AfterEach
    void stop() {
        store.close();
        server.stop(0);
    }

    @Test
    void serverPutsCarryNoChecksumTrailer(@TempDir Path dir) throws IOException {
        Path file = Files.write(dir.resolve("derived.bin"), "payload".getBytes(StandardCharsets.UTF_8));

        store.put("stock/a", "payload".getBytes(StandardCharsets.UTF_8), "image/png");
        store.putVerified("derived/b", file, 7, "a".repeat(64), "image/webp");

        assertThat(seen).hasSize(2).allSatisfy(request -> {
            assertThat(request.method()).isEqualTo("PUT");
            assertPlain(request.headers());
        });
        assertThat(seen.get(1).headers()).containsEntry("if-none-match", "*");
    }

    @Test
    void presignedUploadsCarryNoChecksumParameters() {
        var single = store.singleUrl("session/object", 1234);
        var part = store.partUrl("session/object", "upload-1", 1, 5_242_880);

        for (var signed : List.of(single, part)) {
            assertThat(signed.url().toLowerCase(Locale.ROOT)).doesNotContain("checksum", "trailer");
            assertThat(signed.headers().keySet()).allSatisfy(name ->
                    assertThat(name.toLowerCase(Locale.ROOT)).doesNotContain("checksum", "trailer"));
            assertThat(signed.headers()).containsEntry("content-length", signed == single ? "1234" : "5242880");
        }
    }

    @Test
    void userMetadataIsReadCaseInsensitively() {
        assertThat(MediaObjectStore.userMetadata(Map.of("Sha256", "abc"))).containsEntry("sha256", "abc");
        assertThat(MediaObjectStore.userMetadata(Map.of("sha256", "abc"))).containsEntry("sha256", "abc");
        assertThat(MediaObjectStore.userMetadata(Map.of())).doesNotContainKey("sha256");
    }

    private static void assertPlain(Map<String, String> headers) {
        // Plain HTTP makes the SDK sign the chunked payload itself (x-amz-content-sha256 STREAMING-*); that is
        // loopback-only. What must never appear is the default checksum trailer.
        assertThat(headers).doesNotContainKeys("x-amz-trailer", "x-amz-sdk-checksum-algorithm", "x-amz-checksum-crc32");
        assertThat(headers.get("x-amz-content-sha256")).doesNotContain("TRAILER");
    }
}
