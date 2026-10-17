package app.mnema.learning.onboarding;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The closed registry of tour steps ({@code steps} of {@code contracts/onboarding/journey.json}); a test compares it with the fixture. A command names one of
 * these keys or is a 400, and the metric {@code mnema_onboarding_journey_total} takes its {@code step} label from here only, so its cardinality is bounded.
 */
final class JourneySteps {
    /** The tour version this server writes ({@code tourVersion} of the fixture). */
    static final int TOUR_VERSION = 1;

    private static final Map<String, JourneyBranch> REGISTRY = registry();

    private JourneySteps() { }

    private static Map<String, JourneyBranch> registry() {
        Map<String, JourneyBranch> steps = new LinkedHashMap<>();
        for (String key : new String[] {"welcome", "survey.goal", "survey.interests", "survey.experience", "survey.days", "choose"}) steps.put(key, null);
        for (String key : new String[] {"sample.hub", "sample.material", "sample.exercises", "sample.study"}) steps.put(key, JourneyBranch.SAMPLE);
        for (String key : new String[] {"community.search", "community.deck", "community.add", "community.study"}) steps.put(key, JourneyBranch.COMMUNITY);
        for (String key : new String[] {"own.details", "own.material", "own.exercise", "own.study"}) steps.put(key, JourneyBranch.OWN);
        for (String key : new String[] {"finale.ai", "finale.done"}) steps.put(key, null);
        return java.util.Collections.unmodifiableMap(steps);
    }

    static boolean known(String key) {
        return REGISTRY.containsKey(key);
    }

    /** @return the branch a step belongs to, null for a step shared by every path; the key must be {@link #known} */
    static JourneyBranch branchOf(String key) {
        return REGISTRY.get(key);
    }

    /** The registry in tour order, for the contract test. */
    static Map<String, JourneyBranch> all() {
        return REGISTRY;
    }
}
