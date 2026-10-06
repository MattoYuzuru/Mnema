package app.mnema.learning.platform.management;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Anything beyond health and info is refused unless the management server has a port of its own. */
class ManagementExposureGuardTest {
    private static MockEnvironment env(String exposure, String port) {
        MockEnvironment environment = new MockEnvironment().withProperty("server.port", "8080").withProperty("management.server.address", "127.0.0.1");
        if (exposure != null) environment.withProperty("management.endpoints.web.exposure.include", exposure);
        if (port != null) environment.withProperty("management.server.port", port);
        return environment;
    }

    @Test
    void healthAndInfoMayShareThePublicPortAndMetricsNeedAPortOfTheirOwn() {
        assertThatCode(() -> new ManagementExposureGuard(env(null, null))).doesNotThrowAnyException();
        assertThatCode(() -> new ManagementExposureGuard(env("health,info", ""))).doesNotThrowAnyException();
        assertThatCode(() -> new ManagementExposureGuard(env(" Health , INFO ", null))).doesNotThrowAnyException();
        assertThatCode(() -> new ManagementExposureGuard(env("health,info,metrics", "8081"))).doesNotThrowAnyException();

        assertThatThrownBy(() -> new ManagementExposureGuard(env("health,info,metrics", null))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ManagementExposureGuard(env("health,info,metrics", " "))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ManagementExposureGuard(env("health,info,metrics", "8080"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ManagementExposureGuard(env("health,info,metrics", "08080"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ManagementExposureGuard(env("*", null))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ManagementExposureGuard(env("prometheus", null)).toString()).hasMessageNotContaining("8080");
    }

    @Test
    void aManagementPortNeedsAPrivateAddress() {
        MockEnvironment noAddress = new MockEnvironment().withProperty("server.port", "8080").withProperty("management.server.port", "8081");
        assertThatThrownBy(() -> new ManagementExposureGuard(noAddress)).isInstanceOf(IllegalStateException.class);
        noAddress.withProperty("management.server.address", " ");
        assertThatThrownBy(() -> new ManagementExposureGuard(noAddress)).isInstanceOf(IllegalStateException.class);
        noAddress.withProperty("management.server.address", "127.0.0.1");
        assertThatCode(() -> new ManagementExposureGuard(noAddress)).doesNotThrowAnyException();
        for (String address : new String[] {"::1", "10.0.0.2", "192.168.1.3", "fd00::2"}) {
            noAddress.withProperty("management.server.address", address);
            assertThatCode(() -> new ManagementExposureGuard(noAddress)).doesNotThrowAnyException();
        }
        for (String address : new String[] {"0.0.0.0", "::", "8.8.8.8", "localhost", "224.0.0.1"}) {
            noAddress.withProperty("management.server.address", address);
            assertThatThrownBy(() -> new ManagementExposureGuard(noAddress)).isInstanceOf(IllegalStateException.class);
        }
    }
}
