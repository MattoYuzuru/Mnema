package app.mnema.identityaccount.security;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.authorization.GrantTransactionFilter;
import app.mnema.identityaccount.contract.AccountErrors;
import app.mnema.identityaccount.contract.IssuerContract;
import app.mnema.identityaccount.federation.FederationRequests;
import app.mnema.identityaccount.federation.FederationSuccess;
import app.mnema.identityaccount.federation.ProviderTokenDiscarder;
import app.mnema.identityaccount.federation.ProviderUsers;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.oidc.web.authentication.OidcLogoutAuthenticationSuccessHandler;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.net.URI;
import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    private static AuthorizationDecision scope(Authentication auth, String scope) {
        boolean authenticated =
                auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
        return new AuthorizationDecision(authenticated && (auth instanceof JwtAuthenticationToken
                ? auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("SCOPE_" + scope))
                : auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ACCOUNT"))));
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${identity.frontend-origin}") String origin) {
        new IssuerContract(URI.create(origin));
        var config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(origin));
        config.setAllowCredentials(true);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN", "Authorization"));
        config.setMaxAge(600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    @Order(1)
    SecurityFilterChain authorization(HttpSecurity http, AccountStore accounts, BrowserSessions sessions,
                                      JdbcClient jdbcClient, TransactionTemplate transactions, Clock clock,
                                      AccountErrors errors) throws Exception {
        var server = OAuth2AuthorizationServerConfigurer.authorizationServer();
        var oidcLogout = new OidcLogoutAuthenticationSuccessHandler();
        oidcLogout.setLogoutHandler((request, response, authentication) -> {
            var logout = (OidcLogoutAuthenticationToken) authentication;
            if (logout.isPrincipalAuthenticated())
                sessions.logout(BrowserSessions.access((Authentication) logout.getPrincipal()), request);
        });
        http.securityMatcher(server.getEndpointsMatcher())
                .with(server, s -> s.oidc(o -> o.logoutEndpoint(l -> l.logoutResponseHandler(oidcLogout))))
                .addFilterAfter(new RecoveryAuthorizationBoundaryFilter(errors), SecurityContextHolderFilter.class)
                .addFilterBefore(new GrantTransactionFilter(jdbcClient, transactions), AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().authenticated()).cors(Customizer.withDefaults())
                .exceptionHandling(
                        e -> e.defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .oauth2ResourceServer(r -> r.jwt(Customizer.withDefaults()))
                .addFilterBefore(new CurrentAccountFilter(accounts, clock, errors), AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain accountSecurity(HttpSecurity http, AccountStore accounts,
                                        ObjectProvider<ClientRegistrationRepository> registrations,
                                        FederationSuccess success, ProviderUsers users, Clock clock,
                                        AccountErrors errors) throws Exception {
        http.cors(Customizer.withDefaults()).csrf(c -> c.csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .authorizeHttpRequests(
                        a -> a.requestMatchers("/api/actuator/health/**", "/api/actuator/info", "/error", "/login",
                                        "/login/continue", "/oauth2/authorization/**", "/login/oauth2/code/**").permitAll()
                                .requestMatchers(HttpMethod.GET, "/api/accounts/csrf", "/api/accounts/profiles/**")
                                .permitAll()
                                .requestMatchers(HttpMethod.POST, "/api/accounts/register", "/api/accounts/login",
                                        "/api/accounts/password-reset/request", "/api/accounts/password-reset/confirm",
                                        "/api/accounts/email-verification/request",
                                        "/api/accounts/email-verification/confirm",
                                        "/api/accounts/deletion/recovery/federated",
                                        "/api/accounts/deletion/proof/password",
                                        "/api/accounts/deletion/proof/federated",
                                        "/api/accounts/deletion/confirmed").permitAll()
                                .requestMatchers("/api/accounts/deletion/recovery/**")
                                .hasAuthority("ACCOUNT_RECOVERY")
                                .requestMatchers(HttpMethod.GET, "/api/accounts/**")
                                .access((auth, context) -> scope(auth.get(), "account.read"))
                                .requestMatchers("/api/accounts/**")
                                .access((auth, context) -> scope(auth.get(), "account.write")).anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(
                                (request, response, failure) -> errors.write(response, 401, "authentication_failed"))
                        .accessDeniedHandler(
                                (request, response, failure) -> errors.write(response, 403, "operation_denied")))
                .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(
                                (request, response, failure) -> errors.write(response, 401, "authentication_failed"))
                        .accessDeniedHandler(
                                (request, response, failure) -> errors.write(response, 403, "operation_denied")))
                .logout(l -> l.disable()).requestCache(c -> c.disable())
                .addFilterBefore(new CurrentAccountFilter(accounts, clock, errors), AuthorizationFilter.class);
        if (registrations.getIfAvailable() != null) {
            var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations.getObject(),
                    "/oauth2/authorization");
            resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
            http.oauth2Login(o -> o.authorizedClientRepository(new ProviderTokenDiscarder()).authorizationEndpoint(
                            a -> a.authorizationRequestRepository(new FederationRequests(clock))
                                    .authorizationRequestResolver(resolver))
                    .tokenEndpoint(token -> token.accessTokenResponseClient(users.tokenClient()))
                    .userInfoEndpoint(u -> u.userService(users).oidcUserService(users.oidcUsers()))
                    .successHandler(success).failureHandler((r, s, e) -> {
                        var session = r.getSession(false);
                        if (session != null) session.removeAttribute("identity.intent");
                        s.sendRedirect("/login?error=federation_failed");
                    }));
        }
        return http.build();
    }
}
