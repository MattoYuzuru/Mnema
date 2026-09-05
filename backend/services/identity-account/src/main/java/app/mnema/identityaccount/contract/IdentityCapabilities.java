package app.mnema.identityaccount.contract;

import app.mnema.identityaccount.avatar.AvatarStorage;
import app.mnema.identityaccount.mail.PostboxMail;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Configuration availability is reported separately from runtime readiness or delivery evidence.
 */
@Component
public class IdentityCapabilities implements InfoContributor {
    private final PostboxMail mail;
    private final AvatarStorage avatars;
    private final ClientRegistrationRepository providers;

    public IdentityCapabilities(PostboxMail mail, AvatarStorage avatars, ClientRegistrationRepository providers) {
        this.mail = mail;
        this.avatars = avatars;
        this.providers = providers;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("accountCapabilities", Map.of(
                "emailDeliveryConfigured", mail.configured(),
                "avatarStorageConfigured", avatars.configured(),
                "configuredProviders", List.of("google", "github", "yandex").stream()
                        .filter(provider -> providers.findByRegistrationId(provider) != null).toList()
        ));
    }
}
