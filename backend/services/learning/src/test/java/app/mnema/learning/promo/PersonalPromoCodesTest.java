package app.mnema.learning.promo;

import app.mnema.learning.usage.Entitlement;
import app.mnema.learning.usage.Plan;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Personal promo codes (OB-402): the derived code, the code row an issuer writes, and the rules of redeeming one: its recipient only (any other account is {@code PROMO_INVALID},
 * indistinguishable from an unknown code), the terms of the campaign, and the end of the offer.
 */
class PersonalPromoCodesTest extends PromoIntegrationTest {
    @Autowired private PersonalPromoCodes codes;
    @Autowired private PromoCampaignService campaigns;
    @Autowired private PromoSettings settings;
    @Autowired private PlatformTransactionManager transactions;

    private final UUID administrator = UUID.randomUUID();

    @BeforeEach
    void noCampaignIsEnabled() {
        jdbc.sql("UPDATE app_learning.promo_campaign SET enabled=false").update();
    }

    @AfterEach
    void switchEveryCampaignOff() {
        jdbc.sql("UPDATE app_learning.promo_campaign SET enabled=false").update();
    }

    private PersonalPromoCodes.Campaign enabledCampaign(PromoType offer, Integer percent, String plan, Integer days) {
        JsonNode created = campaigns.createCommand(administrator, UUID.randomUUID(), JSON.createObjectNode().put("n", UUID.randomUUID().toString()),
                new PromoCampaignService.Create(PromoCampaignType.PATH_REWARD, offer, percent, plan, days));
        campaigns.setEnabled(administrator, UUID.fromString(created.path("campaignId").stringValue(null)), true);
        return codes.enabledCampaign(PromoCampaignType.PATH_REWARD).orElseThrow();
    }

    /** A personal code issued the way the issuer does, valid for seven days from the test clock; returns the plain code the recipient sees. */
    private String issue(PersonalPromoCodes.Campaign campaign, UUID recipient) {
        Instant issuedAt = now().truncatedTo(ChronoUnit.SECONDS);
        UUID id = new TransactionTemplate(transactions).execute(status -> codes.issue(campaign, recipient, issuedAt, issuedAt.plus(7, ChronoUnit.DAYS)));
        byte[] hash = jdbc.sql("SELECT code_hash FROM app_learning.promo_code WHERE code_id=:id").param("id", id).query(byte[].class).single();
        return codes.displayCode(id, hash).orElseThrow();
    }

    private PromoRejectedException.Reason refusal(UUID account, String code) {
        try {
            promo.redeem(account, jwt(account), UUID.randomUUID(), code, network());
        } catch (PromoRejectedException rejected) {
            return rejected.reason();
        }
        throw new AssertionError("The redemption was not refused");
    }

    @Test
    void aDerivedCodeIsTwelveLettersOfTheAlphabetFixedByTheIdAndTheSecret() {
        byte[] secret = "a-secret-of-more-than-thirty-two-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] other = "another-secret-of-more-than-thirty-two-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UUID id = UUID.fromString("0a000000-0000-4000-8000-000000000001");

        String code = PromoCodes.derive(secret, id);

        assertThat(code).hasSize(PromoCodes.GENERATED_LENGTH).matches("[" + PromoCodes.ALPHABET + "]{12}");
        assertThat(PromoCodes.derive(secret, id)).isEqualTo(code);
        assertThat(PromoCodes.derive(secret, UUID.fromString("0a000000-0000-4000-8000-000000000002"))).isNotEqualTo(code);
        assertThat(PromoCodes.derive(other, id)).isNotEqualTo(code);
        // the display form is a code the redemption normalizes back to the same text
        assertThat(PromoCodes.normalize(PromoCodes.display(code))).contains(code);
    }

    @Test
    void everyLetterOfTheAlphabetIsEquallyLikelyAndTheCodesDoNotCollide() {
        byte[] secret = "a-secret-of-more-than-thirty-two-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<Character, Integer> counts = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        int codes = 4_000;
        for (int index = 0; index < codes; index++) {
            String code = PromoCodes.derive(secret, UUID.randomUUID());
            seen.add(code);
            for (char letter : code.toCharArray()) counts.merge(letter, 1, Integer::sum);
        }
        double expected = codes * (double) PromoCodes.GENERATED_LENGTH / PromoCodes.ALPHABET.length();

        assertThat(seen).hasSize(codes);
        assertThat(counts).hasSize(PromoCodes.ALPHABET.length());
        // 1 550 expected per letter, standard deviation about 38: a 12 % window is more than 4.5 deviations, so a fair derivation never fails it
        assertThat(counts.values()).allSatisfy(count -> assertThat(count).isBetween((int) (expected * 0.88), (int) (expected * 1.12)));
    }

    @Test
    void theRowHoldsTheKeyedHashAndTheHintAndTheTermsOfTheCampaignNeverThePlainCode() {
        PersonalPromoCodes.Campaign campaign = enabledCampaign(PromoType.TIER_DAYS, null, "PLUS", 30);
        UUID recipient = UUID.randomUUID();

        String code = issue(campaign, recipient);

        String normalized = PromoCodes.normalize(code).orElseThrow();
        var row = jdbc.sql("SELECT * FROM app_learning.promo_code WHERE recipient_id=:r").param("r", recipient).query().singleRow();
        assertThat(row.get("code_hash")).isEqualTo(PromoCodes.hash(settings.hashSecret, normalized));
        assertThat(row.get("code_hint")).isEqualTo(PromoCodes.hint(normalized));
        assertThat(row).containsEntry("type", "TIER_DAYS").containsEntry("plan", "PLUS").containsEntry("days", 30).containsEntry("max_redemptions", 1)
                .containsEntry("once_per_account", true).containsEntry("channel", "path-reward").containsEntry("enabled", true)
                .containsEntry("created_by", administrator).containsEntry("campaign_id", campaign.campaignId());
        assertThat(row.get("percent")).isNull();
        assertThat(row.values().stream().map(String::valueOf).toList()).noneMatch(value -> value.contains(normalized));
        // the code is not in the list of the owner's own codes
        assertThat(admin.list(null).path("codes")).noneMatch(entry -> entry.path("hint").stringValue("").equals(PromoCodes.hint(normalized))
                && entry.path("channel").stringValue("").equals("path-reward"));
    }

    @Test
    void theRecipientRedeemsATierCodeAndGetsThePlanForTheDaysOfTheCampaign() {
        PersonalPromoCodes.Campaign campaign = enabledCampaign(PromoType.TIER_DAYS, null, "PLUS", 30);
        UUID recipient = account(true, false);
        String code = issue(campaign, recipient);

        JsonNode result = promo.redeem(recipient, jwt(recipient), UUID.randomUUID(), code, network());

        assertThat(result.path("type").stringValue(null)).isEqualTo("TIER_DAYS");
        assertThat(result.path("plan").stringValue(null)).isEqualTo("PLUS");
        Entitlement active = entitlements.current(recipient, now());
        assertThat(active.plan()).isEqualTo(Plan.PLUS);
        assertThat(active.validUntil()).isEqualTo(now().plus(30, ChronoUnit.DAYS));
    }

    @Test
    void theRecipientRedeemsADiscountCodeAndTheDiscountLastsUntilTheEndOfTheOffer() {
        PersonalPromoCodes.Campaign campaign = enabledCampaign(PromoType.DISCOUNT_PERCENT, 15, null, null);
        UUID recipient = account(true, false);
        String code = issue(campaign, recipient);
        Instant expires = now().truncatedTo(ChronoUnit.SECONDS).plus(7, ChronoUnit.DAYS);

        JsonNode result = promo.redeem(recipient, jwt(recipient), UUID.randomUUID(), code, network());

        assertThat(result.path("percent").intValue()).isEqualTo(15);
        assertThat(result.path("validUntil").stringValue(null)).isEqualTo(expires.toString());
        var discount = jdbc.sql("SELECT percent, valid_until FROM app_learning.promo_discount WHERE owner_id=:o").param("o", recipient)
                .query((row, number) -> Map.entry(row.getInt("percent"), row.getTimestamp("valid_until").toInstant())).single();
        assertThat(discount.getKey()).isEqualTo(15);
        assertThat(discount.getValue()).isEqualTo(expires);
    }

    @Test
    void anotherAccountGetsInvalidExactlyAsForAnUnknownCodeAndTheCodeIsNotBurned() {
        PersonalPromoCodes.Campaign campaign = enabledCampaign(PromoType.TIER_DAYS, null, "PRO", 10);
        UUID recipient = account(true, false);
        UUID stranger = account(true, false);
        String code = issue(campaign, recipient);
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PromoService.class);
        ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        logger.addAppender(log);
        try {
            assertThat(refusal(stranger, code)).isEqualTo(PromoRejectedException.Reason.INVALID);
            assertThat(refusal(stranger, "ZZZZ-ZZZZ-ZZZZ")).isEqualTo(PromoRejectedException.Reason.INVALID);
        } finally {
            logger.detachAppender(log);
        }

        // the same log line, with the same fields, for a personal code and for an unknown one
        List<String> lines = log.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).hasSize(2).containsOnly("promo rejected owner_id=" + stranger + " reason=INVALID");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.promo_redemption WHERE owner_id=:o").param("o", stranger).query(Long.class).single()).isZero();
        assertThat(entitlements.current(stranger, now()).plan()).isEqualTo(Plan.FREE);
        // and the recipient still can
        assertThat(promo.redeem(recipient, jwt(recipient), UUID.randomUUID(), code, network()).path("plan").stringValue(null)).isEqualTo("PRO");
    }

    @Test
    void theRecipientCannotRedeemAfterTheEndOfTheOfferAndCanTheSecondBefore() {
        PersonalPromoCodes.Campaign campaign = enabledCampaign(PromoType.TIER_DAYS, null, "PLUS", 5);
        UUID late = account(true, false);
        UUID punctual = account(true, false);
        String lateCode = issue(campaign, late);
        String punctualCode = issue(campaign, punctual);
        Instant end = now().truncatedTo(ChronoUnit.SECONDS).plus(7, ChronoUnit.DAYS);

        clock.set(end.minusSeconds(1).toString());
        assertThat(promo.redeem(punctual, jwt(punctual), UUID.randomUUID(), punctualCode, network()).path("plan").stringValue(null)).isEqualTo("PLUS");
        clock.set(end.toString());
        assertThat(refusal(late, lateCode)).isEqualTo(PromoRejectedException.Reason.INVALID);
        assertThat(entitlements.current(late, now()).plan()).isEqualTo(Plan.FREE);
    }

    @Test
    void aCodeIsRedeemedOnceByItsRecipient() {
        PersonalPromoCodes.Campaign campaign = enabledCampaign(PromoType.TIER_DAYS, null, "PLUS", 5);
        UUID recipient = account(true, false);
        String code = issue(campaign, recipient);

        promo.redeem(recipient, jwt(recipient), UUID.randomUUID(), code, network());

        assertThat(refusal(recipient, code)).isEqualTo(PromoRejectedException.Reason.ALREADY_USED);
    }

    @Test
    void issuingNeedsTheTransactionOfTheCaller() {
        PersonalPromoCodes.Campaign campaign = enabledCampaign(PromoType.TIER_DAYS, null, "PLUS", 5);
        Instant issuedAt = now().truncatedTo(ChronoUnit.SECONDS);

        assertThatThrownBy(() -> codes.issue(campaign, UUID.randomUUID(), issuedAt, issuedAt.plusSeconds(60))).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
}
