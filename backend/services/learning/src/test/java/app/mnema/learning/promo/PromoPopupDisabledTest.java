package app.mnema.learning.promo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** With the campaign off (the default) an event is a 204 no-op that never reaches the database, whatever campaign the client names. */
class PromoPopupDisabledTest extends PromoIntegrationTest {
    @Autowired private PromoPopupController popup;

    @Test
    void anEventWhileTheCampaignIsDisabledIsANoOp() throws Exception {
        UUID account = UUID.randomUUID();

        for (String campaign : new String[] {"autumn-2026", "any-other"}) {
            for (String event : new String[] {"SHOWN", "DISMISSED", "DECLINED"}) {
                var response = as(account, popup).perform(post("/promo-popup/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"campaignId\":\"" + campaign + "\",\"event\":\"" + event + "\"}")).andReturn().getResponse();
                assertThat(response.getStatus()).as(campaign + event).isEqualTo(204);
                assertThat(response.getHeader("Promo-Event-Recorded")).as(campaign + event).isEqualTo("false");
            }
        }

        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.promo_popup_state WHERE owner_id=:o").param("o", account).query(Long.class).single())
                .isZero();
    }
}
