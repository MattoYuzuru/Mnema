package app.mnema.learning.promo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The owner's console of promo campaigns (OB-402): access, creation (idempotent, disabled), the one-enabled rule, the kill switch and its journal, counts and the issuance list. */
class PromoCampaignHttpTest extends PromoIntegrationTest {
    private static final String DISCOUNT = "{\"type\":\"PATH_REWARD\",\"offerType\":\"DISCOUNT_PERCENT\",\"percent\":10}";
    private static final String TIER = "{\"type\":\"PATH_REWARD\",\"offerType\":\"TIER_DAYS\",\"plan\":\"PLUS\",\"days\":30}";

    @Autowired private PromoCampaignController campaignController;
    @Autowired private PromoCampaignService campaigns;
    @Autowired private PersonalPromoCodes codes;
    @Autowired private PlatformTransactionManager transactions;

    @BeforeEach
    void noCampaignIsEnabled() {
        jdbc.sql("UPDATE app_learning.promo_campaign SET enabled=false").update();
    }

    @AfterEach
    void switchEveryCampaignOff() {
        jdbc.sql("UPDATE app_learning.promo_campaign SET enabled=false").update();
    }

    private MockHttpServletResponse create(UUID account, String json, UUID key) throws Exception {
        var request = post("/admin/promo-campaigns").contentType(MediaType.APPLICATION_JSON).content(json);
        if (key != null) request.header("Idempotency-Key", key.toString());
        return as(account, campaignController).perform(request).andReturn().getResponse();
    }

    private JsonNode created(UUID administrator, String json) throws Exception {
        MockHttpServletResponse response = create(administrator, json, UUID.randomUUID());
        assertThat(response.getStatus()).isEqualTo(201);
        return body(response);
    }

    private MockHttpServletResponse switchTo(UUID account, String campaignId, boolean enabled) throws Exception {
        return as(account, campaignController).perform(patch("/admin/promo-campaigns/" + campaignId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + enabled + "}")).andReturn().getResponse();
    }

    private long audited(String action, String resource) {
        return jdbc.sql("SELECT count(*) FROM app_learning.admin_audit WHERE resource_id=:id AND action=:action").param("id", UUID.fromString(resource)).param("action", action)
                .query(Long.class).single();
    }

    /** One issuance as the issuer writes it: the personal code and the reward row; {@code issuedAt} is the test's, so the order and the 24 hours are decided here. */
    private String issue(String campaignId, UUID recipient, Instant issuedAt) {
        PersonalPromoCodes.Campaign campaign = codes.enabledCampaign(PromoCampaignType.PATH_REWARD).orElseThrow();
        assertThat(campaign.campaignId()).hasToString(campaignId);
        UUID code = new TransactionTemplate(transactions).execute(status -> {
            UUID id = codes.issue(campaign, recipient, issuedAt, issuedAt.plus(7, ChronoUnit.DAYS));
            jdbc.sql("INSERT INTO app_learning.onboarding_reward(account_id,campaign_id,promo_code_id,issued_at,expires_at) VALUES (:a,:c,:p,:i,:e)")
                    .param("a", recipient).param("c", campaign.campaignId()).param("p", id).param("i", Timestamp.from(issuedAt)).param("e", Timestamp.from(issuedAt.plus(7, ChronoUnit.DAYS)))
                    .update();
            return id;
        });
        byte[] hash = jdbc.sql("SELECT code_hash FROM app_learning.promo_code WHERE code_id=:id").param("id", code).query(byte[].class).single();
        return codes.displayCode(code, hash).orElseThrow();
    }

    @Test
    void aNonAdministratorIsForbiddenOnEveryCampaignEndpointAndIdentityThatDoesNotAnswerFailsClosed() throws Exception {
        UUID learner = account(true, false);
        UUID administrator = account(true, true);
        String id = created(administrator, DISCOUNT).path("campaignId").stringValue(null);

        assertThat(create(learner, DISCOUNT, UUID.randomUUID()).getStatus()).isEqualTo(403);
        assertThat(as(learner, campaignController).perform(get("/admin/promo-campaigns")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(switchTo(learner, id, true).getStatus()).isEqualTo(403);
        assertThat(as(learner, campaignController).perform(get("/admin/promo-campaigns/" + id + "/issuances")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(audited("PROMO_CAMPAIGN_ENABLE", id)).isZero();

        standings.unavailable = true;
        assertThat(create(administrator, DISCOUNT, UUID.randomUUID()).getStatus()).isEqualTo(503);
        assertThat(as(administrator, campaignController).perform(get("/admin/promo-campaigns")).andReturn().getResponse().getStatus()).isEqualTo(503);
    }

    @Test
    void creationIsDisabledIdempotentByKeyAndJournaledOnce() throws Exception {
        UUID administrator = account(true, true);
        UUID key = UUID.randomUUID();

        MockHttpServletResponse first = create(administrator, DISCOUNT, key);
        MockHttpServletResponse second = create(administrator, DISCOUNT, key);

        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(first.getHeader("Cache-Control")).isEqualTo("private, no-store");
        assertThat(second.getStatus()).isEqualTo(201);
        assertThat(body(second)).isEqualTo(body(first));
        JsonNode view = body(first);
        assertThat(view.path("enabled").booleanValue()).isFalse();
        assertThat(view.path("type").stringValue(null)).isEqualTo("PATH_REWARD");
        assertThat(view.path("offerType").stringValue(null)).isEqualTo("DISCOUNT_PERCENT");
        assertThat(view.path("percent").intValue()).isEqualTo(10);
        assertThat(view.path("plan").isNull()).isTrue();
        assertThat(view.path("days").isNull()).isTrue();
        assertThat(view.path("issued").longValue()).isZero();
        assertThat(view.path("redeemed").longValue()).isZero();
        assertThat(view.path("issuedLast24h").longValue()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.promo_campaign WHERE campaign_id=:id").param("id", UUID.fromString(view.path("campaignId").stringValue(null)))
                .query(Long.class).single()).isEqualTo(1);
        assertThat(audited("PROMO_CAMPAIGN_CREATE", view.path("campaignId").stringValue(null))).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.admin_audit WHERE command_id=:k AND action='PROMO_CAMPAIGN_CREATE'").param("k", key).query(Long.class).single()).isEqualTo(1);
        // the same key with another body is a conflict and creates nothing
        assertThat(create(administrator, TIER, key).getStatus()).isEqualTo(409);
        assertThat(create(account(true, true), DISCOUNT, key).getStatus()).isEqualTo(409);
    }

    @Test
    void aTierCampaignKeepsItsPlanAndDays() throws Exception {
        JsonNode view = created(account(true, true), TIER);

        assertThat(view.path("offerType").stringValue(null)).isEqualTo("TIER_DAYS");
        assertThat(view.path("plan").stringValue(null)).isEqualTo("PLUS");
        assertThat(view.path("days").intValue()).isEqualTo(30);
        assertThat(view.path("percent").isNull()).isTrue();
    }

    @Test
    void anInvalidOfferOrBodyIsRefusedBeforeAnythingIsWritten() throws Exception {
        UUID administrator = account(true, true);
        long before = jdbc.sql("SELECT count(*) FROM app_learning.promo_campaign").query(Long.class).single();

        assertThat(create(administrator, DISCOUNT, null).getStatus()).as("no key").isEqualTo(400);
        for (String bad : new String[] {
                "{}", "{\"type\":\"PATH_REWARD\"}", "{\"offerType\":\"DISCOUNT_PERCENT\",\"percent\":10}",
                "{\"type\":\"OTHER\",\"offerType\":\"DISCOUNT_PERCENT\",\"percent\":10}",
                "{\"type\":\"PATH_REWARD\",\"offerType\":\"TIER_MONTHS\",\"plan\":\"PLUS\",\"days\":5}",
                "{\"type\":\"PATH_REWARD\",\"offerType\":\"WHATEVER\"}",
                "{\"type\":\"PATH_REWARD\",\"offerType\":\"DISCOUNT_PERCENT\"}",
                "{\"type\":\"PATH_REWARD\",\"offerType\":\"DISCOUNT_PERCENT\",\"percent\":0}",
                "{\"type\":\"PATH_REWARD\",\"offerType\":\"DISCOUNT_PERCENT\",\"percent\":91}",
                "{\"type\":\"PATH_REWARD\",\"offerType\":\"DISCOUNT_PERCENT\",\"percent\":10,\"days\":3}",
                "{\"type\":\"PATH_REWARD\",\"offerType\":\"DISCOUNT_PERCENT\",\"percent\":10,\"plan\":\"MAX\"}",
                "{\"type\":\"PATH_REWARD\",\"offerType\":\"TIER_DAYS\",\"days\":30}",
                "{\"type\":\"PATH_REWARD\",\"offerType\":\"TIER_DAYS\",\"plan\":\"PLUS\"}",
                "{\"type\":\"PATH_REWARD\",\"offerType\":\"TIER_DAYS\",\"plan\":\"PLUS\",\"days\":367}",
                "{\"type\":\"PATH_REWARD\",\"offerType\":\"TIER_DAYS\",\"plan\":\"PLUS\",\"days\":0}",
                "{\"type\":\"PATH_REWARD\",\"offerType\":\"TIER_DAYS\",\"plan\":\"PLUS\",\"days\":5,\"percent\":10}",
                "{\"type\":\"PATH_REWARD\",\"offerType\":\"DISCOUNT_PERCENT\",\"percent\":10,\"enabled\":true}",
                "{\"type\":\"PATH_REWARD\",\"offerType\":\"DISCOUNT_PERCENT\",\"percent\":\"10\"}",
                "[]", "not json"}) {
            assertThat(create(administrator, bad, UUID.randomUUID()).getStatus()).as(bad).isEqualTo(400);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.promo_campaign").query(Long.class).single()).isEqualTo(before);
    }

    @Test
    void theSwitchJournalsAnActualChangeOnlyAndKeepsAtMostOneCampaignEnabled() throws Exception {
        UUID administrator = account(true, true);
        String one = created(administrator, DISCOUNT).path("campaignId").stringValue(null);
        String two = created(administrator, TIER).path("campaignId").stringValue(null);

        MockHttpServletResponse on = switchTo(administrator, one, true);
        assertThat(on.getStatus()).isEqualTo(200);
        assertThat(body(on).path("enabled").booleanValue()).isTrue();
        assertThat(audited("PROMO_CAMPAIGN_ENABLE", one)).isEqualTo(1);
        // repeating the state changes and journals nothing
        assertThat(body(switchTo(administrator, one, true)).path("enabled").booleanValue()).isTrue();
        assertThat(audited("PROMO_CAMPAIGN_ENABLE", one)).isEqualTo(1);
        assertThat(body(switchTo(administrator, two, false)).path("enabled").booleanValue()).isFalse();
        assertThat(audited("PROMO_CAMPAIGN_DISABLE", two)).isZero();

        MockHttpServletResponse conflict = switchTo(administrator, two, true);
        assertThat(conflict.getStatus()).isEqualTo(409);
        assertThat(body(conflict).path("code").stringValue(null)).isEqualTo("PROMO_CAMPAIGN_ACTIVE");
        assertThat(audited("PROMO_CAMPAIGN_ENABLE", two)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.promo_campaign WHERE enabled").query(Long.class).single()).isEqualTo(1);

        assertThat(body(switchTo(administrator, one, false)).path("enabled").booleanValue()).isFalse();
        assertThat(audited("PROMO_CAMPAIGN_DISABLE", one)).isEqualTo(1);
        assertThat(switchTo(administrator, two, true).getStatus()).isEqualTo(200);
        assertThat(audited("PROMO_CAMPAIGN_ENABLE", two)).isEqualTo(1);
        // a campaign is immutable except for enabled: nothing else changes on a switch
        JsonNode first = body(switchTo(administrator, one, false));
        assertThat(first.path("percent").intValue()).isEqualTo(10);
        assertThat(first.path("offerType").stringValue(null)).isEqualTo("DISCOUNT_PERCENT");
    }

    @Test
    void anUnknownCampaignIs404AndAMalformedIdIs400() throws Exception {
        UUID administrator = account(true, true);

        assertThat(switchTo(administrator, UUID.randomUUID().toString(), true).getStatus()).isEqualTo(404);
        assertThat(switchTo(administrator, "nope", true).getStatus()).isEqualTo(400);
        assertThat(as(administrator, campaignController).perform(get("/admin/promo-campaigns/" + UUID.randomUUID() + "/issuances")).andReturn().getResponse().getStatus())
                .isEqualTo(404);
        MockHttpServletResponse body = as(administrator, campaignController).perform(patch("/admin/promo-campaigns/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":\"yes\"}")).andReturn().getResponse();
        assertThat(body.getStatus()).isEqualTo(400);
    }

    @Test
    void twoCampaignsEnabledTogetherLeaveExactlyOneEnabled() throws Exception {
        UUID administrator = account(true, true);
        String one = created(administrator, DISCOUNT).path("campaignId").stringValue(null);
        String two = created(administrator, TIER).path("campaignId").stringValue(null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> outcomes = new ArrayList<>();
        for (String id : new String[] {one, two}) {
            outcomes.add(pool.submit(() -> {
                start.await();
                try {
                    campaigns.setEnabled(administrator, UUID.fromString(id), true);
                    return "ENABLED";
                } catch (PromoCampaignActiveException active) {
                    return "ACTIVE";
                }
            }));
        }
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> outcome : outcomes) results.add(outcome.get());
        pool.shutdown();

        assertThat(results).containsExactlyInAnyOrder("ENABLED", "ACTIVE");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.promo_campaign WHERE enabled AND campaign_id IN (:a,:b)").param("a", UUID.fromString(one)).param("b", UUID.fromString(two))
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void theListIsNewestFirstWithTheCountsOfIssuedRedeemedAndTheLastDay() throws Exception {
        UUID administrator = account(true, true);
        String older = created(administrator, DISCOUNT).path("campaignId").stringValue(null);
        String newer = created(administrator, TIER).path("campaignId").stringValue(null);
        jdbc.sql("UPDATE app_learning.promo_campaign SET created_at=created_at - interval '1 day' WHERE campaign_id=:id").param("id", UUID.fromString(older)).update();
        assertThat(switchTo(administrator, newer, true).getStatus()).isEqualTo(200);
        Instant now = jdbc.sql("SELECT date_trunc('second', statement_timestamp())").query(Timestamp.class).single().toInstant();
        UUID redeemer = account(true, false);
        String redeemed = issue(newer, redeemer, now.minus(2, ChronoUnit.HOURS));
        issue(newer, account(true, false), now.minus(30, ChronoUnit.HOURS));
        issue(newer, account(true, false), now.minus(1, ChronoUnit.HOURS));
        clock.set(now.toString());
        promo.redeem(redeemer, jwt(redeemer), UUID.randomUUID(), redeemed, network());

        JsonNode list = body(as(administrator, campaignController).perform(get("/admin/promo-campaigns")).andReturn().getResponse());

        List<String> ids = new ArrayList<>();
        list.path("campaigns").forEach(entry -> ids.add(entry.path("campaignId").stringValue(null)));
        assertThat(ids).contains(newer, older);
        assertThat(ids.indexOf(newer)).isLessThan(ids.indexOf(older));
        JsonNode entry = null;
        for (JsonNode candidate : list.path("campaigns")) if (newer.equals(candidate.path("campaignId").stringValue(null))) entry = candidate;
        assertThat(entry).isNotNull();
        assertThat(entry.path("issued").longValue()).isEqualTo(3);
        assertThat(entry.path("redeemed").longValue()).isEqualTo(1);
        assertThat(entry.path("issuedLast24h").longValue()).isEqualTo(2);
        assertThat(entry.path("enabled").booleanValue()).isTrue();
    }

    @Test
    void theIssuanceListIsNewestFirstPagedWithoutAccountIdsOrCodes() throws Exception {
        UUID administrator = account(true, true);
        String campaign = created(administrator, DISCOUNT).path("campaignId").stringValue(null);
        assertThat(switchTo(administrator, campaign, true).getStatus()).isEqualTo(200);
        Instant start = jdbc.sql("SELECT date_trunc('second', statement_timestamp())").query(Timestamp.class).single().toInstant().minus(10, ChronoUnit.DAYS);
        List<UUID> recipients = new ArrayList<>();
        List<String> plainCodes = new ArrayList<>();
        UUID redeemer = null;
        for (int index = 0; index < 105; index++) {
            UUID recipient = account(true, false);
            recipients.add(recipient);
            // the last three share one second, so the order inside it is decided by the code id and the cursor must still walk each of them once
            plainCodes.add(issue(campaign, recipient, start.plus(Math.min(index, 102), ChronoUnit.SECONDS)));
            if (index == 104) redeemer = recipient;
        }
        // valid for seven days from an issuance ten days ago: only the clock of the redemption has to be inside it
        clock.set(start.plus(1, ChronoUnit.HOURS).toString());
        promo.redeem(redeemer, jwt(redeemer), UUID.randomUUID(), plainCodes.get(104), network());

        MockHttpServletResponse firstResponse = as(administrator, campaignController).perform(get("/admin/promo-campaigns/" + campaign + "/issuances")).andReturn().getResponse();
        JsonNode first = body(firstResponse);
        String next = first.path("next").stringValue(null);
        MockHttpServletResponse secondResponse = as(administrator, campaignController).perform(get("/admin/promo-campaigns/" + campaign + "/issuances").queryParam("after", next))
                .andReturn().getResponse();
        assertThat(secondResponse.getStatus()).as(secondResponse.getContentAsString()).isEqualTo(200);
        JsonNode second = body(secondResponse);

        assertThat(first.path("issuances")).hasSize(100);
        assertThat(next).isNotBlank();
        assertThat(second.path("issuances")).hasSize(5);
        assertThat(second.path("next").isNull()).isTrue();
        List<String> issuedAt = new ArrayList<>();
        List<String> hints = new ArrayList<>();
        for (JsonNode page : new JsonNode[] {first, second}) {
            for (JsonNode entry : page.path("issuances")) {
                issuedAt.add(entry.path("issuedAt").stringValue(null));
                hints.add(entry.path("codeHint").stringValue(null));
                assertThat(entry.propertyNames()).containsExactlyInAnyOrder("codeHint", "issuedAt", "expiresAt", "redeemedAt");
                assertThat(Instant.parse(entry.path("expiresAt").stringValue(null))).isEqualTo(Instant.parse(entry.path("issuedAt").stringValue(null)).plus(7, ChronoUnit.DAYS));
            }
        }
        assertThat(issuedAt).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(hints).hasSize(105);
        int redeemedEntries = 0;
        for (JsonNode page : new JsonNode[] {first, second}) for (JsonNode entry : page.path("issuances")) if (!entry.path("redeemedAt").isNull()) redeemedEntries++;
        assertThat(redeemedEntries).isOne();
        // nothing that names an account or reveals a code leaves the server
        String wire = first + second.toString();
        for (UUID recipient : recipients) assertThat(wire).doesNotContain(recipient.toString());
        for (String plain : plainCodes) assertThat(wire).doesNotContain(plain).doesNotContain(PromoCodes.normalize(plain).orElseThrow());
        assertThat(wire).doesNotContain("accountId").doesNotContain("recipient");
        // a cursor that this service did not make is refused
        assertThat(as(administrator, campaignController).perform(get("/admin/promo-campaigns/" + campaign + "/issuances").queryParam("after", "bogus")).andReturn().getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void theOwnersCodeListLeavesCampaignCodesOut() throws Exception {
        UUID administrator = account(true, true);
        String campaign = created(administrator, DISCOUNT).path("campaignId").stringValue(null);
        assertThat(switchTo(administrator, campaign, true).getStatus()).isEqualTo(200);
        UUID recipient = account(true, false);
        issue(campaign, recipient, jdbc.sql("SELECT date_trunc('second', statement_timestamp())").query(Timestamp.class).single().toInstant());
        UUID personal = jdbc.sql("SELECT code_id FROM app_learning.promo_code WHERE recipient_id=:r").param("r", recipient).query(UUID.class).single();
        UUID ordinary = UUID.fromString(create(administrator, tier(PromoType.TIER_DAYS, "PLUS", 3, null, 5)).path("codeId").stringValue(null));

        List<String> listed = new ArrayList<>();
        String after = null;
        do {
            var request = get("/admin/promo-codes");
            if (after != null) request.queryParam("after", after);
            JsonNode page = body(as(administrator, adminController).perform(request).andReturn().getResponse());
            page.path("codes").forEach(entry -> listed.add(entry.path("codeId").stringValue(null)));
            after = page.path("next").stringValue(null);
        } while (after != null);

        assertThat(listed).contains(ordinary.toString()).doesNotContain(personal.toString());
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.promo_code WHERE campaign_id IS NOT NULL").query(Long.class).single()).isPositive();
    }
}
