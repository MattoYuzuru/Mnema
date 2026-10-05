package app.mnema.learning.promo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The one promo campaign ({@code learning.promo.popup.*}), an owner decision kept in configuration: off unless {@code enabled}; its copy and the
 * optional code the client offers; and how long a dismissal silences the popup (7 to 30 days). A campaign without an id, a title or a body is
 * refused at startup when enabled.
 */
@Component
final class PromoPopupSettings {
    /** The campaign as shown; {@code code} may be null. */
    record Campaign(String id, String title, String body, String cta, String code) { }

    static final String DEFAULT_CTA = "Посмотреть тарифы";

    final boolean enabled;
    final Duration cooldown;
    final Campaign campaign;

    PromoPopupSettings(@Value("${learning.promo.popup.enabled:false}") boolean enabled,
                       @Value("${learning.promo.popup.id:}") String id,
                       @Value("${learning.promo.popup.title:}") String title,
                       @Value("${learning.promo.popup.body:}") String body,
                       @Value("${learning.promo.popup.cta:}") String ctaText,
                       @Value("${learning.promo.popup.code:}") String code,
                       @Value("${learning.promo.popup.cooldown:P14D}") Duration cooldown) {
        String cta = ctaText.isBlank() ? DEFAULT_CTA : ctaText;
        if (cooldown.compareTo(Duration.ofDays(7)) < 0 || cooldown.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("Invalid promo popup settings: cooldown");
        }
        if (enabled && (!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,59}") || title.isBlank() || title.length() > 120
                || body.isBlank() || body.length() > 500 || cta.isBlank() || cta.length() > 40
                || (!code.isBlank() && PromoCodes.normalize(code).isEmpty()))) {
            throw new IllegalArgumentException("Invalid promo popup settings: campaign");
        }
        this.enabled = enabled;
        this.cooldown = cooldown;
        this.campaign = new Campaign(id, title, body, cta, code.isBlank() ? null : code.strip());
    }
}
