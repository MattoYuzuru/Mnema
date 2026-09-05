package app.mnema.identityaccount.account;

import app.mnema.identityaccount.contract.*;
import app.mnema.identityaccount.local.LocalAccounts;
import app.mnema.identityaccount.federation.*;
import app.mnema.identityaccount.security.*;
import app.mnema.identityaccount.recovery.PasswordRecovery;
import app.mnema.identityaccount.profile.Profiles;
import app.mnema.identityaccount.moderation.Moderation;
import app.mnema.identityaccount.avatar.*;
import app.mnema.identityaccount.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class AccountBehaviorIntegrationTest extends PostgresIntegrationTest {
    static final HttpServer SERVER;
    static final Map<String, byte[]> OBJECTS = new ConcurrentHashMap<>();
    static final List<Map<String, List<String>>> MAIL_HEADERS = new CopyOnWriteArrayList<>();
    static volatile String delivered;
    static volatile boolean failMail, failPut, failDelete, timeoutMail;
    static volatile Runnable afterPut = () -> {
    };

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            SERVER.createContext("/", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                String path = exchange.getRequestURI().getPath();
                int status = 200;
                byte[] response = new byte[0];
                if (path.equals("/mail")) {
                    if (timeoutMail) {
                        try { Thread.sleep(6000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                        exchange.sendResponseHeaders(200, -1);
                        exchange.close();
                        return;
                    }
                    MAIL_HEADERS.add(new HashMap<>(exchange.getRequestHeaders()));
                    if (failMail) status = 503;
                    else delivered = new String(body, StandardCharsets.UTF_8);
                } else switch (exchange.getRequestMethod()) {
                    case "PUT" -> {
                        if (failPut) status = 503;
                        else {
                            if ("aws-chunked".equals(exchange.getRequestHeaders().getFirst("Content-Encoding")))
                                body = decodeChunks(body);
                            OBJECTS.put(path, body);
                            afterPut.run();
                            exchange.getResponseHeaders().set("ETag", "\"synthetic-etag\"");
                        }
                    }
                    case "GET" -> {
                        response = OBJECTS.get(path);
                        if (response == null) {
                            status = 404;
                            response = "<Error><Code>NoSuchKey</Code></Error>".getBytes(StandardCharsets.UTF_8);
                        }
                    }
                    case "DELETE" -> {
                        if (failDelete) status = 503;
                        else {
                            OBJECTS.remove(path);
                            status = 204;
                        }
                    }
                    default -> status = 400;
                }
                exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            SERVER.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static byte[] decodeChunks(byte[] encoded) throws IOException {
        var source = new ByteArrayInputStream(encoded);
        var output = new ByteArrayOutputStream();
        while (true) {
            var line = new StringBuilder();
            int b;
            while ((b = source.read()) != -1 && b != '\n') if (b != '\r') line.append((char) b);
            int length = Integer.parseInt(line.toString().split(";")[0], 16);
            if (length == 0) break;
            output.write(source.readNBytes(length));
            source.readNBytes(2);
        }
        return output.toByteArray();
    }

    @DynamicPropertySource
    static void fixtures(DynamicPropertyRegistry r) {
        r.add("identity.postbox.endpoint", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/mail");
        r.add("identity.postbox.allow-loopback-http", () -> true);
        r.add("identity.postbox.access-key", () -> "synthetic-postbox-access");
        r.add("identity.postbox.secret-key", () -> "synthetic-postbox-secret");
        r.add("identity.avatar.endpoint", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        r.add("identity.avatar.allow-loopback-http", () -> true);
        r.add("identity.avatar.access-key", () -> "synthetic-s3-access");
        r.add("identity.avatar.secret-key", () -> "synthetic-s3-secret");
    }

    @Autowired
    org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired
    LocalAccounts local;
    @Autowired
    AccountStore accounts;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    OwnershipProofs proofs;
    @Autowired
    PasswordRecovery recovery;
    @Autowired
    FederatedAccounts federation;
    @Autowired
    Profiles profiles;
    @Autowired
    Moderation moderation;
    @Autowired
    Avatars avatars;
    @Autowired
    ObjectMapper json;
    final String password = "correct-horse-battery-42";

    AccountAccess account() {
        String key = UUID.randomUUID().toString();
        return local.register(key + "@example.test", key, password, null, key);
    }

    @BeforeEach
    void resetFixture() {
        delivered = null;
        MAIL_HEADERS.clear();
        failMail = false;
        timeoutMail = false;
        failPut = false;
        failDelete = false;
        afterPut = () -> {
        };
    }

    @Test
    void ambiguousMailTimeoutInvalidatesSecretWithinBoundedTransport() {
        var account = account();
        jdbc.sql("UPDATE app_identity.account SET email_verified=true WHERE account_id=:id")
                .param("id", account.accountId()).update();
        timeoutMail = true;
        long started = System.nanoTime();
        recovery.request(accounts.get(account.accountId(), false).email(), "timeout-fixture");
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(5));
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.ownership_challenge WHERE account_id=:id")
                .param("id", account.accountId()).query(Long.class).single()).isZero();
    }

    @Test
    void jpegDecodesAndForeignAvatarReferenceFailsDatabaseOwnershipConstraint() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(5, 6, BufferedImage.TYPE_INT_RGB), "jpeg", bytes);
        assertThat(AvatarImage.read(new ByteArrayInputStream(bytes.toByteArray()), "image/jpeg").width()).isEqualTo(5);
        var first = account();
        var second = account();
        avatars.replace(first, png(2, 2));
        avatars.replace(second, png(3, 3));
        String foreign = avatars.owned(second.accountId()).orElseThrow().storageKey();
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_identity.account_avatar SET storage_key=:key WHERE account_id=:id")
                .param("key", foreign).param("id", first.accountId()).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(avatars.read(first.accountId()).bytes()).isEqualTo(png(2, 2).bytes());
    }

    @Test
    void successfulObjectPutWithFailedPublicationLeavesDurableRecoverableIntent() throws Exception {
        var account = account();
        afterPut = () -> jdbc.sql(
                        "UPDATE app_identity.account SET security_generation=security_generation+1 WHERE account_id=:id")
                .param("id", account.accountId()).update();
        assertThatThrownBy(() -> avatars.replace(account, png(7, 7))).isInstanceOf(AccountFailure.class);
        assertThat(avatars.owned(account.accountId())).isEmpty();
        String key = jdbc.sql("SELECT storage_key FROM app_identity.avatar_cleanup WHERE account_id=:id")
                .param("id", account.accountId()).query(String.class).single();
        assertThat(OBJECTS).containsKey("/mnema-avatars/" + key);
        afterPut = () -> {
        };
        // Recovery only needs committed DB intent and S3 state, as after a process restart.
        avatars.retryCleanup();
        assertThat(OBJECTS).doesNotContainKey("/mnema-avatars/" + key);
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.avatar_cleanup WHERE account_id=:id")
                .param("id", account.accountId()).query(Long.class).single()).isZero();
    }

    @Test
    void concurrentAvatarReplacementRemovalAndCleanupDoNotDeletePublishedObject() throws Exception {
        var account = account();
        avatars.replace(account, png(2, 2));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var replace = executor.submit(() -> {
                start.await();
                avatars.replace(account, png(5, 5));
                return true;
            });
            var remove = executor.submit(() -> {
                start.await();
                avatars.remove(account);
                return true;
            });
            start.countDown();
            assertThat(replace.get(15, TimeUnit.SECONDS)).isTrue();
            assertThat(remove.get(15, TimeUnit.SECONDS)).isTrue();
        }
        avatars.retryCleanup();
        var published = avatars.owned(account.accountId());
        if (published.isPresent()) assertThat(avatars.read(account.accountId()).bytes()).isEqualTo(png(5, 5).bytes());
        else assertThat(OBJECTS.keySet()).noneMatch(key -> key.contains(account.accountId().toString()));
    }

    @Test
    void avatarHttpUploadReadAndRemovalRequireOwnedSession() throws Exception {
        var account = account();
        var login = mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/accounts/login")
                                .secure(true)
                                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                                .contentType("application/json").content(json.writeValueAsString(
                                        Map.of("login", accounts.get(account.accountId(), false).email(), "password", password))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        var cookie = login.getResponse().getCookie("SESSION");
        byte[] bytes = png(6, 6).bytes();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/accounts/me/avatar")
                                .file(new org.springframework.mock.web.MockMultipartFile("file", "fixture.png", "image/png",
                                        bytes))
                                .with(request -> {
                                    request.setMethod("PUT");
                                    return request;
                                }).secure(true).cookie(cookie)
                                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/accounts/profiles/" + account.accountId() + "/avatar"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(bytes));
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/accounts/me/avatar")
                                .secure(true).cookie(cookie)
                                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/accounts/password-reset/request")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", UUID.randomUUID() + "@example.test"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
    }

    @Test
    void avatarStorageFailuresDoNotReplaceCurrentOwnedReference() throws Exception {
        var account = account();
        avatars.replace(account, png(3, 3));
        var original = avatars.owned(account.accountId()).orElseThrow();
        failPut = true;
        assertThatThrownBy(() -> avatars.replace(account, png(4, 4))).isInstanceOf(AccountFailure.class);
        assertThat(avatars.owned(account.accountId()).orElseThrow().assetId()).isEqualTo(original.assetId());
        failPut = false;
        avatars.retryCleanup();
        OBJECTS.put("/mnema-avatars/" + original.storageKey(), new byte[]{1, 2, 3});
        assertThatThrownBy(() -> avatars.read(account.accountId())).isInstanceOf(AccountFailure.class);
        OBJECTS.remove("/mnema-avatars/" + original.storageKey());
        assertThatThrownBy(() -> avatars.read(account.accountId())).isInstanceOf(AccountFailure.class);
    }

    @Test
    void cleanupAndInjectedTimeRespectTheExactProofExpiryBoundary() {
        var account = account();
        var instant = java.time.Instant.parse("2026-09-05T00:00:00Z");
        var issuing = new OwnershipProofs(jdbc, accounts, java.time.Clock.fixed(instant, java.time.ZoneOffset.UTC));
        var proof = tx.execute(status -> issuing.issue(account, OwnershipProofs.Purpose.DELETE_ACCOUNT));
        var expired = new OwnershipProofs(jdbc, accounts,
                java.time.Clock.fixed(instant.plusSeconds(600), java.time.ZoneOffset.UTC));
        assertThatThrownBy(() -> tx.executeWithoutResult(
                status -> expired.consume(account, proof.token(), OwnershipProofs.Purpose.DELETE_ACCOUNT)))
                .isInstanceOf(AccountFailure.class);
        var cleanup = new app.mnema.identityaccount.security.ExpiredStateCleanup(jdbc,
                java.time.Clock.fixed(instant.plusSeconds(601), java.time.ZoneOffset.UTC));
        cleanup.removeExpired();
        assertThatThrownBy(() -> proofs.identify(proof.token())).isInstanceOf(AccountFailure.class);
    }

    @Test
    void freshLocalEmailVerificationEnablesRecoveryWithoutGrantingSession() throws Exception {
        var account = account();
        String email = accounts.get(account.accountId(), false).email();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/accounts/email-verification/request")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json").content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
        String message = json.readTree(delivered).at("/Content/Simple/Body/Text/Data").asText();
        assertThat(message).contains("https://mnema.app/verify-email#token=");
        String token = message.split("#token=")[1];
        assertThatThrownBy(() -> recovery.confirm(token, "new-password-666"))
                .isInstanceOf(AccountFailure.class);
        var confirmation = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/accounts/email-verification/confirm")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json").content(json.writeValueAsString(Map.of("token", token))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent())
                .andReturn();
        assertThat(accounts.get(account.accountId(), false).emailVerified()).isTrue();
        assertThatThrownBy(() -> recovery.confirmVerification(token)).isInstanceOf(AccountFailure.class);
        delivered = null;
        recovery.request(email, "fresh-verified-reset");
        assertThat(delivered).contains("Reset your Mnema password");
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.spring_session WHERE principal_name=:id")
                .param("id", account.accountId().toString()).query(Long.class).single()).isZero();
    }

    @Test
    void verifiedResetUsesRealSigV4TransportOneUseSecretAndRevokesOldGeneration() throws Exception {
        var a = account();
        String email = accounts.get(a.accountId(), false).email();
        recovery.request(email, "reset-fixture");
        assertThat(delivered).isNull();
        jdbc.sql("UPDATE app_identity.account SET email_verified=true WHERE account_id=:id").param("id", a.accountId())
                .update();
        recovery.request(email, "reset-fixture");
        assertThat(delivered).isNotNull();
        JsonNode mail = json.readTree(delivered);
        assertThat(mail.get("FromEmailAddress").asText()).isEqualTo("noreply@mnema.app");
        assertThat(MAIL_HEADERS.getFirst().entrySet()).anySatisfy(e -> {
            assertThat(e.getKey()).isEqualToIgnoringCase("Authorization");
            assertThat(e.getValue().getFirst()).startsWith("AWS4-HMAC-SHA256 Credential=synthetic-postbox-access/")
                    .contains("/ru-central1/ses/aws4_request");
        });
        String token = mail.at("/Content/Simple/Body/Text/Data").asText().split("#token=")[1];
        assertThat(jdbc.sql("SELECT secret_hash FROM app_identity.ownership_challenge WHERE account_id=:id")
                .param("id", a.accountId()).query(String.class).single()).isEqualTo(Secrets.hash(token))
                .isNotEqualTo(token);
        recovery.confirm(token, "new-password-fixture-44");
        assertThatThrownBy(() -> accounts.require(a, false)).isInstanceOf(AccountFailure.class);
        assertThatThrownBy(() -> local.login(email, password, "reset-fixture")).isInstanceOf(AccountFailure.class);
        assertThat(local.login(email, "new-password-fixture-44", "reset-fixture").accountId()).isEqualTo(a.accountId());
        assertThatThrownBy(() -> recovery.confirm(token, "another-password-45")).isInstanceOf(AccountFailure.class);
    }

    @Test
    void resetIneligibleOrFailedDeliveryDoesNotLeaveUsableChallenge() {
        recovery.request(UUID.randomUUID() + "@example.test", "reset-ineligible");
        assertThat(delivered).isNull();
        var fed = federation.complete(new FederatedAccounts.External("google", UUID.randomUUID().toString(),
                UUID.randomUUID() + "@example.test", true), null);
        recovery.request(accounts.get(fed.accountId(), false).email(), "reset-ineligible");
        assertThat(delivered).isNull();
        var a = account();
        jdbc.sql("UPDATE app_identity.account SET email_verified=true WHERE account_id=:id").param("id", a.accountId())
                .update();
        failMail = true;
        recovery.request(accounts.get(a.accountId(), false).email(), "reset-failed");
        assertThat(delivered).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.ownership_challenge WHERE account_id=:id")
                .param("id", a.accountId()).query(Long.class).single()).isZero();
    }

    @Test
    void allProvidersBindOpaqueSubjectNeverAutolinkAndRespectLastFactor() {
        for (String provider : List.of("google", "github", "yandex")) {
            String subject = "Case-" + UUID.randomUUID();
            String email = UUID.randomUUID() + "@example.test";
            var first = federation.complete(
                    new FederatedAccounts.External(provider, subject, email, !provider.equals("yandex")), null);
            assertThat(
                    federation.complete(new FederatedAccounts.External(provider, subject, "drift@example.test", false),
                            null)).isEqualTo(first);
            assertThat(accounts.get(first.accountId(), false).email()).isEqualTo(email);
            var separate = federation.complete(
                    new FederatedAccounts.External(provider, subject.toLowerCase(Locale.ROOT),
                            UUID.randomUUID() + "@example.test", false), null);
            assertThat(separate.accountId()).isNotEqualTo(first.accountId());
            assertThatThrownBy(() -> federation.complete(
                    new FederatedAccounts.External(provider, UUID.randomUUID().toString(), email, true),
                    null)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> federation.complete(
                    new FederatedAccounts.External(provider, UUID.randomUUID().toString(), null, false),
                    null)).isInstanceOf(AccountFailure.class);
            var proof = tx.execute(s -> proofs.issue(first, OwnershipProofs.Purpose.UNLINK_IDENTITY));
            assertThatThrownBy(() -> federation.unlink(first, federation.identities(first).getFirst().identityId(),
                    proof.token())).isInstanceOf(AccountFailure.class);
        }
    }

    @Test
    void explicitLinkNeedsOneUseProofAndUnlinkRevokesOnlyOwnedAccount() {
        var a = account();
        var b = account();
        var proof = tx.execute(s -> proofs.issue(a, OwnershipProofs.Purpose.LINK_IDENTITY));
        federation.authorizeLink(a, proof.token());
        assertThatThrownBy(() -> federation.authorizeLink(a, proof.token())).isInstanceOf(AccountFailure.class);
        var external = new FederatedAccounts.External("github", UUID.randomUUID().toString(), null, false);
        assertThat(federation.complete(external, a)).isEqualTo(a);
        assertThatThrownBy(() -> federation.complete(external, b)).isInstanceOf(AccountFailure.class);
        var identity = federation.identities(a).getFirst();
        var unlink = tx.execute(s -> proofs.issue(a, OwnershipProofs.Purpose.UNLINK_IDENTITY));
        federation.unlink(a, identity.identityId(), unlink.token());
        assertThatThrownBy(() -> accounts.require(a, false)).isInstanceOf(AccountFailure.class);
        accounts.require(b, false);
    }

    @Test
    void adminGraphRejectsSelfBootstrapForeignGrantorAndActiveSubordinates() {
        var root = account();
        var subordinate = account();
        var child = account();
        var outsider = account();
        jdbc.sql("UPDATE app_identity.account SET is_admin=true WHERE account_id=:id").param("id", root.accountId())
                .update();
        assertThatThrownBy(() -> moderation.apply(root, root.accountId(), Moderation.Action.BAN, null)).isInstanceOf(
                AccountFailure.class);
        assertThatThrownBy(
                () -> moderation.apply(outsider, child.accountId(), Moderation.Action.GRANT_ADMIN, null)).isInstanceOf(
                AccountFailure.class);
        moderation.apply(root, subordinate.accountId(), Moderation.Action.GRANT_ADMIN, null);
        moderation.apply(subordinate, child.accountId(), Moderation.Action.GRANT_ADMIN, null);
        assertThatThrownBy(() -> moderation.apply(root, subordinate.accountId(), Moderation.Action.REVOKE_ADMIN,
                null)).isInstanceOf(AccountFailure.class);
        assertThatThrownBy(
                () -> moderation.apply(root, child.accountId(), Moderation.Action.REVOKE_ADMIN, null)).isInstanceOf(
                AccountFailure.class);
        assertThatThrownBy(() -> moderation.apply(subordinate, root.accountId(), Moderation.Action.REVOKE_ADMIN,
                null)).isInstanceOf(AccountFailure.class);
        assertThatThrownBy(() -> moderation.apply(root, child.accountId(), Moderation.Action.BAN, null)).isInstanceOf(
                AccountFailure.class);
        moderation.apply(subordinate, child.accountId(), Moderation.Action.REVOKE_ADMIN, null);
        moderation.apply(root, subordinate.accountId(), Moderation.Action.REVOKE_ADMIN, null);
        assertThat(accounts.get(child.accountId(), false).isAdmin()).isFalse();
        assertThat(accounts.get(subordinate.accountId(), false).isAdmin()).isFalse();
    }

    @Test
    void avatarRealS3ProtocolPreservesOwnershipAndRetriesExactCleanup() throws Exception {
        var a = account();
        var b = account();
        AvatarImage first = png(8, 8);
        avatars.replace(a, first);
        var owned = avatars.owned(a.accountId()).orElseThrow();
        assertThat(owned.storageKey()).startsWith("account-avatar/" + a.accountId() + "/");
        assertThat(avatars.read(a.accountId()).bytes()).isEqualTo(first.bytes());
        avatars.replace(b, png(4, 4));
        String foreign = avatars.owned(b.accountId()).orElseThrow().storageKey();
        failDelete = true;
        avatars.replace(a, png(9, 9));
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.avatar_cleanup WHERE storage_key=:key")
                .param("key", owned.storageKey()).query(Long.class).single()).isEqualTo(1);
        failDelete = false;
        avatars.retryCleanup();
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.avatar_cleanup WHERE storage_key=:key")
                .param("key", owned.storageKey()).query(Long.class).single()).isZero();
        assertThat(avatars.read(b.accountId()).bytes()).isEqualTo(png(4, 4).bytes());
        assertThat(avatars.owned(b.accountId()).orElseThrow().storageKey()).isEqualTo(foreign);
        avatars.remove(a);
        assertThatThrownBy(() -> avatars.read(a.accountId())).isInstanceOf(AccountFailure.class);
    }

    @Test
    void avatarRejectsMimeSpoofOversizeDimensionsAndMalformedImage() throws Exception {
        var png = png(10, 10);
        assertThatThrownBy(() -> AvatarImage.read(new ByteArrayInputStream(png.bytes()), "image/jpeg")).isInstanceOf(
                AccountFailure.class);
        assertThatThrownBy(() -> AvatarImage.read(new ByteArrayInputStream(new byte[AvatarImage.MAX_BYTES + 1]),
                "image/png")).isInstanceOf(AccountFailure.class);
        assertThatThrownBy(
                () -> AvatarImage.read(new ByteArrayInputStream(new byte[]{1, 2, 3}), "image/png")).isInstanceOf(
                AccountFailure.class);
        assertThatThrownBy(() -> png(1025, 1)).isInstanceOf(AccountFailure.class);
    }

    @Test
    void concurrentProofConsumptionHasOneWinner() throws Exception {
        var a = account();
        var proof = tx.execute(s -> proofs.issue(a, OwnershipProofs.Purpose.DELETE_ACCOUNT));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        tx.executeWithoutResult(
                                s -> proofs.consume(a, proof.token(), OwnershipProofs.Purpose.DELETE_ACCOUNT));
                        return true;
                    } catch (AccountFailure e) {
                        return false;
                    }
                }));
            start.countDown();
            assertThat(List.of(futures.get(0).get(5, TimeUnit.SECONDS),
                    futures.get(1).get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
    }

    AvatarImage png(int width, int height) throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return AvatarImage.read(new ByteArrayInputStream(bytes.toByteArray()), "image/png");
    }
}
