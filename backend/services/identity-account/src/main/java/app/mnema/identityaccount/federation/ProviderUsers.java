package app.mnema.identityaccount.federation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.RequestEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequestEntityConverter;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ProviderUsers implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
    private final RestClient http;
    private final JdkClientHttpRequestFactory requestFactory;
    private final URI emailsUri;

    public ProviderUsers(
            @Value("${identity.federation.github-emails-uri:https://api.github.com/user/emails}") URI emailsUri,
            @Value("${identity.federation.allow-loopback-http:false}") boolean loopback) {
        if (!emailsUri.equals(URI.create("https://api.github.com/user/emails")) &&
                !(loopback && emailsUri.getScheme().equals("http") &&
                        Set.of("127.0.0.1", "localhost").contains(emailsUri.getHost())))
            throw new IllegalArgumentException("GitHub email API requires fixed HTTPS endpoint");
        this.emailsUri = emailsUri;
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        requestFactory = factory;
        var template = new RestTemplate(factory);
        template.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        delegate.setRestOperations(template);
        var converter = new OAuth2UserRequestEntityConverter();
        delegate.setRequestEntityConverter(request -> {
            var entity = converter.convert(request);
            if (!request.getClientRegistration().getRegistrationId().equals("yandex")) return entity;
            var headers = new HttpHeaders();
            headers.putAll(entity.getHeaders());
            headers.set("Authorization", "OAuth " + request.getAccessToken().getTokenValue());
            return new RequestEntity<>(headers, entity.getMethod(), entity.getUrl());
        });
        http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) {
        try {
            OAuth2User user = delegate.loadUser(request);
            var attrs = new HashMap<>(user.getAttributes());
            if (request.getClientRegistration().getRegistrationId().equals("yandex") &&
                    !request.getClientRegistration().getClientId().equals(attrs.get("client_id"))) {
                throw new OAuth2AuthenticationException("invalid_provider_client");
            }
            if (request.getClientRegistration().getRegistrationId().equals("github")) {
                var emails = http.get().uri(emailsUri)
                        .header("Authorization", "Bearer " + request.getAccessToken().getTokenValue()).retrieve()
                        .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {
                        });
                attrs.remove("email");
                attrs.put("email_verified", false);
                if (emails != null)
                    emails.stream().filter(e -> Boolean.TRUE.equals(e.get("verified")) &&
                            Boolean.TRUE.equals(e.get("primary"))).findFirst().ifPresent(e -> {
                        attrs.put("email", e.get("email"));
                        attrs.put("email_verified", true);
                    });
            }
            return new DefaultOAuth2User(user.getAuthorities(), attrs,
                    request.getClientRegistration().getProviderDetails().getUserInfoEndpoint()
                            .getUserNameAttributeName());
        } catch (RuntimeException failure) {
            throw new OAuth2AuthenticationException("invalid_provider_response");
        }
    }


    public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenClient() {
        var client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(RestClient.builder().requestFactory(requestFactory)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build());
        return client;
    }

    public OidcUserService oidcUsers() {
        var service = new OidcUserService();
        service.setOauth2UserService(delegate);
        return service;
    }

    public static FederatedAccounts.External external(String provider, OAuth2User user) {
        Map<String, Object> attrs = user.getAttributes();
        String subject = switch (provider) {
            case "google" -> (String) attrs.get("sub");
            case "github" -> Objects.toString(attrs.get("id"), null);
            case "yandex" -> Objects.toString(attrs.get("id"), null);
            default -> throw new OAuth2AuthenticationException("invalid_provider");
        };
        String email = (String) attrs.get(provider.equals("yandex") ? "default_email" : "email");
        boolean verified = !provider.equals("yandex") && Boolean.TRUE.equals(attrs.get("email_verified"));
        return new FederatedAccounts.External(provider, subject, email, verified);
    }
}
