package app.mnema.learning.ai;

/**
 * Server-owned text routes ({@code learning.ai.routes.*}). A caller names the route; which provider and model serve it
 * is configuration, never a user choice.
 */
public enum AiRoute {
    /** Default drafting route (non-thinking Flash class). */
    TEXT_FAST(AiCapability.TEXT),
    /** Escalation route for output that stayed invalid after a repair. */
    TEXT_STRONG(AiCapability.TEXT),
    /** Interactive semantic grading. */
    ASSESS(AiCapability.ASSESS);

    private final AiCapability capability;

    AiRoute(AiCapability capability) { this.capability = capability; }

    public AiCapability capability() { return capability; }

    /** The route tried after an output stayed invalid after its repair, or null when there is none. */
    public AiRoute escalation() { return this == TEXT_FAST ? TEXT_STRONG : null; }
}
