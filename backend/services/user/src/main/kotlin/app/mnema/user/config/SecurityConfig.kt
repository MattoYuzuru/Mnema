package app.mnema.user.config

import app.mnema.user.security.InternalTokenAuthFilter
import app.mnema.user.security.UserInternalAuthProps
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(UserInternalAuthProps::class)
class SecurityConfig(
    private val env: Environment
) {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        internalTokenAuthFilter: InternalTokenAuthFilter,
        bearerTokenResolver: BearerTokenResolver
    ): SecurityFilterChain {
        val swaggerEnabled = env.getProperty("springdoc.swagger-ui.enabled", Boolean::class.java, true)
        val apiDocsEnabled = env.getProperty("springdoc.api-docs.enabled", Boolean::class.java, true)
        val publicMatchers = mutableListOf("/actuator/**")
        if (swaggerEnabled || apiDocsEnabled) {
            publicMatchers.addAll(
                listOf(
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html"
                )
            )
        }

        http
            .cors {  }
            .csrf { it.disable() }
            .authorizeHttpRequests {
                if (publicMatchers.isNotEmpty()) {
                    it.requestMatchers(*publicMatchers.toTypedArray()).permitAll()
                }
                it.requestMatchers(EndpointRequest.to("health", "info")).permitAll()
                it.anyRequest().authenticated()
            }
            .oauth2ResourceServer {
                it.jwt(Customizer.withDefaults())
                    .bearerTokenResolver(bearerTokenResolver)
            }

        http.addFilterBefore(internalTokenAuthFilter, AuthorizationFilter::class.java)
        return http.build()
    }

    @Bean
    fun bearerTokenResolver(props: UserInternalAuthProps): BearerTokenResolver = BearerTokenResolver { request ->
        val authHeader = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return@BearerTokenResolver null
        }
        val token = authHeader.removePrefix("Bearer ").trim()
        if (token == props.internalToken) {
            return@BearerTokenResolver null
        }
        token
    }

    @Bean
    fun internalTokenAuthFilter(props: UserInternalAuthProps): InternalTokenAuthFilter =
        InternalTokenAuthFilter(props)
}
