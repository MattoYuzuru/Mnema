package app.mnema.learning.platform.management;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.net.InetAddress;

/**
 * Keeps the operations endpoints off the public API. Two rules, both enforced at start (the messages name the settings, never a value):
 * <ul>
 *   <li>any actuator endpoint beyond {@code health} and {@code info} (for example {@code metrics}) may be exposed only when
 *       {@code management.server.port} names a port of its own; otherwise it would share the public listener and the bearer-token chain of the API, where
 *       any signed-in account could read it;</li>
 *   <li>a management port needs {@code management.server.address}, the private address it listens on (loopback or an address of a private network), so that
 *       a port set without an address cannot end up on every interface.</li>
 * </ul>
 */
@Component
final class ManagementExposureGuard {
    private static final Set<String> PUBLIC_SAFE = Set.of("health", "info");

    ManagementExposureGuard(Environment environment) {
        String exposure = environment.getProperty("management.endpoints.web.exposure.include", "");
        boolean wide = Arrays.stream(exposure.split(",")).map(value -> value.strip().toLowerCase(Locale.ROOT)).filter(value -> !value.isEmpty())
                .anyMatch(value -> !PUBLIC_SAFE.contains(value));
        String management = environment.getProperty("management.server.port", "").strip();
        String server = environment.getProperty("server.port", "").strip();
        boolean separate = !management.isEmpty() && port(management) != port(server.isEmpty() ? "8080" : server);
        if (wide && !separate) {
            throw new IllegalStateException("management.endpoints.web.exposure.include exposes more than health and info: set management.server.port "
                    + "(MANAGEMENT_SERVER_PORT) to a private port of its own or narrow the exposure");
        }
        if (separate && !privateAddress(environment.getProperty("management.server.address", ""))) {
            throw new IllegalStateException("management.server.port needs management.server.address (MANAGEMENT_SERVER_ADDRESS), a loopback or private IP literal");
        }
    }

    private static int port(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("server and management ports must be integers");
        }
    }

    private static boolean privateAddress(String value) {
        try {
            InetAddress address = InetAddress.ofLiteral(value.strip());
            byte[] bytes = address.getAddress();
            return address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
