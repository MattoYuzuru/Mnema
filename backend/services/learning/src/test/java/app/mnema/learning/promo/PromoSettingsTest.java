package app.mnema.learning.promo;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Configuration that would weaken a limit or show a broken campaign fails at startup; the code shape is one place. */
class PromoSettingsTest {
    private static PromoSettings limits(int attempts, int ip, int accounts, Duration window, String secret) {
        return new PromoSettings(attempts, ip, accounts, window, secret);
    }

    private static PromoPopupSettings popup(boolean enabled, String id, String title, String body, String cta, String code, String cooldown) {
        return new PromoPopupSettings(enabled, id, title, body, cta, code, Duration.parse(cooldown));
    }

    @Test
    void limitsOutOfRangeAreRefused() {
        assertThat(limits(5, 5, 3, Duration.ofHours(24), "s").attemptsPerHour).isEqualTo(5);
        assertThatThrownBy(() -> limits(0, 5, 3, Duration.ofHours(24), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limits(5, 0, 3, Duration.ofHours(24), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limits(5, 5, 0, Duration.ofHours(24), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limits(5, 5, 3, Duration.ofMinutes(5), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limits(5, 5, 3, Duration.ofDays(90), "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withoutASecretARandomOneIsDrawnPerProcess() {
        assertThat(limits(5, 5, 3, Duration.ofHours(24), "").hashSecret).hasSize(32)
                .isNotEqualTo(limits(5, 5, 3, Duration.ofHours(24), "").hashSecret);
        assertThat(limits(5, 5, 3, Duration.ofHours(24), "abc").hashSecret).isEqualTo("abc".getBytes());
    }

    @Test
    void aPopupCooldownMustBeSevenToThirtyDaysAndAnEnabledCampaignComplete() {
        assertThat(popup(false, "", "", "", "Go", "", "P14D").enabled).isFalse();
        assertThat(popup(true, "autumn", "Title", "Body", "Go", "AUTUMN-26", "P7D").campaign.code()).isEqualTo("AUTUMN-26");
        assertThat(popup(true, "autumn", "Title", "Body", "Go", "", "P30D").campaign.code()).isNull();
        for (String cooldown : new String[] {"P6D", "P31D"}) {
            assertThatThrownBy(() -> popup(false, "", "", "", "Go", "", cooldown)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> popup(true, "", "Title", "Body", "Go", "", "P14D")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> popup(true, "a", "", "Body", "Go", "", "P14D")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> popup(true, "a", "T", " ", "Go", "", "P14D")).isInstanceOf(IllegalArgumentException.class);
        assertThat(popup(true, "a", "T", "B", " ", "", "P14D").campaign.cta()).isEqualTo(PromoPopupSettings.DEFAULT_CTA);
        assertThatThrownBy(() -> popup(true, "a", "T", "B", "x".repeat(41), "", "P14D")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> popup(true, "a", "T", "B", "Go", "!!", "P14D")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void codesAreNormalizedHashedAndHinted() {
        assertThat(PromoCodes.normalize(" spring-26 plus ")).contains("SPRING26PLUS");
        assertThat(PromoCodes.normalize("spring_26")).contains("SPRING26");
        assertThat(PromoCodes.normalize("abc")).isEmpty();
        assertThat(PromoCodes.normalize("a".repeat(25))).isEmpty();
        assertThat(PromoCodes.normalize("ab€d1")).isEmpty();
        assertThat(PromoCodes.normalize("ıııı")).isEmpty();
        assertThat(PromoCodes.normalize(null)).isEmpty();
        assertThat(PromoCodes.normalize("a".repeat(65))).isEmpty();
        assertThat(PromoCodes.hash("ABCD")).hasSize(32).isEqualTo(PromoCodes.hash("ABCD")).isNotEqualTo(PromoCodes.hash("ABCE"));
        assertThat(PromoCodes.hint("SPRING26PLUS")).isEqualTo("SP…US");
        String generated = PromoCodes.generate();
        assertThat(generated).hasSize(10).matches("[" + PromoCodes.ALPHABET + "]+");
        assertThat(PromoCodes.display(generated)).isEqualTo(generated.substring(0, 5) + "-" + generated.substring(5));
        assertThat(PromoCodes.display("VANITY")).isEqualTo("VANITY");
        assertThat(PromoCodes.generate()).isNotEqualTo(generated);
    }
}
