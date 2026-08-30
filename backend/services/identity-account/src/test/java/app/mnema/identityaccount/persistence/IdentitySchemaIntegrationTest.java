package app.mnema.identityaccount.persistence;

import app.mnema.identityaccount.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class IdentitySchemaIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void normalizedEmailAndUsernameDuplicatesFailWithNamedConstraints() {
        insertAccount(" User@Example.com ", "MnemaUser");

        assertConstraintViolation(
                () -> insertAccount("user@example.com", "another-user"),
                "uq_account_normalized_email"
        );
        assertConstraintViolation(
                () -> insertAccount("different@example.com", " mnemauser "),
                "uq_account_normalized_username"
        );
    }

    @Test
    void providerIsNormalizedWhileOpaqueProviderSubjectIsCaseSensitiveAndUnique() {
        UUID first = insertAccount(randomEmail(), null);
        UUID second = insertAccount(randomEmail(), null);
        insertIdentity(first, "github", "Subject-42");
        insertIdentity(second, "github", "subject-42");

        assertConstraintViolation(
                () -> insertIdentity(second, "github", "Subject-42"),
                "uq_external_identity_provider_subject"
        );
        assertConstraintViolation(
                () -> insertIdentity(second, "GitHub", "different"),
                "ck_external_identity_provider"
        );
    }

    @Test
    void localCredentialAndFederatedIdentityAreOwnedByOneAccount() {
        UUID accountId = insertAccount(randomEmail(), null);
        jdbcClient.sql("""
                        INSERT INTO app_identity.local_credential(account_id, password_hash)
                        VALUES (:accountId, :passwordHash)
                        """)
                .param("accountId", accountId)
                .param("passwordHash", "$2a$12$opaque-existing-password-hash")
                .update();
        insertIdentity(accountId, "google", "openid-subject");

        assertConstraintViolation(
                () -> jdbcClient.sql("""
                                INSERT INTO app_identity.local_credential(account_id, password_hash)
                                VALUES (:accountId, :passwordHash)
                                """)
                        .param("accountId", accountId)
                        .param("passwordHash", "second-hash")
                        .update(),
                "local_credential_pkey"
        );

        jdbcClient.sql("DELETE FROM app_identity.account WHERE account_id = :accountId")
                .param("accountId", accountId)
                .update();
        assertThat(count("local_credential", accountId)).isZero();
        assertThat(count("external_identity", accountId)).isZero();
    }

    @Test
    void accountAvatarOwnsEitherCompleteAssetMetadataOrSourceUrl() {
        UUID first = insertAccount(randomEmail(), null);
        UUID second = insertAccount(randomEmail(), null);
        UUID assetId = UUID.randomUUID();
        insertOwnedAvatar(first, assetId, "account-avatar/first/source");

        assertConstraintViolation(
                () -> insertOwnedAvatar(second, assetId, "account-avatar/second/source"),
                "account_avatar_asset_id_key"
        );
        assertConstraintViolation(
                () -> jdbcClient.sql("""
                                INSERT INTO app_identity.account_avatar(account_id, asset_id)
                                VALUES (:accountId, :assetId)
                                """)
                        .param("accountId", second)
                        .param("assetId", UUID.randomUUID())
                        .update(),
                "ck_account_avatar_asset_metadata"
        );

        jdbcClient.sql("""
                        INSERT INTO app_identity.account_avatar(account_id, source_url)
                        VALUES (:accountId, :sourceUrl)
                        """)
                .param("accountId", second)
                .param("sourceUrl", "https://profiles.example/avatar.png")
                .update();
    }

    @Test
    void accountStatusAndModerationMetadataAreConstrained() {
        UUID accountId = insertAccount(randomEmail(), null);

        assertConstraintViolation(
                () -> jdbcClient.sql("""
                                UPDATE app_identity.account
                                SET status = 'SUSPENDED'
                                WHERE account_id = :accountId
                                """)
                        .param("accountId", accountId)
                        .update(),
                "ck_account_suspension_metadata"
        );
        assertConstraintViolation(
                () -> jdbcClient.sql("""
                                UPDATE app_identity.account
                                SET admin_granted_at = statement_timestamp()
                                WHERE account_id = :accountId
                                """)
                        .param("accountId", accountId)
                        .update(),
                "ck_account_admin_metadata"
        );
    }

    @Test
    void accountIdsRejectNilAndUnknownUuidVersions() {
        assertConstraintViolation(
                () -> jdbcClient.sql("""
                                INSERT INTO app_identity.account(account_id, email)
                                VALUES ('00000000-0000-0000-0000-000000000000', :email)
                                """)
                        .param("email", randomEmail())
                        .update(),
                "ck_account_id"
        );
        assertConstraintViolation(
                () -> jdbcClient.sql("""
                                INSERT INTO app_identity.account(account_id, email)
                                VALUES ('018f6b77-c4d8-0a2e-8ca2-0242ac120002', :email)
                                """)
                        .param("email", randomEmail())
                        .update(),
                "ck_account_id"
        );
    }

    private UUID insertAccount(String email, String username) {
        UUID accountId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO app_identity.account(account_id, email, username)
                        VALUES (:accountId, :email, :username)
                        """)
                .param("accountId", accountId)
                .param("email", email)
                .param("username", username)
                .update();
        return accountId;
    }

    private void insertIdentity(UUID accountId, String provider, String subject) {
        jdbcClient.sql("""
                        INSERT INTO app_identity.external_identity(account_id, provider, provider_subject)
                        VALUES (:accountId, :provider, :subject)
                        """)
                .param("accountId", accountId)
                .param("provider", provider)
                .param("subject", subject)
                .update();
    }

    private void insertOwnedAvatar(UUID accountId, UUID assetId, String storageKey) {
        jdbcClient.sql("""
                        INSERT INTO app_identity.account_avatar(
                            account_id, asset_id, storage_key, content_type, byte_size, content_sha256
                        ) VALUES (
                            :accountId, :assetId, :storageKey, 'image/png', 128, decode(repeat('ab', 32), 'hex')
                        )
                        """)
                .param("accountId", accountId)
                .param("assetId", assetId)
                .param("storageKey", storageKey)
                .update();
    }

    private long count(String table, UUID accountId) {
        return jdbcClient.sql("SELECT count(*) FROM app_identity." + table + " WHERE account_id = :accountId")
                .param("accountId", accountId)
                .query(Long.class)
                .single();
    }

    private void assertConstraintViolation(Runnable action, String expectedConstraint) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class, error -> {
                    assertThat(error.getSQLState()).isIn("23505", "23514", "23503");
                    assertThat(error.getMessage()).contains(expectedConstraint);
                });
    }

    private String randomEmail() {
        return UUID.randomUUID() + "@example.com";
    }
}
