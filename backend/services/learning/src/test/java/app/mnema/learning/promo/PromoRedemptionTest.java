package app.mnema.learning.promo;

import app.mnema.learning.platform.api.IdentityUnavailableException;
import app.mnema.learning.platform.api.RateLimitedException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.usage.Entitlement;
import app.mnema.learning.usage.EntitlementInbox;
import app.mnema.learning.usage.Plan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    @Autowired private PromoSettings settings;

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
                .param("hash", PromoCodes.hash(settings.hashSecret, PromoCodes.normalize(code).orElseThrow())).query(Long.class).single()).isEqualTo(10);
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
        for (int attempt = 0; attempt < 20; attempt++) refusal(account(true, false), "NOSUCHCODE", shared);

        assertThatThrownBy(() -> redeem(account(true, false), "NOSUCHCODE", shared)).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void anAddressThatAlreadyServedTheLimitOfOtherAccountsForOneCodeIsRefusedByVelocity() {
        PromoClient farm = network();
        String code = code(new PromoAdminService.Create(PromoType.TIER_DAYS, "PLUS", 3, null, null, null, null, 50, true, null, null));
        // the default tolerates a household or a mobile carrier's NAT: ten other accounts, not three
        assertThat(settings.velocityAccounts).isEqualTo(10);
        for (int index = 0; index < settings.velocityAccounts; index++) {
            // the address allowance is 20 attempts an hour: the farm of one address is far below it
            redeem(account(true, false), code, farm);
        }

        assertThat(refusal(account(true, false), code, farm)).isEqualTo(PromoRejectedException.Reason.VELOCITY);

        clock.set("2026-10-03T09:00:50Z");
        redeem(account(true, false), code, farm);
    }

    @Test
    void manyAccountsOfOneAddressMayRedeemDifferentCodesTheVelocityRuleCountsOneCodeAtATime() {
        PromoClient carrierNat = network();
        String shared = code(new PromoAdminService.Create(PromoType.TIER_DAYS, "PLUS", 3, null, null, null, null, 50, true, null, null));
        for (int index = 0; index < settings.velocityAccounts; index++) redeem(account(true, false), shared, carrierNat);
        assertThat(refusal(account(true, false), shared, carrierNat)).isEqualTo(PromoRejectedException.Reason.VELOCITY);

        // another code from the same address is a different matter: the accounts that took the first one do not count against it
        String other = code(new PromoAdminService.Create(PromoType.TIER_DAYS, "PLUS", 3, null, null, null, null, 50, true, null, null));
        redeem(account(true, false), other, carrierNat);

        // the audit holds the address hash only: the User-Agent is no longer stored
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.promo_redemption WHERE ip_hash=:ip AND device_hash IS NOT NULL")
                .param("ip", carrierNat.ipHash()).query(Long.class).single()).isZero();
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
        assertThat(refusal(account, twenty)).isEqualTo(PromoRejectedException.Reason.NOT_ELIGIBLE);

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
        redeem(account, plain, network());

        String normalized = PromoCodes.normalize(plain).orElseThrow();
        var hint = jdbc.sql("SELECT code_hint FROM app_learning.promo_code WHERE code_hash=:h").param("h", PromoCodes.hash(settings.hashSecret, normalized))
                .query(String.class).single();
        assertThat(hint).isEqualTo(normalized.substring(0, 2) + "…" + normalized.substring(10));
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.promo_code WHERE code_hint=:c").param("c", normalized).query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("SELECT octet_length(ip_hash) FROM app_learning.promo_redemption WHERE owner_id=:o").param("o", account)
                .query(Integer.class).single()).isEqualTo(32);
    }

    @Test
    void accountsRefusedForAnUnverifiedEmailDoNotBurnTheSharedAddressAllowance() {
        PromoClient office = network();
        for (int index = 0; index < 30; index++) {
            UUID unverified = account(false, false);
            assertThat(refusal(unverified, "NOSUCHCODE", office)).isEqualTo(PromoRejectedException.Reason.NOT_ELIGIBLE);
        }

        assertThat(refusal(account(true, false), "NOSUCHCODE", office)).isEqualTo(PromoRejectedException.Reason.INVALID);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.promo_attempt WHERE ip_hash=:ip").param("ip", office.ipHash())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void theAccountBucketStillCountsAnUnverifiedAttemptBeforeIdentityIsAsked() {
        UUID unverified = account(false, false);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(refusal(unverified, "NOSUCHCODE")).isEqualTo(PromoRejectedException.Reason.NOT_ELIGIBLE);
        }

        assertThatThrownBy(() -> redeem(unverified, "NOSUCHCODE", network())).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void theAddressLimitIsTwentyAnHourWhileTheAccountLimitStaysFive() {
        assertThat(settings.ipAttemptsPerHour).isEqualTo(20);
        assertThat(settings.attemptsPerHour).isEqualTo(5);
    }

    @Test
    void codesAreStoredAsAKeyedHashAndGeneratedCodesAreTwelveCharacters() throws Exception {
        String plain = code(tier(PromoType.TIER_DAYS, "PLUS", 15, null, 5));
        String normalized = PromoCodes.normalize(plain).orElseThrow();

        assertThat(normalized).hasSize(12);
        byte[] stored = jdbc.sql("SELECT code_hash FROM app_learning.promo_code WHERE code_hash=:h")
                .param("h", PromoCodes.hash(settings.hashSecret, normalized)).query(byte[].class).single();
        assertThat(stored).isNotEqualTo(java.security.MessageDigest.getInstance("SHA-256")
                .digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.promo_code WHERE code_hash=:h")
                .param("h", java.security.MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .query(Long.class).single()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
            "2026-01-30T22:15:00Z,1,2026-02-27T22:15:00Z",
            "2028-01-30T22:15:00Z,1,2028-02-28T22:15:00Z",
            "2026-03-30T22:15:00Z,1,2026-04-29T22:15:00Z",
            "2028-02-28T22:15:00Z,12,2029-02-27T22:15:00Z",
            "2026-12-30T22:15:00Z,2,2027-02-27T22:15:00Z"})
    void calendarMonthsKeepTheMoscowTimeAndClampToTheActualMonthEnd(String start, int months, String expiry) {
        clock.set(start);
        UUID account = account(true, false);
        String code = code(tier(PromoType.TIER_MONTHS, "PLUS", null, months, 1));
        Instant expectedEnd = Instant.parse(expiry);

        JsonNode result = redeem(account, code, network());

        assertThat(result.path("validUntil").stringValue()).isEqualTo(expiry);
        assertThat(entitlements.current(account, expectedEnd.minusSeconds(1)).plan()).isEqualTo(Plan.PLUS);
        assertThat(entitlements.current(account, expectedEnd).plan()).isEqualTo(Plan.FREE);
        Instant nextMonth = expectedEnd.atZone(java.time.ZoneId.of("Europe/Moscow")).toLocalDate()
                .withDayOfMonth(1).plusMonths(1).atStartOfDay(java.time.ZoneId.of("Europe/Moscow")).toInstant();
        assertThat(entitlements.current(account, nextMonth).plan()).as("an expired gift cannot unlock a new paid monthly allowance")
                .isEqualTo(Plan.FREE);
    }

    @Test
    void aCommandReceiptCannotBeUsedToVerifyAnUnkeyedVanityCodeGuess() {
        String plain = code(new PromoAdminService.Create(PromoType.TIER_DAYS, "PLUS", 15, null, null, null, null, 1,
                true, null, "SPRING26"));
        UUID account = account(true, false);
        UUID command = UUID.randomUUID();
        JsonNode first = promo.redeem(account, jwt(account), command, plain, network());

        byte[] receipt = jdbc.sql("SELECT payload_hash FROM app_learning.command_receipt WHERE command_id=:command")
                .param("command", command).query(byte[].class).single();
        byte[] guessedPayload = new app.mnema.learning.platform.json.CanonicalJsonHasher()
                .hash(JSON.createObjectNode().put("code", "SPRING26")).sha256();
        assertThat(receipt).as("the idempotency receipt must not bypass the code table's keyed hash").isNotEqualTo(guessedPayload);
        assertThat(promo.redeem(account, jwt(account), command, " spring-26 ", network())).isEqualTo(first);
        assertThat(jdbc.sql("SELECT result::text FROM app_learning.command_receipt WHERE command_id=:command")
                .param("command", command).query(String.class).single()).doesNotContain("SPRING26");
    }

    @Test
    void aDiscountThatDoesNotBeatThePendingOneIsRefusedAndTheCodeIsNotBurned() {
        UUID account = account(true, false);
        var until = Instant.parse("2026-10-31T20:59:59Z");
        String thirty = code(new PromoAdminService.Create(PromoType.DISCOUNT_PERCENT, null, null, null, 30, null, until, 5, true, null, null));
        String twenty = code(new PromoAdminService.Create(PromoType.DISCOUNT_PERCENT, null, null, null, 20, null, until, 5, true, null, null));
        String thirtyToo = code(new PromoAdminService.Create(PromoType.DISCOUNT_PERCENT, null, null, null, 30, null, until, 5, true, null, null));
        String fifty = code(new PromoAdminService.Create(PromoType.DISCOUNT_PERCENT, null, null, null, 50, null, until, 5, true, null, null));
        redeem(account, thirty, network());

        assertThat(refusal(account, twenty)).isEqualTo(PromoRejectedException.Reason.NOT_ELIGIBLE);
        assertThat(refusal(account, thirtyToo)).isEqualTo(PromoRejectedException.Reason.NOT_ELIGIBLE);

        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.promo_redemption WHERE owner_id=:o").param("o", account).query(Long.class).single())
                .isEqualTo(1);
        redeem(account(true, false), twenty, network());
        redeem(account, fifty, network());
        assertThat(jdbc.sql("SELECT percent FROM app_learning.promo_discount WHERE owner_id=:o").param("o", account).query(Integer.class).single())
                .isEqualTo(50);
    }

    @Test
    void anExpiredPendingDiscountDoesNotBlockASmallerNewOne() {
        UUID account = account(true, false);
        String big = code(new PromoAdminService.Create(PromoType.DISCOUNT_PERCENT, null, null, null, 40, null,
                Instant.parse("2026-10-05T00:00:00Z"), 5, true, null, null));
        String small = code(new PromoAdminService.Create(PromoType.DISCOUNT_PERCENT, null, null, null, 10, null,
                Instant.parse("2026-12-05T00:00:00Z"), 5, true, null, null));
        redeem(account, big, network());

        clock.set("2026-10-06T00:00:00Z");
        redeem(account, small, network());

        assertThat(jdbc.sql("SELECT percent FROM app_learning.promo_discount WHERE owner_id=:o").param("o", account).query(Integer.class).single())
                .isEqualTo(10);
    }

    @Test
    void parallelAccountsFromOneAddressRedeemExactlyAsManyTimesAsTheVelocityRuleAllows() throws Exception {
        String code = code(new PromoAdminService.Create(PromoType.TIER_DAYS, "PLUS", 3, null, null, null, null, 50, true, null, null));
        PromoClient farm = network();
        int parallel = settings.velocityAccounts + 4;
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> outcomes = new ArrayList<>();
        for (int index = 0; index < parallel; index++) {
            UUID account = account(true, false);
            outcomes.add(pool.submit(() -> {
                start.await();
                try {
                    redeem(account, code, farm);
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

        assertThat(results.stream().filter("OK"::equals)).hasSize(settings.velocityAccounts);
        assertThat(results.stream().filter("VELOCITY"::equals)).hasSize(parallel - settings.velocityAccounts);
        assertThat(jdbc.sql("SELECT count(DISTINCT owner_id) FROM app_learning.promo_redemption WHERE ip_hash=:ip").param("ip", farm.ipHash())
                .query(Long.class).single()).isEqualTo(settings.velocityAccounts);
    }
}
