package app.mnema.identityaccount.transfer;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.federation.FederatedAccounts;
import app.mnema.identityaccount.local.LocalAccounts;
import app.mnema.identityaccount.mail.PostboxMail;
import app.mnema.identityaccount.recovery.PasswordRecovery;
import app.mnema.identityaccount.security.OwnershipProofs;
import app.mnema.identityaccount.security.RateLimits;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = false)
class AccountTransferIntegrationTest {
    private static final UUID MODERATOR = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID STUDENT = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID BANNED = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID AVATAR = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    private static final String PASSWORD = "correct horse battery staple";
    private static final String TOKEN = "forbidden-access-token-value";

    @Container
    private static final PostgreSQLContainer<?> SOURCE = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("legacy").withUsername("mnema").withPassword("mnema");

    @Container
    private static final PostgreSQLContainer<?> TARGET = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"))
            .withDatabaseName("fresh").withUsername("mnema").withPassword("mnema");

    @TempDir
    Path temporary;

    private DataSource source;
    private DataSource target;
    private Path sourceAvatars;
    private Path targetAvatars;

    @BeforeEach
    void prepare() throws IOException {
        source = dataSource(SOURCE);
        target = dataSource(TARGET);
        JdbcTemplate sourceJdbc = new JdbcTemplate(source);
        sourceJdbc.execute("DROP SCHEMA IF EXISTS auth CASCADE");
        sourceJdbc.execute("DROP SCHEMA IF EXISTS app_user CASCADE");
        sourceJdbc.execute("DROP SCHEMA IF EXISTS app_media CASCADE");
        sourceJdbc.execute(legacySchema());
        Flyway flyway = Flyway.configure().dataSource(target).schemas("app_identity").defaultSchema("app_identity")
                .locations("classpath:db/identity/migration").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        sourceAvatars = Files.createDirectory(temporary.resolve("source-avatars"));
        targetAvatars = Files.createDirectory(temporary.resolve("target-avatars"));
        Path sourceObject = sourceAvatars.resolve("legacy/student/avatar.png");
        Files.createDirectories(sourceObject.getParent());
        Files.write(sourceObject, PNG);
        seedLegacy(sourceJdbc);
    }

    @Test
    void exportsImportsTwiceAndSmokesRestoredAccountCapabilities() throws IOException {
        var codec = codec();
        var exported = new LegacyAccountExporter(source, new DirectoryAvatarBlobStore(sourceAvatars)).export();
        Path artifactPath = temporary.resolve("accounts.zip");
        codec.write(artifactPath, exported);
        assertThat(new String(Files.readAllBytes(artifactPath), StandardCharsets.ISO_8859_1))
                .doesNotContain("student@example.test", PASSWORD, TOKEN);
        AccountTransferArtifact artifact = codec.read(artifactPath);

        assertThat(artifact.bundle().accounts()).hasSize(3);
        assertThat(artifact.bundle().accounts().stream().mapToInt(account -> account.externalIdentities().size()).sum())
                .isEqualTo(1);
        assertThat(artifact.bundle().accounts()).allSatisfy(account ->
                assertThat(account.toString()).doesNotContain(TOKEN));

        var importer = new AccountTransferImporter(target, new DirectoryAvatarBlobStore(targetAvatars), codec);
        AccountTransferEvidence first = importer.importAndReconcile(artifact);
        AccountTransferEvidence repeated = importer.importAndReconcile(codec.read(artifactPath));

        assertThat(repeated).isEqualTo(first);
        assertThat(first.accountCount()).isEqualTo(3);
        assertThat(first.credentialCount()).isEqualTo(1);
        assertThat(first.externalIdentityCount()).isEqualTo(1);
        assertThat(first.avatarCount()).isEqualTo(1);
        String evidence = new String(codec.evidence(first), StandardCharsets.UTF_8);
        assertThat(evidence).doesNotContain(PASSWORD, "student@example.test", "github-subject", TOKEN, "$2a$");

        JdbcClient jdbc = JdbcClient.create(target);
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.spring_session").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.oauth2_authorization").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT account_id FROM app_identity.external_identity WHERE provider='github' AND provider_subject='github-subject'")
                .query(UUID.class).single()).isEqualTo(STUDENT);
        AccountStore accounts = new AccountStore(jdbc);
        assertThat(accounts.get(STUDENT, false).profileUsername()).isEqualTo("student-profile");
        assertThat(accounts.get(BANNED, false).status()).isEqualTo("BANNED");
        assertThat(Files.exists(targetAvatars.resolve("account-avatar/" + STUDENT + "/" + AVATAR))).isTrue();

        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(target));
        BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);
        Clock clock = Clock.systemUTC();
        RateLimits limits = new RateLimits(jdbc, clock);
        LocalAccounts local = new LocalAccounts(jdbc, accounts, transactions, passwords, limits, clock);
        assertThat(local.login("student-login", PASSWORD, "192.0.2.1").accountId()).isEqualTo(STUDENT);

        OwnershipProofs proofs = new OwnershipProofs(jdbc, accounts, clock);
        FederatedAccounts federation = new FederatedAccounts(jdbc, accounts, proofs, transactions);
        assertThat(federation.complete(new FederatedAccounts.External(
                "github", "github-subject", "ignored@example.test", false), null).accountId()).isEqualTo(STUDENT);

        PasswordRecovery recovery = new PasswordRecovery(jdbc, accounts, proofs, local, limits,
                new PostboxMail("", "", URI.create("https://postbox.cloud.yandex.net/v2/email/outbound-emails"),
                        false, new ObjectMapper()), transactions, "https://mnema.app");
        OwnershipProofs.Proof reset = transactions.execute(status ->
                proofs.issue(accounts.get(STUDENT, true).access(), OwnershipProofs.Purpose.RESET_PASSWORD));
        recovery.confirm(reset.token(), "replacement password value");
        assertThat(local.login("student@example.test", "replacement password value", "192.0.2.2").accountId())
                .isEqualTo(STUDENT);
    }

    @Test
    void failsClosedForUnknownMissingAndForbiddenArtifactFields() throws Exception {
        AccountTransferCodec codec = codec();
        Path unknown = archive("unknown.zip", """
                {"schemaVersion":1,"kind":"mnema-account-transfer","accounts":[],"accessToken":"secret"}
                """);
        Path missing = archive("missing.zip", """
                {"schemaVersion":1,"kind":"mnema-account-transfer"}
                """);
        Path duplicate = archive("duplicate.zip", """
                {"schemaVersion":1,"kind":"mnema-account-transfer","kind":"mnema-account-transfer","accounts":[]}
                """);

        assertThatThrownBy(() -> codec.read(unknown)).isInstanceOf(AccountTransferFailure.class);
        assertThatThrownBy(() -> codec.read(missing)).isInstanceOf(AccountTransferFailure.class);
        assertThatThrownBy(() -> codec.read(duplicate)).isInstanceOf(AccountTransferFailure.class);
    }

    @Test
    void requiresExactRehearsalEnvironmentAndExplicitDisposableTarget() {
        assertThatThrownBy(() -> AccountTransferCli.execute(new String[0],
                Map.of("MNEMA_ACCOUNT_TRANSFER_DISPOSABLE_TARGET", "true")))
                .isInstanceOf(AccountTransferFailure.class).hasMessage("rehearsal_execution_required");
        for (String appEnvironment : new String[]{"dev", "staging", "prod", "production"}) {
            assertThatThrownBy(() -> AccountTransferCli.execute(new String[0],
                    Map.of("APP_ENV", appEnvironment, "MNEMA_ACCOUNT_TRANSFER_DISPOSABLE_TARGET", "true")))
                    .isInstanceOf(AccountTransferFailure.class).hasMessage("rehearsal_execution_required");
        }
        assertThatThrownBy(() -> AccountTransferCli.execute(new String[]{"export", "--artifact=x"}, Map.of()))
                .isInstanceOf(AccountTransferFailure.class).hasMessage("disposable_target_confirmation_required");
        assertThatThrownBy(() -> AccountTransferCli.execute(new String[0],
                Map.of("APP_ENV", " ReHeArSaL ", "MNEMA_ACCOUNT_TRANSFER_DISPOSABLE_TARGET", "true")))
                .isInstanceOf(AccountTransferFailure.class).hasMessage("invalid_arguments");
    }

    @Test
    void commandLineBoundaryExportsImportsAndReconcilesWithoutSecretsInArguments() {
        Path artifact = temporary.resolve("cli-accounts.enc");
        Path importedEvidence = temporary.resolve("cli-import-evidence.json");
        Path reconciledEvidence = temporary.resolve("cli-reconcile-evidence.json");
        Map<String, String> environment = new HashMap<>();
        environment.put("APP_ENV", "rehearsal");
        environment.put("MNEMA_ACCOUNT_TRANSFER_DISPOSABLE_TARGET", "true");
        environment.put("MNEMA_ACCOUNT_TRANSFER_ENCRYPTION_KEY_B64",
                Base64.getEncoder().encodeToString(new byte[32]));
        environment.put("MNEMA_ACCOUNT_TRANSFER_SOURCE_URL", SOURCE.getJdbcUrl());
        environment.put("MNEMA_ACCOUNT_TRANSFER_SOURCE_USERNAME", SOURCE.getUsername());
        environment.put("MNEMA_ACCOUNT_TRANSFER_SOURCE_PASSWORD", SOURCE.getPassword());
        environment.put("MNEMA_ACCOUNT_TRANSFER_SOURCE_AVATAR_ROOT", sourceAvatars.toString());
        environment.put("MNEMA_ACCOUNT_TRANSFER_TARGET_URL", TARGET.getJdbcUrl());
        environment.put("MNEMA_ACCOUNT_TRANSFER_TARGET_USERNAME", TARGET.getUsername());
        environment.put("MNEMA_ACCOUNT_TRANSFER_TARGET_PASSWORD", TARGET.getPassword());
        environment.put("MNEMA_ACCOUNT_TRANSFER_TARGET_AVATAR_ROOT", targetAvatars.toString());

        AccountTransferCli.execute(new String[]{"export", "--artifact=" + artifact}, environment);
        AccountTransferCli.execute(new String[]{"import", "--artifact=" + artifact,
                "--evidence=" + importedEvidence}, environment);
        AccountTransferCli.execute(new String[]{"reconcile", "--artifact=" + artifact,
                "--evidence=" + reconciledEvidence}, environment);

        assertThat(importedEvidence).hasSameTextualContentAs(reconciledEvidence);
        assertThatThrownBy(() -> AccountTransferCli.execute(
                new String[]{"erase", "--artifact=" + artifact}, environment))
                .isInstanceOf(AccountTransferFailure.class).hasMessage("invalid_operation");
        assertThatThrownBy(() -> AccountTransferCli.execute(
                new String[]{"export", "--unknown=" + artifact}, environment))
                .isInstanceOf(AccountTransferFailure.class).hasMessage("invalid_arguments");
    }

    private void seedLegacy(JdbcTemplate jdbc) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        jdbc.update("""
                        INSERT INTO auth.users(id,email,email_verified,name,created_at,last_login_at,username,password_hash)
                        VALUES (?,?,true,'Moderator','2026-01-01T00:00:00Z',NULL,NULL,NULL),
                               (?,?,true,'Student','2026-01-02T00:00:00Z','2026-02-02T00:00:00Z','student-login',?),
                               (?,?,true,'Banned','2026-01-03T00:00:00Z',NULL,NULL,NULL)
                        """, MODERATOR, "moderator@example.test", STUDENT, "student@example.test", encoder.encode(PASSWORD),
                BANNED, "banned@example.test");
        jdbc.update("""
                        INSERT INTO app_user.users(
                            id,email,username,bio,is_admin,created_at,updated_at,avatar_media_id,
                            admin_granted_by,admin_granted_at,banned_by,banned_at,ban_reason)
                        VALUES (?,?,?,'moderates',true,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z',NULL,NULL,NULL,NULL,NULL,NULL),
                               (?,?,?,'learns',false,'2026-01-02T00:00:00Z','2026-01-02T00:00:00Z',?,NULL,NULL,NULL,NULL,NULL),
                               (?,?,?,'blocked',false,'2026-01-03T00:00:00Z','2026-01-03T00:00:00Z',NULL,NULL,NULL,?,'2026-03-01T00:00:00Z','abuse')
                        """, MODERATOR, "moderator@example.test", "moderator-profile",
                STUDENT, "student@example.test", "student-profile", AVATAR,
                BANNED, "banned@example.test", "banned-profile", MODERATOR);
        jdbc.update("""
                        INSERT INTO auth.accounts(id,provider,provider_sub,created_at,last_login_at,user_id)
                        VALUES ('30000000-0000-4000-8000-000000000001','GitHub','github-subject',
                                '2026-01-04T00:00:00Z','2026-02-04T00:00:00Z',?)
                        """, STUDENT);
        jdbc.update("""
                        INSERT INTO app_media.media_assets(
                            media_id,owner_user_id,kind,status,storage_key,mime_type,size_bytes,width,height,created_at)
                        VALUES (?,?,'avatar','ready','legacy/student/avatar.png','image/png',?,1,1,'2026-01-05T00:00:00Z')
                        """, AVATAR, STUDENT, PNG.length);
        jdbc.update("INSERT INTO auth.oauth2_authorization(id,access_token_value) VALUES ('grant-1',?)",
                TOKEN.getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO auth.oauth2_authorization_consent(registered_client_id,principal_name,authorities) VALUES ('client','student','scope')");
        jdbc.update("INSERT INTO auth.spring_session(primary_id,attribute_bytes) VALUES ('session',?)", TOKEN.getBytes(StandardCharsets.UTF_8));
    }

    private Path archive(String name, String json) throws Exception {
        Path path = temporary.resolve(name);
        byte[] magic = "MNEMA-ACCOUNT-XFER-1\n".getBytes(StandardCharsets.US_ASCII);
        byte[] nonce = new byte[12];
        var raw = Files.newOutputStream(path);
        raw.write(magic);
        raw.write(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(new byte[32], "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(magic);
        cipher.updateAAD(nonce);
        try (CipherOutputStream encrypted = new CipherOutputStream(raw, cipher);
             ZipOutputStream zip = new ZipOutputStream(encrypted)) {
            zip.putNextEntry(new ZipEntry("accounts.json"));
            zip.write(json.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }

    private static AccountTransferCodec codec() {
        return new AccountTransferCodec(new byte[32]);
    }

    private static DataSource dataSource(PostgreSQLContainer<?> postgres) {
        return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static String legacySchema() {
        return """
                CREATE SCHEMA auth;
                CREATE SCHEMA app_user;
                CREATE SCHEMA app_media;
                CREATE TYPE app_media.media_kind AS ENUM ('avatar','attachment');
                CREATE TYPE app_media.media_status AS ENUM ('pending','ready','failed');
                CREATE TABLE auth.users(
                    id uuid PRIMARY KEY,email text NOT NULL,email_verified boolean NOT NULL,name text,
                    created_at timestamptz NOT NULL,last_login_at timestamptz,username text,password_hash text);
                CREATE TABLE auth.accounts(
                    id uuid PRIMARY KEY,provider text NOT NULL,provider_sub text NOT NULL,created_at timestamptz NOT NULL,
                    last_login_at timestamptz,user_id uuid NOT NULL);
                CREATE TABLE auth.oauth2_authorization(id text PRIMARY KEY,access_token_value bytea);
                CREATE TABLE auth.oauth2_authorization_consent(
                    registered_client_id text,principal_name text,authorities text);
                CREATE TABLE auth.spring_session(primary_id text PRIMARY KEY,attribute_bytes bytea);
                CREATE TABLE app_user.users(
                    id uuid PRIMARY KEY,email text NOT NULL,username text NOT NULL,bio text,is_admin boolean NOT NULL,
                    created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,avatar_media_id uuid,
                    admin_granted_by uuid,admin_granted_at timestamptz,banned_by uuid,banned_at timestamptz,
                    ban_reason varchar(280));
                CREATE TABLE app_media.media_assets(
                    media_id uuid PRIMARY KEY,owner_user_id uuid NOT NULL,kind app_media.media_kind NOT NULL,
                    status app_media.media_status NOT NULL,storage_key text NOT NULL,mime_type text NOT NULL,
                    size_bytes bigint,width int,height int,created_at timestamptz NOT NULL,deleted_at timestamptz);
                """;
    }
}
