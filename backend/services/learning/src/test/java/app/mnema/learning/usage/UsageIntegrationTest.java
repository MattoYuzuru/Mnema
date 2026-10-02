package app.mnema.learning.usage;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/** Shared wiring of the usage tests that need the real database, a movable clock and per-account plans. */
@SpringBootTest
@Import(UsageTestConfiguration.class)
abstract class UsageIntegrationTest extends PostgresIntegrationTest {
    static final String FRIDAY = "2026-10-02T09:00:42Z";

    @Autowired protected UsageLedger ledger;
    @Autowired protected UsageService usage;
    @Autowired protected UsageRepository repository;
    @Autowired protected UsageCalendar calendar;
    @Autowired protected UsageTestConfiguration.MutableClock clock;
    @Autowired protected UsageTestConfiguration.TestEntitlements entitlements;
    @Autowired protected PlatformTransactionManager transactions;
    @Autowired protected JdbcClient jdbc;

    @BeforeEach
    void resetClockAndForgetOtherTestsHolds() {
        clock.set(FRIDAY);
        // The database is shared by every test: holds that earlier tests left behind must not be due in this one.
        jdbc.sql("UPDATE app_learning.usage_reservation SET state='RELEASED', ended_at=:now WHERE state='ACTIVE'")
                .param("now", java.sql.Timestamp.from(Instant.parse(FRIDAY))).update();
    }

    protected UUID owner(Plan plan) {
        UUID owner = UUID.randomUUID();
        entitlements.set(owner, plan);
        return owner;
    }

    protected <T> T inTx(Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> action.get());
    }

    protected void runInTx(java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action) {
        new TransactionTemplate(transactions).executeWithoutResult(action::accept);
    }

    protected Instant now() {
        return clock.now();
    }

    protected Reservation reserve(UUID owner, int credits) {
        return inTx(() -> ledger.reserve(owner, ReservationScope.SESSION, UUID.randomUUID(), null, credits));
    }

    protected UsageLedger.Settlement settle(UUID owner, Reservation reservation, String key, int credits) {
        return inTx(() -> ledger.settle(owner, new UsageLedger.Debit(reservation.reservationId(), key, "MATERIAL_MEDIUM",
                credits, 780_000L, "step:" + key)));
    }

    protected int[] balance(UUID owner) {
        var row = repository.balance(owner, calendar.period(now()).id()).orElseThrow();
        return new int[] {row.unlocked(), row.used(), row.reserved()};
    }

    protected long countOf(String table, UUID owner) {
        return jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE owner_id=:o").param("o", owner)
                .query(Long.class).single();
    }

    protected long notifications(UUID owner, String kind) {
        return jdbc.sql("SELECT count(*) FROM app_learning.notification WHERE owner_id=:o AND kind=:k")
                .param("o", owner).param("k", kind).query(Long.class).single();
    }
}
