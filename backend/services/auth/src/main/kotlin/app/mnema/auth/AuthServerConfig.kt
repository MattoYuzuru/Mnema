package app.mnema.auth

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings
import org.springframework.security.web.SecurityFilterChain
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.util.*

@Configuration
class AuthServerConfig(
    private val accountService: AccountService
) {
    /** Chain #1: эндпоинты Authorization Server */
    @Bean
    @Order(1)
    fun authServerSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val asConfigurer = OAuth2AuthorizationServerConfigurer()
        val endpointsMatcher = asConfigurer.endpointsMatcher

        http
            .securityMatcher(endpointsMatcher)
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .csrf { it.ignoringRequestMatchers(endpointsMatcher) }
            .with(asConfigurer) { asCfg -> asCfg.oidc(Customizer.withDefaults()) }
            .oauth2ResourceServer { it.jwt(Customizer.withDefaults()) }

        return http.build()
    }

    /** Chain #2: обычная веб-безопасность */
    @Bean
    @Order(2)
    fun appSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests {
                it.requestMatchers("/actuator/**").permitAll()
                it.anyRequest().authenticated()
            }
            .oauth2Login { login ->
                login.userInfoEndpoint { userInfo ->
                    userInfo.oidcUserService { req ->
                        // Создаём/обновляем локальный аккаунт после Google-login
                        val user =
                            org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService().loadUser(req)
                        accountService.upsertGoogleAccount(
                            providerSub = user.subject,
                            email = user.email,
                            name = user.fullName ?: user.givenName ?: user.preferredUsername,
                            picture = user.picture
                        )
                        user
                    }
                }
            }
            .formLogin(Customizer.withDefaults())
            .csrf { it.disable() }

        return http.build()
    }

    /** Issuer - ДОЛЖЕН совпадать с iss в токенах */
    @Bean
    fun authorizationServerSettings(
        @Value("\${auth.issuer}") issuer: String
    ): AuthorizationServerSettings =
        AuthorizationServerSettings.builder().issuer(issuer).build()

    /** JWK (dev): генерим RSA ключ на старте */
    @Bean
    fun jwkSource(): JWKSource<SecurityContext> {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val kp: KeyPair = kpg.generateKeyPair()
        val publicKey = kp.public as RSAPublicKey
        val privateKey = kp.private as RSAPrivateKey

        val rsa = RSAKey.Builder(publicKey)
            .privateKey(privateKey)
            .keyID(UUID.randomUUID().toString())
            .build()

        val jwkSet = JWKSet(listOf(rsa)) // избегаем неоднозначности конструктора
        return JWKSource { selector, _ -> selector.select(jwkSet) }
    }

    /** Хранилище клиентов (таблица auth.oauth2_registered_client) */
    @Bean
    fun registeredClientRepository(jdbcTemplate: JdbcTemplate): RegisteredClientRepository =
        JdbcRegisteredClientRepository(jdbcTemplate)

    /** Засеять dev-клиента для Swagger в user-сервисе */
    @Bean
    fun seedClient(repo: RegisteredClientRepository) = CommandLineRunner {
        val clientId = "swagger-ui"
        if (repo.findByClientId(clientId) == null) {
            val client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientSecret("{noop}secret") // dev only
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://localhost:8084/swagger-ui/oauth2-redirect.html")
                .scope("openid")
                .scope("user.read")
                .scope("user.write")
                .tokenSettings(
                    TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(1))
                        .refreshTokenTimeToLive(Duration.ofDays(30))
                        .reuseRefreshTokens(false)
                        .build()
                )
                .clientSettings(
                    ClientSettings.builder()
                        .requireProofKey(true)       // PKCE
                        .requireAuthorizationConsent(false)
                        .build()
                )
                .build()
            repo.save(client)
        }
    }
}
