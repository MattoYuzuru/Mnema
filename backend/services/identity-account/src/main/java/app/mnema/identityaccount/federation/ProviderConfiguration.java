package app.mnema.identityaccount.federation;

import app.mnema.identityaccount.contract.IssuerContract;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class ProviderConfiguration {
    @Bean
    ClientRegistrationRepository providerRegistrations(Environment env, IssuerContract issuer) {
        Map<String, ClientRegistration> registrations = new HashMap<>();
        for (String provider : List.of("google", "github", "yandex")) {
            String prefix = "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_" + provider.toUpperCase(Locale.ROOT);
            String id = env.getProperty(prefix + "_CLIENT_ID", "");
            String secret = env.getProperty(prefix + "_CLIENT_SECRET", "");
            if (id.isBlank() || secret.isBlank()) continue;
            var b = ClientRegistration.withRegistrationId(provider).clientId(id).clientSecret(secret)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri(issuer.issuer() + "/login/oauth2/code/" + provider).clientName(provider);
            switch (provider) {
                case "google" -> b.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .scope("openid", "profile", "email")
                        .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                        .tokenUri("https://oauth2.googleapis.com/token")
                        .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                        .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                        .issuerUri("https://accounts.google.com").userNameAttributeName("sub");
                case "github" -> b.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .scope("read:user", "user:email")
                        .authorizationUri("https://github.com/login/oauth/authorize")
                        .tokenUri("https://github.com/login/oauth/access_token")
                        .userInfoUri("https://api.github.com/user").userNameAttributeName("id");
                case "yandex" -> b.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                        .scope("login:email,login:info")
                        .authorizationUri("https://oauth.yandex.com/authorize")
                        .tokenUri("https://oauth.yandex.com/token").userInfoUri("https://login.yandex.ru/info")
                        .userNameAttributeName("id");
            }
            registrations.put(provider, b.build());
        }
        return registrations::get;
    }
}
