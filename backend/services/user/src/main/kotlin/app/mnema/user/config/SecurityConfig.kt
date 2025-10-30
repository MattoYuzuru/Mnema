package app.mnema.user.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .authorizeHttpRequests {
                it
                    .requestMatchers(
                        "/api/user/actuator/health",
                        "/api/user/actuator/health/**",
                        "/api/user/actuator/info"
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { it.jwt {} } // оставляем JWT для остальных эндпоинтов
        return http.build()
    }
}
