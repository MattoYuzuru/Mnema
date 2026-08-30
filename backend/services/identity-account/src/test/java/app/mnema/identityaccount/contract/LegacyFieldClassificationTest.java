package app.mnema.identityaccount.contract;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyFieldClassificationTest {

    private static final Pattern CLASSIFICATION_ROW = Pattern.compile(
            "^\\| `([^`]+)` \\| `([^`]+)` \\| (PRESERVE|RECREATE|DELETE) \\|.*$"
    );

    private static final Set<String> EXPECTED_SOURCE_FIELDS = Set.of(
            "auth.accounts.id",
            "auth.accounts.provider",
            "auth.accounts.provider_sub",
            "auth.accounts.email",
            "auth.accounts.email_verified",
            "auth.accounts.name",
            "auth.accounts.picture_url",
            "auth.accounts.created_at",
            "auth.accounts.last_login_at",
            "auth.accounts.user_id",
            "auth.users.id",
            "auth.users.email",
            "auth.users.email_verified",
            "auth.users.name",
            "auth.users.picture_url",
            "auth.users.created_at",
            "auth.users.last_login_at",
            "auth.users.username",
            "auth.users.password_hash",
            "auth.users.failed_login_attempts",
            "auth.users.locked_until",
            "auth.oauth2_registered_client.id",
            "auth.oauth2_registered_client.client_id",
            "auth.oauth2_registered_client.client_id_issued_at",
            "auth.oauth2_registered_client.client_secret",
            "auth.oauth2_registered_client.client_secret_expires_at",
            "auth.oauth2_registered_client.client_name",
            "auth.oauth2_registered_client.client_authentication_methods",
            "auth.oauth2_registered_client.authorization_grant_types",
            "auth.oauth2_registered_client.redirect_uris",
            "auth.oauth2_registered_client.post_logout_redirect_uris",
            "auth.oauth2_registered_client.scopes",
            "auth.oauth2_registered_client.client_settings",
            "auth.oauth2_registered_client.token_settings",
            "auth.oauth2_authorization.id",
            "auth.oauth2_authorization.registered_client_id",
            "auth.oauth2_authorization.principal_name",
            "auth.oauth2_authorization.authorization_grant_type",
            "auth.oauth2_authorization.authorized_scopes",
            "auth.oauth2_authorization.attributes",
            "auth.oauth2_authorization.state",
            "auth.oauth2_authorization.authorization_code_value",
            "auth.oauth2_authorization.authorization_code_issued_at",
            "auth.oauth2_authorization.authorization_code_expires_at",
            "auth.oauth2_authorization.authorization_code_metadata",
            "auth.oauth2_authorization.access_token_value",
            "auth.oauth2_authorization.access_token_issued_at",
            "auth.oauth2_authorization.access_token_expires_at",
            "auth.oauth2_authorization.access_token_metadata",
            "auth.oauth2_authorization.access_token_type",
            "auth.oauth2_authorization.access_token_scopes",
            "auth.oauth2_authorization.oidc_id_token_value",
            "auth.oauth2_authorization.oidc_id_token_issued_at",
            "auth.oauth2_authorization.oidc_id_token_expires_at",
            "auth.oauth2_authorization.oidc_id_token_metadata",
            "auth.oauth2_authorization.refresh_token_value",
            "auth.oauth2_authorization.refresh_token_issued_at",
            "auth.oauth2_authorization.refresh_token_expires_at",
            "auth.oauth2_authorization.refresh_token_metadata",
            "auth.oauth2_authorization_consent.registered_client_id",
            "auth.oauth2_authorization_consent.principal_name",
            "auth.oauth2_authorization_consent.authorities",
            "app_user.users.id",
            "app_user.users.email",
            "app_user.users.username",
            "app_user.users.bio",
            "app_user.users.is_admin",
            "app_user.users.avatar_url",
            "app_user.users.created_at",
            "app_user.users.updated_at",
            "app_user.users.avatar_media_id",
            "app_user.users.admin_granted_by",
            "app_user.users.admin_granted_at",
            "app_user.users.banned_by",
            "app_user.users.banned_at",
            "app_user.users.ban_reason"
    );

    @Test
    void classifiesEveryCurrentAuthAndUserSourceFieldExactlyOnce() throws Exception {
        Set<String> classified = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        Map<String, String> classifications = new LinkedHashMap<>();

        for (String line : Files.readAllLines(projectDirectory().resolve("legacy-field-classification.md"))) {
            var matcher = CLASSIFICATION_ROW.matcher(line);
            if (matcher.matches()) {
                String field = matcher.group(1) + "." + matcher.group(2);
                if (!classified.add(field)) {
                    duplicates.add(field);
                }
                classifications.put(field, matcher.group(3));
            }
        }

        assertThat(duplicates).isEmpty();
        assertThat(classified).containsExactlyInAnyOrderElementsOf(EXPECTED_SOURCE_FIELDS);
        assertThat(classifications)
                .containsEntry("auth.users.id", "PRESERVE")
                .containsEntry("auth.users.password_hash", "PRESERVE")
                .containsEntry("auth.users.username", "PRESERVE")
                .containsEntry("app_user.users.username", "PRESERVE")
                .containsEntry("app_user.users.avatar_media_id", "PRESERVE")
                .containsEntry("auth.accounts.provider", "PRESERVE")
                .containsEntry("auth.accounts.provider_sub", "PRESERVE")
                .containsEntry("auth.accounts.email", "DELETE")
                .containsEntry("auth.accounts.picture_url", "DELETE")
                .containsEntry("auth.users.picture_url", "DELETE")
                .containsEntry("app_user.users.avatar_url", "DELETE")
                .containsEntry("auth.users.failed_login_attempts", "RECREATE")
                .containsEntry("auth.users.locked_until", "RECREATE");
    }

    private static Path projectDirectory() {
        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        if (Files.isDirectory(workingDirectory.resolve("src/main"))) {
            return workingDirectory;
        }
        return workingDirectory.resolve("services/identity-account");
    }
}
