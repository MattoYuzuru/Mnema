package app.mnema.identityaccount.avatar;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Yandex Object Storage does not document the default CRC32 trailer of AWS SDK v2.30+; the PUT must be plain. */
class AvatarStorageWireTest {
    @Test
    void avatarPutCarriesNoChecksumTrailerAndReturnsTheVersionId() throws IOException {
        var seen = new CopyOnWriteArrayList<Map<String, String>>();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            var headers = new java.util.HashMap<String, String>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.getFirst()));
            seen.add(headers);
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("x-amz-version-id", "version-1");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try (var storage = new AvatarStorage(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "ru-central1", "synthetic-bucket", "synthetic-access", "synthetic-secret", true, false)) {
            var image = new AvatarImage(new byte[]{1, 2, 3}, "image/webp", 256, 256);

            assertThat(storage.put("account-avatar/a/b", UUID.randomUUID(), UUID.randomUUID(), image))
                    .isEqualTo("version-1");

            assertThat(seen).hasSize(1);
            var headers = seen.getFirst();
            // Plain HTTP makes the SDK sign the chunked payload itself (x-amz-content-sha256 STREAMING-*); that is
            // loopback-only. What must never appear is the default checksum trailer.
            assertThat(headers).doesNotContainKeys("x-amz-trailer", "x-amz-sdk-checksum-algorithm",
                    "x-amz-checksum-crc32");
            assertThat(headers.get("x-amz-content-sha256")).doesNotContain("TRAILER");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void userMetadataIsReadCaseInsensitively() {
        assertThat(AvatarStorage.userMetadata(Map.of("Account-Id", "a", "ASSET-ID", "b")))
                .containsEntry("account-id", "a").containsEntry("asset-id", "b");
        assertThat(AvatarStorage.userMetadata(Map.of("account-id", "a"))).containsEntry("account-id", "a");
    }

    /** Yandex returns user metadata as {@code x-amz-meta-Account-Id}; a raw socket keeps that header case on the wire. */
    @Test
    void exactEraseAcceptsProviderCapitalisedOwnershipMetadata() throws Exception {
        UUID account = UUID.randomUUID(), asset = UUID.randomUUID();
        String key = "account-avatar/" + account + "/" + asset;
        var deleted = new java.util.concurrent.atomic.AtomicBoolean();
        try (var listener = new java.net.ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                while (!listener.isClosed()) {
                    try (var socket = listener.accept()) {
                        var in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                                java.nio.charset.StandardCharsets.US_ASCII));
                        String[] request = in.readLine().split(" ");
                        for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) { }
                        String target = request[1];
                        boolean versioned = target.contains("versionId=");
                        String status, headers = "", body = "";
                        if ("DELETE".equals(request[0])) {
                            deleted.set(true);
                            status = "204 No Content";
                        } else if ("HEAD".equals(request[0]) && versioned && !deleted.get()) {
                            status = "200 OK";
                            headers = "x-amz-meta-Account-Id: " + account + "\r\nx-amz-meta-Asset-Id: " + asset + "\r\n";
                        } else if ("HEAD".equals(request[0])) {
                            status = "404 Not Found";
                        } else {
                            status = "200 OK";
                            headers = "Content-Type: application/xml\r\n";
                            body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                                    + "<Name>synthetic-bucket</Name><IsTruncated>false</IsTruncated>" + (deleted.get() ? "" :
                                    "<Version><Key>" + key + "</Key><VersionId>v1</VersionId><IsLatest>true</IsLatest>"
                                    + "<Size>3</Size></Version>") + "</ListVersionsResult>";
                        }
                        byte[] payload = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        String length = "HEAD".equals(request[0]) ? "0" : String.valueOf(payload.length);
                        socket.getOutputStream().write(("HTTP/1.1 " + status + "\r\n" + headers + "Content-Length: "
                                + length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(payload);
                    } catch (IOException ignored) { /* Listener closed. */ }
                }
            });
            try (var storage = new AvatarStorage(URI.create("http://127.0.0.1:" + listener.getLocalPort()),
                    "ru-central1", "synthetic-bucket", "synthetic-access", "synthetic-secret", true, false)) {
                storage.deleteOwned(new OwnedAvatarEraser.Manifest(account, asset, key, null, new byte[32]));
            }
        }

        assertThat(deleted).isTrue();
    }
}
