package app.mnema.learning.library;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicMetaOriginTest {
    @Test
    void blankIsTheProductionOrigin() {
        assertThat(PublicMetaService.origin(null)).isEqualTo("https://mnema.app");
        assertThat(PublicMetaService.origin("  ")).isEqualTo("https://mnema.app");
    }

    @Test
    void anOriginIsNormalisedToSchemeHostAndPort() {
        assertThat(PublicMetaService.origin("https://Mnema.APP/")).isEqualTo("https://mnema.app");
        assertThat(PublicMetaService.origin("https://example.test:8443")).isEqualTo("https://example.test:8443");
        assertThat(PublicMetaService.origin("http://localhost:3005")).isEqualTo("http://localhost:3005");
    }

    @Test
    void anythingElseFailsAtStartup() {
        for (String bad : new String[] {"http://mnema.app", "ftp://mnema.app", "mnema.app", "https://mnema.app/path", "https://mnema.app?x=1", "https://mnema.app#x",
                "https://user@mnema.app", "https:///x", "javascript:alert(1)", "https://mne ma.app"}) {
            assertThatThrownBy(() -> PublicMetaService.origin(bad)).as(bad).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid public origin");
        }
    }
}
