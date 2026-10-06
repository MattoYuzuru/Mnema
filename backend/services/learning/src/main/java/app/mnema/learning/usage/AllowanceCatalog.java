package app.mnema.learning.usage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * The plans of {@code contracts/usage/allowances-v1.json} (classpath copy {@code usage/allowances-v1.json}). The Free
 * portions and the daily burst fraction come from {@link UsagePolicy}: they are owner decisions kept in configuration.
 */
@Component
final class AllowanceCatalog {
    private static final int SECONDS_PER_MINUTE = 60;
    /** "Weekly" smart plans share one monthly bucket with the Pro plans of the Max plan. */
    private static final int WEEKLY_PLANS_PER_MONTH = 4;

    /**
     * The display facts of a plan that are not limits: the monthly price of the contract and the rough number of materials with
     * five exercises its bar buys ({@code displayHint}; the same number twice for a single value).
     */
    record Facts(int priceRubPerMonth, int materialsMin, int materialsMax) { }

    private final Map<Plan, Allowance> plans = new EnumMap<>(Plan.class);
    private final Map<Plan, Facts> facts = new EnumMap<>(Plan.class);

    @Autowired
    AllowanceCatalog(UsagePolicy policy) {
        this(read(), policy);
    }

    AllowanceCatalog(JsonNode document, UsagePolicy policy) {
        for (Plan plan : Plan.values()) {
            JsonNode node = document.path("plans").path(plan.name());
            if (!node.isObject()) throw new IllegalStateException("Allowances without plan " + plan);
            plans.put(plan, parse(plan, node, policy));
            facts.put(plan, facts(node));
        }
    }

    private static Allowance parse(Plan plan, JsonNode node, UsagePolicy policy) {
        int credits = node.path("monthlyCredits").intValue(-1);
        boolean weekly = "WEEKLY_PORTIONS".equals(node.path("creditSchedule").path("kind").stringValue(null));
        if (credits < 0) throw new IllegalStateException("Plan without a credit bar");
        if (weekly && policy.freeWeeklyPortions.stream().mapToInt(Integer::intValue).sum() != credits) {
            throw new IllegalStateException("Free weekly portions must add up to the bar");
        }
        JsonNode stt = node.path("stt");
        JsonNode assessment = node.path("assessment");
        JsonNode caps = node.path("caps");
        JsonNode smart = node.path("smartPlan");
        int smartLimit = 0;
        Window smartWindow = Window.MONTH;
        if (smart.isObject()) {
            if (smart.has("perMonth")) {
                smartLimit = smart.path("perMonth").intValue(0);
            } else if (smart.has("proPlansPerMonth")) {
                smartLimit = WEEKLY_PLANS_PER_MONTH + smart.path("proPlansPerMonth").intValue(0);
            } else {
                smartLimit = 1;
                smartWindow = Window.WEEK;
            }
        }
        Long sttMonth = stt.path("minutesPerMonth").isNumber() ? stt.path("minutesPerMonth").longValue() * SECONDS_PER_MINUTE : null;
        long sttDayMinutes = stt.has("minutesPerDay") ? stt.path("minutesPerDay").longValue()
                : stt.path("velocityMinutesPerDay").longValue();
        return new Allowance(plan, credits, weekly ? policy.freeWeeklyPortions : null,
                weekly ? null : policy.dailyBurstFraction, sttMonth, sttDayMinutes * SECONDS_PER_MINUTE,
                assessment.path("answersPerMonth").longValue(), assessment.path("answersPerDay").longValue(),
                caps.path("podcasts").intValue(), caps.path("qualityImages").intValue(),
                caps.path("highFactcheck").intValue(), smartLimit, smartWindow);
    }

    private static Facts facts(JsonNode node) {
        JsonNode hint = node.path("displayHint").path("approxMaterialsWithFiveExercisesPerMonth");
        int min = hint.isNumber() ? hint.intValue() : hint.path("min").intValue(0);
        int max = hint.isNumber() ? hint.intValue() : hint.path("max").intValue(min);
        return new Facts(node.path("priceRubPerMonth").intValue(0), min, max);
    }

    private static JsonNode read() {
        try (InputStream stream = AllowanceCatalog.class.getClassLoader().getResourceAsStream("usage/allowances-v1.json")) {
            if (stream == null) throw new IllegalStateException("Missing allowances");
            return JsonMapper.builder().build().readTree(stream);
        } catch (IOException | JacksonException failure) {
            throw new IllegalStateException("Unreadable allowances", failure);
        }
    }

    Allowance allowance(Plan plan) {
        return plans.get(plan);
    }

    Facts facts(Plan plan) {
        return facts.get(plan);
    }
}
