package app.mnema.learning.usage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The catalogue of {@code GET /api/plans}: prices, discounts, highlights and the Max teaser, from configuration and the contract. */
class PlansServiceTest {
    private static final UsagePolicy POLICY = new UsagePolicy("rc-v1", "Europe/Moscow", new BigDecimal("0.35"),
            "13,13,12,12", 80, Duration.ofHours(2));
    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");
    private static final UsageClock CLOCK = () -> NOW;
    private final AllowanceCatalog catalog = new AllowanceCatalog(POLICY);
    private final EntitlementSource free = (owner, now) -> new Entitlement(Plan.FREE, Entitlement.Source.CONFIG,
            Instant.parse("2026-10-31T21:00:00Z"));

    private static PlanSettings settings(boolean teaser) {
        return new PlanSettings(5, 10, 10, teaser, Plan.PRO, Plan.PLUS, Plan.PLUS, Plan.PRO, Plan.PLUS);
    }

    private PlansView view(boolean teaser) {
        return view(teaser, new StaticListableBeanFactory());
    }

    private PlansView view(boolean teaser, StaticListableBeanFactory beans) {
        return new PlansService(free, catalog, settings(teaser), CLOCK,
                org.mockito.Mockito.mock(app.mnema.learning.experiment.ExperimentAssignments.class),
                org.mockito.Mockito.mock(app.mnema.learning.promo.PromoDiscounts.class),
                beans.getBeanProvider(CheckoutAvailability.class)).read(UUID.randomUUID());
    }

    @Test
    void checkoutIsUnavailableWithoutBillingAndFollowsItsAnswerWhenThereIsOne() {
        assertThat(view(false).checkout()).isEqualTo("UNAVAILABLE");

        var beans = new StaticListableBeanFactory();
        beans.addBean("availability", (CheckoutAvailability) owner -> true);
        assertThat(view(false, beans).checkout()).isEqualTo("AVAILABLE");

        var closed = new StaticListableBeanFactory();
        closed.addBean("availability", (CheckoutAvailability) owner -> false);
        assertThat(view(false, closed).checkout()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void pricesAreTheContractsMonthlyPricesAndTheYearIsTwelveMonthsAfterTheConfiguredDiscount() {
        var plans = view(false).plans();

        assertThat(plans).extracting(PlansView.PlanView::plan).containsExactly("FREE", "PLUS", "PRO");
        assertThat(plans.get(0).priceRub()).isEqualTo(new PlansView.PriceView(0, 0));
        assertThat(plans.get(1).priceRub()).isEqualTo(new PlansView.PriceView(449, 5119)); // 5388 * 0.95 = 5118.6
        assertThat(plans.get(2).priceRub()).isEqualTo(new PlansView.PriceView(990, 10692)); // 11880 * 0.90
        assertThat(plans.get(1).yearDiscountPercent()).isEqualTo(5);
        assertThat(plans.get(1).perDayRub()).isEqualTo(15);
        assertThat(plans.get(2).perDayRub()).isEqualTo(33);
        assertThat(PlansService.year(1900, 10)).isEqualTo(20520);
    }

    @Test
    void maxIsAbsentUnlessTheTeaserIsEnabledAndThenItIsATeaser() {
        assertThat(view(false).plans()).extracting(PlansView.PlanView::plan).doesNotContain("MAX");

        var max = view(true).plans().get(3);

        assertThat(max.plan()).isEqualTo("MAX");
        assertThat(max.availability()).isEqualTo("TEASER");
        assertThat(max.priceRub().month()).isEqualTo(1900);
        assertThat(view(true).plans().subList(0, 3)).extracting(PlansView.PlanView::availability).containsOnly("AVAILABLE");
    }

    @Test
    void highlightsAreHumanUnitsComputedFromTheAllowances() {
        var plans = view(true).plans();

        assertThat(plans.get(0).highlights()).containsExactly("≈ 2 материала с ИИ в месяц", "Голос: 60 мин в месяц",
                "Проверка ответов: 50 в месяц");
        assertThat(plans.get(1).highlights()).containsExactly("≈ 12–17 материалов с ИИ в месяц", "Голос: 300 мин в месяц",
                "Проверка ответов: 500 в месяц");
        assertThat(plans.get(2).highlights()).contains("≈ 29–39 материалов с ИИ в месяц", "Голос: 600 мин в месяц");
        assertThat(plans.get(3).highlights()).containsExactly("≈ 63–85 материалов с ИИ в месяц", "Голос: без месячного лимита",
                "Проверка ответов: 1500 в месяц");
    }

    @Test
    void theComparisonTableCarriesEveryAllowanceInDisplayUnits() {
        var plus = view(true).plans().get(1).allowances();
        var max = view(true).plans().get(3).allowances();

        assertThat(plus.voiceMinutesPerMonth()).isEqualTo(300);
        assertThat(plus.voiceMinutesPerDay()).isEqualTo(30);
        assertThat(plus.answerChecksPerDay()).isEqualTo(40);
        assertThat(plus.podcastsPerMonth()).isEqualTo(2);
        assertThat(plus.qualityImagesPerMonth()).isZero();
        assertThat(plus.smartPlans()).isEqualTo(new PlansView.SmartPlans(4, "MONTH"));
        assertThat(max.voiceMinutesPerMonth()).isNull();
        assertThat(max.voiceMinutesPerDay()).isEqualTo(120);
        assertThat(max.qualityImagesPerMonth()).isEqualTo(25);
    }

    @Test
    void goalsPointAtTheConfiguredTiers() {
        var plans = view(false).plans();

        assertThat(plans.get(0).recommendedFor()).isEmpty();
        assertThat(plans.get(1).recommendedFor()).containsExactly("INTERVIEW", "LANGUAGE", "SELF");
        assertThat(plans.get(2).recommendedFor()).containsExactly("EXAMS", "WORK");
    }

    @Test
    void theCurrentEntitlementIsReportedWithoutAutoRenew() {
        var current = view(false).current();

        assertThat(current).isEqualTo(new PlansView.CurrentView("FREE", "MONTH", "2026-10-31T21:00:00Z", false, "CONFIG"));
    }

    @Test
    void invalidConfigurationFailsAtStartup() {
        assertThatThrownBy(() -> new PlanSettings(5, 10, 51, false, Plan.PRO, Plan.PLUS, Plan.PLUS, Plan.PRO, Plan.PLUS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanSettings(5, 10, 10, false, Plan.MAX, Plan.PLUS, Plan.PLUS, Plan.PRO, Plan.PLUS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(List.of(1, 2, 3, 4, 5, 11, 12, 21).stream().map(count -> PlansService.highlights(
                new PlansView.AllowanceTable(new PlansView.Range(count, count), 1L, 1, 1, 1, 0, 0, 0, null)).getFirst()))
                .containsExactly("≈ 1 материал с ИИ в месяц", "≈ 2 материала с ИИ в месяц", "≈ 3 материала с ИИ в месяц",
                        "≈ 4 материала с ИИ в месяц", "≈ 5 материалов с ИИ в месяц", "≈ 11 материалов с ИИ в месяц",
                        "≈ 12 материалов с ИИ в месяц", "≈ 21 материал с ИИ в месяц");
    }
}
