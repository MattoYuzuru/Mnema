package app.mnema.learning.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The reconciler: unfinished orders whose notification never came are asked about, once per interval, and the hopeless ones are closed. */
class BillingReconciliationTest extends BillingIntegrationTest {
    @Autowired private PlatformTransactionManager transactions;

    /** Orders of other tests share the database; only the ones of this test may be open, or the batch and the counts would not be ours. */
    @BeforeEach
    void closeLeftovers() {
        jdbc.sql("UPDATE app_learning.billing_order SET status='FAILED', failure_reason='TEST' WHERE status IN ('CREATED','PENDING')").update();
    }

    @Test
    void aMissedConfirmationIsFoundAndPaidOnceTheOrderIsOldEnough() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = open(owner, "PLUS").path("orderId").stringValue(null);
        BANK.move(orderId, "CONFIRMED");

        assertThat(reconciliation.runOnce()).as("a fresh order is the notification's business first").isZero();
        assertThat(status(orderId)).isEqualTo("PENDING");

        clock.advance(Duration.ofMinutes(3));
        assertThat(reconciliation.runOnce()).isEqualTo(1);

        assertThat(status(orderId)).isEqualTo("PAID");
        assertThat(snapshots(owner)).isEqualTo(1);
        assertThat(events(orderId)).contains("RECONCILE:CONFIRMED:PAID");
        assertThat(reconciliation.runOnce()).isZero();
    }

    @Test
    void anOrderIsLookedAtOncePerReconcileInterval() throws Exception {
        UUID owner = UUID.randomUUID();
        open(owner, "PLUS");
        clock.advance(Duration.ofMinutes(2));

        assertThat(reconciliation.runOnce()).isEqualTo(1);
        assertThat(reconciliation.runOnce()).isZero();
        clock.advance(Duration.ofSeconds(100));
        assertThat(reconciliation.runOnce()).isZero();
        clock.advance(Duration.ofSeconds(21));
        assertThat(reconciliation.runOnce()).isEqualTo(1);
        assertThat(BANK.getStates).hasSize(2);
        assertThat(BANK.badSignatures).hasValue(0);
    }

    @Test
    void aRejectedPaymentFailsAndABankThatDoesNotAnswerIsRetriedNextTime() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = open(owner, "PLUS").path("orderId").stringValue(null);
        clock.advance(Duration.ofMinutes(3));
        BANK.breakGetState = true;

        assertThat(reconciliation.runOnce()).isEqualTo(1);
        assertThat(status(orderId)).isEqualTo("PENDING");

        BANK.breakGetState = false;
        BANK.move(orderId, "REJECTED");
        clock.advance(Duration.ofMinutes(3));
        assertThat(reconciliation.runOnce()).isEqualTo(1);

        assertThat(status(orderId)).isEqualTo("FAILED");
        assertThat(column(orderId, "failure_reason")).isEqualTo("REJECTED");
        assertThat(snapshots(owner)).isZero();
    }

    @Test
    void anOrderWhoseInitNeverAnsweredFailsOnlyAfterItsLinkTimeIsOver() throws Exception {
        UUID owner = UUID.randomUUID();
        BANK.refuseInit = true;
        assertThat(checkout(owner, "PLUS").getStatus()).isEqualTo(503);
        String orderId = jdbc.sql("SELECT order_id::text FROM app_learning.billing_order WHERE owner_id=:owner").param("owner", owner).query(String.class).single();

        clock.advance(Duration.ofMinutes(5));
        assertThat(reconciliation.runOnce()).isEqualTo(1);
        assertThat(status(orderId)).isEqualTo("CREATED");

        clock.advance(Duration.ofMinutes(60));
        assertThat(reconciliation.runOnce()).isEqualTo(1);

        assertThat(status(orderId)).isEqualTo("FAILED");
        assertThat(column(orderId, "failure_reason")).isEqualTo("INIT_FAILED");
        assertThat(BANK.getStates).as("there was no payment to ask about").isEmpty();
        assertThat(events(orderId)).contains("RECONCILE:-:FAILED");
        assertThat(applier.failUninitialized(UUID.fromString(orderId))).isFalse();
    }

    @Test
    void aFormNeverCompletedIsAbandonedADayAfterTheLinkEndedButAHalfDonePaymentIsKept() throws Exception {
        UUID owner = UUID.randomUUID();
        String abandoned = open(owner, "PLUS").path("orderId").stringValue(null);
        String authorized = open(owner, "PRO").path("orderId").stringValue(null);
        BANK.move(authorized, "AUTHORIZED");

        clock.advance(Duration.ofHours(24).plusMinutes(59));
        reconciliation.runOnce();
        assertThat(status(abandoned)).isEqualTo("PENDING");

        clock.advance(Duration.ofMinutes(3));
        reconciliation.runOnce();

        assertThat(status(abandoned)).isEqualTo("FAILED");
        assertThat(column(abandoned, "failure_reason")).isEqualTo("EXPIRED");
        assertThat(status(authorized)).as("the bank still holds the money: it is not ours to give up on").isEqualTo("PENDING");
        assertThat(applier.failAbandoned(UUID.fromString(authorized))).isFalse();
        assertThat(applier.failAbandoned(UUID.fromString(abandoned))).isFalse();
    }

    @Test
    void reconcilersRunningTogetherTakeDisjointOrders() throws Exception {
        for (int index = 0; index < 6; index++) open(UUID.randomUUID(), "PLUS");
        clock.advance(Duration.ofMinutes(3));
        List<Callable<Integer>> passes = new ArrayList<>();
        for (int index = 0; index < 3; index++) passes.add(reconciliation::runOnce);
        int total = 0;

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Integer> pass : pool.invokeAll(passes)) total += pass.get();
        }

        assertThat(total).isEqualTo(6);
        assertThat(BANK.getStates).hasSize(6);
    }

    @Test
    void withoutCredentialsNothingIsClaimed() throws Exception {
        open(UUID.randomUUID(), "PLUS");
        clock.advance(Duration.ofMinutes(3));
        BillingSettings unset = new BillingSettings("ON", "", "", BANK.baseUrl(), "", "", Duration.ofHours(1), Duration.ofSeconds(10), 10, Duration.ofMinutes(2),
                Duration.ofSeconds(5), Duration.ofSeconds(5));

        assertThat(new PaymentReconciliation(repository, unset, bank, applier, clock, transactions).runOnce()).isZero();

        assertThat(BANK.getStates).isEmpty();
    }

    @Test
    void theScheduleRunsAPassAndSurvivesAFailingOne() {
        PaymentReconciliation pass = mock(PaymentReconciliation.class);
        PaymentReconciler schedule = new PaymentReconciler(pass);

        schedule.reconcile();
        verify(pass).runOnce();

        doThrow(new IllegalStateException("database down")).when(pass).runOnce();
        schedule.reconcile();
    }
}
