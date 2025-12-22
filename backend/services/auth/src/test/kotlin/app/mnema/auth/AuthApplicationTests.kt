package app.mnema.auth

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class AuthApplicationTests {

    companion object {
        @Container
        private val postgres =
            PostgreSQLContainer(DockerImageName.parse("postgres:18")).apply {
                withDatabaseName("mnema")
                withUsername("mnema")
                withPassword("mnema")
            }

        @JvmStatic
        @DynamicPropertySource
        fun overrideProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)

            registry.add("spring.flyway.url", postgres::getJdbcUrl)
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)

            registry.add("spring.security.oauth2.client.registration.google.client-id") { "test-google-id" }
            registry.add("spring.security.oauth2.client.registration.google.client-secret") { "test-google-secret" }
            registry.add("spring.security.oauth2.client.registration.github.client-id") { "test-github-id" }
            registry.add("spring.security.oauth2.client.registration.github.client-secret") { "test-github-secret" }
            registry.add("spring.security.oauth2.client.registration.yandex.client-id") { "test-yandex-id" }
            registry.add("spring.security.oauth2.client.registration.yandex.client-secret") { "test-yandex-secret" }
        }
    }

    @Test
    fun contextLoads() {
    }
}
