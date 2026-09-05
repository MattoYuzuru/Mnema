package app.mnema.learning;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LearningApplicationIntegrationTest extends PostgresIntegrationTest {

    private static final Set<String> LEGACY_ROUTE_PREFIXES = Set.of(
            "/auth", "/users", "/me", "/admin", "/decks", "/templates", "/review",
            "/search", "/uploads", "/imports", "/providers", "/jobs", "/internal"
    );

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private BuildProperties buildProperties;

    @Autowired
    private Environment environment;

    @Autowired
    private Flyway flyway;

    @Autowired
    private InfoEndpoint infoEndpoint;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping requestMappings;

    @Test
    void bootsFreshMigrationHistoryWithoutLegacySchemas() {
        assertThat(flyway.info().applied())
                .filteredOn(migration -> migration.getVersion() != null)
                .singleElement()
                .satisfies(migration -> {
                    assertThat(migration.getVersion().getVersion()).isEqualTo("1");
                    assertThat(migration.getDescription()).isEqualTo("platform foundation");
                });

        assertThat(jdbcClient.sql("""
                        SELECT schema_name
                        FROM information_schema.schemata
                        WHERE schema_name LIKE 'app_%' OR schema_name = 'auth'
                        ORDER BY schema_name
                        """)
                .query(String.class)
                .list())
                .containsExactly("app_learning");
        assertThat(jdbcClient.sql("SELECT to_regclass('app_learning.command_receipt')")
                .query(String.class)
                .single()).isEqualTo("app_learning.command_receipt");
        assertThat(jdbcClient.sql("SELECT current_setting('server_version_num')::int / 10000")
                .query(Integer.class)
                .single()).isEqualTo(18);
    }

    @Test
    void exposesLivenessReadinessAndReproducibleBuildIdentity() throws Exception {
        assertThat(buildProperties.getName()).isEqualTo("learning");
        assertThat(buildProperties.get("runtime")).isEqualTo("learning-api");
        assertThat(buildProperties.get("apiBoundary")).isEqualTo("canonical");
        assertThat(buildProperties.getTime()).isNull();
        assertThat(infoEndpoint.info()).containsEntry("build", Map.of(
                "name", "learning",
                "group", "app.mnema",
                "artifact", "learning",
                "version", "0.0.1-SNAPSHOT",
                "runtime", "learning-api",
                "apiBoundary", "canonical"
        )).containsEntry("release", Map.of(
                "id", "test-release",
                "mode", "maintenance",
                "topology", "identity-learning",
                "runtime", "learning-api",
                "api-boundary", "canonical"
        ));

        mockMvc.perform(get("/api/actuator/health/liveness").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/api/actuator/health/readiness").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/api/actuator/info").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.build.runtime").value("learning-api"))
                .andExpect(jsonPath("$.build.apiBoundary").value("canonical"))
                .andExpect(jsonPath("$.release.id").value("test-release"))
                .andExpect(jsonPath("$.release.mode").value("maintenance"))
                .andExpect(jsonPath("$.release.topology").value("identity-learning"));
    }

    @Test
    void routeInventoryHasNoVersionOrLegacyAliases() throws Exception {
        assertThat(applicationContext.getBeanNamesForAnnotation(RestController.class)).isEmpty();
        assertThat(requestMappings.getHandlerMethods().keySet())
                .flatExtracting(mapping -> mapping.getPatternValues())
                .allSatisfy(route -> {
                    assertThat(route).doesNotContain("/v2");
                    assertThat(LEGACY_ROUTE_PREFIXES).noneMatch(route::startsWith);
                });

        mockMvc.perform(get("/api/v2").contextPath("/api"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/decks").contextPath("/api"))
                .andExpect(status().isNotFound());
    }

    @Test
    void runtimeRequiresOnlyDatabasePlatformConfiguration() {
        assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,db");
        assertThat(environment.containsProperty("app.ai.provider"))
                .isFalse();
        assertThat(environment.containsProperty("app.provider.api-key"))
                .isFalse();
    }
}
