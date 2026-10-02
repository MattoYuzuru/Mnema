package app.mnema.learning.usage;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The idempotent snapshot contract billing will publish to; nothing consumes the rows yet. */
class EntitlementInboxTest extends UsageIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @org.springframework.beans.factory.annotation.Autowired private EntitlementInbox inbox;

    private EntitlementInbox.Snapshot snapshot(String id, UUID owner, Plan plan, JsonNode allowances) {
        return new EntitlementInbox.Snapshot(id, owner, plan, "BILLING", Instant.parse("2026-09-30T21:00:00Z"),
                Instant.parse("2026-10-31T21:00:00Z"), allowances, Instant.parse("2026-10-31T21:00:00Z"));
    }

    @Test
    void aSnapshotIsStoredOnceAndAnIdenticalRepeatIsANoOp() {
        UUID owner = UUID.randomUUID();
        String id = "snap-" + owner;
        JsonNode allowances = JSON.readTree("{\"monthlyCredits\":360,\"podcasts\":2}");

        assertThat(inbox.accept(snapshot(id, owner, Plan.PLUS, allowances))).isTrue();
        assertThat(inbox.accept(snapshot(id, owner, Plan.PLUS, allowances))).isFalse();

        assertThat(jdbc.sql("SELECT plan||':'||source||':'||(allowances->>'monthlyCredits') FROM app_learning.entitlement_inbox "
                + "WHERE snapshot_id=:id").param("id", id).query(String.class).single()).isEqualTo("PLUS:BILLING:360");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.entitlement_inbox WHERE owner_id=:o").param("o", owner)
                .query(Long.class).single()).isOne();
    }

    @Test
    void aDifferentSnapshotUnderTheSameIdIsRefused() {
        UUID owner = UUID.randomUUID();
        String id = "snap-" + owner;
        inbox.accept(snapshot(id, owner, Plan.PLUS, JSON.readTree("{}")));

        assertThatThrownBy(() -> inbox.accept(snapshot(id, owner, Plan.PRO, JSON.readTree("{}"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbox.accept(snapshot(id, owner, Plan.PLUS, JSON.readTree("{\"a\":1}"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbox.accept(snapshot(id, UUID.randomUUID(), Plan.PLUS, JSON.readTree("{}"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedSnapshotsAreRefusedBeforeTheDatabase() {
        UUID owner = UUID.randomUUID();
        JsonNode empty = JSON.readTree("{}");
        assertThatThrownBy(() -> inbox.accept(snapshot("bad id", owner, Plan.PLUS, empty))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbox.accept(snapshot(null, owner, Plan.PLUS, empty))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbox.accept(snapshot("s1", owner, Plan.PLUS, JSON.readTree("[]")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbox.accept(snapshot("s1", owner, Plan.PLUS, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbox.accept(snapshot("s1", new UUID(0, 0), Plan.PLUS, empty))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbox.accept(new EntitlementInbox.Snapshot("s1", owner, Plan.PLUS, "CONFIG",
                Instant.parse("2026-09-30T21:00:00Z"), Instant.parse("2026-10-31T21:00:00Z"), empty,
                Instant.parse("2026-10-31T21:00:00Z")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbox.accept(new EntitlementInbox.Snapshot("s1", owner, Plan.PLUS, "BILLING",
                Instant.parse("2026-10-31T21:00:00Z"), Instant.parse("2026-09-30T21:00:00Z"), empty,
                Instant.parse("2026-10-31T21:00:00Z")))).isInstanceOf(IllegalArgumentException.class);
        String big = "{\"x\":\"" + "y".repeat(9_000) + "\"}";
        assertThatThrownBy(() -> inbox.accept(snapshot("s2", owner, Plan.PLUS, JSON.readTree(big))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
