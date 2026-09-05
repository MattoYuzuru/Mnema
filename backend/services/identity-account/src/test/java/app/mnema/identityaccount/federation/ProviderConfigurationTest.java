package app.mnema.identityaccount.federation;

import app.mnema.identityaccount.contract.IssuerContract;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderConfigurationTest {
    @Test
    void optionalPairsUseFixedProviderEndpointsAndConfiguredIssuerCallbacks() {
        var environment = new MockEnvironment();
        var configuration = new ProviderConfiguration();
        var issuer = new IssuerContract(URI.create("https://identity.fixture.test"));
        assertThat(configuration.providerRegistrations(environment, issuer).findByRegistrationId("google")).isNull();
        for (String provider : List.of("google", "github", "yandex")) {
            String prefix = "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_" + provider.toUpperCase(Locale.ROOT);
            environment.setProperty(prefix + "_CLIENT_ID", "synthetic-client");
            assertThat(
                    configuration.providerRegistrations(environment, issuer).findByRegistrationId(provider)).isNull();
            environment.setProperty(prefix + "_CLIENT_SECRET", "synthetic-secret");
            var registration = configuration.providerRegistrations(environment, issuer).findByRegistrationId(provider);
            assertThat(registration.getRedirectUri()).isEqualTo(
                    "https://identity.fixture.test/login/oauth2/code/" + provider);
            assertThat(registration.getProviderDetails().getAuthorizationUri()).startsWith("https://");
            assertThat(registration.getProviderDetails().getTokenUri()).startsWith("https://");
        }
    }
}
