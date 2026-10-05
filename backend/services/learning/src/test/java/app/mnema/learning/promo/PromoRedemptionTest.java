package app.mnema.learning.promo;

import app.mnema.learning.platform.api.IdentityUnavailableException;
import app.mnema.learning.platform.api.RateLimitedException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.usage.Entitlement;
import app.mnema.learning.usage.EntitlementInbox;
import app.mnema.learning.usage.Plan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

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

/** Redemption rules: limits, exactness under concurrency, the entitlement a tier code publishes and the discount a percentage code stores. */
class PromoRedemptionTest extends PromoIntegrationTest {
    @Autowired private EntitlementInbox inbox;

    private JsonNode redeem(UUID account, String code, PromoClient client) {
        return promo.redeem(account, jwt(account), UUID.randomUUID(), code, client);
    }

    private PromoRejectedException.Reason refusal(UUID account, String code) {
        return refusal(account, code, network());
    }

    private PromoRejectedException.Reason refusal(UUID account, String code, PromoClient client) {
        try {
            redeem(account, code, client);
        } catch (PromoRejectedException rejected) {
            return rejected.reason();
        }
        throw new AssertionError("The redemption was not refused");
    }

    @Test
    void aCodeWithTenRedemptionsIsRedeemedByExactlyTenOfTwentyParallelAccounts() throws Exception {
        String code = code(tier(PromoType.TIER_DAYS, "PLUS", 15, null, 10));
        int parallel = 20;
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> outcomes = new ArrayList<>();
        for (int index = 0; index < parallel; index++) {
            UUID account = account(true, false);
            PromoClient client = network();
            outcomes.add(pool.submit(() -> {
                start.await();
                try {
                    redeem(account, code, client);
                    return "OK";
                } catch (PromoRejectedException rejected) {
                    return rejected.reason().name();
                }
            }));
        }
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> outcome : outcomes) results.add(outcome.get());
        pool.shutdown();

        assertThat(results.stream().filter("OK"::equals)).hasSize(10);
        assertThat(results.stream().filter("EXHAUSTED"::equals)).hasSize(10);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.promo_redemption r JOIN app_learning.promo_code c USING (code_id) WHERE c.code_hash=:hash")
                .param("hash", PromoCodes.hash(PromoCodes.normalize(code).orElseThrow())).query(Long.class).single()).isEqualTo(10);
    }

    @Test
    void anAccountRedeemsACodeOnceAndTheSecondTryIsAlreadyUsed() {
        String code = code(tier(PromoType.TIER_DAYS, "PLUS", 15, null, 5));
        UUID account = account(true, false);

        redeem(account, code, network());

        assertThat(refusal(account, code)).isEqualTo(PromoRejectedException.Reason.ALREADY_USED);
    }

    @Test
    void aCodeThatMayBeUsedRepeatedlyByOneAccountIsAllowedAgain() {
        var command = new PromoAdminService.Create(PromoType.TIER_DAYS, "PLUS", 3, null, null, null, null, 5, false, null, null);
        String code = code(command);
        UUID account = account(true, false);

        redeem(account, code, network());
        redeem(account, code, network());

        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.promo_redemption WHERE owner_id=:o").param("o", account)
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void theSixthAttemptInAnHourIsRateLimitedPerAccountAndTheWindowSlides() {
        UUID account = account(true, false);
        for (int attempt = 0; attempt < 5; attempt++) assertThat(refusal(account, "NOSUCHCODE" + attempt)).isEqualTo(PromoRejectedException.Reason.INVALID);

        assertThatThrownBy(() -> redeem(account, "NOSUCHCODE", network())).isInstanceOfSatisfying(RateLimitedException.class,
                limited -> assertThat(limited.retryAfterSeconds()).isBetween(3_500L, 3_601L));

        clock.set("2026-10-02T10:00:50Z");
        assertThat(refusal(account, "NOSUCHCODE")).isEqualTo(PromoRejectedException.Reason.INVALID);
    }

    @Test
    void theAddressHasItsOwnHourlyLimitAcrossAccounts() {
        PromoClient shared = network();
        for (int attempt = 0; attempt < 5; attempt++) refusal(account(true, false), "NOSUCHCODE", shared);

        assertThatThrownBy(() -> redeem(account(true, false), "NOSUCHCODE", shared)).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void anAddressThatAlreadyServedThreeOtherAccountsIsRefusedByVelocity() {
        PromoClient farm = network();
        String code = code(new PromoAdminService.Create(PromoType.TIER_DAYS, "PLUS", 3, null, null, null, null, 50, true, null, null));
        for (int index = 0; index < 3; index++) redeem(account(true, false), code, farm);

        assertThat(refusal(account(true, false), code, farm)).isEqualTo(PromoRejectedException.Reason.VELOCITY);

        clock.set("2026-10-03T09:00:50Z");
        redeem(account(true, false), code, farm);
    }

    @Test
    void anUnverifiedEmailIsNotEligibleAndAnIdentityThatDoesNotAnswerRefusesToo() {
        String code = code(tier(PromoType.TIER_DAYS, "PLUS", 15, null, 5));
        UUID unverified = account(true, false);
        standings.put(unverified, false, false);

        assertThat(refusal(unverified, code)).isEqualTo(PromoRejectedException.Reason.NOT_ELIGIBLE);

        UUID verified = account(true, false);
        standings.unavailable = true;
        assertThatThrownBy(() -> redeem(verified, code, network())).isInstanceOf(IdentityUnavailableException.class);
        assertThat(entitlements.current(verified, now()).plan()).isEqualTo(Plan.FREE);
    }

    @Test
    void unknownDisabledExpiredAndNotStartedCodesAllLookInvalid() {
        UUID account = account(true, false);
        var disabled = admin.create(UUID.randomUUID(), tier(PromoType.TIER_DAYS, "PLUS", 5, null, 5));
        admin.setEnabled(UUID.randomUUID(), UUID.fromString(disabled.path("codeId").stringValue(null)), false);
        String expired = code(new PromoAdminService.Create(PromoType.TIER_DAYS, "PLUS", 5, null, null,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"), 5, true, null, null));
        String future = code(new PromoAdminService.Create(PromoType.TIER_DAYS, "PLUS", 5, null, null,
                Instant.parse("2026-11-01T00:00:00Z"), null, 5, true, null, null));

        for (String code : new String[] {"UNKNOWN-CODE", disabled.path("code").stringValue(null), expired, future, "!!", "x"}) {
            assertThat(refusal(account(true, false), code)).as(code).isEqualTo(PromoRejectedException.Reason.INVALID);
        }
        assertThat(entitlements.current(account, now()).plan()).isEqualTo(Plan.FREE);
    }

    @Test
    void aPlusCodeForFifteenDaysGivesPlusWithoutRenewalAndThenTheAccountIsFree() {
        String code = code(tier(PromoType.TIER_DAYS, "PLUS", 15, null, 5));
        UUID account = account(true, false);

        JsonNode result = redeem(account, code, network());

        assertThat(result.path("type").stringValue(null)).isEqualTo("TIER_DAYS");
        assertThat(result.path("plan").stringValue(null)).isEqualTo("PLUS");
        assertThat(result.path("validUntil").stringValue(null)).isEqualTo("2026-10-17T09:00:42Z");
        assertThat(result.path("message").stringValue(null)).isEqualTo("Plus до 17 октября, без автопродления.");
        Entitlement active = entitlements.current(account, now());
        assertThat(active.plan()).isEqualTo(Plan.PLUS);
        assertThat(active.source()).isEqualTo(Entitlement.Source.PROMO);
        assertThat(active.validUntil()).isEqualTo(Instant.parse("2026-10-17T09:00:42Z"));
        assertThat(jdbc.sql("SELECT snapshot_id FROM app_learning.promo_redemption WHERE owner_id=:o").param("o", account)
                .query(String.class).single()).startsWith("promo:");

        clock.set("2026-10-16T09:00:00Z");
        assertThat(entitlements.current(account, now()).plan()).isEqualTo(Plan.PLUS);
        clock.set("2026-10-17T09:00:43Z");
        assertThat(entitlements.current(account, now()).plan()).isEqualTo(Plan.FREE);
        assertThat(entitlements.current(account, now()).source()).isEqualTo(Entitlement.Source.CONFIG);
    }

    @Test
    void aMonthsCodeLastsCalendarMonths() {
        String code = code(tier(PromoType.TIER_MONTHS, "PRO", null, 2, 5));
        UUID account = account(true, false);

        JsonNode result = redeem(account, code, network());

        assertThat(result.path("validUntil").stringValue(null)).isEqualTo("2026-12-02T09:00:42Z");
        assertThat(entitlements.current(account, now()).plan()).isEqualTo(Plan.PRO);
    }

    @Test
    void aTierBelowThePlanTheAccountHasIsNotEligibleAndTheCodeIsNotBurned() {
        String code = code(tier(PromoType.TIER_DAYS, "PLUS", 15, null, 1));
        UUID account = account(true, false);
        inbox.accept(new EntitlementInbox.Snapshot("billing-" + account, account, Plan.PRO, "BILLING", Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-31T00:00:00Z"), JSON.readTree("{}"), Instant.parse("2026-10-31T00:00:00Z")));

        assertThat(refusal(account, code)).isEqualTo(PromoRejectedException.Reason.NOT_ELIGIBLE);

        redeem(account(true, false), code, network());
    }

    @Test
    void aDiscountCodeStoresAPendingDiscountForBillingAndTellsWhenItEnds() {
        String code = code(new PromoAdminService.Create(PromoType.DISCOUNT_PERCENT, "PLUS", null, null, 20, null,
                Instant.parse("2026-10-31T20:59:59Z"), 5, true, "spring", null));
        UUID account = account(true, false);

        JsonNode result = redeem(account, code, network());

        assertThat(result.path("type").stringValue(null)).isEqualTo("DISCOUNT_PERCENT");
        assertThat(result.path("percent").intValue()).isEqualTo(20);
        assertThat(result.path("message").stringValue(null)).isEqualTo("Скидка 20 % применится к оплате до 31 октября.");
        assertThat(entitlements.current(account, now()).plan()).isEqualTo(Plan.FREE);
        var pending = jdbc.sql("SELECT percent,plan FROM app_learning.promo_discount WHERE owner_id=:o").param("o", account)
                .query((row, number) -> row.getInt("percent") + row.getString("plan")).single();
        assertThat(pending).isEqualTo("20PLUS");
    }

    @Test
    void aLargerDiscountReplacesASmallerOneButNotTheOtherWayRound() {
        UUID account = account(true, false);
        var until = Instant.parse("2026-10-31T20:59:59Z");
        String ten = code(new PromoAdminService.Create(PromoType.DISCOUNT_PERCENT, null, null, null, 10, null, until, 5, true, null, null));
        String thirty = code(new PromoAdminService.Create(PromoType.DISCOUNT_PERCENT, null, null, null, 30, null, until, 5, true, null, null));
        String twenty = code(new PromoAdminService.Create(PromoType.DISCOUNT_PERCENT, null, null, null, 20, null, until, 5, true, null, null));

        redeem(account, ten, network());
        redeem(account, thirty, network());
        redeem(account, twenty, network());

        assertThat(jdbc.sql("SELECT percent FROM app_learning.promo_discount WHERE owner_id=:o").param("o", account).query(Integer.class)
                .single()).isEqualTo(30);
    }

    @Test
    void aRepeatedCommandReturnsTheSameAnswerWithoutAnotherRedemptionOrAttempt() {
        String code = code(tier(PromoType.TIER_DAYS, "PLUS", 15, null, 5));
        UUID account = account(true, false);
        UUID key = UUID.randomUUID();
        PromoClient client = network();

        JsonNode first = promo.redeem(account, jwt(account), key, code, client);
        JsonNode second = promo.redeem(account, jwt(account), key, code, client);

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.promo_redemption WHERE owner_id=:o").param("o", account).query(Long.class)
                .single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.promo_attempt WHERE owner_id=:o").param("o", account).query(Long.class)
                .single()).isEqualTo(1);
        assertThatThrownBy(() -> promo.redeem(account, jwt(account), key, "OTHER-CODE", client)).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void theAuditHoldsHashesNeverACodeOrAnAddress() {
        String plain = code(tier(PromoType.TIER_DAYS, "PLUS", 15, null, 5));
        UUID account = account(true, false);
        redeem(account, plain, new PromoClient(network().ipHash(), new byte[32]));

        String normalized = PromoCodes.normalize(plain).orElseThrow();
        var hint = jdbc.sql("SELECT code_hint FROM app_learning.promo_code WHERE code_hash=:h").param("h", PromoCodes.hash(normalized))
                .query(String.class).single();
        assertThat(hint).isEqualTo(normalized.substring(0, 2) + "…" + normalized.substring(8));
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.promo_code WHERE code_hint=:c").param("c", normalized).query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("SELECT octet_length(ip_hash) FROM app_learning.promo_redemption WHERE owner_id=:o").param("o", account)
                .query(Integer.class).single()).isEqualTo(32);
    }
}
