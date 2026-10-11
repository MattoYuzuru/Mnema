package app.mnema.learning.platform.security;

import app.mnema.learning.library.PublicAccountLimitFilter;
import app.mnema.learning.library.PublicReadLimiter;
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
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

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
        if (endpoints.base() == null) return token -> { throw new BadJwtException("Identity is not configured"); };
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
            if (token.length() > 16_384) throw new BadJwtException("Invalid access token");
            try {
                return decoder.decode(token);
            } catch (JwtException failure) {
                // Generic JwtException becomes an AuthenticationServiceException and bypasses
                // the bearer entry point. Local token/key rejection must use the stable 401 path.
                throw new BadJwtException("Invalid access token");
            }
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
    SecurityFilterChain publicEvents(HttpSecurity http, ApiSecurityErrors errors) throws Exception {
        return http.securityMatcher("/events")
                .authorizeHttpRequests(requests -> requests.requestMatchers(HttpMethod.GET, "/events").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/events").permitAll().anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable()).logout(logout -> logout.disable())
                .exceptionHandling(failures -> failures.authenticationEntryPoint((r, s, e) -> errors.unauthorized(r, s))
                        .accessDeniedHandler((r, s, e) -> errors.forbidden(r, s))).build();
    }

    /**
     * The bank's payment notification (#389): no session and no bearer, because the bank has neither. Authenticity is the {@code Token} signature and
     * the {@code TerminalKey}, verified by the controller before anything is read, and every notification is re-checked with {@code GetState}. Exactly
     * one path and one method are open; no cookie is read and no OAuth resource server runs here. The CSRF filter stays on and exempts only that one
     * endpoint: the bank cannot send a CSRF token, and a forged cross-site request still has to carry a valid {@code Token}.
     */
    @Bean
    @Order(3)
    SecurityFilterChain publicBillingNotifications(HttpSecurity http, ApiSecurityErrors errors) throws Exception {
        return http.securityMatcher("/billing/tbank/notifications")
                .authorizeHttpRequests(requests -> requests.requestMatchers(HttpMethod.POST, "/billing/tbank/notifications").permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.ignoringRequestMatchers("/billing/tbank/notifications"))
                .requestCache(cache -> cache.disable()).logout(logout -> logout.disable())
                .exceptionHandling(failures -> failures.authenticationEntryPoint((r, s, e) -> errors.unauthorized(r, s))
                        .accessDeniedHandler((r, s, e) -> errors.forbidden(r, s))).build();
    }

    /**
     * The public read of a deck by its code (Share/7): an OPTIONAL bearer, GET and HEAD only. Without a token the request is a guest; with one it must
     * validate exactly like the private API (the same decoder and the same live Identity check): the resource server rejects a present but invalid
     * token with 401 before authorization, so it is never silently treated as a guest ({@code permitAll} does not override that). A valid token without
     * {@code learning.read} is 403, as on the private API. An account is admitted by the limiter BEFORE Identity is asked ({@link PublicAccountLimitFilter}). Every other method is refused; no session, cookie or form login exists here.
     */
    @Bean
    @Order(4)
    SecurityFilterChain publicDecks(HttpSecurity http, JwtDecoder decoder, IdentityHttp identity, IdentityEndpoints endpoints,
                                    ApiSecurityErrors errors, PublicReadLimiter limiter) throws Exception {
        AuthorizationManager<RequestAuthorizationContext> guestOrReader = AuthorizationManagers.anyOf(
                AuthenticatedAuthorizationManager.<RequestAuthorizationContext>anonymous(),
                AuthorityAuthorizationManager.<RequestAuthorizationContext>hasAuthority("SCOPE_learning.read"));
        PublicAccountLimitFilter accountLimit = new PublicAccountLimitFilter(limiter, errors);
        http.securityMatcher("/public/decks/**")
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Only GET and HEAD reach a controller and the bearer header is the only credential: there is no cookie for a forged request to ride on.
                .csrf(csrf -> csrf.disable()).requestCache(cache -> cache.disable()).logout(logout -> logout.disable())
                .authorizeHttpRequests(requests -> requests.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/public/decks/**").access(guestOrReader)
                        .requestMatchers(HttpMethod.HEAD, "/public/decks/**").access(guestOrReader)
                        .anyRequest().denyAll())
                .exceptionHandling(failures -> failures.authenticationEntryPoint((r, s, e) -> errors.unauthorized(r, s))
                        .accessDeniedHandler((r, s, e) -> errors.forbidden(r, s)))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(decoder))
                        .authenticationEntryPoint((r, s, e) -> errors.unauthorized(r, s))
                        .accessDeniedHandler((r, s, e) -> errors.forbidden(r, s)))
                // The account is admitted (limit per account) before Identity is asked: one token cannot drive unlimited /userinfo round trips.
                .addFilterAfter(accountLimit, AuthorizationFilter.class)
                .addFilterAfter(new CurrentIdentityFilter(identity,
                        endpoints.base() == null ? null : endpoints.endpoint("/userinfo"), errors), PublicAccountLimitFilter.class);
        return http.build();
    }

    @Bean
    @Order(5)
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
