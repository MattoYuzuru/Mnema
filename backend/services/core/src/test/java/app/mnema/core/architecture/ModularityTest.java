package app.mnema.core.architecture;

import app.mnema.core.CoreApplication;
import app.mnema.core.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ModularityTest extends PostgresIntegrationTest {

    @Test
    void applicationModulesCanBeLoaded() {
        ApplicationModules modules = ApplicationModules.of(CoreApplication.class);

        assertThat(modules).isNotNull();
        assertThat(modules.stream()).isNotEmpty();
    }
}
