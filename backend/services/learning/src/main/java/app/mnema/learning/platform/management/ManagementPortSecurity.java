package app.mnema.learning.platform.management;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * The management port ({@code management.server.port}, bound to a private address) is the operator's, not the API's: it answers {@code GET} and {@code HEAD}
 * without a bearer token (the metrics script and the probes have none) and refuses everything else. Nothing here applies to the public port: the matcher is
 * the local port of the connection, and the guard ({@link ManagementExposureGuard}) has already refused a configuration that shares the port.
 * CSRF remains enabled with a cookie repository (HttpOnly by default), never a server session; safe probes defer token generation.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!'${management.server.port:}'.trim().isEmpty()")
class ManagementPortSecurity {
    @Bean
    @Order(0)
    SecurityFilterChain managementPort(HttpSecurity http, @Value("${management.server.port}") int port) throws Exception {
        return http.securityMatcher(request -> request.getLocalPort() == port)
                .authorizeHttpRequests(requests -> requests.requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/**").permitAll().anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.csrfTokenRepository(new CookieCsrfTokenRepository()))
                .requestCache(cache -> cache.disable()).build();
    }
}
