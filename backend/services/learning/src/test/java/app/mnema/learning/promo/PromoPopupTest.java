package app.mnema.learning.promo;

import app.mnema.learning.usage.EntitlementInbox;
import app.mnema.learning.usage.Plan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The promo popup per account: shown while eligible, silenced by a dismissal for the cooldown, ended by a decline or a purchase. */
@SpringBootTest(properties = {"learning.promo.popup.enabled=true", "learning.promo.popup.id=autumn-2026",
        "learning.promo.popup.title=Осенняя скидка", "learning.promo.popup.body=Plus дешевле до конца октября.",
        "learning.promo.popup.code=AUTUMN-26", "learning.promo.popup.cooldown=P10D"})
class PromoPopupTest extends PromoIntegrationTest {
    @Autowired private PromoPopupController popup;
    @Autowired private EntitlementInbox inbox;

    private JsonNode read(UUID account) throws Exception {
        return body(as(account, popup).perform(get("/promo-popup")).andReturn().getResponse());
    }

    private int event(UUID account, String campaign, String event) throws Exception {
        var response = as(account, popup).perform(post("/promo-popup/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"campaignId\":\"" + campaign + "\",\"event\":\"" + event + "\"}")).andReturn().getResponse();
        if (response.getStatus() == 204) assertThat(response.getHeader("Promo-Event-Recorded")).isEqualTo("true");
        return response.getStatus();
    }

    @Test
    void anEligibleAccountSeesTheCampaignAndShownDoesNotHideIt() throws Exception {
        UUID account = UUID.randomUUID();

        JsonNode first = read(account);
        assertThat(first.path("eligible").booleanValue()).isTrue();
        assertThat(first.path("campaign").path("id").stringValue(null)).isEqualTo("autumn-2026");
        assertThat(first.path("campaign").path("title").stringValue(null)).isEqualTo("Осенняя скидка");
        assertThat(first.path("campaign").path("cta").stringValue(null)).isEqualTo("Посмотреть тарифы");
        assertThat(first.path("campaign").path("code").stringValue(null)).isEqualTo("AUTUMN-26");

        assertThat(event(account, "autumn-2026", "SHOWN")).isEqualTo(204);
        assertThat(read(account).path("eligible").booleanValue()).isTrue();
    }

    @Test
    void aDismissalSilencesThePopupForTheCooldownOnly() throws Exception {
        UUID account = UUID.randomUUID();
        event(account, "autumn-2026", "SHOWN");
        assertThat(event(account, "autumn-2026", "DISMISSED")).isEqualTo(204);

        JsonNode silenced = read(account);
        assertThat(silenced.path("eligible").booleanValue()).isFalse();
        assertThat(silenced.path("campaign").isNull()).isTrue();

        clock.set("2026-10-12T09:00:41Z");
        assertThat(read(account).path("eligible").booleanValue()).isFalse();
        clock.set("2026-10-12T09:00:43Z");
        assertThat(read(account).path("eligible").booleanValue()).isTrue();
    }

    @Test
    void aDeclineIsFinal() throws Exception {
        UUID account = UUID.randomUUID();
        assertThat(event(account, "autumn-2026", "DECLINED")).isEqualTo(204);

        clock.set("2027-10-12T09:00:43Z");

        assertThat(read(account).path("eligible").booleanValue()).isFalse();
    }

    @Test
    void aPurchaseEndsPromotionsForGoodButAPromoDoesNot() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID promoUser = account(true, false);
        inbox.accept(new EntitlementInbox.Snapshot("billing-" + buyer, buyer, Plan.PLUS, "BILLING", Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-31T00:00:00Z"), JSON.readTree("{}"), Instant.parse("2026-10-31T00:00:00Z")));
        promo.redeem(promoUser, jwt(promoUser), UUID.randomUUID(), code(tier(PromoType.TIER_DAYS, "PLUS", 5, null, 5)), network());

        assertThat(read(buyer).path("eligible").booleanValue()).isFalse();
        clock.set("2030-01-01T00:00:00Z");
        assertThat(read(buyer).path("eligible").booleanValue()).isFalse();
        clock.set(PromoTestConfiguration.START);
        assertThat(read(promoUser).path("eligible").booleanValue()).isTrue();
    }

    @Test
    void anotherCampaignOrABadEventIsAnInvalidRequest() throws Exception {
        UUID account = UUID.randomUUID();

        assertThat(event(account, "other-campaign", "SHOWN")).isEqualTo(400);
        assertThat(event(account, "autumn-2026", "CLICKED")).isEqualTo(400);
        assertThat(as(account, popup).perform(post("/promo-popup/events").contentType(MediaType.APPLICATION_JSON).content("{\"event\":\"SHOWN\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(read(account).path("eligible").booleanValue()).isTrue();
    }
}
