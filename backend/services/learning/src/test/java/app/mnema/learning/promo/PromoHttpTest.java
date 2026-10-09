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
        try {
            JsonNode parsed = JSON.readTree(json);
            if (parsed instanceof tools.jackson.databind.node.ObjectNode object && !object.has("code")) {
                object.put("code", PromoTestCodes.generate());
                json = JSON.writeValueAsString(object);
            }
        } catch (tools.jackson.core.JacksonException ignored) { /* malformed bodies must reach the HTTP boundary */ }
        return as(account, adminController).perform(post("/admin/promo-codes").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse();
    }

    @Test
    void creationRequiresKeyAndExplicitCodeAndExactRetriesExposeNoDurablePlaintext() throws Exception {
        UUID administrator=account(true,true), key=UUID.randomUUID();
        String plain=PromoTestCodes.generate();
        String payload="{\"type\":\"TIER_DAYS\",\"plan\":\"PLUS\",\"days\":15,\"maxRedemptions\":3,\"code\":\""+plain+"\"}";
        var missingKey=as(administrator,adminController).perform(post("/admin/promo-codes").contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn().getResponse();
        assertThat(missingKey.getStatus()).isEqualTo(400);
        var missingCode=as(administrator,adminController).perform(post("/admin/promo-codes").header("Idempotency-Key",key.toString()).contentType(MediaType.APPLICATION_JSON).content(CREATE_PLUS)).andReturn().getResponse();
        assertThat(missingCode.getStatus()).isEqualTo(400);
        var first=as(administrator,adminController).perform(post("/admin/promo-codes").header("Idempotency-Key",key.toString()).contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn().getResponse();
        var second=as(administrator,adminController).perform(post("/admin/promo-codes").header("Idempotency-Key",key.toString()).contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn().getResponse();
        assertThat(first.getStatus()).isEqualTo(201); assertThat(second.getStatus()).isEqualTo(201);
        assertThat(body(first)).isEqualTo(body(second));
        assertThat(body(first).path("code").stringValue()).isEqualTo(PromoCodes.display(plain));
        String saved=jdbc.sql("SELECT result::text FROM app_learning.command_receipt WHERE command_id=:key").param("key",key).query(String.class).single();
        assertThat(saved).doesNotContain(plain,PromoCodes.display(plain),"\"code\"");
        UUID codeId=UUID.fromString(body(first).path("codeId").stringValue());
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.admin_audit WHERE resource_id=:id AND action='PROMO_CREATE'").param("id",codeId).query(Long.class).single()).isEqualTo(1);
        var conflict=as(administrator,adminController).perform(post("/admin/promo-codes").header("Idempotency-Key",key.toString()).contentType(MediaType.APPLICATION_JSON).content(payload.replace("15","16"))).andReturn().getResponse();
        assertThat(conflict.getStatus()).isEqualTo(409);
        var actorConflict=as(account(true,true),adminController).perform(post("/admin/promo-codes").header("Idempotency-Key",key.toString()).contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn().getResponse();
        assertThat(actorConflict.getStatus()).isEqualTo(409);
        for(String header:new String[]{"bad",key.toString().toUpperCase(java.util.Locale.ROOT),"10000000-0000-1000-8000-000000000001"}) {
            var invalid=as(administrator,adminController).perform(post("/admin/promo-codes").header("Idempotency-Key",header).contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn().getResponse();
            assertThat(invalid.getStatus()).isEqualTo(400);
        }
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
    void anAdministratorCreatesAnExplicitCodeSeesItOnceListsItAndSwitchesItOff() throws Exception {
        UUID administrator = account(true, true);

        var created = create(administrator, CREATE_PLUS);

        assertThat(created.getStatus()).isEqualTo(201);
        assertThat(created.getHeader("Cache-Control")).isEqualTo("private, no-store");
        JsonNode view = body(created);
        String code = view.path("code").stringValue(null);
        assertThat(code).matches("([" + PromoCodes.ALPHABET + "]{4}-){2}[" + PromoCodes.ALPHABET + "]{4}");
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
        UUID switched = UUID.fromString(codeId);
        java.util.function.Function<String, Long> audited = action -> jdbc.sql("SELECT count(*) FROM app_learning.admin_audit WHERE resource_id=:id AND action=:action")
                .param("id", switched).param("action", action).query(Long.class).single();
        assertThat(audited.apply("PROMO_DISABLE")).isEqualTo(1);
        // Repeating the current state changes nothing and is not journaled.
        var again = as(administrator, adminController).perform(patch("/admin/promo-codes/" + codeId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andReturn().getResponse();
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(body(again).path("enabled").booleanValue()).isFalse();
        assertThat(audited.apply("PROMO_DISABLE")).isEqualTo(1);
        var on = as(administrator, adminController).perform(patch("/admin/promo-codes/" + codeId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andReturn().getResponse();
        assertThat(on.getStatus()).isEqualTo(200);
        assertThat(audited.apply("PROMO_ENABLE")).isEqualTo(1);
        as(administrator, adminController).perform(patch("/admin/promo-codes/" + codeId).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andReturn();
        assertThat(audited.apply("PROMO_ENABLE")).isEqualTo(1);
        as(administrator, adminController).perform(patch("/admin/promo-codes/" + codeId).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andReturn();
        assertThat(audited.apply("PROMO_DISABLE")).isEqualTo(2);
        var after = redeem(account(true, false), code, UUID.randomUUID().toString(), address());
        assertThat(after.getStatus()).isEqualTo(422);
        assertThat(body(after).path("code").stringValue(null)).isEqualTo("PROMO_INVALID");
    }

    @Test
    void theAdminListIsPagedByACursorOfTwoHundred() throws Exception {
        UUID administrator = account(true, true);
        for (int index = 0; index < PromoAdminService.PAGE_SIZE + 5; index++) create(administrator, tier(PromoType.TIER_DAYS, "PLUS", 1, null, 1));

        java.util.Set<String> seen = new java.util.HashSet<>();
        String after = null;
        int pages = 0;
        do {
            var response = as(administrator, adminController).perform(get("/admin/promo-codes" + (after == null ? "" : "?after=" + after)))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            JsonNode page = body(response);
            assertThat(page.path("codes").size()).isLessThanOrEqualTo(PromoAdminService.PAGE_SIZE);
            if (pages == 0) assertThat(page.path("codes").size()).isEqualTo(PromoAdminService.PAGE_SIZE);
            for (JsonNode entry : page.path("codes")) assertThat(seen.add(entry.path("codeId").stringValue(null))).as("no code twice").isTrue();
            after = page.path("next").isNull() ? null : page.path("next").stringValue(null);
            pages++;
        } while (after != null);

        assertThat(seen).hasSizeGreaterThanOrEqualTo(PromoAdminService.PAGE_SIZE + 5);
        assertThat(pages).isGreaterThanOrEqualTo(2);
        assertThat(as(administrator, adminController).perform(get("/admin/promo-codes?after=not-an-id")).andReturn().getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void aVanityCodeIsNormalizedAndCannotBeTakenTwiceAndMaxIsNeverGranted() throws Exception {
        UUID administrator = account(true, true);
        String vanity = "SPRING" + (10_000 + (int) (Math.random() * 90_000));

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
                "{\"type\":\"TIER_DAYS\",\"plan\":\"PLUS\",\"days\":5,\"maxRedemptions\":1,\"code\":\"short-7\"}",
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
