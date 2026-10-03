package app.mnema.learning.ai;

import java.util.List;

/**
 * Availability from the live routing state; it never calls a provider.
 *
 * <p>Text generation (and, on the {@code assess} route, semantic grading) is {@code NOT_CONFIGURED} until a usable adapter exists on the {@code text-fast} route (a key is
 * present, or the Stub is selected) and, for a real provider, the user-key secret is configured. It is
 * {@code TEMPORARILY_UNAVAILABLE} while the global daily budget is spent or every candidate of the route has an open
 * circuit, so a healthy fallback keeps it available. Capabilities without an adapter are always {@code NOT_CONFIGURED}.
 */
final class DefaultAiAvailability implements AiAvailability {
    private final AiRouting routing;
    private final BreakerRegistry breakers;
    private final AiBudget budget;
    private final UserKeys userKeys;
    private final boolean stub;

    DefaultAiAvailability(AiRouting routing, BreakerRegistry breakers, AiBudget budget, UserKeys userKeys, boolean stub) {
        this.routing = routing;
        this.breakers = breakers;
        this.budget = budget;
        this.userKeys = userKeys;
        this.stub = stub;
    }

    @Override
    public State text() { return route(AiRoute.TEXT_FAST); }

    @Override
    public State assessment() { return route(AiRoute.ASSESS); }

    private State route(AiRoute route) {
        AiCapability capability = route.capability();
        List<AiRouting.Candidate> candidates = routing.candidates(route);
        if (candidates.isEmpty() || (!stub && !userKeys.configured())) return State.NOT_CONFIGURED;
        if (budget.exhausted(capability)) return State.TEMPORARILY_UNAVAILABLE;
        boolean allOpen = candidates.stream().allMatch(candidate -> breakers.of(candidate.provider(), capability).isOpen());
        return allOpen ? State.TEMPORARILY_UNAVAILABLE : State.AVAILABLE;
    }

    @Override
    public State port(AiCapability capability, boolean present) {
        if (!present) return State.NOT_CONFIGURED;
        return budget.exhausted(capability) ? State.TEMPORARILY_UNAVAILABLE : State.AVAILABLE;
    }
}
