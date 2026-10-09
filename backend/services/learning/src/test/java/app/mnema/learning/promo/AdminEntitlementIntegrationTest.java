package app.mnema.learning.promo;

import app.mnema.learning.admin.AdminReportRange;
import app.mnema.learning.admin.AdminReports;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Admin reads the effective entitlement without creating a usage period, including actual unconsumed promo gifts. */
class AdminEntitlementIntegrationTest extends PromoIntegrationTest {
    private static final AdminReportRange HISTORY = new AdminReportRange(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-02"));
    @Autowired AdminReports reports;

    @Test void unusedAccountsHaveCurrentConfiguredAccessWithoutAnyStoredAllowance() {
        UUID owner = account(true, false);
        var report = reports.user(owner, HISTORY);
        assertThat(report.path("generatedAt").stringValue()).isEqualTo(now().toString());
        current(report, "FREE", "CONFIG", "2026-10-31T21:00:00Z");
        assertThat(report.path("allowances")).isEmpty();
        assertUnused(owner);
    }

    @Test void aRealMultiMonthPromoRemainsCurrentAcrossQuotaMonthsWithoutConsumption() {
        UUID owner = account(true, false);
        String code = code(tier(PromoType.TIER_MONTHS, "PRO", null, 3, 1));
        var redemption = promo.redeem(owner, jwt(owner), UUID.randomUUID(), code, network());
        String end = redemption.path("validUntil").stringValue();
        var first = reports.user(owner, HISTORY);
        current(first, "PRO", "PROMO", end);
        assertThat(first.path("allowances")).isEmpty();
        assertUnused(owner);
        clock.set("2026-10-31T21:00:00Z");
        current(reports.user(owner, HISTORY), "PRO", "PROMO", end);
        assertUnused(owner);
        clock.set(Instant.parse(end).minusMillis(1).toString());
        current(reports.user(owner, HISTORY), "PRO", "PROMO", end);
        clock.set(end);
        current(reports.user(owner, HISTORY), "FREE", "CONFIG", "2027-01-31T21:00:00Z");
        assertUnused(owner);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.promo_redemption WHERE owner_id=:owner").param("owner", owner).query(Long.class).single()).isEqualTo(1);
    }

    @Test void configuredValidityRollsAtTheActualMoscowMonthBoundaryWithoutInitializingUsage() {
        UUID owner = account(true, false);
        clock.set("2026-10-31T20:59:59.999999999Z");
        current(reports.user(owner, HISTORY), "FREE", "CONFIG", "2026-10-31T21:00:00Z");
        clock.set("2026-10-31T21:00:00Z");
        current(reports.user(owner, HISTORY), "FREE", "CONFIG", "2026-11-30T21:00:00Z");
        assertUnused(owner);
    }

    private static void current(JsonNode report, String plan, String source, String end) {
        JsonNode current = report.path("currentEntitlement");
        assertThat(current.path("plan").stringValue()).isEqualTo(plan);
        assertThat(current.path("source").stringValue()).isEqualTo(source);
        assertThat(current.path("period").stringValue()).isEqualTo("MONTH");
        assertThat(current.path("validUntil").stringValue()).isEqualTo(end);
    }

    private void assertUnused(UUID owner) {
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.usage_allowance WHERE owner_id=:owner").param("owner", owner).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.usage_balance WHERE owner_id=:owner").param("owner", owner).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.usage_ledger_entry WHERE owner_id=:owner").param("owner", owner).query(Long.class).single()).isZero();
    }
}
