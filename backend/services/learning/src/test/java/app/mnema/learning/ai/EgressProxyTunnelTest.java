package app.mnema.learning.ai;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The egress path end to end on loopback, no network: a CONNECT forward proxy fixture that demands Basic credentials, and a TLS target
 * with a throw-away certificate. Proves that the adapter reaches its provider through CONNECT with the configured credentials, that TLS
 * stays end to end (the proxy sees only encrypted bytes), that wrong credentials end in a fixed failure, that a provider challenge is
 * never answered with the proxy credentials, and that a proxied provider without an active proxy has no adapter.
 *
 * <p>Needs {@code -Djdk.http.auth.tunneling.disabledSchemes=} (set by the Gradle test task): without it the JDK silently drops the
 * Basic credentials of a CONNECT and every case here would see a 407.
 */
class EgressProxyTunnelTest {
    private static final String USER = "mnema-egress";
    private static final String PASSWORD = "proxy-PASSWORD-canary-77";
    private static final String KEY = "sk-egress-LIVE-KEY";

    @TempDir Path temp;
    private ConnectProxy proxy;
    private HttpsServer target;
    private SSLContext clientTls;
    private final List<String> targetAuthorization = new CopyOnWriteArrayList<>();
    private volatile int targetStatus = 200;
    private String baseUrl;
    private String hostPort;

    @BeforeEach
    void start() throws Exception {
        Path store = temp.resolve("target.p12");
        run(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair", "-alias", "target", "-keyalg", "RSA",
                "-keysize", "2048", "-dname", "CN=localhost", "-ext", "san=dns:localhost,ip:127.0.0.1", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "changeit", "-keypass", "changeit");
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            keys.load(in, "changeit".toCharArray());
        }
        var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, "changeit".toCharArray());
        var serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keyManagers.getKeyManagers(), null, null);
        KeyStore trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("target", keys.getCertificate("target"));
        var trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trusted);
        clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, trustManagers.getTrustManagers(), null);

        InetAddress loopback = InetAddress.getByName("localhost");
        target = HttpsServer.create(new InetSocketAddress(loopback, 0), 0);
        target.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        target.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            targetAuthorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = targetStatus == 200 ? FakeProvider.fixture("chat-ok.json").getBytes(StandardCharsets.UTF_8) : new byte[0];
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (targetStatus == 401) exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"provider\"");
            exchange.sendResponseHeaders(targetStatus, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        target.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        target.start();
        hostPort = "localhost:" + target.getAddress().getPort();
        baseUrl = "https://" + hostPort;
        proxy = new ConnectProxy(USER, PASSWORD);
    }

    @AfterEach
    void stop() {
        target.stop(0);
        proxy.close();
    }

    private static void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) throw new IllegalStateException("keytool failed");
    }

    private AiProperties properties(AiProperties.EgressMode mode, AiProperties.Egress egress) {
        var base = AiTestSupport.properties("", AiTestSupport.routes(List.of("deepseek:deepseek-flash"), List.of(), List.of()),
                Map.of("deepseek", new AiProperties.Provider(true, baseUrl, KEY, "", "", "", mode)));
        return new AiProperties(base.provider(), base.routes(), base.providers(), base.models(), base.transport(), base.retry(),
                base.breaker(), base.permits(), base.budget(), base.userKey(), base.prompt(), egress);
    }

    private AiProperties.Egress egress(String password) {
        return new AiProperties.Egress("http://127.0.0.1:" + proxy.port(), USER, password, true);
    }

    private AiResult<TextResponse> call(AiProperties properties) {
        try (var clients = EgressClients.create(properties, clientTls)) {
            TextAdapter adapter = AiConfiguration.adapters(properties, clients, Clock.systemUTC()).get("deepseek");
            assertThat(adapter).isNotNull();
            assertThat(adapter.egress()).isEqualTo(AiProperties.EgressMode.PROXY);
            return adapter.attempt("deepseek-flash", AiTestSupport.request(), Duration.ofSeconds(10));
        }
    }

    @Test
    void aProxiedProviderCallGoesThroughConnectWithTheConfiguredCredentials() {
        AiResult<TextResponse> result = call(properties(AiProperties.EgressMode.PROXY, egress(PASSWORD)));

        assertThat(result).isInstanceOf(AiResult.Ok.class);
        assertThat(((AiResult.Ok<TextResponse>) result).value().text()).isNotBlank();
        String expected = "Basic " + Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        assertThat(proxy.connects()).as("CONNECT to the provider host and port, authorised").anySatisfy(connect -> {
            assertThat(connect.target()).isEqualTo(hostPort);
            assertThat(connect.proxyAuthorization()).isEqualTo(expected);
            assertThat(connect.granted()).isTrue();
        });
        assertThat(proxy.plainRequests()).as("no plain HTTP request ever crosses the proxy").isZero();
        assertThat(targetAuthorization).as("the provider got its own bearer").containsExactly("Bearer " + KEY);
        String piped = proxy.pipedText();
        assertThat(piped).as("TLS is end to end: the proxy sees ciphertext only").isNotEmpty()
                .doesNotContain(KEY).doesNotContain("СИСТЕМА").doesNotContain("chat.completion").doesNotContain("POST ");
    }

    @Test
    void wrongProxyCredentialsEndInAFixedFailureWithoutASecret() {
        AiResult<TextResponse> result = call(properties(AiProperties.EgressMode.PROXY, egress("not-the-password")));

        assertThat(result).isInstanceOf(AiResult.Failed.class);
        AiFailure failure = ((AiResult.Failed<TextResponse>) result).failure();
        assertThat(failure).isEqualTo(new AiFailure.Transient("io_error"));
        assertThat(failure.toString()).doesNotContain(PASSWORD).doesNotContain("not-the-password").doesNotContain(KEY);
        assertThat(proxy.connects()).isNotEmpty().allSatisfy(connect -> assertThat(connect.granted()).isFalse());
        assertThat(targetAuthorization).as("the provider was never reached").isEmpty();
    }

    @Test
    void aProviderChallengeIsNeverAnsweredWithTheProxyCredentials() {
        targetStatus = 401;

        AiResult<TextResponse> result = call(properties(AiProperties.EgressMode.PROXY, egress(PASSWORD)));

        assertThat(result).isInstanceOf(AiResult.Failed.class);
        assertThat(((AiResult.Failed<TextResponse>) result).failure()).isInstanceOf(AiFailure.NotConfigured.class);
        assertThat(targetAuthorization).as("one request, no retry with Basic credentials").containsExactly("Bearer " + KEY);
    }

    @Test
    void aProxiedProviderWithoutAnActiveProxyHasNoAdapterAndItsCapabilityIsNotConfigured() {
        var withoutProxy = properties(AiProperties.EgressMode.PROXY, new AiProperties.Egress("", "", "", true));
        var killed = properties(AiProperties.EgressMode.PROXY, new AiProperties.Egress(egress(PASSWORD).proxyUrl(), USER, PASSWORD, false));
        var direct = properties(AiProperties.EgressMode.DIRECT, new AiProperties.Egress("", "", "", true));
        for (AiProperties properties : List.of(withoutProxy, killed)) {
            try (var clients = EgressClients.create(properties)) {
                var adapters = AiConfiguration.adapters(properties, clients, Clock.systemUTC());
                assertThat(adapters).isEmpty();
                var availability = new DefaultAiAvailability(new AiRouting(properties, adapters),
                        new BreakerRegistry(Clock.systemUTC(), properties.breaker()),
                        new AiBudget(properties.budget(), (capability, since) -> 0, Clock.systemUTC()), new UserKeys(properties.userKey()), false);
                assertThat(availability.text()).isEqualTo(AiAvailability.State.NOT_CONFIGURED);
                assertThat(clients.http(AiProperties.EgressMode.PROXY)).isNull();
            }
        }
        try (var clients = EgressClients.create(direct)) {
            assertThat(AiConfiguration.adapters(direct, clients, Clock.systemUTC())).containsKey("deepseek");
            assertThat(clients.http(AiProperties.EgressMode.DIRECT).egress()).isEqualTo(AiProperties.EgressMode.DIRECT);
        }
        assertThat(proxy.connects()).isEmpty();
    }

    // ------------------------------------------------------------------ fixture

    /** One CONNECT the fixture saw. */
    record Connect(String target, String proxyAuthorization, boolean granted) { }

    /**
     * A minimal forward proxy on loopback: answers 407 until the request carries the expected Basic credentials, then opens the tunnel to
     * the requested host and port and copies bytes both ways, keeping a copy of what crossed it.
     */
    private static final class ConnectProxy implements AutoCloseable {
        private final ServerSocket server;
        private final String expected;
        private final List<Connect> connects = new CopyOnWriteArrayList<>();
        private final ByteArrayOutputStream piped = new ByteArrayOutputStream();
        private volatile int plain;

        ConnectProxy(String user, String password) throws IOException {
            this.server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            this.expected = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            Thread.ofVirtual().start(this::accept);
        }

        int port() { return server.getLocalPort(); }

        List<Connect> connects() { return List.copyOf(connects); }

        int plainRequests() { return plain; }

        String pipedText() {
            synchronized (piped) {
                return piped.toString(StandardCharsets.ISO_8859_1);
            }
        }

        private void accept() {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    Thread.ofVirtual().start(() -> serve(client));
                } catch (IOException closed) {
                    return;
                }
            }
        }

        private void serve(Socket client) {
            try (client) {
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();
                while (true) {
                    String head = readHead(in);
                    if (head == null) return;
                    String[] lines = head.split("\r\n");
                    String[] request = lines[0].split(" ");
                    if (!"CONNECT".equals(request[0])) {
                        plain++;
                        out.write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        return;
                    }
                    String auth = null;
                    for (int index = 1; index < lines.length; index++) {
                        if (lines[index].regionMatches(true, 0, "Proxy-Authorization:", 0, 20)) auth = lines[index].substring(20).strip();
                    }
                    boolean granted = expected.equals(auth);
                    connects.add(new Connect(request[1], auth, granted));
                    if (!granted) {
                        out.write(("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"mnema\"\r\n"
                                + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        continue;
                    }
                    int colon = request[1].lastIndexOf(':');
                    try (Socket upstream = new Socket(request[1].substring(0, colon), Integer.parseInt(request[1].substring(colon + 1)))) {
                        out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        Thread back = Thread.ofVirtual().start(() -> copy(upstream, client));
                        copy(client, upstream);
                        back.join(2_000);
                    }
                    return;
                }
            } catch (IOException | InterruptedException | RuntimeException ignored) {
                // a closed test connection
            }
        }

        private void copy(Socket from, Socket to) {
            byte[] chunk = new byte[8_192];
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                for (int count; (count = in.read(chunk)) != -1; ) {
                    synchronized (piped) {
                        piped.write(chunk, 0, count);
                    }
                    out.write(chunk, 0, count);
                    out.flush();
                }
                to.shutdownOutput();
            } catch (IOException closed) {
                // either side went away
            }
        }

        private static String readHead(InputStream in) throws IOException {
            var head = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4) {
                int value = in.read();
                if (value < 0) return null;
                head.write(value);
                matched = value == "\r\n\r\n".charAt(matched) ? matched + 1 : value == '\r' ? 1 : 0;
            }
            return head.toString(StandardCharsets.ISO_8859_1);
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (IOException ignored) {
                // test teardown
            }
        }
    }
}
