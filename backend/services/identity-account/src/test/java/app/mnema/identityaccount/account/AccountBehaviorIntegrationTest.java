package app.mnema.identityaccount.account;

import app.mnema.identityaccount.contract.*;
import app.mnema.identityaccount.local.LocalAccounts;
import app.mnema.identityaccount.federation.*;
import app.mnema.identityaccount.security.*;
import app.mnema.identityaccount.recovery.PasswordRecovery;
import app.mnema.identityaccount.profile.Profiles;
import app.mnema.identityaccount.profile.PublicProfiles;
import app.mnema.identityaccount.moderation.Moderation;
import app.mnema.identityaccount.avatar.*;
import app.mnema.identityaccount.support.PostgresIntegrationTest;
import tools.jackson.databind.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class AccountBehaviorIntegrationTest extends PostgresIntegrationTest {
    static final HttpServer SERVER;
    static final Map<String, byte[]> OBJECTS = new ConcurrentHashMap<>();
    static final Map<String, Map<String, String>> OBJECT_OWNERS = new ConcurrentHashMap<>();
    static final Map<String, Set<String>> OBJECT_VERSIONS = new ConcurrentHashMap<>();
    static final Map<String, Set<String>> DELETE_MARKERS = new ConcurrentHashMap<>();
    static final Map<String, Map<String, String>> VERSION_OWNERS = new ConcurrentHashMap<>();
    static final List<String> HEAD_VERSIONS = new CopyOnWriteArrayList<>();
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
                } else if ("GET".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getRawQuery() != null &&
                        exchange.getRequestURI().getRawQuery().contains("versions")) {
                    response = versionListing(query(exchange.getRequestURI().getRawQuery(), "prefix"));
                    exchange.getResponseHeaders().set("Content-Type", "application/xml");
                } else switch (exchange.getRequestMethod()) {
                    case "PUT" -> {
                        if (failPut) status = 503;
                        else {
                            if ("aws-chunked".equals(exchange.getRequestHeaders().getFirst("Content-Encoding")))
                                body = decodeChunks(body);
                            OBJECTS.put(path, body);
                            OBJECT_OWNERS.put(path, Map.of(
                                    "account-id", exchange.getRequestHeaders().getFirst("x-amz-meta-account-id"),
                                    "asset-id", exchange.getRequestHeaders().getFirst("x-amz-meta-asset-id")));
                            OBJECT_VERSIONS.computeIfAbsent(path, ignored -> ConcurrentHashMap.newKeySet())
                                    .add("fixture-version");
                            afterPut.run();
                            exchange.getResponseHeaders().set("ETag", "\"synthetic-etag\"");
                            exchange.getResponseHeaders().set("x-amz-version-id", "fixture-version");
                        }
                    }
                    case "HEAD" -> {
                        String version = query(exchange.getRequestURI().getRawQuery(), "versionId");
                        if (version != null) HEAD_VERSIONS.add(version);
                        if (!OBJECTS.containsKey(path) || version != null &&
                                !OBJECT_VERSIONS.getOrDefault(path, Set.of()).contains(version)) status = 404;
                        else {
                            VERSION_OWNERS.getOrDefault(path + "\u0000" + version,
                                    OBJECT_OWNERS.getOrDefault(path, Map.of())).forEach((key, value) ->
                                    exchange.getResponseHeaders().set("x-amz-meta-" + key, value));
                            if (version != null) exchange.getResponseHeaders().set("x-amz-version-id", version);
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
                            String version = query(exchange.getRequestURI().getRawQuery(), "versionId");
                            if (version == null) {
                                OBJECTS.remove(path);
                                OBJECT_OWNERS.remove(path);
                                OBJECT_VERSIONS.remove(path);
                                DELETE_MARKERS.remove(path);
                            } else {
                                Set<String> objects = OBJECT_VERSIONS.get(path);
                                if (objects != null) objects.remove(version);
                                Set<String> markers = DELETE_MARKERS.get(path);
                                if (markers != null) markers.remove(version);
                                VERSION_OWNERS.remove(path + "\u0000" + version);
                                if (OBJECT_VERSIONS.getOrDefault(path, Set.of()).isEmpty()) {
                                    OBJECTS.remove(path);
                                    OBJECT_OWNERS.remove(path);
                                    OBJECT_VERSIONS.remove(path);
                                }
                                if (DELETE_MARKERS.getOrDefault(path, Set.of()).isEmpty())
                                    DELETE_MARKERS.remove(path);
                            }
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

    static String query(String input, String name) {
        if (input == null) return null;
        for (String part : input.split("&")) {
            String[] pair = part.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(name))
                return pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
        }
        return null;
    }

    static byte[] versionListing(String prefix) {
        var entries = new StringBuilder();
        OBJECT_VERSIONS.forEach((path, versions) -> {
            String key = path.substring("/mnema-avatars/".length());
            if (prefix != null && key.startsWith(prefix)) versions.forEach(version -> entries.append("<Version><Key>")
                    .append(key).append("</Key><VersionId>").append(version)
                    .append("</VersionId><IsLatest>true</IsLatest><LastModified>2026-09-05T00:00:00Z</LastModified>")
                    .append("<ETag>\"fixture\"</ETag><Size>3</Size><StorageClass>STANDARD</StorageClass></Version>"));
        });
        DELETE_MARKERS.forEach((path, versions) -> {
            String key = path.substring("/mnema-avatars/".length());
            if (prefix != null && key.startsWith(prefix)) versions.forEach(version -> entries.append("<DeleteMarker><Key>")
                    .append(key).append("</Key><VersionId>").append(version)
                    .append("</VersionId><IsLatest>true</IsLatest><LastModified>2026-09-05T00:00:00Z")
                    .append("</LastModified></DeleteMarker>"));
        });
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
                "<Name>mnema-avatars</Name><Prefix>" + (prefix == null ? "" : prefix) +
                "</Prefix><KeyMarker></KeyMarker><VersionIdMarker></VersionIdMarker><MaxKeys>1000</MaxKeys>" +
                "<IsTruncated>false</IsTruncated>" + entries + "</ListVersionsResult>")
                .getBytes(StandardCharsets.UTF_8);
    }

    @DynamicPropertySource
    static void fixtures(DynamicPropertyRegistry r) {
        r.add("identity.postbox.endpoint", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/mail");
        r.add("identity.postbox.allow-loopback-http", () -> true);
        r.add("identity.postbox.access-key", () -> "synthetic-postbox-access");
        r.add("identity.postbox.secret-key", () -> "synthetic-postbox-secret");
        r.add("identity.avatar.endpoint", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        r.add("identity.avatar.allow-loopback-http", () -> true);
        r.add("identity.avatar.bucket", () -> "mnema-avatars");
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
    PublicProfiles publicProfiles;
    @Autowired
    Moderation moderation;
    @Autowired
    Avatars avatars;
    @Autowired
    AvatarStorage avatarStorage;
    @Autowired
    ObjectMapper json;
    @Autowired
    org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository clients;
    @Autowired
    org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService authorizations;
    final String password = "correct-horse-battery-42";

    AccountAccess account() {
        String key = UUID.randomUUID().toString();
        return local.register(key + "@example.test", key, password, null, key);
    }

    String bearer(AccountAccess access) throws Exception {
        Instant expires = Instant.now().plusSeconds(120);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID())
                        .type(new JOSEObjectType("at+jwt")).build(),
                new JWTClaimsSet.Builder().subject(access.accountId().toString())
                        .issuer("https://identity.mnema.test").audience("mnema-api")
                        .issueTime(Date.from(Instant.now().minusSeconds(5))).expirationTime(Date.from(expires))
                        .claim("generation", Long.toString(access.generation()))
                        .claim("scope", "account.read account.write").build());
        jwt.sign(new RSASSASigner(SIGNING_KEY));
        String token = jwt.serialize();
        authorizations.save(org.springframework.security.oauth2.server.authorization.OAuth2Authorization
                .withRegisteredClient(clients.findByClientId("mnema-web"))
                .principalName(access.accountId().toString())
                .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .attribute("generation", Long.toString(access.generation()))
                .accessToken(new org.springframework.security.oauth2.core.OAuth2AccessToken(
                        org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER, token,
                        Instant.now().minusSeconds(5), expires, Set.of("account.read", "account.write")))
                .build());
        return token;
    }

    @BeforeEach
    void resetFixture() {
        delivered = null;
        MAIL_HEADERS.clear();
        failMail = false;
        timeoutMail = false;
        failPut = false;
        failDelete = false;
        OBJECT_VERSIONS.clear();
        DELETE_MARKERS.clear();
        VERSION_OWNERS.clear();
        HEAD_VERSIONS.clear();
        afterPut = () -> {
        };
    }

    @Test
    void multilineBioRoundTripsThroughHttpAndInvalidEditsLeaveStoredProfileUntouched() throws Exception {
        var access = account();
        String token = bearer(access);
        String username = "bio-" + UUID.randomUUID();
        String expected = "Учусь каждый день ✨\n\n• Математика и языки";
        var edit = Map.of("profileUsername", username, "displayName", "Reader",
                "bio", "\r\n  Учусь\t\tкаждый  день ✨  \r\n\r\n\r\n  • Математика и языки  \r\n");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/accounts/me")
                        .header("Authorization", "Bearer " + token).contentType("application/json")
                        .content(json.writeValueAsString(edit)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.bio").value(expected));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/accounts/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.bio").value(expected));
        for (String invalid : List.of(String.join("\n", Collections.nCopies(7, "строка")), "bad\u0001", "а".repeat(201))) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/accounts/me")
                            .header("Authorization", "Bearer " + token).contentType("application/json")
                            .content(json.writeValueAsString(Map.of("profileUsername", username,
                                    "displayName", "Reader", "bio", invalid))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
            assertThat(profiles.get(access).bio()).isEqualTo(expected);
        }
        assertThat(jdbc.sql("SELECT bio FROM app_identity.account WHERE account_id=:id")
                .param("id", access.accountId()).query(String.class).single()).isEqualTo(expected);
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
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/accounts/me/avatar")
                        .secure(true).cookie(cookie))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(bytes));
        profiles.update(account, "av-" + UUID.randomUUID().toString().substring(0, 8), "", "");
        publicProfiles.update(account, new PublicProfiles.Update(true, false, true, false, PublicProfiles.TEXT_VERSION));
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
    void nativeProfileAvatarUploadAcceptsCookieFreeBearer() throws Exception {
        var access = account();
        byte[] image = png(6, 6).bytes();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/accounts/me/avatar")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "profile.png", "image/png", image))
                        .with(request -> { request.setMethod("PUT"); return request; })
                        .secure(true).header("Authorization", "Bearer " + bearer(access)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        profiles.update(access, "av-" + UUID.randomUUID().toString().substring(0, 8), "", "");
        publicProfiles.update(access, new PublicProfiles.Update(true, false, true, false, PublicProfiles.TEXT_VERSION));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/accounts/profiles/" + access.accountId() + "/avatar"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(image));
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
    void exactAvatarErasureVerifiesOwnershipResolvesVersionAndTreatsAbsenceAsSuccess() throws Exception {
        var account = account();
        avatars.replace(account, png(3, 3));
        var owned = avatars.owned(account.accountId()).orElseThrow();
        var manifest = new OwnedAvatarEraser.Manifest(owned.accountId(), owned.assetId(), owned.storageKey(),
                owned.storageVersion(), owned.contentSha256());
        String path = "/mnema-avatars/" + owned.storageKey();
        OBJECT_VERSIONS.get(path).add("fixture-old-version");
        DELETE_MARKERS.computeIfAbsent(path, ignored -> ConcurrentHashMap.newKeySet()).add("fixture-delete-marker");

        OBJECT_OWNERS.put(path, Map.of("account-id", UUID.randomUUID().toString(),
                "asset-id", owned.assetId().toString()));
        assertThatThrownBy(() -> avatarStorage.deleteOwned(manifest)).isInstanceOf(AccountFailure.class)
                .hasMessage("avatar_ownership_mismatch");
        assertThat(OBJECTS).containsKey(path);

        OBJECT_OWNERS.put(path, Map.of("account-id", owned.accountId().toString(),
                "asset-id", owned.assetId().toString()));
        VERSION_OWNERS.put(path + "\u0000fixture-old-version", Map.of(
                "account-id", UUID.randomUUID().toString(), "asset-id", owned.assetId().toString()));
        assertThatThrownBy(() -> avatarStorage.deleteOwned(manifest)).isInstanceOf(AccountFailure.class)
                .hasMessage("avatar_ownership_mismatch");
        assertThat(OBJECT_VERSIONS.get(path)).contains("fixture-version", "fixture-old-version");
        VERSION_OWNERS.put(path + "\u0000fixture-old-version", OBJECT_OWNERS.get(path));
        var versionlessReceipt = new OwnedAvatarEraser.Manifest(owned.accountId(), owned.assetId(),
                owned.storageKey(), null, owned.contentSha256());
        avatarStorage.deleteOwned(versionlessReceipt);
        avatarStorage.deleteOwned(versionlessReceipt);
        assertThat(OBJECTS).doesNotContainKey(path);
        assertThat(OBJECT_VERSIONS).doesNotContainKey(path);
        assertThat(DELETE_MARKERS).doesNotContainKey(path);
        assertThat(HEAD_VERSIONS).contains("fixture-version", "fixture-old-version");
        assertThatThrownBy(() -> avatarStorage.deleteOwned(new OwnedAvatarEraser.Manifest(owned.accountId(),
                UUID.randomUUID(), owned.storageKey(), owned.storageVersion(), owned.contentSha256())))
                .hasMessage("avatar_ownership_mismatch");
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
    void cleanupDrainsAnExpiredRateLimitBacklogLargerThanOneBatch() {
        int rows = 2 * 1000 + 345;
        jdbc.sql("""
                        INSERT INTO app_identity.rate_limit(bucket,window_start,attempts)
                        SELECT 'cleanup-backlog-' || n, now() - interval '2 days', 1 FROM generate_series(1,:rows) n
                        """).param("rows", rows).update();
        jdbc.sql("INSERT INTO app_identity.rate_limit(bucket,window_start,attempts) VALUES('cleanup-fresh',now(),1)")
                .update();
        new app.mnema.identityaccount.security.ExpiredStateCleanup(jdbc, java.time.Clock.systemUTC()).removeExpired();
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.rate_limit WHERE bucket LIKE 'cleanup-backlog-%'")
                .query(Long.class).single()).as("one run keeps pace with a multi-batch backlog").isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.rate_limit WHERE bucket='cleanup-fresh'")
                .query(Long.class).single()).isOne();
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
        String message = json.readTree(delivered).at("/Content/Simple/Body/Text/Data").asString();
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
        assertThat(mail.get("FromEmailAddress").asString()).isEqualTo("noreply@mnema.app");
        assertThat(MAIL_HEADERS.getFirst().entrySet()).anySatisfy(e -> {
            assertThat(e.getKey()).isEqualToIgnoringCase("Authorization");
            assertThat(e.getValue().getFirst()).startsWith("AWS4-HMAC-SHA256 Credential=synthetic-postbox-access/")
                    .contains("/ru-central1/ses/aws4_request");
        });
        String token = mail.at("/Content/Simple/Body/Text/Data").asString().split("#token=")[1];
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

    /**
     * A database clock that steps backwards makes the next transaction see rows created "in the future".
     * Profile, login, password and verification writes must stay monotonic for the unchanged CHECK constraints.
     */
    @Test
    void accountWritesStayMonotonicWhenRowsAreAheadOfTheDatabaseClock() {
        String key = UUID.randomUUID().toString();
        var access = local.register(key + "@example.test", key, password, null, key);
        placeAccountAhead(access.accountId());
        jdbc.sql("UPDATE app_identity.local_credential SET created_at=statement_timestamp() + interval '1 hour',"
                + "updated_at=statement_timestamp() + interval '1 hour' WHERE account_id=:id")
                .param("id", access.accountId()).update();

        profiles.update(access, "ahead-" + key.substring(0, 8), "Display", "Bio");
        local.login(key, password, key);
        local.replacePassword(access.accountId(), "replacement-password-77");
        var current = accounts.get(access.accountId(), false).access();
        var proof = tx.execute(s -> proofs.issue(current, OwnershipProofs.Purpose.VERIFY_EMAIL));
        recovery.confirmVerification(proof.token());

        assertThat(jdbc.sql("SELECT updated_at >= created_at AND profile_created_at >= created_at "
                        + "AND last_login_at >= created_at AND created_at > statement_timestamp() "
                        + "FROM app_identity.account WHERE account_id=:id")
                .param("id", access.accountId()).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT updated_at >= created_at AND created_at > statement_timestamp() "
                        + "FROM app_identity.local_credential WHERE account_id=:id")
                .param("id", access.accountId()).query(Boolean.class).single()).isTrue();

        var external = new FederatedAccounts.External("github", "ahead-" + key, key + "@provider.test", true);
        var federated = federation.complete(external, null);
        placeAccountAhead(federated.accountId());
        jdbc.sql("UPDATE app_identity.external_identity SET linked_at=statement_timestamp() + interval '1 hour',last_login_at=NULL "
                + "WHERE account_id=:id").param("id", federated.accountId()).update();
        federation.complete(external, null);
        assertThat(jdbc.sql("SELECT a.last_login_at >= a.created_at AND i.last_login_at >= i.linked_at "
                        + "AND i.linked_at > statement_timestamp() FROM app_identity.account a "
                        + "JOIN app_identity.external_identity i USING (account_id) WHERE account_id=:id")
                .param("id", federated.accountId()).query(Boolean.class).single()).isTrue();
    }

    private void placeAccountAhead(UUID accountId) {
        jdbc.sql("UPDATE app_identity.account SET created_at=statement_timestamp() + interval '1 hour',"
                + "updated_at=statement_timestamp() + interval '1 hour',profile_created_at=NULL,last_login_at=NULL "
                + "WHERE account_id=:id").param("id", accountId).update();
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
