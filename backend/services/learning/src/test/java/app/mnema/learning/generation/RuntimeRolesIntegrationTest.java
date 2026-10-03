package app.mnema.learning.generation;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code learning.runtime.roles=api}: HTTP and the executors exist, the dispatcher that claims steps does not. */
@SpringBootTest(properties = {"learning.runtime.roles=api", "learning.ai.provider=stub",
        "learning.features.ai-generation.enabled=true", "spring.datasource.hikari.maximum-pool-size=4"})
class RuntimeRolesIntegrationTest extends PostgresIntegrationTest {
    @Autowired private ApplicationContext context;

    @Test
    void anApiProcessServesGenerationButNeverClaimsAStep() {
        assertThat(context.getBeanNamesForType(StepDispatcher.class)).isEmpty();
        // the retention sweep is the worker's too; the service it runs exists (a command may need it) but nothing schedules it
        assertThat(context.getBeanNamesForType(RetentionWorker.class)).isEmpty();
        assertThat(context.getBeanNamesForType(SessionRetention.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(GenerationController.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(TextDraftExecutor.class)).hasSize(1);
        assertThat(context.getBean(RuntimeRoles.class).runsWorker()).isFalse();
    }
}
