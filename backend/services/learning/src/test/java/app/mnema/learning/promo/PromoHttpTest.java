package app.mnema.learning.promo;

import app.mnema.learning.usage.Plan;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The HTTP faces of promo: the admin endpoints (administrator in Identity, fail closed) and the redemption endpoint (problem codes, headers, idempotency). */
class PromoHttpTest extends PromoIntegrationTest {
    private static final String CREATE_PLUS = "{\"type\":\"TIER_DAYS\",\"plan\":\"PLUS\",\"days\":15,\"maxRedemptions\":3,\"channel\":\"newsletter\"}";

    private org.springframework.mock.web.MockHttpServletResponse create(UUID account, String json) throws Exception {
        return as(account, adminController).perform(post("/admin/promo-codes").contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse();
    }

    private org.springframework.mock.web.MockHttpServletResponse redeem(UUID account, String code, String key, String address) throws Exception {
        var request = post("/promo-codes/redemptions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}").header("X-Forwarded-For", address);
        if (key != null) request.header("Idempotency-Key", key);
        return as(account, controller).perform(request).andReturn().getResponse();
    }

    private static String address() {
        return "203.0.113." + (1 + (int) (Math.random() * 250)) + ", 127.0.0.1";
    }

    @Test
    void aNonAdministratorIsForbiddenOnEveryAdminEndpoint() throws Exception {
        UUID learner = account(true, false);

        assertThat(create(learner, CREATE_PLUS).getStatus()).isEqualTo(403);
        assertThat(as(learner, adminController).perform(get("/admin/promo-codes")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(as(learner, adminController).perform(patch("/admin/promo-codes/" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(body(create(learner, CREATE_PLUS)).path("code").stringValue(null)).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void anIdentityThatDoesNotAnswerFailsClosedAndAnUnknownAccountToo() throws Exception {
        UUID administrator = account(true, true);
        standings.unavailable = true;

        var response = create(administrator, CREATE_PLUS);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(body(response).path("code").stringValue(null)).isEqualTo("IDENTITY_UNAVAILABLE");
        standings.unavailable = false;
        assertThat(create(UUID.randomUUID(), CREATE_PLUS).getStatus()).isEqualTo(503);
    }

    @Test
    void anAdministratorCreatesAGeneratedCodeSeesItOnceListsItAndSwitchesItOff() throws Exception {
        UUID administrator = account(true, true);

        var created = create(administrator, CREATE_PLUS);

        assertThat(created.getStatus()).isEqualTo(201);
        assertThat(created.getHeader("Cache-Control")).isEqualTo("private, no-store");
        JsonNode view = body(created);
        String code = view.path("code").stringValue(null);
        assertThat(code).matches("[" + PromoCodes.ALPHABET + "]{5}-[" + PromoCodes.ALPHABET + "]{5}");
        assertThat(view.path("hint").stringValue(null)).hasSize(5);
        assertThat(view.path("enabled").booleanValue()).isTrue();
        assertThat(view.path("channel").stringValue(null)).isEqualTo("newsletter");
        String codeId = view.path("codeId").stringValue(null);

        var list = body(as(administrator, adminController).perform(get("/admin/promo-codes")).andReturn().getResponse());
        JsonNode listed = null;
        for (JsonNode entry : list.path("codes")) if (codeId.equals(entry.path("codeId").stringValue(null))) listed = entry;
        assertThat(listed).isNotNull();
        assertThat(listed.has("code")).isFalse();
        assertThat(listed.path("redemptions").longValue()).isZero();

        var learner = account(true, false);
        assertThat(redeem(learner, code.toLowerCase(), UUID.randomUUID().toString(), address()).getStatus()).isEqualTo(200);

        var off = as(administrator, adminController).perform(patch("/admin/promo-codes/" + codeId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andReturn().getResponse();
        assertThat(off.getStatus()).isEqualTo(200);
        assertThat(body(off).path("enabled").booleanValue()).isFalse();
        assertThat(body(off).path("redemptions").longValue()).isEqualTo(1);
        var after = redeem(account(true, false), code, UUID.randomUUID().toString(), address());
        assertThat(after.getStatus()).isEqualTo(422);
        assertThat(body(after).path("code").stringValue(null)).isEqualTo("PROMO_INVALID");
    }

    @Test
    void aVanityCodeIsNormalizedAndCannotBeTakenTwiceAndMaxIsNeverGranted() throws Exception {
        UUID administrator = account(true, true);
        String vanity = "SPRING" + (int) (Math.random() * 1_000_000);

        var first = create(administrator, "{\"type\":\"TIER_MONTHS\",\"plan\":\"PRO\",\"months\":2,\"maxRedemptions\":5,\"code\":\"spring-" + vanity.substring(6) + "\"}");
        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(body(first).path("code").stringValue(null)).isEqualTo(vanity);
        var second = create(administrator, "{\"type\":\"TIER_MONTHS\",\"plan\":\"PRO\",\"months\":2,\"maxRedemptions\":5,\"code\":\"" + vanity + "\"}");
        assertThat(second.getStatus()).isEqualTo(400);
        assertThat(body(second).path("reason").stringValue(null)).isEqualTo("code_taken");

        assertThat(body(create(administrator, "{\"type\":\"TIER_DAYS\",\"plan\":\"MAX\",\"days\":5,\"maxRedemptions\":5}")).path("reason")
                .stringValue(null)).isEqualTo("plan");
    }

    @Test
    void malformedAdminRequestsAreInvalid() throws Exception {
        UUID administrator = account(true, true);
        for (String json : new String[] {"{}", "[]", "not json", "{\"type\":\"GIFT\",\"maxRedemptions\":1}",
                "{\"type\":\"TIER_DAYS\",\"plan\":\"PLUS\",\"days\":0,\"maxRedemptions\":1}",
                "{\"type\":\"TIER_DAYS\",\"plan\":\"PLUS\",\"days\":5,\"maxRedemptions\":0}",
                "{\"type\":\"TIER_DAYS\",\"plan\":\"PLUS\",\"days\":5,\"months\":1,\"maxRedemptions\":1}",
                "{\"type\":\"TIER_DAYS\",\"days\":5,\"maxRedemptions\":1}",
                "{\"type\":\"TIER_DAYS\",\"plan\":\"PLUS\",\"days\":5,\"maxRedemptions\":1,\"extra\":1}",
                "{\"type\":\"TIER_DAYS\",\"plan\":\"PLUS\",\"days\":5,\"maxRedemptions\":1,\"channel\":\"<script>\"}",
                "{\"type\":\"TIER_DAYS\",\"plan\":\"PLUS\",\"days\":5,\"maxRedemptions\":1,\"code\":\"ab\"}",
                "{\"type\":\"TIER_DAYS\",\"plan\":\"PLUS\",\"days\":5,\"maxRedemptions\":1,\"validFrom\":\"tomorrow\"}",
                "{\"type\":\"TIER_DAYS\",\"plan\":\"PLUS\",\"days\":5,\"maxRedemptions\":1,\"validFrom\":\"2026-10-05T00:00:00Z\",\"validUntil\":\"2026-10-04T00:00:00Z\"}",
                "{\"type\":\"DISCOUNT_PERCENT\",\"percent\":20,\"maxRedemptions\":1}",
                "{\"type\":\"DISCOUNT_PERCENT\",\"percent\":95,\"validUntil\":\"2027-01-01T00:00:00Z\",\"maxRedemptions\":1}",
                "{\"type\":\"TIER_DAYS\",\"plan\":\"PLUS\",\"days\":\"5\",\"maxRedemptions\":1}"}) {
            assertThat(create(administrator, json).getStatus()).as(json).isEqualTo(400);
        }
        for (String json : new String[] {"{}", "{\"enabled\":\"no\"}", "{\"enabled\":true,\"x\":1}"}) {
            assertThat(as(administrator, adminController).perform(patch("/admin/promo-codes/" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn().getResponse().getStatus()).as(json).isEqualTo(400);
        }
        assertThat(as(administrator, adminController).perform(patch("/admin/promo-codes/not-an-id").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(as(administrator, adminController).perform(patch("/admin/promo-codes/" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void redemptionNeedsAnIdempotencyKeyAndAnswersWithStableProblemCodes() throws Exception {
        String code = code(tier(PromoType.TIER_DAYS, "PLUS", 15, null, 1));
        UUID learner = account(true, false);

        var missing = redeem(learner, code, null, address());
        assertThat(missing.getStatus()).isEqualTo(400);
        assertThat(body(missing).path("reason").stringValue(null)).isEqualTo("idempotency_key_required");
        assertThat(redeem(learner, code, "not-a-uuid", address()).getStatus()).isEqualTo(400);

        String key = UUID.randomUUID().toString();
        var ok = redeem(learner, code, key, address());
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(ok.getHeader("Cache-Control")).isEqualTo("private, no-store");
        assertThat(body(ok).path("plan").stringValue(null)).isEqualTo("PLUS");
        assertThat(redeem(learner, code, key, address()).getContentAsString()).isEqualTo(ok.getContentAsString());

        assertThat(body(redeem(learner, code, UUID.randomUUID().toString(), address())).path("code").stringValue(null)).isEqualTo("PROMO_ALREADY_USED");
        var exhausted = redeem(account(true, false), code, UUID.randomUUID().toString(), address());
        assertThat(exhausted.getStatus()).isEqualTo(409);
        assertThat(body(exhausted).path("code").stringValue(null)).isEqualTo("PROMO_EXHAUSTED");
        var unverified = account(false, false);
        assertThat(body(redeem(unverified, code, UUID.randomUUID().toString(), address())).path("code").stringValue(null))
                .isEqualTo("PROMO_NOT_ELIGIBLE");
        assertThat(entitlements.current(learner, now()).plan()).isEqualTo(Plan.PLUS);
    }

    @Test
    void theSixthAttemptAnswers429WithRetryAfter() throws Exception {
        UUID learner = account(true, false);
        String address = address();
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(redeem(learner, "WRONGCODE", UUID.randomUUID().toString(), address).getStatus()).isEqualTo(422);
        }

        var limited = redeem(learner, "WRONGCODE", UUID.randomUUID().toString(), address);

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(limited.getHeader("Retry-After"))).isBetween(1L, 3_601L);
        assertThat(body(limited).path("code").stringValue(null)).isEqualTo("RATE_LIMITED");
    }

    @Test
    void aMalformedRedemptionBodyIsInvalid() throws Exception {
        UUID learner = account(true, false);
        for (String json : new String[] {"{}", "{\"code\":1}", "{\"code\":\"A\",\"x\":1}", "[]"}) {
            var response = as(learner, controller).perform(post("/promo-codes/redemptions").contentType(MediaType.APPLICATION_JSON)
                    .content(json).header("Idempotency-Key", UUID.randomUUID().toString())).andReturn().getResponse();
            assertThat(response.getStatus()).as(json).isEqualTo(400);
        }
    }
}
