package app.mnema.identityaccount.federation;

import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Public availability only: client credentials and provider tokens never leave Identity. */
@RestController
public class LoginProviders {
    private final ClientRegistrationRepository registrations;

    public LoginProviders(ClientRegistrationRepository registrations) {
        this.registrations = registrations;
    }

    @GetMapping("/api/accounts/providers")
    Map<String, List<String>> available() {
        return Map.of("providers", List.of("google", "yandex", "github").stream()
                .filter(provider -> registrations.findByRegistrationId(provider) != null).toList());
    }
}
