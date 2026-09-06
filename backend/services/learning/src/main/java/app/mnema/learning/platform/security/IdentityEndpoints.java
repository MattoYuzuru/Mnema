package app.mnema.learning.platform.security;

import java.net.URI;
import java.util.Set;

/** Endpoints come exclusively from trusted deployment configuration, never token claims. */
record IdentityEndpoints(String issuer, URI base) {
    static IdentityEndpoints configured(String issuer, String transportBase, boolean loopbackHttp) {
        URI issuerUri = URI.create(issuer);
        requireEndpoint(issuerUri, false);
        URI base = transportBase.isBlank() ? issuerUri : URI.create(transportBase);
        requireEndpoint(base, loopbackHttp);
        return new IdentityEndpoints(issuer, base);
    }

    private static void requireEndpoint(URI value, boolean loopbackHttp) {
        boolean https = "https".equals(value.getScheme());
        boolean loopback = loopbackHttp && "http".equals(value.getScheme())
                && Set.of("127.0.0.1", "[::1]").contains(value.getHost());
        if ((!https && !loopback) || value.getHost() == null || value.getUserInfo() != null
                || value.getQuery() != null || value.getFragment() != null
                || !value.normalize().equals(value)) {
            throw new IllegalArgumentException("Identity requires a configured HTTPS endpoint");
        }
    }

    URI endpoint(String path) {
        String root = base.toASCIIString();
        return URI.create((root.endsWith("/") ? root.substring(0, root.length() - 1) : root) + path);
    }
}
