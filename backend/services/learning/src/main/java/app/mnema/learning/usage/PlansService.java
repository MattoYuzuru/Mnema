package app.mnema.learning.usage;

import app.mnema.learning.experiment.ExperimentAssignments;
import app.mnema.learning.profile.LearningGoal;
import app.mnema.learning.promo.PromoDiscounts;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Builds {@code GET /api/plans}: a pure read of configuration, the allowance contract and the owner's entitlement. */
@Service
class PlansService {
    private static final int SECONDS_PER_MINUTE = 60;
    private static final int DAYS_PER_MONTH_FOR_DAILY_PRICE = 30;
    private static final int MONTHS = 12;

    private final EntitlementSource entitlements;
    private final AllowanceCatalog catalog;
    private final PlanSettings settings;
    private final UsageClock clock;
    private final ExperimentAssignments experiments;
    private final PromoDiscounts discounts;

    PlansService(EntitlementSource entitlements, AllowanceCatalog catalog, PlanSettings settings, UsageClock clock,
                 ExperimentAssignments experiments, PromoDiscounts discounts) {
        this.entitlements = entitlements;
        this.catalog = catalog;
        this.settings = settings;
        this.clock = clock;
        this.experiments = experiments;
        this.discounts = discounts;
    }

    PlansView read(UUID owner) {
        Instant now = clock.now();
        Entitlement entitlement = entitlements.current(owner, now);
        List<PlansView.PlanView> plans = new ArrayList<>();
        for (Plan plan : Plan.values()) {
            if (plan == Plan.MAX && !settings.maxTeaserEnabled) continue;
            plans.add(plan(plan));
        }
        return new PlansView(new PlansView.CurrentView(entitlement.plan().name(), entitlement.period().name(),
                Wire.time(entitlement.validUntil()), false, entitlement.source().name()), plans,
                experiments.variants(owner), discounts.pending(owner)
                .map(pending -> new PlansView.PendingDiscount(pending.percent(), pending.plan(), Wire.time(pending.validUntil())))
                .orElse(null));
    }

    private PlansView.PlanView plan(Plan plan) {
        AllowanceCatalog.Facts facts = catalog.facts(plan);
        Allowance allowance = catalog.allowance(plan);
        int month = facts.priceRubPerMonth();
        int discount = settings.yearDiscountPercent(plan);
        PlansView.AllowanceTable table = table(allowance, facts);
        return new PlansView.PlanView(plan.name(), plan == Plan.MAX ? "TEASER" : "AVAILABLE",
                new PlansView.PriceView(month, year(month, discount)), perDay(month), discount, highlights(table),
                table, settings.recommendedFor(plan).stream().map(LearningGoal::name).toList());
    }

    /** Twelve months after the discount, to whole rubles (half up). */
    static int year(int month, int discountPercent) {
        return BigDecimal.valueOf((long) month * MONTHS).multiply(BigDecimal.valueOf(100 - discountPercent))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).intValueExact();
    }

    static int perDay(int month) {
        return BigDecimal.valueOf(month).divide(BigDecimal.valueOf(DAYS_PER_MONTH_FOR_DAILY_PRICE), 0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    private static PlansView.AllowanceTable table(Allowance allowance, AllowanceCatalog.Facts facts) {
        return new PlansView.AllowanceTable(new PlansView.Range(facts.materialsMin(), facts.materialsMax()),
                allowance.sttMonth() == null ? null : allowance.sttMonth() / SECONDS_PER_MINUTE,
                allowance.sttDay() / SECONDS_PER_MINUTE, allowance.assessmentMonth(), allowance.assessmentDay(),
                allowance.podcasts(), allowance.qualityImages(), allowance.highFactcheck(),
                new PlansView.SmartPlans(allowance.smartPlanLimit(), allowance.smartPlanWindow().name()));
    }

    /** Materials, voice and answer checks in the units a learner counts in, from the same numbers as the table. */
    static List<String> highlights(PlansView.AllowanceTable table) {
        PlansView.Range range = table.materialsPerMonth();
        String amount = range.min() == range.max() ? Integer.toString(range.max()) : range.min() + "–" + range.max();
        return List.of("≈ " + amount + " " + materials(range.max()) + " с ИИ в месяц",
                table.voiceMinutesPerMonth() == null ? "Голос: без месячного лимита"
                        : "Голос: " + table.voiceMinutesPerMonth() + " мин в месяц",
                "Проверка ответов: " + table.answerChecksPerMonth() + " в месяц");
    }

    private static String materials(int count) {
        int lastTwo = count % 100;
        int last = count % 10;
        if (lastTwo >= 11 && lastTwo <= 14) return "материалов";
        if (last == 1) return "материал";
        if (last >= 2 && last <= 4) return "материала";
        return "материалов";
    }
}
