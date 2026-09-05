package app.mnema.identityaccount.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = false)
public abstract class PostgresIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"))
                    .withDatabaseName("mnema_identity")
                    .withUsername("mnema")
                    .withPassword("mnema");

    public static final String SIGNING_FILE;
    public static final com.nimbusds.jose.jwk.RSAKey SIGNING_KEY;

    static {
        POSTGRES.start();
        try {
            SIGNING_KEY = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("fresh-test-key").generate();
            var path = java.nio.file.Files.createTempFile("identity-synthetic-signing-", ".json");
            java.nio.file.Files.setPosixFilePermissions(path,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            java.nio.file.Files.writeString(path, new com.nimbusds.jose.jwk.JWKSet(SIGNING_KEY).toString(false));
            path.toFile().deleteOnExit();
            SIGNING_FILE = path.toString();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    protected static String fixtureJdbcUrl() { return POSTGRES.getJdbcUrl(); }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("identity.signing.jwk-set-file", () -> SIGNING_FILE);
        registry.add("identity.signing.active-kid", () -> "fresh-test-key");
        registry.add("MNEMA_BUILD_ID", () -> "test-release");
        registry.add("MNEMA_IDENTITY_ISSUER", () -> "https://identity.mnema.test");
    }
}
