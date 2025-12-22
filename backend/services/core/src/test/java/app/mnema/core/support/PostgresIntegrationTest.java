package app.mnema.core.support;

import org.junit.jupiter.api.Assumptions;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public abstract class PostgresIntegrationTest {

    private static final String ENV_URL = System.getenv("SPRING_DATASOURCE_URL");
    private static final String ENV_USERNAME = firstNonBlank(System.getenv("SPRING_DATASOURCE_USERNAME"), System.getenv("POSTGRES_USER"));
    private static final String ENV_PASSWORD = firstNonBlank(System.getenv("SPRING_DATASOURCE_PASSWORD"), System.getenv("POSTGRES_PASSWORD"));

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        String url = (ENV_URL != null && !ENV_URL.isBlank())
                ? ENV_URL
                : "jdbc:postgresql://localhost:5432/mnema";
        String user = ENV_USERNAME != null && !ENV_USERNAME.isBlank() ? ENV_USERNAME : "mnema";
        String password = ENV_PASSWORD != null && !ENV_PASSWORD.isBlank() ? ENV_PASSWORD : "";

        if (!canConnect(url, user, password)) {
            Assumptions.assumeTrue(false, "Postgres is not reachable at " + url + " for user " + user);
            return;
        }

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> user);
        registry.add("spring.flyway.password", () -> password);
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) return null;
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean canConnect(String url, String user, String password) {
        try (Connection ignored = DriverManager.getConnection(url, user, password)) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }
}
