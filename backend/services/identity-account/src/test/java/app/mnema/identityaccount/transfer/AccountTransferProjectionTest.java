package app.mnema.identityaccount.transfer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The canonical projection and its evidence are reconciliation artifacts: key order, null handling and
 * the numeric {@code seconds.nanos} instants are part of the format. The expectations were captured from
 * the Jackson 2 implementation before the platform migration.
 */
class AccountTransferProjectionTest {

    private final AccountTransferCodec codec = new AccountTransferCodec(new byte[32]);

    @Test
    void projectionKeepsAlphabeticalKeysNullsAndNumericInstants() {
        var account = new AccountTransferBundle.Account(
                UUID.fromString("018f0000-0000-7000-8000-000000000001"), "a@example.test", true, "profile",
                "Display é", "bio", "ACTIVE", false, null, null, null, null, null,
                Instant.parse("2026-09-28T12:00:00.123456789Z"), Instant.parse("2026-09-28T12:00:01Z"), null,
                new AccountTransferBundle.Credential("login", "$2a$hash"),
                List.of(new AccountTransferBundle.ExternalIdentity("github", "sub",
                        Instant.parse("2026-09-28T12:00:02.5Z"), null)),
                new AccountTransferBundle.Avatar(UUID.fromString("018f0000-0000-7000-8000-0000000000a1"),
                        "image/png", 10, "a".repeat(64), 5, 6, Instant.parse("2026-09-28T12:00:03Z")));
        var bundle = new AccountTransferBundle(1, "mnema-account-transfer", List.of(account));

        assertThat(new String(codec.canonicalProjection(bundle), StandardCharsets.UTF_8)).isEqualTo(
                "{\"accounts\":[{\"accountId\":\"018f0000-0000-7000-8000-000000000001\",\"admin\":false,"
                + "\"adminGrantedAt\":null,\"adminGrantedBy\":null,\"avatar\":{\"assetId\":"
                + "\"018f0000-0000-7000-8000-0000000000a1\",\"byteSize\":10,\"contentSha256\":\""
                + "a".repeat(64) + "\",\"contentType\":\"image/png\",\"createdAt\":1790596803.000000000,"
                + "\"height\":6,\"width\":5},\"banReason\":null,\"bannedAt\":null,\"bannedBy\":null,"
                + "\"bio\":\"bio\",\"createdAt\":1790596800.123456789,\"credential\":{\"loginName\":\"login\","
                + "\"passwordHash\":\"$2a$hash\"},\"displayName\":\"Display é\",\"email\":\"a@example.test\","
                + "\"emailVerified\":true,\"externalIdentities\":[{\"lastLoginAt\":null,"
                + "\"linkedAt\":1790596802.500000000,\"provider\":\"github\",\"providerSubject\":\"sub\"}],"
                + "\"lastLoginAt\":null,\"profileCreatedAt\":1790596801.000000000,\"profileUsername\":\"profile\","
                + "\"status\":\"ACTIVE\"}],\"kind\":\"mnema-account-transfer\",\"schemaVersion\":1}");
    }

    @Test
    void evidenceKeepsAlphabeticalKeys() {
        var evidence = new AccountTransferEvidence(1, "k", "OK", 1, 1, 1, 1, 10L, "b".repeat(64), "c".repeat(64));

        assertThat(new String(codec.evidence(evidence), StandardCharsets.UTF_8)).isEqualTo(
                "{\"accountCount\":1,\"avatarBytes\":10,\"avatarCount\":1,\"avatarSetSha256\":\"" + "c".repeat(64)
                + "\",\"credentialCount\":1,\"externalIdentityCount\":1,\"kind\":\"k\","
                + "\"projectionSha256\":\"" + "b".repeat(64) + "\",\"schemaVersion\":1,\"status\":\"OK\"}");
    }
}
