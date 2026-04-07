package app.mnema.auth

import app.mnema.auth.config.AuthFeaturesProps
import app.mnema.auth.federation.FederatedOAuth2UserService
import app.mnema.auth.federation.FederatedUserMapper
import app.mnema.auth.identity.FederatedIdentityResult
import app.mnema.auth.identity.FederatedIdentityService
import app.mnema.auth.security.LogoutRedirectService
import app.mnema.auth.security.ProviderAwareLoginEntryPoint
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import java.security.KeyFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.util.*
import java.util.Base64

@Configuration
class SecurityConfig(
    private val featureProps: AuthFeaturesProps,
    private val identityService: FederatedIdentityService,
    private val userMapper: FederatedUserMapper
) {
    private val supportedProviders = setOf("google", "github", "yandex")
    private val log = LoggerFactory.getLogger(SecurityConfig::class.java)

    /** Chain #0: локальная регистрация/логин */
    @Bean
    @Order(0)
    fun localAuthSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/auth/**")
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        "/auth/login",
                        "/auth/register",
                        "/auth/turnstile/config"
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { it.jwt(Customizer.withDefaults()) }
            .csrf { it.disable() }
            .cors {}

        return http.build()
    }

    /** Chain #1: Authorization Server эндпоинты (/oauth2/authorize, /oauth2/token, .well-known и т.д.) */
    @Bean
    @Order(1)
    fun authorizationServerSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val asConfigurer = OAuth2AuthorizationServerConfigurer.authorizationServer()
        val endpointsMatcher = asConfigurer.endpointsMatcher

        http
            .securityMatcher(endpointsMatcher)
            .with(asConfigurer) { server ->
                server.oidc(Customizer.withDefaults())
            }
            .authorizeHttpRequests { auth ->
                auth.anyRequest().authenticated()
            }
            .csrf { csrf -> csrf.ignoringRequestMatchers(endpointsMatcher) }
            .cors {}

        if (featureProps.federatedEnabled) {
            http.exceptionHandling { exceptions ->
                exceptions.defaultAuthenticationEntryPointFor(
                    ProviderAwareLoginEntryPoint("google", supportedProviders),
                    MediaTypeRequestMatcher(MediaType.TEXT_HTML)
                )
            }
        }

        return http.build()
    }

    /** Chain #2: обычные веб-эндпоинты самого auth-сервиса */
    @Bean
    @Order(2)
    fun appSecurityFilterChain(
        http: HttpSecurity,
        federatedSuccessHandler: AuthenticationSuccessHandler,
        federatedOAuth2UserService: FederatedOAuth2UserService,
        logoutRedirectService: LogoutRedirectService
    ): SecurityFilterChain {
        http
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/auth/**").permitAll()
                    .requestMatchers("/actuator/**", "/error").permitAll()
                    .anyRequest().authenticated()
            }
            .logout { logout ->
                logout
                    .logoutUrl("/logout")
                    .clearAuthentication(true)
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID")
                    .logoutSuccessHandler { request, response, _ ->
                        val redirect = logoutRedirectService.resolve(request.getParameter("redirect"))
                        response.sendRedirect(redirect)
                    }
            }
            .csrf { it.disable() }
            .cors {}

        if (featureProps.federatedEnabled) {
            http.oauth2Login { oauth2 ->
                oauth2
                    .userInfoEndpoint { userInfo -> userInfo.userService(federatedOAuth2UserService) }
                    .successHandler(federatedSuccessHandler)
            }
        }

        return http.build()
    }


    /**
     * SuccessHandler в духе официального FederatedIdentityAuthenticationSuccessHandler:
     *  - маппим любые провайдеры (google/github/yandex)
     *  - синхронизируем данные в auth.users + auth.accounts
     *  - делегируем дальше стандартному SavedRequestAwareAuthenticationSuccessHandler,
     *    чтобы всё корректно вернулось к /oauth2/authorize и продолжилось до redirect_uri.
     */
    @Bean
    fun federatedSuccessHandler(): AuthenticationSuccessHandler {
        val delegate = SavedRequestAwareAuthenticationSuccessHandler()

        return AuthenticationSuccessHandler { request, response, authentication ->
            val oauth2 = authentication as? OAuth2AuthenticationToken
            val info = oauth2?.let { userMapper.from(it) }

            if (oauth2 != null && info != null) {
                val result = identityService.synchronize(info)
                oauth2.details = result
            }

            delegate.onAuthenticationSuccess(request, response, authentication)
        }
    }

    /** Issuer - ДОЛЖЕН совпадать с iss в токенах */
    @Bean
    fun authorizationServerSettings(
        @Value("\${auth.issuer}") issuer: String
    ): AuthorizationServerSettings =
        AuthorizationServerSettings.builder().issuer(issuer).build()

    /** JWK (dev): генерим RSA ключ на старте */
    @Bean
    fun jwkSource(
        @Value("\${auth.jwt.public-key:}") publicKeyValue: String,
        @Value("\${auth.jwt.private-key:}") privateKeyValue: String
    ): JWKSource<SecurityContext> {
        val configuredPublicKey = parsePublicKey(publicKeyValue)
        val configuredPrivateKey = parsePrivateKey(privateKeyValue)

        val rsa = if (configuredPublicKey != null && configuredPrivateKey != null) {
            RSAKey.Builder(configuredPublicKey)
                .privateKey(configuredPrivateKey)
                .keyID(keyIdFor(configuredPublicKey))
                .build()
        } else {
            if (publicKeyValue.isNotBlank() || privateKeyValue.isNotBlank()) {
                log.warn("auth.jwt.public-key/private-key invalid or incomplete; using ephemeral key")
            } else {
                log.warn("auth.jwt.public-key/private-key not configured; using ephemeral key")
            }
            val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            val kp: KeyPair = kpg.generateKeyPair()
            val publicKey = kp.public as RSAPublicKey
            val privateKey = kp.private as RSAPrivateKey
            RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(keyIdFor(publicKey))
                .build()
        }

        val jwkSet = JWKSet(listOf(rsa))
        return JWKSource { selector, _ -> selector.select(jwkSet) }
    }

    /** Хранилище клиентов (таблица auth.oauth2_registered_client) */
    @Bean
    fun registeredClientRepository(jdbcTemplate: JdbcTemplate): RegisteredClientRepository =
        JdbcRegisteredClientRepository(jdbcTemplate)

    @Bean
    fun seedClients(
        repo: RegisteredClientRepository,
        @Value("\${auth.clients.swagger.redirect-uris}") swaggerRedirectUris: List<String>,
        @Value("\${auth.clients.web.redirect-uris}") webRedirectUris: List<String>
    ) = CommandLineRunner {
        // 1) swagger-ui как было
        if (repo.findByClientId("swagger-ui") == null) {
            var swaggerBuilder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("swagger-ui")
                .clientSecret("{noop}secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope("openid")
                .scope("user.read")
                .scope("user.write")
                .tokenSettings(
                    TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(8))
                        .refreshTokenTimeToLive(Duration.ofDays(30))
                        .reuseRefreshTokens(false)
                        .build()
                )
                .clientSettings(
                    ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build()
                )
            val swaggerUris = swaggerRedirectUris
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            if (swaggerUris.isEmpty()) {
                throw IllegalStateException("auth.clients.swagger.redirect-uris must not be empty")
            }
            swaggerUris.forEach { uri ->
                swaggerBuilder = swaggerBuilder.redirectUri(uri)
            }
            val swaggerClient = swaggerBuilder.build()

            repo.save(swaggerClient)
        }

        // 2) публичный фронтовый клиент mnema-web
        if (repo.findByClientId("mnema-web") == null) {
            var webBuilder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("mnema-web")
                // public client -> без секрета
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope("openid")
                .scope("profile")
                .scope("email")
                .scope("user.read")
                .scope("user.write")
                .clientSettings(
                    ClientSettings.builder()
                        .requireProofKey(true)            // PKCE
                        .requireAuthorizationConsent(false)
                        .build()
                )
                .tokenSettings(
                    TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(8))
                        .refreshTokenTimeToLive(Duration.ofDays(30))
                        .reuseRefreshTokens(false)
                        .build()
                )
            val webUris = webRedirectUris
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            if (webUris.isEmpty()) {
                throw IllegalStateException("auth.clients.web.redirect-uris must not be empty")
            }
            webUris.forEach { uri ->
                webBuilder = webBuilder.redirectUri(uri)
            }
            val webClient = webBuilder.build()

            repo.save(webClient)
        }
    }

    @Bean
    fun jwtCustomizer(): OAuth2TokenCustomizer<JwtEncodingContext> =
        OAuth2TokenCustomizer { context ->
            if (OAuth2TokenType.ACCESS_TOKEN != context.tokenType) return@OAuth2TokenCustomizer

            val principal = context.getPrincipal() as? OAuth2AuthenticationToken ?: return@OAuth2TokenCustomizer
            val info = userMapper.from(principal) ?: return@OAuth2TokenCustomizer

            val result = (principal.details as? FederatedIdentityResult)
                ?: identityService.synchronize(info)
            val user = result.user

            user.id?.toString()?.let { context.claims.claim("user_id", it) }
            context.claims.claim("email", user.email)
            context.claims.claim("email_verified", user.emailVerified)
            user.username?.let { context.claims.claim("username", it) }
            user.name?.let { context.claims.claim("name", it) }
            user.pictureUrl?.let { context.claims.claim("picture", it) }
        }

    private fun parsePublicKey(value: String): RSAPublicKey? {
        val cleaned = cleanPem(value)
        if (cleaned.isBlank()) return null
        return runCatching {
            val spec = X509EncodedKeySpec(Base64.getDecoder().decode(cleaned))
            KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
        }.onFailure {
            log.warn("Failed to parse auth.jwt.public-key", it)
        }.getOrNull()
    }

    private fun parsePrivateKey(value: String): RSAPrivateKey? {
        val cleaned = cleanPem(value)
        if (cleaned.isBlank()) return null
        return runCatching {
            val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(cleaned))
            KeyFactory.getInstance("RSA").generatePrivate(spec) as RSAPrivateKey
        }.onFailure {
            log.warn("Failed to parse auth.jwt.private-key", it)
        }.getOrNull()
    }

    private fun cleanPem(value: String): String {
        return value
            .replace(Regex("-----BEGIN [A-Z ]+-----"), "")
            .replace(Regex("-----END [A-Z ]+-----"), "")
            .replace(Regex("\\s"), "")
            .trim()
    }

    private fun keyIdFor(publicKey: RSAPublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
