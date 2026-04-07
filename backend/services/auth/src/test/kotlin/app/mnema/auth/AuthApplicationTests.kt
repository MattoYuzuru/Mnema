package app.mnema.auth

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(AuthApplicationTests.TestOAuth2ClientConfig::class)
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

        }
    }

    @Test
    fun contextLoads() {
    }

    @TestConfiguration
    class TestOAuth2ClientConfig {
        @Bean
        fun clientRegistrationRepository(): ClientRegistrationRepository =
            InMemoryClientRegistrationRepository(
                registration("google", "openid", "profile", "email"),
                registration("github", "read:user", "user:email"),
                registration("yandex", "login:info", "login:email", "login:avatar")
            )

        private fun registration(
            registrationId: String,
            vararg scopes: String
        ): ClientRegistration =
            ClientRegistration.withRegistrationId(registrationId)
                .clientId("test-$registrationId-id")
                .clientSecret("test-$registrationId-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://example.test/oauth2/authorize")
                .tokenUri("https://example.test/oauth2/token")
                .userInfoUri("https://example.test/oauth2/userinfo")
                .userNameAttributeName("sub")
                .jwkSetUri("https://example.test/oauth2/jwks")
                .scope(*scopes)
                .clientName(registrationId.replaceFirstChar(Char::titlecase))
                .build()
    }
}
