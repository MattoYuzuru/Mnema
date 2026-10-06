package app.mnema.learning.usage;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reservation, settlement, release and expiry against real PostgreSQL (Testcontainers) with a movable clock. */
class UsageLedgerTest extends UsageIntegrationTest {

    @Test
    void reservationsNeedTheCallersTransaction() {
        UUID owner = owner(Plan.PLUS);
        assertThatThrownBy(() -> ledger.reserve(owner, ReservationScope.SESSION, null, null, 10))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> ledger.settle(owner, debit(UUID.randomUUID(), "debit:x:1", 1)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> ledger.release(owner, UUID.randomUUID()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> ledger.consume(owner, Bucket.STT, 10, "k:" + owner, null))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void aPaidAccountGetsTheWholeBarAndAHoldThatEndsWithItsPeriodOrTheTtl() {
        UUID owner = owner(Plan.PLUS);
        Reservation reservation = reserve(owner, 10);

        assertThat(balance(owner)).containsExactly(360, 0, 10);
        assertThat(reservation.state()).isEqualTo(ReservationState.ACTIVE);
        assertThat(reservation.periodId()).isEqualTo("2026-10");
        assertThat(reservation.rateCardVersion()).isEqualTo("rc-v1");
        assertThat(reservation.expiresAt()).isEqualTo(now().plus(Duration.ofHours(2)));
        assertThat(jdbc.sql("SELECT credits||':'||idempotency_key FROM app_learning.usage_ledger_entry "
                + "WHERE owner_id=:o AND kind='GRANT'").param("o", owner).query(String.class).single())
                .isEqualTo("360:grant:config-" + owner + "-PLUS:2026-10:0");

        clock.set("2026-10-31T20:30:00Z");
        Reservation late = reserve(owner, 5);
        assertThat(late.expiresAt()).isEqualTo(Instant.parse("2026-10-31T21:00:00Z"));
    }

    @Test
    void aReserveBeyondTheRemainderIsUsageLimitReachedAndWritesNothing() {
        UUID owner = owner(Plan.PLUS);
        reserve(owner, 348);

        var failure = catchLimit(() -> reserve(owner, 44));

        assertThat(failure.block()).isEqualTo(new UsageLimitReachedException.Block(Bucket.CREDITS, Window.MONTH,
                Unit.CREDITS, 360L, 348, 44, true, Instant.parse("2026-10-31T21:00:00Z"), true, Plan.PLUS));
        assertThat(balance(owner)).containsExactly(360, 0, 348);
        assertThat(countOf("usage_reservation", owner)).isOne();
    }

    @Test
    void aFreeAccountIsLimitedByItsOpenedPortionsAndToldWhenTheNextOneOpens() {
        UUID owner = owner(Plan.FREE);
        Reservation held = reserve(owner, 8);
        settle(owner, held, "debit:free:1", 8);

        var failure = catchLimit(() -> reserve(owner, 22));

        assertThat(failure.block()).isEqualTo(new UsageLimitReachedException.Block(Bucket.CREDITS, Window.WEEK,
                Unit.CREDITS, 13L, 8, 22, true, Instant.parse("2026-10-04T21:00:00Z"), false, Plan.FREE));
        // After the second portion (26 credits, 8 used) 18 are free: 6 more than the 5 left now fits then.
        assertThat(catchLimit(() -> reserve(owner, 6)).block().fitsAfterRenewal()).isTrue();
        assertThat(balance(owner)).containsExactly(13, 8, 0);
    }

    @Test
    void aDetailedMaterialFitsAfterTheSecondFreePortionWhenNothingIsSpent() {
        UUID owner = owner(Plan.FREE);
        var failure = catchLimit(() -> reserve(owner, 22));
        assertThat(failure.block().window()).isEqualTo(Window.WEEK);
        assertThat(failure.block().limit()).isEqualTo(13L);
        assertThat(failure.block().fitsAfterRenewal()).isTrue();
        assertThat(failure.block().renewsAt()).isEqualTo(Instant.parse("2026-10-04T21:00:00Z"));
    }

    @Test
    void theLastFreePortionRenewsAtTheEndOfTheMonthWithTheFirstPortionOfTheNext() {
        UUID owner = owner(Plan.FREE);
        clock.set("2026-10-25T09:00:00Z");
        Reservation held = reserve(owner, 50);
        settle(owner, held, "debit:late:1", 50);

        var failure = catchLimit(() -> reserve(owner, 14));

        assertThat(failure.block().window()).isEqualTo(Window.MONTH);
        assertThat(failure.block().limit()).isEqualTo(50L);
        assertThat(failure.block().renewsAt()).isEqualTo(Instant.parse("2026-10-31T21:00:00Z"));
        assertThat(failure.block().fitsAfterRenewal()).isFalse();
        assertThat(catchLimit(() -> reserve(owner, 13)).block().fitsAfterRenewal()).isTrue();
    }

    @Test
    void twoParallelReservesOnABalanceForOneYieldExactlyOneSuccess() throws Exception {
        for (int round = 0; round < 15; round++) {
            UUID owner = owner(Plan.FREE);
            // Open the period first so both racers contest only the balance row.
            inTx(() -> ledger.release(owner, ledger.reserve(owner, ReservationScope.TURN, null, null, 1).reservationId()));

            CountDownLatch firstHolds = new CountDownLatch(1);
            CountDownLatch secondArrives = new CountDownLatch(1);
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                Future<Object> first = pool.submit(() -> outcome(owner, 10, () -> {
                    firstHolds.countDown();
                    await(secondArrives);
                }));
                Future<Object> second = pool.submit(() -> {
                    await(firstHolds);
                    secondArrives.countDown();
                    return outcome(owner, 10, () -> { });
                });
                List<Object> results = List.of(first.get(), second.get());

                assertThat(results.stream().filter(Reservation.class::isInstance)).hasSize(1);
                assertThat(results.stream().filter(UsageLimitReachedException.class::isInstance)).hasSize(1);
            }
            assertThat(balance(owner)).containsExactly(13, 0, 10);
        }
    }

    @Test
    void manyParallelReservesNeverOvercommitTheBalance() throws Exception {
        UUID owner = owner(Plan.PLUS);
        reserve(owner, 1);
        int racers = 12;
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit(() -> {
                    await(go);
                    return outcome(owner, 50, () -> { });
                }));
            }
            go.countDown();
            long granted = 0;
            for (Future<Object> future : futures) {
                if (future.get() instanceof Reservation) granted++;
            }
            // 359 free credits fit seven holds of 50, whatever the interleaving.
            assertThat(granted).isEqualTo(7);
        }
        assertThat(balance(owner)).containsExactly(360, 0, 351);
    }

    @Test
    void aRepeatedSettleWithTheSameKeyChangesNothing() {
        UUID owner = owner(Plan.PLUS);
        Reservation held = reserve(owner, 20);

        var first = settle(owner, held, "debit:step-1:1", 10);
        var repeat = settle(owner, held, "debit:step-1:1", 10);

        assertThat(first.replayed()).isFalse();
        assertThat(repeat.replayed()).isTrue();
        assertThat(first.reservation().debitedCredits()).isEqualTo(10);
        assertThat(balance(owner)).containsExactly(360, 10, 10);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.usage_ledger_entry WHERE owner_id=:o AND kind='DEBIT'")
                .param("o", owner).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT credits||':'||cost_micros||':'||rate_card_version||':'||period_id||':'||reference "
                + "FROM app_learning.usage_ledger_entry WHERE owner_id=:o AND kind='DEBIT'").param("o", owner)
                .query(String.class).single()).isEqualTo("-10:780000:rc-v1:2026-10:step:debit:step-1:1");
        assertThatThrownBy(() -> settle(owner, held, "debit:step-1:1", 9)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDebitBeyondTheRemainingHoldIsRefusedBeforeAnythingIsWritten() {
        UUID owner = owner(Plan.PLUS);
        Reservation held = reserve(owner, 10);
        settle(owner, held, "debit:over:1", 6);

        assertThatThrownBy(() -> settle(owner, held, "debit:over:2", 5))
                .isInstanceOfSatisfying(EstimateExceededException.class, failure -> {
                    assertThat(failure.held()).isEqualTo(4);
                    assertThat(failure.required()).isEqualTo(5);
                });

        assertThat(balance(owner)).containsExactly(360, 6, 4);
        assertThat(repository.ledgerByKey("debit:over:2")).isEmpty();
        // A free outcome (a cache hit) is still recorded.
        assertThat(settle(owner, held, "debit:over:3", 0).replayed()).isFalse();
        assertThat(balance(owner)).containsExactly(360, 6, 4);
    }

    @Test
    void releaseReturnsTheRemainderAndEndsAsSettledOrReleased() {
        UUID owner = owner(Plan.PLUS);
        Reservation spent = reserve(owner, 20);
        settle(owner, spent, "debit:rel:1", 12);
        Reservation idle = reserve(owner, 7);
        assertThat(balance(owner)).containsExactly(360, 12, 15);

        Reservation settled = inTx(() -> ledger.release(owner, spent.reservationId()));
        Reservation released = inTx(() -> ledger.release(owner, idle.reservationId()));
        Reservation again = inTx(() -> ledger.release(owner, spent.reservationId()));

        assertThat(settled.state()).isEqualTo(ReservationState.SETTLED);
        assertThat(released.state()).isEqualTo(ReservationState.RELEASED);
        assertThat(again.state()).isEqualTo(ReservationState.SETTLED);
        assertThat(balance(owner)).containsExactly(360, 12, 0);
        assertThat(usage.read(owner).credits().remaining()).isEqualTo(348);
        assertThatThrownBy(() -> settle(owner, spent, "debit:rel:2", 1)).isInstanceOfSatisfying(
                ReservationNotActiveException.class, failure -> assertThat(failure.state()).isEqualTo(ReservationState.SETTLED));
        // The settle that already happened is still answered as a repeat.
        assertThat(settle(owner, spent, "debit:rel:1", 12).replayed()).isTrue();
    }

    @Test
    void reservationsOfOtherAccountsAndUnknownIdsAreRefused() {
        UUID owner = owner(Plan.PLUS);
        UUID stranger = owner(Plan.PLUS);
        Reservation held = reserve(owner, 3);
        assertThatThrownBy(() -> inTx(() -> ledger.release(stranger, held.reservationId())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settle(stranger, held, "debit:x:1", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inTx(() -> ledger.release(owner, UUID.randomUUID()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reserve(owner, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anExpiredHoldReturnsTheBalanceAndTheUsageViewShowsPeriodRemainingReservedAndRenewal() {
        UUID owner = owner(Plan.PLUS);
        Reservation held = reserve(owner, 30);
        UsageView before = usage.read(owner);
        assertThat(before.credits().reserved()).isEqualTo(30);
        assertThat(before.credits().remaining()).isEqualTo(330);
        assertThat(before.period().periodId()).isEqualTo("2026-10");
        assertThat(before.period().end()).isEqualTo("2026-10-31T21:00:00Z");

        clock.set("2026-10-02T10:59:00Z");
        assertThat(inTx(() -> ledger.expireDue(100))).isZero();
        assertThat(balance(owner)).containsExactly(360, 0, 30);

        clock.set("2026-10-02T11:00:42Z");
        assertThat(inTx(() -> ledger.expireDue(100))).isOne();

        assertThat(repository.reservation(held.reservationId()).orElseThrow().state()).isEqualTo(ReservationState.EXPIRED);
        UsageView after = usage.read(owner);
        assertThat(after.credits().reserved()).isZero();
        assertThat(after.credits().remaining()).isEqualTo(360);
        assertThat(inTx(() -> ledger.expireDue(100))).isZero();
    }

    @Test
    void aPeriodRolloverEndsEveryLiveHoldAndTheNewPeriodStartsFresh() {
        UUID owner = owner(Plan.PLUS);
        clock.set("2026-10-31T20:30:00Z");
        Reservation spent = reserve(owner, 40);
        settle(owner, spent, "debit:roll:1", 15);
        Reservation idle = reserve(owner, 10);

        clock.set("2026-10-31T21:00:00Z");
        assertThat(inTx(() -> ledger.expireDue(100))).isEqualTo(2);

        assertThat(repository.reservation(spent.reservationId()).orElseThrow().state()).isEqualTo(ReservationState.SETTLED);
        assertThat(repository.reservation(idle.reservationId()).orElseThrow().state()).isEqualTo(ReservationState.RELEASED);
        assertThat(repository.balance(owner, "2026-10").orElseThrow().reserved()).isZero();
        assertThat(repository.balance(owner, "2026-10").orElseThrow().used()).isEqualTo(15);
        UsageView november = usage.read(owner);
        assertThat(november.period().periodId()).isEqualTo("2026-11");
        assertThat(november.credits().used()).isZero();
        assertThat(november.credits().remaining()).isEqualTo(360);
        // The new period re-reserves from scratch: nothing carries over.
        assertThat(reserve(owner, 360).periodId()).isEqualTo("2026-11");
    }

    @Test
    void theFreeBarOpensInPortionsAcrossTheMonthAndNothingCarriesOver() {
        UUID owner = owner(Plan.FREE);
        assertThat(unlockedAt(owner, "2026-10-01T00:00:00Z")).isEqualTo(13);
        assertThat(unlockedAt(owner, "2026-10-04T20:59:59Z")).isEqualTo(13);
        assertThat(unlockedAt(owner, "2026-10-04T21:00:00Z")).isEqualTo(26);
        assertThat(unlockedAt(owner, "2026-10-11T21:00:00Z")).isEqualTo(38);
        assertThat(unlockedAt(owner, "2026-10-18T21:00:00Z")).isEqualTo(50);
        // The fifth Monday (26 Oct) unlocks nothing extra.
        assertThat(unlockedAt(owner, "2026-10-25T21:00:00Z")).isEqualTo(50);
        // The month after starts again with the first portion, whatever was left.
        assertThat(unlockedAt(owner, "2026-10-31T21:00:00Z")).isEqualTo(13);
        assertThat(jdbc.sql("SELECT credits FROM app_learning.usage_ledger_entry WHERE owner_id=:o AND kind='GRANT' "
                + "AND period_id='2026-10' ORDER BY created_at").param("o", owner).query(Integer.class).list())
                .containsExactly(13, 13, 12, 12);
    }

    private int unlockedAt(UUID owner, String instant) {
        clock.set(instant);
        Reservation held = reserve(owner, 1);
        int unlocked = balance(owner)[0];
        inTx(() -> ledger.release(owner, held.reservationId()));
        return unlocked;
    }

    @Test
    void aPlanChangeMidPeriodTopsUpOnUpgradeAndRebasesOnDowngradeNeverBelowWhatIsSpent() {
        UUID owner = owner(Plan.FREE);
        Reservation held = reserve(owner, 10);
        settle(owner, held, "debit:up:1", 10);
        entitlements.set(owner, Plan.PLUS);

        reserve(owner, 5);

        assertThat(balance(owner)).containsExactly(360, 10, 5);
        assertThat(repository.allowance(owner, "2026-10").orElseThrow().allowance().plan()).isEqualTo(Plan.PLUS);
        entitlements.set(owner, Plan.FREE);
        // Re-based to the Free bar of the month so far, never below the 15 credits already used or held.
        assertThat(usage.read(owner).credits().unlocked()).isEqualTo(15);
        assertThat(usage.read(owner).plan()).isEqualTo("FREE");
        assertThat(catchLimit(() -> reserve(owner, 1)).block().plan()).isEqualTo(Plan.FREE);
    }

    @Test
    void theDailyBurstLimitsDebitsPerCalendarDayAndNeverFailsAnAdmissionOrASettle() {
        UUID owner = owner(Plan.PLUS);
        assertThat(inTx(() -> ledger.dailyDebitRoom(owner))).contains(new UsageLedger.DailyBurst(126, 0, 126,
                Instant.parse("2026-10-02T21:00:00Z")));

        Reservation big = reserve(owner, 200);
        settle(owner, big, "debit:burst:1", 130);

        assertThat(ledger.dailyDebitRoom(owner)).contains(new UsageLedger.DailyBurst(126, 130, 0,
                Instant.parse("2026-10-02T21:00:00Z")));
        assertThat(reserve(owner, 100).state()).isEqualTo(ReservationState.ACTIVE);

        clock.set("2026-10-02T21:00:00Z");
        assertThat(ledger.dailyDebitRoom(owner)).contains(new UsageLedger.DailyBurst(126, 0, 126,
                Instant.parse("2026-10-03T21:00:00Z")));
        assertThat(ledger.dailyDebitRoom(owner(Plan.FREE))).isEmpty();
        assertThat(usage.read(owner).dailyBurst().debitedTodayCredits()).isZero();
    }

    @Test
    void aRollbackLeavesNoHoldNoDebitNoGrantAndNoNotification() {
        UUID owner = owner(Plan.PLUS);
        Reservation held = reserve(owner, 360);
        runInTx(status -> {
            ledger.settle(owner, debit(held.reservationId(), "debit:rb:1", 360));
            status.setRollbackOnly();
        });
        assertThat(repository.ledgerByKey("debit:rb:1")).isEmpty();
        assertThat(balance(owner)).containsExactly(360, 0, 360);
        assertThat(notifications(owner, "USAGE_EXHAUSTED")).isZero();
        assertThat(notifications(owner, "USAGE_LOW")).isZero();
    }

    @Test
    void theLedgerIsAppendOnlyAndTheBalanceCanNeverGoNegative() {
        UUID owner = owner(Plan.PLUS);
        reserve(owner, 10);
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.usage_ledger_entry SET credits=1 WHERE owner_id=:o")
                .param("o", owner).update()).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.usage_balance SET used=400 WHERE owner_id=:o")
                .param("o", owner).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.usage_balance SET reserved=-1 WHERE owner_id=:o")
                .param("o", owner).update()).isInstanceOf(DataAccessException.class);
    }

    @Test
    void aReplayedSettleMustBeTheSameDebitInEveryField() {
        UUID owner = owner(Plan.PLUS);
        Reservation held = reserve(owner, 20);
        var original = new UsageLedger.Debit(held.reservationId(), "debit:same:" + owner, "MATERIAL_MEDIUM", 5, 100L, "step:a");
        assertThat(inTx(() -> ledger.settle(owner, original)).replayed()).isFalse();
        assertThat(inTx(() -> ledger.settle(owner, original)).replayed()).isTrue();

        for (UsageLedger.Debit changed : List.of(
                new UsageLedger.Debit(held.reservationId(), original.idempotencyKey(), "EDIT_SELECTION", 5, 100L, "step:a"),
                new UsageLedger.Debit(held.reservationId(), original.idempotencyKey(), "MATERIAL_MEDIUM", 5, 101L, "step:a"),
                new UsageLedger.Debit(held.reservationId(), original.idempotencyKey(), "MATERIAL_MEDIUM", 5, null, "step:a"),
                new UsageLedger.Debit(held.reservationId(), original.idempotencyKey(), "MATERIAL_MEDIUM", 5, 100L, "step:b"),
                new UsageLedger.Debit(held.reservationId(), original.idempotencyKey(), "MATERIAL_MEDIUM", 5, 100L, null),
                new UsageLedger.Debit(held.reservationId(), original.idempotencyKey(), "MATERIAL_MEDIUM", 6, 100L, "step:a"))) {
            assertThatThrownBy(() -> inTx(() -> ledger.settle(owner, changed))).isInstanceOf(IllegalArgumentException.class);
        }
        // Another account holding the key's reservation cannot reach it at all.
        UUID stranger = owner(Plan.PLUS);
        assertThatThrownBy(() -> inTx(() -> ledger.settle(stranger, original))).isInstanceOf(IllegalArgumentException.class);
        assertThat(balance(owner)).containsExactly(360, 5, 15);
    }

    @Test
    void renewExtendsALiveHoldNeverShortensItAndTheSweepLeavesItAlone() {
        UUID owner = owner(Plan.PLUS);
        Reservation held = reserve(owner, 30);
        assertThat(held.expiresAt()).isEqualTo(Instant.parse("2026-10-02T11:00:42Z"));

        clock.set("2026-10-02T10:30:00Z");
        Reservation renewed = inTx(() -> ledger.renew(owner, held.reservationId()));
        assertThat(renewed.expiresAt()).isEqualTo(Instant.parse("2026-10-02T12:30:00Z"));
        assertThat(renewed.state()).isEqualTo(ReservationState.ACTIVE);
        // Repeating it at the same instant changes nothing; an earlier clock never shortens the hold.
        assertThat(inTx(() -> ledger.renew(owner, held.reservationId())).expiresAt()).isEqualTo(renewed.expiresAt());
        clock.set("2026-10-02T09:30:00Z");
        assertThat(inTx(() -> ledger.renew(owner, held.reservationId())).expiresAt()).isEqualTo(renewed.expiresAt());

        clock.set("2026-10-02T11:30:00Z");
        assertThat(inTx(() -> ledger.expireDue(100))).isZero();
        assertThat(balance(owner)).containsExactly(360, 0, 30);
        clock.set("2026-10-02T12:30:00Z");
        assertThat(inTx(() -> ledger.expireDue(100))).isOne();
        assertThat(repository.reservation(held.reservationId()).orElseThrow().state()).isEqualTo(ReservationState.EXPIRED);
    }

    @Test
    void renewNeverMovesAHoldPastItsPeriod() {
        UUID owner = owner(Plan.PLUS);
        clock.set("2026-10-31T19:30:00Z");
        Reservation held = reserve(owner, 10);
        assertThat(held.expiresAt()).isEqualTo(Instant.parse("2026-10-31T21:00:00Z"));
        clock.set("2026-10-31T20:30:00Z");
        assertThat(inTx(() -> ledger.renew(owner, held.reservationId())).expiresAt())
                .isEqualTo(Instant.parse("2026-10-31T21:00:00Z"));

        // After the period ended the hold is returned unchanged and the next sweep ends it.
        clock.set("2026-10-31T21:00:00Z");
        assertThat(inTx(() -> ledger.renew(owner, held.reservationId())).expiresAt())
                .isEqualTo(Instant.parse("2026-10-31T21:00:00Z"));
        assertThat(inTx(() -> ledger.expireDue(100))).isOne();
    }

    @Test
    void renewNeedsALiveHoldOfTheSameOwnerAndLeavesTheCallersTransactionUsable() {
        UUID owner = owner(Plan.PLUS);
        Reservation held = reserve(owner, 10);
        inTx(() -> ledger.release(owner, held.reservationId()));

        assertThatThrownBy(() -> inTx(() -> ledger.renew(owner, held.reservationId())))
                .isInstanceOfSatisfying(ReservationNotActiveException.class,
                        failure -> assertThat(failure.state()).isEqualTo(ReservationState.RELEASED));
        assertThatThrownBy(() -> inTx(() -> ledger.renew(owner(Plan.PLUS), held.reservationId())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inTx(() -> ledger.renew(owner, UUID.randomUUID()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.renew(owner, held.reservationId()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);

        // The caller records the failure and commits: the transaction was not marked rollback-only.
        Reservation live = reserve(owner, 10);
        String state = inTx(() -> {
            try {
                ledger.renew(owner, held.reservationId());
            } catch (ReservationNotActiveException expected) {
                // fall through: the artifact failure is recorded by the caller
            }
            return ledger.release(owner, live.reservationId()).state().name();
        });
        assertThat(state).isEqualTo("RELEASED");
    }

    @Test
    void anExpectedSettleRefusalDoesNotPoisonTheCallersTransactionButALimitRefusalDoes() {
        UUID owner = owner(Plan.PLUS);
        Reservation held = reserve(owner, 10);

        // ESTIMATE_EXCEEDED: the caller fails the artifact and still commits its own writes.
        Reservation after = inTx(() -> {
            try {
                ledger.settle(owner, debit(held.reservationId(), "debit:nr:1", 11));
            } catch (EstimateExceededException expected) {
                // recorded by the caller
            }
            return ledger.release(owner, held.reservationId());
        });
        assertThat(after.state()).isEqualTo(ReservationState.RELEASED);

        // The hold has ended: a late debit is refused the same way, and the caller commits all the same.
        inTx(() -> {
            try {
                ledger.settle(owner, debit(held.reservationId(), "debit:nr:2", 1));
            } catch (ReservationNotActiveException expected) {
                // recorded by the caller
            }
            return ledger.release(owner, held.reservationId());
        });

        // USAGE_LIMIT_REACHED keeps its rollback semantics: catching it inside the transaction cannot commit.
        assertThatThrownBy(() -> inTx(() -> {
            try {
                ledger.reserve(owner, ReservationScope.TURN, null, null, 1_000);
            } catch (UsageLimitReachedException expected) {
                // swallowed on purpose
            }
            return "committed";
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
    }

    @Test
    void twoSettlesOfTheSameKeyInParallelDebitOnce() throws Exception {
        UUID owner = owner(Plan.PLUS);
        Reservation held = reserve(owner, 20);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<UsageLedger.Settlement>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    await(go);
                    return inTx(() -> ledger.settle(owner, debit(held.reservationId(), "debit:par:" + owner, 7)));
                }));
            }
            go.countDown();
            List<Boolean> replayed = List.of(results.get(0).get().replayed(), results.get(1).get().replayed());
            assertThat(replayed).containsExactlyInAnyOrder(false, true);
        }
        assertThat(balance(owner)).containsExactly(360, 7, 13);
        assertThat(countOf("usage_ledger_entry", owner)).isEqualTo(2);
    }

    @Test
    void aSettleThatHoldsTheReservationIsSkippedByTheSweepAndTheHoldSurvives() throws Exception {
        UUID owner = owner(Plan.PLUS);
        Reservation held = reserve(owner, 20);
        clock.set("2026-10-02T11:00:42Z");
        CountDownLatch settled = new CountDownLatch(1);
        CountDownLatch swept = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> settling = pool.submit(() -> inTx(() -> {
                ledger.settle(owner, debit(held.reservationId(), "debit:sweep:" + owner, 8));
                settled.countDown();
                await(swept);
                return null;
            }));
            await(settled);
            int ended = inTx(() -> ledger.expireDue(100));
            swept.countDown();
            settling.get();
            assertThat(ended).isZero();
        }
        // The debit committed and the hold is still live: the next sweep, not this one, ends it.
        assertThat(repository.reservation(held.reservationId()).orElseThrow().state()).isEqualTo(ReservationState.ACTIVE);
        assertThat(balance(owner)).containsExactly(360, 8, 12);
        assertThat(inTx(() -> ledger.expireDue(100))).isOne();
        assertThat(balance(owner)).containsExactly(360, 8, 0);
    }

    // ------------------------------------------------------------------ helpers

    private UsageLedger.Debit debit(UUID reservationId, String key, int credits) {
        return new UsageLedger.Debit(reservationId, key, "MATERIAL_MEDIUM", credits, null, null);
    }

    private UsageLimitReachedException catchLimit(Runnable action) {
        try {
            action.run();
        } catch (UsageLimitReachedException failure) {
            return failure;
        }
        throw new AssertionError("expected USAGE_LIMIT_REACHED");
    }

    /**
     * One admission in its own transaction. A refusal propagates through the transaction, which rolls back (a 409 never
     * leaves a partial admission), so it is caught outside it. {@code beforeCommit} runs after a successful reserve.
     */
    private Object outcome(UUID owner, int credits, Runnable beforeCommit) {
        try {
            return inTx(() -> {
                Reservation reservation = ledger.reserve(owner, ReservationScope.SESSION, UUID.randomUUID(), null, credits);
                beforeCommit.run();
                return reservation;
            });
        } catch (UsageLimitReachedException failure) {
            return failure;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }
}
