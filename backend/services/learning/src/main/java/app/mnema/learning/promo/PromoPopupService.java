package app.mnema.learning.promo;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.usage.UsageClock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The state of the promo popup per account (not per device). The popup is eligible when the campaign is enabled, the account never declined, no
 * dismissal is younger than the cooldown, and the account never bought anything (a {@code BILLING} snapshot in the entitlement inbox ends
 * promotions for good). "Not twice in a session" is the
 * client's rule on top of this (sessionStorage): the server cannot see a session.
 */
@Service
public class PromoPopupService {
    /** What the client may record. */
    public enum Event { SHOWN, DISMISSED, DECLINED }

    private final JdbcClient jdbc;
    private final PromoPopupSettings settings;
    private final UsageClock clock;

    PromoPopupService(JdbcClient jdbc, PromoPopupSettings settings, UsageClock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
    }

    private record State(Instant dismissedAt, Instant declinedAt) { }

    /** @return {@code {eligible, campaign: {id,title,body,cta,code}|null}} */
    @Transactional(readOnly = true)
    public ObjectNode read(UUID owner) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        if (!eligible(owner, clock.now())) return result.put("eligible", false).putNull("campaign");
        PromoPopupSettings.Campaign campaign = settings.campaign;
        ObjectNode view = JsonNodeFactory.instance.objectNode().put("id", campaign.id()).put("title", campaign.title())
                .put("body", campaign.body()).put("cta", campaign.cta());
        view.put("code", campaign.code());
        return result.put("eligible", true).set("campaign", view);
    }

    private boolean eligible(UUID owner, Instant now) {
        if (!settings.enabled) return false;
        Optional<State> state = jdbc.sql("SELECT dismissed_at,declined_at FROM app_learning.promo_popup_state WHERE owner_id=:owner")
                .param("owner", owner).query((row, number) -> new State(instant(row.getTimestamp("dismissed_at")),
                        instant(row.getTimestamp("declined_at")))).optional();
        if (state.isPresent()) {
            if (state.get().declinedAt() != null) return false;
            if (state.get().dismissedAt() != null && now.isBefore(state.get().dismissedAt().plus(settings.cooldown))) return false;
        }
        return !jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_learning.entitlement_inbox WHERE owner_id=:owner AND source='BILLING')")
                .param("owner", owner).query(Boolean.class).single();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * Records what happened to the popup of the configured campaign.
     *
     * While the campaign is disabled there is nothing to record: the call is a no-op that never touches the database (a client with a stale
     * campaign in hand still gets its 204; the method opens no transaction, the one upsert below is atomic).
     *
     * @return true after the preference was written, false for a disabled-campaign no-op
     * @throws InvalidRequestException the campaign is not the configured one
     */
    public boolean record(UUID owner, String campaignId, Event event) {
        if (!settings.enabled) return false;
        if (!settings.campaign.id().equals(campaignId)) throw new InvalidRequestException();
        Timestamp now = Timestamp.from(clock.now());
        String column = switch (event) {
            case SHOWN -> "last_shown_at";
            case DISMISSED -> "dismissed_at";
            case DECLINED -> "declined_at";
        };
        // DECLINED is final (never shown again); DISMISSED only starts the cooldown; SHOWN is recorded for diagnostics.
        return jdbc.sql("INSERT INTO app_learning.promo_popup_state(owner_id,campaign_id," + column + ") VALUES (:owner,:campaign,:now) "
                        + "ON CONFLICT (owner_id) DO UPDATE SET campaign_id=EXCLUDED.campaign_id," + column + "=EXCLUDED." + column)
                .param("owner", owner).param("campaign", campaignId).param("now", now).update() == 1;
    }
}
