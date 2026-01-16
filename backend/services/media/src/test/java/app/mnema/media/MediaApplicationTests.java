package app.mnema.media;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class MediaApplicationTests {

    @Container
    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"))
                    .withDatabaseName("mnema_media")
                    .withUsername("mnema")
                    .withPassword("mnema");

    @DynamicPropertySource
    static void dataSourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);

        registry.add("app.s3.bucket", () -> "test-bucket");
        registry.add("app.s3.region", () -> "us-east-1");
        registry.add("app.s3.endpoint", () -> "http://localhost:9000");
        registry.add("app.s3.path-style-access", () -> "true");
        registry.add("app.s3.access-key", () -> "test-access");
        registry.add("app.s3.secret-key", () -> "test-secret");
    }

    @Test
    void contextLoads() {
    }
}
