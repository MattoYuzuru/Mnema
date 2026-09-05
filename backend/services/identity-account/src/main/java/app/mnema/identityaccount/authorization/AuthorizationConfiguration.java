package app.mnema.identityaccount.authorization;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.AccountAccess;
import app.mnema.identityaccount.contract.AccountFailure;
import app.mnema.identityaccount.contract.IssuerContract;
import app.mnema.identityaccount.security.BrowserSessions;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class AuthorizationConfiguration {
    @Bean
    AuthorizationServerSettings authorizationServerSettings(IssuerContract issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer.issuer()).build();
    }

    @Bean
    RegisteredClientRepository clients(JdbcTemplate jdbcClient, @Value("${identity.redirect-uri}") String redirect) {
        new IssuerContract(URI.create(redirect));
        var repo = new JdbcRegisteredClientRepository(jdbcClient);
        var client = RegisteredClient.withId("mnema-web").clientId("mnema-web")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri(redirect)
                .scope(OidcScopes.OPENID).scope(OidcScopes.PROFILE).scope("account.read").scope("account.write")
                .clientSettings(
                        ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(5))
                        .authorizationCodeTimeToLive(Duration.ofMinutes(2)).build()).build();
        repo.save(client);
        return repo;
    }

    @Bean
    OAuth2AuthorizationService authorizations(JdbcTemplate jdbcClient, RegisteredClientRepository clients,
                                              AccountStore accounts, TransactionTemplate transactions) {
        return new GenerationAuthorizations(new JdbcOAuth2AuthorizationService(jdbcClient, clients), accounts, transactions);
    }

    @Bean
    OAuth2AuthorizationConsentService consents(JdbcTemplate jdbcClient, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcClient, clients);
    }

    @Bean
    JWKSet signingKeys(@Value("${identity.signing.jwk-set-file}") String file,
                       @Value("${identity.signing.active-kid}") String kid) throws Exception {
        var keys = JWKSet.parse(Files.readString(Path.of(file)));
        var active = keys.getKeyByKeyId(kid);
        if (!(active instanceof RSAKey rsa) || !rsa.isPrivate() || rsa.size() < 2048 ||
                keys.getKeys().stream().anyMatch(k -> k.getKeyID() == null || !(k instanceof RSAKey)))
            throw new IllegalArgumentException("A new, durable RSA signing key set and active kid are required");
        if (keys.getKeys().stream().map(JWK::getKeyID).distinct().count() != keys.size())
            throw new IllegalArgumentException("Duplicate signing kid");
        return new JWKSet(keys.getKeys().stream().map(k -> k.getKeyID().equals(kid) ? k : k.toPublicJWK()).toList());
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(JWKSet keys) {
        return (selector, context) -> selector.select(keys);
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> tokenClaims(AccountStore accounts, TransactionTemplate transactions,
                                                          @Value("${identity.signing.active-kid}") String kid) {
        return context -> transactions.executeWithoutResult(s -> {
            var authorization = context.getAuthorization();
            long generation = authorization == null ? BrowserSessions.access(context.getPrincipal())
                    .generation() : GenerationAuthorizations.generation(authorization);
            var access = new AccountAccess(UUID.fromString(context.getPrincipal().getName()), generation);
            try {
                accounts.require(access, true);
            } catch (AccountFailure e) {
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
            }
            context.getJwsHeader().keyId(kid);
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                context.getJwsHeader().type("at+jwt");
                context.getClaims().subject(access.accountId().toString())
                        .audience(new ArrayList<>(List.of("mnema-api"))).claim("generation", Long.toString(generation));
            }
        });
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSet keys, IssuerContract issuer, AccountStore accounts,
                          OAuth2AuthorizationService authorizations) {
        var processor = new DefaultJWTProcessor<SecurityContext>();
        processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType("at+jwt")));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,
                (selector, context) -> selector.select(keys.toPublicJWKSet())));
        var decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer.issuer()), jwt -> {
                    try {
                        if (!jwt.getAudience().contains("mnema-api") ||
                                !(jwt.getClaim("generation") instanceof String gen) ||
                                !jwt.getSubject().equals(UUID.fromString(jwt.getSubject()).toString()))
                            throw AccountFailure.denied();
                        accounts.require(new AccountAccess(UUID.fromString(jwt.getSubject()), Long.parseLong(gen)),
                                false);
                        var grant = authorizations.findByToken(jwt.getTokenValue(), OAuth2TokenType.ACCESS_TOKEN);
                        if (grant == null || grant.getAccessToken() == null || !grant.getAccessToken().isActive())
                            throw AccountFailure.denied();
                        return OAuth2TokenValidatorResult.success();
                    } catch (RuntimeException e) {
                        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
                    }
                }));
        return decoder;
    }
}
