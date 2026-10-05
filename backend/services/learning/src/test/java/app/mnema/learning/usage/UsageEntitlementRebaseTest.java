package app.mnema.learning.usage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The month bar follows the entitlement in force, driven through the real {@link InboxEntitlementSource}: a paid
 * snapshot grants its plan's bar once per calendar month, a drop inside a month re-bases the bar to the new plan (never
 * below what is spent or held) and a top-up never stacks a second bar.
 */
class UsageEntitlementRebaseTest extends UsageIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private InboxEntitlementSource source;
    @Autowired private EntitlementInbox inbox;
    @Autowired private AllowanceCatalog catalog;
    @Autowired private UsageState state;
    @Autowired private UsagePolicy policy;
    @Autowired private RateCard rateCard;
    @Autowired private UsageNotifier notifier;

    /** The ledger of the application wired to the real entitlement source instead of the test one. */
    private UsageLedger realLedger() {
        return new UsageLedger(repository, state, calendar, policy, rateCard, catalog, source, notifier, clock);
    }

    private void snapshot(String id, UUID owner, Plan plan, String kind, String start, String end) {
        inbox.accept(new EntitlementInbox.Snapshot(id + "-" + owner, owner, plan, kind, Instant.parse(start),
                Instant.parse(end), JSON.readTree("{}"), Instant.parse(end)));
    }

    private Reservation reserveReal(UUID owner, int credits) {
        return inTx(() -> realLedger().reserve(owner, ReservationScope.SESSION, UUID.randomUUID(), null, credits));
    }

    private int unlockedNow(UUID owner) {
        return repository.balance(owner, calendar.period(now()).id()).orElseThrow().unlocked();
    }

    private int freeBar(Instant at) {
        return state.scheduled(catalog.allowance(Plan.FREE), calendar.period(at), at);
    }

    private long grantedIn(UUID owner, String period) {
        return jdbc.sql("SELECT coalesce(sum(credits),0) FROM app_learning.usage_ledger_entry WHERE owner_id=:o "
                + "AND period_id=:p AND kind IN ('GRANT','ADJUSTMENT')").param("o", owner).param("p", period)
                .query(Long.class).single();
    }

    @Test
    void aMonthSnapshotGrantsItsBarThenTheBarFallsBackToFreeWhenItExpiresInsideTheNextMonth() {
        UUID owner = UUID.randomUUID();
        snapshot("pro", owner, Plan.PRO, "BILLING", "2026-10-05T00:00:00Z", "2026-11-05T00:00:00Z");
        int pro = catalog.allowance(Plan.PRO).credits();

        clock.set("2026-10-06T09:00:00Z");
        reserveReal(owner, 5);
        assertThat(unlockedNow(owner)).isEqualTo(pro);

        clock.set("2026-11-01T09:00:00Z");
        reserveReal(owner, 5);
        assertThat(unlockedNow(owner)).isEqualTo(pro);
        assertThat(grantedIn(owner, "2026-11")).isEqualTo(pro);

        clock.set("2026-11-05T09:00:00Z");
        assertThat(source.current(owner, now()).plan()).isEqualTo(Plan.FREE);
        reserveReal(owner, 1);
        int free = freeBar(now());
        assertThat(free).isLessThan(pro);
        assertThat(unlockedNow(owner)).isEqualTo(free);
        assertThat(grantedIn(owner, "2026-11")).isEqualTo(free);
        assertThat(repository.allowance(owner, "2026-11").orElseThrow().allowance().plan()).isEqualTo(Plan.FREE);
    }

    @Test
    void theRebaseKeepsWhatIsAlreadySpentAndHeldAndIsIdempotent() {
        UUID owner = UUID.randomUUID();
        snapshot("plus", owner, Plan.PLUS, "BILLING", "2026-10-05T00:00:00Z", "2026-11-05T00:00:00Z");
        clock.set("2026-11-02T09:00:00Z");
        Reservation held = reserveReal(owner, 200);
        inTx(() -> realLedger().settle(owner, new UsageLedger.Debit(held.reservationId(), "debit:rebase:1",
                "MATERIAL_MEDIUM", 150, 780_000L, "step:rebase")));
        inTx(() -> realLedger().release(owner, held.reservationId()));

        clock.set("2026-11-05T09:00:00Z");
        var resolved = new UsageState(source, calendar, catalog, repository).resolve(owner, now());
        assertThat(resolved.entitlement().plan()).isEqualTo(Plan.FREE);
        assertThat(state.credits(resolved).unlocked()).isEqualTo(150);
        assertThat(state.credits(resolved).remaining()).isZero();

        // Spent beyond the Free bar: an admission is refused and rolls back, nothing is granted back.
        for (int i = 0; i < 3; i++) {
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> reserveReal(owner, 1)))
                    .isInstanceOf(UsageLimitReachedException.class);
        }
        assertThat(balance(owner)[1]).isEqualTo(150);
        assertThat(grantedIn(owner, "2026-11")).isEqualTo(catalog.allowance(Plan.PLUS).credits());
    }

    @Test
    void aPaidSnapshotInsideAMonthThatHadAFreeBarTopsUpToTheHigherBarWithoutAddingASecondOne() {
        UUID owner = UUID.randomUUID();
        clock.set("2026-10-02T09:00:00Z");
        reserveReal(owner, 5);
        assertThat(unlockedNow(owner)).isEqualTo(freeBar(now()));

        snapshot("pro", owner, Plan.PRO, "BILLING", "2026-10-03T00:00:00Z", "2026-11-03T00:00:00Z");
        clock.set("2026-10-03T09:00:00Z");
        reserveReal(owner, 5);
        int pro = catalog.allowance(Plan.PRO).credits();
        assertThat(unlockedNow(owner)).isEqualTo(pro);
        assertThat(grantedIn(owner, "2026-10")).isEqualTo(pro);
    }

    @Test
    void aYearSnapshotNeverGrantsMoreThanOneBarPerMonth() {
        UUID owner = UUID.randomUUID();
        snapshot("year", owner, Plan.PLUS, "BILLING", "2026-09-30T21:00:00Z", "2027-09-30T21:00:00Z");
        int plus = catalog.allowance(Plan.PLUS).credits();

        for (String at : new String[] {"2026-10-02T09:00:00Z", "2026-10-20T09:00:00Z", "2026-11-03T09:00:00Z",
                "2026-11-30T09:00:00Z", "2026-12-15T09:00:00Z"}) {
            clock.set(at);
            reserveReal(owner, 1);
            assertThat(unlockedNow(owner)).isEqualTo(plus);
            assertThat(grantedIn(owner, calendar.period(now()).id())).isEqualTo(plus);
        }
    }

    @Test
    void aPlanThatIsGrantedRebasedAwayAndGrantedAgainInTheSameMonthStillGetsItsBar() {
        UUID owner = UUID.randomUUID();
        snapshot("promo", owner, Plan.PRO, "PROMO", "2026-10-01T00:00:00Z", "2026-10-10T00:00:00Z");
        clock.set("2026-10-05T09:00:00Z");
        reserveReal(owner, 1);
        int pro = catalog.allowance(Plan.PRO).credits();
        assertThat(unlockedNow(owner)).isEqualTo(pro);

        clock.set("2026-10-11T09:00:00Z");
        reserveReal(owner, 1);
        assertThat(unlockedNow(owner)).isEqualTo(freeBar(now()));

        snapshot("again", owner, Plan.PRO, "BILLING", "2026-10-11T00:00:00Z", "2026-11-11T00:00:00Z");
        clock.set("2026-10-12T09:00:00Z");
        reserveReal(owner, 1);
        assertThat(unlockedNow(owner)).isEqualTo(pro);
    }
}
