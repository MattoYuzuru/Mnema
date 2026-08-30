package app.mnema.identityaccount.persistence;

import app.mnema.identityaccount.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class IdentitySchemaIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private DataSource dataSource;

    @Test
    void normalizedEmailAndUsernameDuplicatesFailWithNamedConstraints() {
        insertAccount(" User@Example.com ", "MnemaUser");

        assertConstraintViolation(
                () -> insertAccount("user@example.com", "another-user"),
                "uq_account_normalized_email"
        );
        assertConstraintViolation(
                () -> insertAccount("different@example.com", " mnemauser "),
                "uq_account_normalized_profile_username"
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
        UUID accountId = insertAccount(randomEmail(), "PublicHandle");
        insertCredential(accountId, "LocalLogin", "$2a$12$opaque-existing-password-hash");
        insertIdentity(accountId, "google", "openid-subject");

        UUID secondAccountId = insertAccount(randomEmail(), "LocalLogin");
        assertConstraintViolation(
                () -> insertCredential(secondAccountId, " localLogin ", "another-opaque-hash"),
                "uq_local_credential_normalized_login"
        );
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
    void accountAvatarRequiresCompleteBoundedOwnedAssetMetadata() {
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
                "storage_key"
        );
        assertConstraintViolation(
                () -> jdbcClient.sql("""
                                INSERT INTO app_identity.account_avatar(
                                    account_id, asset_id, storage_key, content_type,
                                    byte_size, content_sha256, created_at
                                ) VALUES (
                                    :accountId, :assetId, 'account-avatar/oversized/source',
                                    'image/gif', 128, decode(repeat('ab', 32), 'hex'), statement_timestamp()
                                )
                                """)
                        .param("accountId", second)
                        .param("assetId", UUID.randomUUID())
                        .update(),
                "ck_account_avatar_content_type"
        );
    }

    @Test
    void accountStatusAndModerationMetadataAreConstrained() {
        UUID accountId = insertAccount(randomEmail(), null);

        assertConstraintViolation(() -> updateAccount(
                accountId, "status = 'BANNED'"
        ), "ck_account_ban_metadata");
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
        assertConstraintViolation(() -> updateAccount(
                accountId, "banned_by = account_id, banned_at = statement_timestamp(), status = 'BANNED'"
        ), "ck_account_moderation_actors");
    }

    @Test
    void concurrentNormalizedEmailAndProviderSubjectCollisionsHaveOneWinner() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        assertConcurrentConstraintViolation(
                connection -> insertAccount(connection, UUID.randomUUID(), " " + email.toUpperCase() + " "),
                connection -> insertAccount(connection, UUID.randomUUID(), email),
                "uq_account_normalized_email"
        );

        UUID firstAccount = insertAccount(randomEmail(), null);
        UUID secondAccount = insertAccount(randomEmail(), null);
        String subject = "race-" + UUID.randomUUID();
        assertConcurrentConstraintViolation(
                connection -> insertIdentity(connection, firstAccount, "github", subject),
                connection -> insertIdentity(connection, secondAccount, "github", subject),
                "uq_external_identity_provider_subject"
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
                        INSERT INTO app_identity.account(account_id, email, profile_username)
                        VALUES (:accountId, :email, :profileUsername)
                        """)
                .param("accountId", accountId)
                .param("email", email)
                .param("profileUsername", username)
                .update();
        return accountId;
    }

    private void insertAccount(Connection connection, UUID accountId, String email) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO app_identity.account(account_id, email)
                VALUES (?, ?)
                """)) {
            statement.setObject(1, accountId);
            statement.setString(2, email);
            statement.executeUpdate();
        }
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

    private void insertIdentity(Connection connection, UUID accountId, String provider, String subject)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO app_identity.external_identity(account_id, provider, provider_subject)
                VALUES (?, ?, ?)
                """)) {
            statement.setObject(1, accountId);
            statement.setString(2, provider);
            statement.setString(3, subject);
            statement.executeUpdate();
        }
    }

    private void insertCredential(UUID accountId, String loginName, String passwordHash) {
        jdbcClient.sql("""
                        INSERT INTO app_identity.local_credential(account_id, login_name, password_hash)
                        VALUES (:accountId, :loginName, :passwordHash)
                        """)
                .param("accountId", accountId)
                .param("loginName", loginName)
                .param("passwordHash", passwordHash)
                .update();
    }

    private void insertOwnedAvatar(UUID accountId, UUID assetId, String storageKey) {
        jdbcClient.sql("""
                        INSERT INTO app_identity.account_avatar(
                            account_id, asset_id, storage_key, content_type, byte_size,
                            content_sha256, width, height, created_at
                        ) VALUES (
                            :accountId, :assetId, :storageKey, 'image/png', 128,
                            decode(repeat('ab', 32), 'hex'), 128, 128, statement_timestamp()
                        )
                        """)
                .param("accountId", accountId)
                .param("assetId", assetId)
                .param("storageKey", storageKey)
                .update();
    }

    private void updateAccount(UUID accountId, String assignment) {
        jdbcClient.sql("UPDATE app_identity.account SET " + assignment + " WHERE account_id = :accountId")
                .param("accountId", accountId)
                .update();
    }

    private void assertConcurrentConstraintViolation(
            SqlWrite winner,
            SqlWrite contender,
            String expectedConstraint
    ) throws Exception {
        try (Connection winnerConnection = dataSource.getConnection();
             Connection contenderConnection = dataSource.getConnection()) {
            winnerConnection.setAutoCommit(false);
            contenderConnection.setAutoCommit(false);
            winner.execute(winnerConnection);

            CountDownLatch attempted = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                java.util.concurrent.Future<SQLException> conflict = executor.submit(() -> {
                    attempted.countDown();
                    try {
                        contender.execute(contenderConnection);
                        contenderConnection.commit();
                        return null;
                    } catch (SQLException error) {
                        contenderConnection.rollback();
                        return error;
                    }
                });

                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                winnerConnection.commit();
                SQLException error = conflict.get(5, TimeUnit.SECONDS);
                assertThat((Throwable) error).isNotNull();
                assertSqlConstraint(error, expectedConstraint);
            }
        }
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
                    assertThat(error.getSQLState()).isIn("23502", "23503", "23505", "23514");
                    assertThat(error.getMessage()).contains(expectedConstraint);
                });
    }

    private void assertSqlConstraint(SQLException error, String expectedConstraint) {
        assertThat(error.getSQLState()).isEqualTo("23505");
        assertThat(error.getMessage()).contains(expectedConstraint);
    }

    private String randomEmail() {
        return UUID.randomUUID() + "@example.com";
    }

    @FunctionalInterface
    private interface SqlWrite {
        void execute(Connection connection) throws SQLException;
    }
}
