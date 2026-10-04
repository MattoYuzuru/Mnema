package app.mnema.learning.ai;

/**
 * Server-owned text routes ({@code learning.ai.routes.*}). A caller names the route; which provider and model serve it
 * is configuration, never a user choice.
 */
public enum AiRoute {
    /** Default drafting route (non-thinking Flash class). */
    TEXT_FAST(AiCapability.TEXT, false),
    /** Escalation route for output that stayed invalid after a repair. */
    TEXT_STRONG(AiCapability.TEXT, false),
    /** Interactive semantic grading. */
    ASSESS(AiCapability.ASSESS, false),
    /** The planner (AI-14, «Сначала показать план»): Flash class with thinking, a long deadline and a large output bound. */
    PLAN(AiCapability.TEXT, true),
    /** Escalation of the planner after an invalid plan stayed invalid after its repair: Pro class with thinking. */
    PLAN_STRONG(AiCapability.TEXT, true);

    private final AiCapability capability;
    private final boolean thinking;

    AiRoute(AiCapability capability, boolean thinking) {
        this.capability = capability;
        this.thinking = thinking;
    }

    public AiCapability capability() { return capability; }

    /** Whether the provider's reasoning mode is switched on for this route (off everywhere else: it multiplies output cost). */
    public boolean thinking() { return thinking; }

    /** The route tried after an output stayed invalid after its repair, or null when there is none. */
    public AiRoute escalation() {
        return switch (this) {
            case TEXT_FAST -> TEXT_STRONG;
            case PLAN -> PLAN_STRONG;
            default -> null;
        };
    }
}
