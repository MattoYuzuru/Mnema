package app.mnema.learning.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;

/**
 * The single place that knows about the egress proxy: builds the transport of each {@link AiProperties.EgressMode}. Adapters of every
 * provider type ask {@link #http(AiProperties.EgressMode)} and never see the proxy address or its credentials.
 *
 * <p>{@code direct} is the plain JDK client. {@code proxy} sends every request through the configured HTTP forward proxy; for https the
 * JDK opens a CONNECT tunnel, so TLS to the provider stays end to end. Credentials are answered by an {@link Authenticator} that serves
 * only {@code RequestorType.PROXY} for that exact host and port, never a provider (SERVER) challenge and never another host.
 *
 * <p><b>Basic over CONNECT needs a JVM flag.</b> The JDK client ignores Basic credentials for a CONNECT tunnel unless
 * {@code jdk.http.auth.tunneling.disabledSchemes} no longer lists {@code Basic}; the value is read once, when the HTTP client classes
 * first load, so it must be a JVM option ({@code -Djdk.http.auth.tunneling.disabledSchemes=}), not something set from configuration
 * (java.net.http module documentation, "System properties":
 * https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/module-summary.html; default in the JDK's {@code conf/net.properties}).
 * A header added by the caller is filtered by the same property, so a preemptive {@code Proxy-Authorization} does not help. The flag
 * lives in the Learning runtime image entrypoint and in the Gradle test task; {@link #warnIfBasicTunnelingMayBeDisabled} reports a
 * missing flag at startup.
 */
final class EgressClients implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(EgressClients.class);
    private static final String TUNNELING_PROPERTY = "jdk.http.auth.tunneling.disabledSchemes";

    private final ChatHttp direct;
    private final ChatHttp proxy;
    private final boolean ownsDirect;

    private EgressClients(ChatHttp direct, ChatHttp proxy, boolean ownsDirect) {
        this.direct = direct;
        this.proxy = proxy;
        this.ownsDirect = ownsDirect;
    }

    /** Builds the direct transport, and the proxied one only when {@code properties.egress()} is active. */
    static EgressClients create(AiProperties properties) { return create(properties, null); }

    /** {@code tls} replaces the default trust of the proxied client; tests only (a self-signed loopback target). */
    static EgressClients create(AiProperties properties, SSLContext tls) {
        AiProperties.Egress egress = properties.egress();
        ChatHttp proxied = null;
        if (egress.active()) {
            warnIfBasicTunnelingMayBeDisabled(egress);
            proxied = new ChatHttp(properties.transport(), proxyClient(properties.transport(), egress, tls), AiProperties.EgressMode.PROXY);
        }
        return new EgressClients(new ChatHttp(properties.transport()), proxied, true);
    }

    /** Wraps an existing direct transport without a proxy; the caller keeps ownership of it. */
    static EgressClients direct(ChatHttp http) { return new EgressClients(http, null, false); }

    /** The transport for {@code mode}, or null when the proxied path is not configured or switched off. */
    ChatHttp http(AiProperties.EgressMode mode) { return mode == AiProperties.EgressMode.DIRECT ? direct : proxy; }

    private static HttpClient proxyClient(AiProperties.Transport transport, AiProperties.Egress egress, SSLContext tls) {
        URI uri = URI.create(egress.proxyUrl());
        var builder = HttpClient.newBuilder().connectTimeout(transport.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).proxy(new ResolvingProxy(uri.getHost(), uri.getPort()));
        if (egress.hasCredentials()) builder.authenticator(new ProxyOnly(uri.getHost(), uri.getPort(), egress.user(), egress.password()));
        if (tls != null) builder.sslContext(tls);
        return builder.build();
    }

    private static void warnIfBasicTunnelingMayBeDisabled(AiProperties.Egress egress) {
        String disabled = System.getProperty(TUNNELING_PROPERTY);
        if (egress.hasCredentials() && (disabled == null || disabled.contains("Basic"))) {
            LOG.warn("ai_egress state=basic_tunneling_may_be_disabled hint=start the JVM with -D{}=", TUNNELING_PROPERTY);
        }
    }

    @Override
    public void close() {
        if (proxy != null) proxy.close();
        if (ownsDirect) direct.close();
    }

    /** Resolves the proxy host on every connection, so a changed DNS record or a late resolver does not pin a dead address. */
    private static final class ResolvingProxy extends ProxySelector {
        private final String host;
        private final int port;

        ResolvingProxy(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public List<Proxy> select(URI uri) { return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port))); }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            // nothing to learn: the next select() resolves again
        }
    }

    /** Answers the proxy challenge of the configured host and port only. */
    private static final class ProxyOnly extends Authenticator {
        private final String host;
        private final int port;
        private final String user;
        private final String password;

        ProxyOnly(String host, int port, String user, String password) {
            this.host = host;
            this.port = port;
            this.user = user;
            this.password = password;
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            if (getRequestorType() != RequestorType.PROXY || getRequestingPort() != port || getRequestingHost() == null
                    || !getRequestingHost().equalsIgnoreCase(host)) {
                return null;
            }
            return new PasswordAuthentication(user, password.toCharArray());
        }
    }
}
