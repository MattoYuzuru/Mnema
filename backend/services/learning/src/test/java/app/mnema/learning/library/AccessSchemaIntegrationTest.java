package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V56: the constraints and the guard of the two tables, and the index each hot statement of the access code uses. */
@SpringBootTest
class AccessSchemaIntegrationTest extends PostgresIntegrationTest {
    @Autowired private DeckService decks;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private UUID deck;

    @BeforeEach
    void deck() {
        deck = UUID.fromString(decks.create(UUID.randomUUID(), new DeckCommand(UUID.randomUUID(), "Схема", "Описание")).acknowledgement().path("deck")
                .path("deckId").stringValue(null));
    }

    private static byte[] digest(int seed) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) seed);
        return bytes;
    }

    private UUID invite(int seed, Integer max, Instant expires) {
        return jdbc.sql("""
                INSERT INTO app_learning.deck_invite(deck_id, token_hash, max_uses, expires_at, created_at, code_rotated_at) VALUES (:deck, :hash, :max, :expires, :now, :now) RETURNING invite_id
                """).param("deck", deck).param("hash", digest(seed)).param("max", max, java.sql.Types.INTEGER)
                .param("expires", expires == null ? null : Timestamp.from(expires), java.sql.Types.TIMESTAMP).param("now", Timestamp.from(Instant.now()))
                .query(UUID.class).single();
    }

    private int update(String sql, UUID invite) { return jdbc.sql(sql).param("id", invite).update(); }

    @Test
    void anInvitationIsOneDigestOfThirtyTwoBytesWithBoundedUses() {
        UUID id = invite(1, 3, Instant.now().plusSeconds(3600));
        assertThat(id).isNotNull();
        assertThat(id.version()).as("uuidv7").isEqualTo(7);
        assertThatThrownBy(() -> invite(1, null, null)).as("the digest is unique").hasMessageContaining("deck_invite_token_key");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.deck_invite(deck_id, token_hash, created_at, code_rotated_at) VALUES (:deck, :hash, now(), now())")
                .param("deck", deck).param("hash", new byte[31]).update()).hasMessageContaining("deck_invite_token_hash_check");
        assertThatThrownBy(() -> invite(2, 0, null)).hasMessageContaining("deck_invite_max_uses_check");
        assertThatThrownBy(() -> invite(3, 1001, null)).hasMessageContaining("deck_invite_max_uses_check");
        assertThatThrownBy(() -> invite(4, 1, Instant.now().minusSeconds(60))).hasMessageContaining("deck_invite_expiry_after_creation");
        assertThat(invite(5, null, null)).as("unlimited and without expiry").isNotNull();
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.deck_invite(deck_id, token_hash, created_at, code_rotated_at) VALUES (:deck, :hash, now(), now())")
                .param("deck", UUID.randomUUID()).param("hash", digest(6)).update()).hasMessageContaining("foreign key");
    }

    @Test
    void aUseAlwaysHasItsTimeAndAnInvitationIsBornUnderTheGenerationItWasGiven() {
        UUID id = invite(30, null, null);
        assertThatThrownBy(() -> update("UPDATE app_learning.deck_invite SET uses = 1 WHERE invite_id = :id", id)).hasMessageContaining("deck_invite_last_use");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.deck_invite(deck_id, token_hash, created_at) VALUES (:deck, :hash, now())")
                .param("deck", deck).param("hash", digest(31)).update()).hasMessageContaining("code_rotated_at");
    }

    @Test
    void theUseCounterNeverPassesTheLimitEvenIfTheCodeWereWrong() {
        UUID id = invite(10, 2, null);
        assertThat(update("UPDATE app_learning.deck_invite SET uses = uses + 1, last_used_at = now() WHERE invite_id = :id", id)).isEqualTo(1);
        assertThat(update("UPDATE app_learning.deck_invite SET uses = uses + 1, last_used_at = now() WHERE invite_id = :id", id)).isEqualTo(1);
        assertThatThrownBy(() -> update("UPDATE app_learning.deck_invite SET uses = uses + 1, last_used_at = now() WHERE invite_id = :id", id)).hasMessageContaining("deck_invite_uses_within_max");
    }

    @Test
    void theGuardFreezesEverythingButUsesAndTheFirstRevocation() {
        UUID id = invite(20, 5, Instant.now().plusSeconds(3600));
        for (String change : new String[] {"token_hash = '\\x" + "ab".repeat(32) + "'", "max_uses = 6", "expires_at = expires_at + interval '1 day'",
                "created_at = created_at - interval '1 day'", "code_rotated_at = code_rotated_at + interval '1 day'", "last_used_at = now()", "deck_id = '" + UUID.randomUUID() + "'", "invite_id = '" + UUID.randomUUID() + "'", "max_uses = NULL"}) {
            assertThatThrownBy(() -> update("UPDATE app_learning.deck_invite SET " + change + " WHERE invite_id = :id", id)).as(change).isInstanceOf(Exception.class);
        }
        update("UPDATE app_learning.deck_invite SET uses = 3, last_used_at = now() WHERE invite_id = :id", id);
        assertThatThrownBy(() -> update("UPDATE app_learning.deck_invite SET uses = 2, last_used_at = now() WHERE invite_id = :id", id)).as("uses only grow").hasMessageContaining("Invalid deck invite transition");
        assertThat(update("UPDATE app_learning.deck_invite SET revoked_at = now() WHERE invite_id = :id", id)).isEqualTo(1);
        assertThatThrownBy(() -> update("UPDATE app_learning.deck_invite SET revoked_at = NULL WHERE invite_id = :id", id)).as("a revocation is final").hasMessageContaining("Invalid deck invite transition");
        assertThatThrownBy(() -> update("UPDATE app_learning.deck_invite SET revoked_at = now() + interval '1 day' WHERE invite_id = :id", id)).as("and not moved").hasMessageContaining("Invalid deck invite transition");
    }

    private int request(String status, String decided, UUID requester) {
        return jdbc.sql("INSERT INTO app_learning.deck_access_request(deck_id, requester_id, status, created_at, decided_at) VALUES (:deck, :requester, :status, :created, " + decided + ")")
                .param("deck", deck).param("requester", requester).param("status", status).param("created", Timestamp.from(Instant.parse("2026-10-11T09:00:00Z"))).update();
    }

    @Test
    void aRequestIsDecidedExactlyWhenItIsNotPending() {
        assertThat(request("PENDING", "NULL", UUID.randomUUID())).isEqualTo(1);
        assertThat(request("APPROVED", "TIMESTAMPTZ '2026-10-11 10:00:00+00'", UUID.randomUUID())).isEqualTo(1);
        assertThat(request("REJECTED", "TIMESTAMPTZ '2026-10-11 10:00:00+00'", UUID.randomUUID())).isEqualTo(1);
        assertThat(request("WITHDRAWN", "TIMESTAMPTZ '2026-10-11 10:00:00+00'", UUID.randomUUID())).isEqualTo(1);
        assertThatThrownBy(() -> request("PENDING", "TIMESTAMPTZ '2026-10-11 10:00:00+00'", UUID.randomUUID())).hasMessageContaining("deck_access_request_decision");
        assertThatThrownBy(() -> request("APPROVED", "NULL", UUID.randomUUID())).hasMessageContaining("deck_access_request_decision");
        assertThatThrownBy(() -> request("REJECTED", "TIMESTAMPTZ '2026-10-11 08:00:00+00'", UUID.randomUUID())).hasMessageContaining("deck_access_request_decided_after_created");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.deck_access_request(deck_id, requester_id, status, created_at, revoked_at) VALUES (:deck, :r, 'PENDING', now(), now())")
                .param("deck", deck).param("r", UUID.randomUUID()).update()).as("only a rejection can be a revocation").hasMessageContaining("deck_access_request_revocation");
        assertThatThrownBy(() -> request("MAYBE", "TIMESTAMPTZ '2026-10-11 10:00:00+00'", UUID.randomUUID())).hasMessageContaining("deck_access_request_status_check");
        UUID twice = UUID.randomUUID();
        request("PENDING", "NULL", twice);
        assertThatThrownBy(() -> request("PENDING", "NULL", twice)).as("one row per deck and requester").hasMessageContaining("deck_access_request_pkey");
    }

    @Test
    void theHotStatementsUseTheirIndexes() {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        String plans = transaction.execute(status -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            UUID id = UUID.randomUUID();
            String invites = String.join("\n", jdbc.sql("EXPLAIN SELECT invite_id FROM app_learning.deck_invite WHERE deck_id = :deck ORDER BY created_at DESC, invite_id DESC LIMIT 21")
                    .param("deck", deck).query(String.class).list());
            String pending = String.join("\n", jdbc.sql("EXPLAIN SELECT requester_id FROM app_learning.deck_access_request WHERE deck_id = :deck AND status = 'PENDING' ORDER BY created_at DESC, requester_id DESC LIMIT 21")
                    .param("deck", deck).query(String.class).list());
            String open = String.join("\n", jdbc.sql("EXPLAIN SELECT count(*) FROM app_learning.deck_access_request WHERE requester_id = :id AND status = 'PENDING'")
                    .param("id", id).query(String.class).list());
            String recent = String.join("\n", jdbc.sql("EXPLAIN SELECT created_at FROM app_learning.deck_access_request WHERE requester_id = :id AND created_at > now() ORDER BY created_at")
                    .param("id", id).query(String.class).list());
            String hash = String.join("\n", jdbc.sql("EXPLAIN SELECT invite_id FROM app_learning.deck_invite WHERE token_hash = :hash").param("hash", digest(99)).query(String.class).list());
            String grants = String.join("\n", jdbc.sql("EXPLAIN SELECT grantee_id FROM app_learning.deck_access_grant WHERE deck_id = :deck ORDER BY granted_at DESC LIMIT 21")
                    .param("deck", deck).query(String.class).list());
            return String.join("\n#\n", invites, pending, open, recent, hash, grants);
        });
        assertThat(plans).contains("deck_invite_deck", "deck_access_request_pending", "deck_access_request_requester", "deck_invite_token_key");
        assertThat(plans).as("the open count is an index condition on the requester, never a table scan").contains("Index Cond: (requester_id = ").doesNotContain("Seq Scan");
        assertThat(plans).as("the grants of a deck are a key range of the primary key").contains("deck_access_grant_pkey");
    }
}
