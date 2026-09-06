package app.mnema.learning.platform.security;

import app.mnema.learning.platform.api.ApiSecurityErrors;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.Resource;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.BadJWTException;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class LearningSecurityConfiguration {
    @Bean
    IdentityEndpoints identityEndpoints(@Value("${learning.identity.issuer:}") String issuer,
                                        @Value("${learning.identity.transport-base:}") String transport,
                                        @Value("${learning.identity.allow-loopback-http:false}") boolean loopback) {
        // Maintenance can boot without Identity configuration, but no private request can authenticate.
        return issuer.isBlank() ? new IdentityEndpoints("", null)
                : IdentityEndpoints.configured(issuer, transport, loopback);
    }

    @Bean(destroyMethod = "close")
    IdentityHttp identityHttp(@Value("${learning.identity.timeout:2s}") Duration timeout,
                              @Value("${learning.identity.max-concurrency:32}") int concurrency) {
        return new IdentityHttp(timeout, concurrency);
    }

    @Bean
    JwtDecoder learningJwtDecoder(IdentityEndpoints endpoints, IdentityHttp http) throws Exception {
        if (endpoints.base() == null) return token -> { throw new JwtException("Identity is not configured"); };
        JWKSource<SecurityContext> keys = JWKSourceBuilder.<SecurityContext>create(
                endpoints.endpoint("/oauth2/jwks").toURL(), url -> {
                    var response = http.get(endpoints.endpoint("/oauth2/jwks"), null, 65_536);
                    if (response.statusCode() != 200) throw new IOException("Identity keys unavailable");
                    return new Resource(new String(response.body(), StandardCharsets.UTF_8), "application/json");
                }).refreshAheadCache(false).cache(300_000, 2_000).rateLimited(1_000).build();
        var processor = new DefaultJWTProcessor<SecurityContext>();
        processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType("at+jwt")));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        // Require raw timestamps before Spring's claim converter can synthesize a missing iat.
        processor.setJWTClaimsSetVerifier((claims, context) -> {
            if (claims.getIssueTime() == null || claims.getExpirationTime() == null) {
                throw new BadJWTException("Required access-token timestamps absent");
            }
        });
        var decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(30)), new JwtIssuerValidator(endpoints.issuer()),
                new LearningTokenValidator()));
        return token -> {
            if (token.length() > 16_384) throw new JwtException("Invalid access token");
            return decoder.decode(token);
        };
    }

    @Bean
    @Order(1)
    SecurityFilterChain publicProbes(HttpSecurity http) throws Exception {
        return http.securityMatcher("/actuator/health/**", "/actuator/info")
                .authorizeHttpRequests(requests -> requests.requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/**").permitAll().anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable()).build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain learningSecurity(HttpSecurity http, JwtDecoder decoder, IdentityHttp identity,
                                         IdentityEndpoints endpoints, ApiSecurityErrors errors) throws Exception {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Bearer header only: neither cookies nor form/query tokens authenticate this API.
                .csrf(csrf -> csrf.disable()).requestCache(cache -> cache.disable()).logout(logout -> logout.disable())
                .authorizeHttpRequests(requests -> requests.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/**").hasAuthority("SCOPE_learning.read")
                        .requestMatchers(HttpMethod.HEAD, "/**").hasAuthority("SCOPE_learning.read")
                        .anyRequest().hasAuthority("SCOPE_learning.write"))
                .exceptionHandling(failures -> failures.authenticationEntryPoint((r, s, e) -> errors.unauthorized(r, s))
                        .accessDeniedHandler((r, s, e) -> errors.forbidden(r, s)))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(decoder))
                        .authenticationEntryPoint((r, s, e) -> errors.unauthorized(r, s))
                        .accessDeniedHandler((r, s, e) -> errors.forbidden(r, s)))
                .addFilterAfter(new CurrentIdentityFilter(identity,
                        endpoints.base() == null ? null : endpoints.endpoint("/userinfo"), errors), AuthorizationFilter.class);
        return http.build();
    }
}
