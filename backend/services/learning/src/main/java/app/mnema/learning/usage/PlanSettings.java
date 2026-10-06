package app.mnema.learning.usage;

import app.mnema.learning.profile.LearningGoal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The commercial knobs of the paywall ({@code learning.plans.*}), all owner decisions kept in configuration: the yearly
 * discounts, whether the Max teaser is shown and which tier a goal points to. Invalid values fail at startup.
 */
@Component
final class PlanSettings {
    private final Map<Plan, Integer> yearDiscountPercent = new EnumMap<>(Plan.class);
    private final Map<LearningGoal, Plan> recommendations = new EnumMap<>(LearningGoal.class);
    final boolean maxTeaserEnabled;

    PlanSettings(@Value("${learning.plans.year-discount-percent.plus:5}") int plus,
                 @Value("${learning.plans.year-discount-percent.pro:10}") int pro,
                 @Value("${learning.plans.year-discount-percent.max:10}") int max,
                 @Value("${learning.plans.max-teaser.enabled:false}") boolean maxTeaserEnabled,
                 @Value("${learning.plans.recommendations.exams:PRO}") Plan exams,
                 @Value("${learning.plans.recommendations.interview:PLUS}") Plan interview,
                 @Value("${learning.plans.recommendations.language:PLUS}") Plan language,
                 @Value("${learning.plans.recommendations.work:PRO}") Plan work,
                 @Value("${learning.plans.recommendations.self:PLUS}") Plan self) {
        for (int discount : List.of(plus, pro, max)) {
            if (discount < 0 || discount > 50) throw new IllegalArgumentException("Invalid plans settings: year discount");
        }
        yearDiscountPercent.put(Plan.FREE, 0);
        yearDiscountPercent.put(Plan.PLUS, plus);
        yearDiscountPercent.put(Plan.PRO, pro);
        yearDiscountPercent.put(Plan.MAX, max);
        this.maxTeaserEnabled = maxTeaserEnabled;
        recommendations.put(LearningGoal.EXAMS, exams);
        recommendations.put(LearningGoal.INTERVIEW, interview);
        recommendations.put(LearningGoal.LANGUAGE, language);
        recommendations.put(LearningGoal.WORK, work);
        recommendations.put(LearningGoal.SELF, self);
        // A tier that is not for sale cannot be recommended.
        if (recommendations.values().stream().anyMatch(plan -> plan == Plan.MAX || plan == Plan.FREE)) {
            throw new IllegalArgumentException("Invalid plans settings: recommendation");
        }
    }

    int yearDiscountPercent(Plan plan) {
        return yearDiscountPercent.get(plan);
    }

    /** The goals that point at {@code plan}, in goal order. */
    List<LearningGoal> recommendedFor(Plan plan) {
        return recommendations.entrySet().stream().filter(entry -> entry.getValue() == plan).map(Map.Entry::getKey).toList();
    }
}
