package app.mnema.learning.platform.security;

import app.mnema.learning.platform.api.RateLimitedException;
import app.mnema.learning.platform.security.ProfileLookupBudget.Pool;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The windows and pools of the shared by-username budget, on a fake monotonic clock. */
class ProfileLookupBudgetTest {
    private static final long SECOND = 1_000_000_000L;
    private static final long WINDOW = 900 * SECOND;

    private final AtomicLong clock = new AtomicLong(0);

    private ProfileLookupBudget budget(int instance, int complaints, int owner, int reporter) {
        return new ProfileLookupBudget(instance, complaints, owner, reporter, clock::get);
    }

    private static long wait(Runnable refused) {
        RateLimitedException[] caught = new RateLimitedException[1];
        assertThatThrownBy(refused::run).isInstanceOfSatisfying(RateLimitedException.class, failure -> caught[0] = failure);
        return caught[0].retryAfterSeconds();
    }

    @Test
    void theDefaultsAreTheDocumentedOnesAndTheWindowIsIdentitys() {
        assertThat(ProfileLookupBudget.WINDOW).isEqualTo(java.time.Duration.ofMinutes(15));
        assertThat(new int[] {ProfileLookupBudget.DEFAULT_PER_INSTANCE, ProfileLookupBudget.DEFAULT_COMPLAINTS, ProfileLookupBudget.DEFAULT_PER_OWNER,
                ProfileLookupBudget.DEFAULT_PER_REPORTER}).containsExactly(40, 10, 10, 3);
        ProfileLookupBudget defaults = new ProfileLookupBudget();
        UUID owner = UUID.randomUUID();
        for (int index = 0; index < 10; index++) defaults.admit(Pool.GRANTS, owner);
        assertThatThrownBy(() -> defaults.admit(Pool.GRANTS, owner)).isInstanceOf(RateLimitedException.class);
        UUID reporter = UUID.randomUUID();
        for (int index = 0; index < 3; index++) defaults.admit(Pool.COMPLAINTS, reporter);
        assertThatThrownBy(() -> defaults.admit(Pool.COMPLAINTS, reporter)).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void anOwnerHasHisOwnCapPerWindowAndTheRetryAfterIsTheRestOfTheWindow() {
        ProfileLookupBudget budget = budget(40, 10, 10, 3);
        UUID owner = UUID.randomUUID();
        for (int index = 0; index < 10; index++) budget.admit(Pool.GRANTS, owner);
        clock.addAndGet(100 * SECOND);
        assertThat(wait(() -> budget.admit(Pool.GRANTS, owner))).isEqualTo(800);
        // another owner is not affected; a minute later the same owner is still refused (a minute is not Identity's unit), until the window ends
        budget.admit(Pool.GRANTS, UUID.randomUUID());
        clock.addAndGet(60 * SECOND);
        assertThat(wait(() -> budget.admit(Pool.GRANTS, owner))).isEqualTo(740);
        clock.addAndGet(740 * SECOND);
        budget.admit(Pool.GRANTS, owner);
    }

    @Test
    void aFractionOfASecondRoundsUpAndTheWaitIsNeverZero() {
        ProfileLookupBudget budget = budget(1, 1, 1, 1);
        budget.admit(Pool.GRANTS, UUID.randomUUID());
        clock.addAndGet(WINDOW - 1);
        assertThat(wait(() -> budget.admit(Pool.GRANTS, UUID.randomUUID()))).isEqualTo(1);
    }

    @Test
    void aReporterHasHisOwnSmallerCapAndTheTwoPoolsKeepSeparateAccountTables() {
        ProfileLookupBudget budget = budget(40, 10, 10, 3);
        UUID account = UUID.randomUUID();
        for (int index = 0; index < 3; index++) budget.admit(Pool.COMPLAINTS, account);
        assertThat(wait(() -> budget.admit(Pool.COMPLAINTS, account))).isEqualTo(900);
        // the same account as an owner has his own ten
        for (int index = 0; index < 10; index++) budget.admit(Pool.GRANTS, account);
        assertThatThrownBy(() -> budget.admit(Pool.GRANTS, account)).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void complaintsAreCappedWithinTheInstanceTotalSoTheyCanNeverStarveGrants() {
        ProfileLookupBudget budget = budget(40, 10, 10, 3);
        // 4 reporters x 3 = 12 wanted, only 10 admitted
        int admitted = 0;
        for (int reporter = 0; reporter < 4; reporter++) {
            UUID who = UUID.randomUUID();
            for (int index = 0; index < 3; index++) {
                try {
                    budget.admit(Pool.COMPLAINTS, who);
                    admitted++;
                } catch (RateLimitedException refused) {
                    assertThat(refused.retryAfterSeconds()).isEqualTo(900);
                }
            }
        }
        assertThat(admitted).isEqualTo(10);
        // grants still have the other 30: three owners x 10
        for (int owner = 0; owner < 3; owner++) {
            UUID who = UUID.randomUUID();
            for (int index = 0; index < 10; index++) budget.admit(Pool.GRANTS, who);
        }
        // and now the total of 40 is spent for everybody
        assertThat(wait(() -> budget.admit(Pool.GRANTS, UUID.randomUUID()))).isEqualTo(900);
        assertThat(wait(() -> budget.admit(Pool.COMPLAINTS, UUID.randomUUID()))).isEqualTo(900);
    }

    @Test
    void grantsMayTakeTheWholeInstanceTotalWhenNobodyComplains() {
        ProfileLookupBudget budget = budget(40, 10, 10, 3);
        for (int owner = 0; owner < 4; owner++) {
            UUID who = UUID.randomUUID();
            for (int index = 0; index < 10; index++) budget.admit(Pool.GRANTS, who);
        }
        assertThat(wait(() -> budget.admit(Pool.COMPLAINTS, UUID.randomUUID()))).isEqualTo(900);
    }

    @Test
    void aRefusedCallSpendsNothingAndEveryCounterStartsOverWithTheWindow() {
        ProfileLookupBudget budget = budget(5, 2, 4, 2);
        UUID reporter = UUID.randomUUID();
        budget.admit(Pool.COMPLAINTS, reporter);
        budget.admit(Pool.COMPLAINTS, reporter);
        // the pool of complaints is spent: this refusal must not cost the owner's or the total's place
        for (int index = 0; index < 20; index++) assertThatThrownBy(() -> budget.admit(Pool.COMPLAINTS, UUID.randomUUID())).isInstanceOf(RateLimitedException.class);
        UUID owner = UUID.randomUUID();
        for (int index = 0; index < 3; index++) budget.admit(Pool.GRANTS, owner);
        assertThat(wait(() -> budget.admit(Pool.GRANTS, owner))).as("the instance total of five").isEqualTo(900);
        clock.addAndGet(WINDOW);
        for (int index = 0; index < 3; index++) budget.admit(Pool.GRANTS, owner);
        budget.admit(Pool.COMPLAINTS, reporter);
        budget.admit(Pool.COMPLAINTS, reporter);
    }

    @Test
    void lookupCallsIdentityAfterAdmissionAndReturnsItsAnswerButNeverCallsWhenRefused() {
        ProfileLookupBudget budget = budget(5, 2, 1, 1);
        UUID owner = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        assertThat(budget.lookup(Pool.GRANTS, owner, () -> {
            calls.incrementAndGet();
            return "answer";
        })).isEqualTo("answer");
        assertThatThrownBy(() -> budget.lookup(Pool.GRANTS, owner, () -> {
            calls.incrementAndGet();
            return "never";
        })).isInstanceOf(RateLimitedException.class);
        assertThat(calls.get()).isEqualTo(1);
        // other failures pass through untouched and the place stays spent
        assertThatThrownBy(() -> budget.lookup(Pool.GRANTS, UUID.randomUUID(), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void identityAnswering429SpendsTheWholeBudgetUntilTheEndOfTheWindowAndTheWaitIsOurs() {
        ProfileLookupBudget budget = budget(40, 10, 10, 3);
        clock.addAndGet(200 * SECOND);
        budget.admit(Pool.GRANTS, UUID.randomUUID());
        clock.addAndGet(100 * SECOND);
        // Identity's own answer carried no useful wait (the directory leaves the minimum): the caller gets the rest of the window, not 1 and not 30
        assertThatThrownBy(() -> budget.lookup(Pool.COMPLAINTS, UUID.randomUUID(), () -> {
            throw new RateLimitedException(0);
        })).isInstanceOfSatisfying(RateLimitedException.class, refused -> assertThat(refused.retryAfterSeconds()).isEqualTo(800));
        // everybody is refused now, in both pools, with the shrinking rest of the same window, and nobody reaches Identity
        AtomicInteger calls = new AtomicInteger();
        clock.addAndGet(50 * SECOND);
        for (Pool pool : Pool.values()) {
            assertThatThrownBy(() -> budget.lookup(pool, UUID.randomUUID(), () -> {
                calls.incrementAndGet();
                return null;
            })).isInstanceOfSatisfying(RateLimitedException.class, refused -> assertThat(refused.retryAfterSeconds()).isEqualTo(750));
        }
        assertThat(calls.get()).isZero();
        // a longer wait that Identity itself names is kept
        clock.addAndGet(750 * SECOND);
        assertThat(budget.lookup(Pool.GRANTS, UUID.randomUUID(), () -> "open again")).isEqualTo("open again");
        assertThatThrownBy(() -> budget.lookup(Pool.GRANTS, UUID.randomUUID(), () -> {
            throw new RateLimitedException(5_000);
        })).isInstanceOfSatisfying(RateLimitedException.class, refused -> assertThat(refused.retryAfterSeconds()).isEqualTo(5_000));
    }

    @Test
    void theCapsAreValidatedAtStart() {
        // instance, complaints, owner, reporter: every cap is 1..1000, and complaints/owner may not exceed the instance, a reporter not the complaints' pool
        for (int[] bad : new int[][] {{0, 1, 1, 1}, {1_001, 10, 10, 3}, {40, 0, 10, 3}, {40, 41, 10, 3}, {40, 10, 0, 3}, {40, 10, 41, 3}, {40, 10, 10, 0}, {40, 10, 10, 11}}) {
            assertThatThrownBy(() -> new ProfileLookupBudget(bad[0], bad[1], bad[2], bad[3], System::nanoTime)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatCode(() -> new ProfileLookupBudget(1_000, 1_000, 1_000, 1_000, System::nanoTime)).doesNotThrowAnyException();
        assertThatCode(() -> new ProfileLookupBudget(1, 1, 1, 1, System::nanoTime)).doesNotThrowAnyException();
    }
}
