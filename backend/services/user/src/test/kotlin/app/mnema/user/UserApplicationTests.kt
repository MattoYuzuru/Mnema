package app.mnema.user

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.info.InfoEndpoint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = ["MNEMA_BUILD_ID=test-release"])
class UserApplicationTests {

    @Autowired
    private lateinit var infoEndpoint: InfoEndpoint

    companion object {
        @Container
        private val postgres =
            PostgreSQLContainer(DockerImageName.parse("postgres:18")).apply {
                withDatabaseName("mnema_user")
                withUsername("mnema")
                withPassword("mnema")
            }

        @JvmStatic
        @DynamicPropertySource
        fun dataSourceProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.url", postgres::getJdbcUrl)
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)
        }
    }

    @Test
    fun contextLoads() {
    }

    @Test
    fun `exposes the deployed build identity`() {
        val build = infoEndpoint.info()["build"] as Map<*, *>

        assertEquals("test-release", build["id"])
    }
}
