package app.mnema.learning.promo;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Configuration that would weaken a limit or show a broken campaign fails at startup; the code shape is one place. */
class PromoSettingsTest {
    private static PromoSettings limits(int attempts, int ip, int accounts, Duration window, String secret) {
        return new PromoSettings(attempts, ip, accounts, window, secret, "dev");
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
    void productionWithoutAUsableSecretSwitchesPromoCodesOffAndLocalWorkKeepsTheRandomOne() {
        String strong = "s".repeat(PromoSettings.MIN_PRODUCTION_SECRET);
        for (String weak : new String[] {"", "  ", "s".repeat(31)}) {
            PromoSettings off = new PromoSettings(5, 20, 3, Duration.ofHours(24), weak, "PROD ");
            assertThat(off.available).isFalse();
            assertThatThrownBy(off::requireAvailable).isInstanceOf(app.mnema.learning.platform.api.CapabilityUnavailableException.class);
        }
        PromoSettings on = new PromoSettings(5, 20, 3, Duration.ofHours(24), strong, "prod");
        assertThat(on.available).isTrue();
        assertThat(on.hashSecret).isEqualTo(strong.getBytes());
        for (String environment : new String[] {"dev", "local", "local-full-stack", "staging", ""}) {
            PromoSettings local = new PromoSettings(5, 20, 3, Duration.ofHours(24), "", environment);
            assertThat(local.hashSecret).hasSize(32);
            assertThat(local.available).isTrue();
        }
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
    void codesAreNormalizedHashedAndHinted() throws Exception {
        assertThat(PromoCodes.normalize(" spring-26 plus ")).contains("SPRING26PLUS");
        assertThat(PromoCodes.normalize("spring_26")).contains("SPRING26");
        assertThat(PromoCodes.normalize("abc")).isEmpty();
        assertThat(PromoCodes.normalize("abcd123")).isEmpty();
        assertThat(PromoCodes.normalize("abcd1234")).contains("ABCD1234");
        assertThat(PromoCodes.normalize("a".repeat(25))).isEmpty();
        assertThat(PromoCodes.normalize("ab€d1")).isEmpty();
        assertThat(PromoCodes.normalize("ıııı")).isEmpty();
        assertThat(PromoCodes.normalize(null)).isEmpty();
        assertThat(PromoCodes.normalize("a".repeat(65))).isEmpty();
        byte[] secret = "a-secret-of-at-least-thirty-two-bytes".getBytes();
        assertThat(PromoCodes.hash(secret, "ABCD1234")).hasSize(32).isEqualTo(PromoCodes.hash(secret, "ABCD1234"))
                .isNotEqualTo(PromoCodes.hash(secret, "ABCD1235")).isNotEqualTo(PromoCodes.hash("another-secret-of-thirty-two-bytes".getBytes(), "ABCD1234"));
        assertThat(PromoCodes.hash(secret, "ABCD1234")).isNotEqualTo(java.security.MessageDigest.getInstance("SHA-256")
                .digest("ABCD1234".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(PromoCodes.hint("SPRING26PLUS")).isEqualTo("SP…US");
        String generated = PromoCodes.generate();
        assertThat(generated).hasSize(12).matches("[" + PromoCodes.ALPHABET + "]+");
        assertThat(PromoCodes.display(generated)).isEqualTo(generated.substring(0, 4) + "-" + generated.substring(4, 8) + "-" + generated.substring(8));
        assertThat(PromoCodes.display("VANITY")).isEqualTo("VANITY");
        assertThat(PromoCodes.generate()).isNotEqualTo(generated);
    }
}
