package app.mnema.user.config

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableMethodSecurity
class SecurityConfig(
    private val env: Environment
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
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
            .oauth2ResourceServer { it.jwt(Customizer.withDefaults()) }
        return http.build()
    }
}
