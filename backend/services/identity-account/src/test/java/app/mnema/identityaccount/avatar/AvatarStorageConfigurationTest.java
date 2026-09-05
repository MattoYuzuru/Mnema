package app.mnema.identityaccount.avatar;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarStorageConfigurationTest {
    @Test
    void acceptsHttpsWithoutAnInsecureTransportOverride() {
        assertThatNoException().isThrownBy(() -> create("https://storage.example.test", false, false).close());
    }

    @Test
    void acceptsLoopbackHttpOnlyWithTheTestOverride() {
        assertThatThrownBy(() -> create("http://127.0.0.1:9000", false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatNoException().isThrownBy(() -> create("http://127.0.0.1:9000", true, false).close());
    }

    @Test
    void acceptsOnlyTheExactStagingMinioEndpointWithTheDedicatedOverride() {
        assertThatThrownBy(() -> create("http://minio:9000", false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatNoException().isThrownBy(() -> create("http://minio:9000", false, true).close());

        for (String endpoint : List.of(
                "http://minio",
                "http://minio:9001",
                "http://minio:9000/avatars",
                "http://user@minio:9000",
                "http://minio:9000?target=external",
                "http://object-store:9000",
                "http://minio.mnema-staging.svc.cluster.local:9000")) {
            assertThatThrownBy(() -> create(endpoint, false, true))
                    .as("reject %s", endpoint)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static AvatarStorage create(String endpoint, boolean loopback, boolean stagingMinio) {
        return new AvatarStorage(URI.create(endpoint), "ru-central1", "synthetic-bucket", "", "", loopback,
                stagingMinio);
    }
}
