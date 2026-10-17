package app.mnema.learning.onboarding;

import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * The sample deck of the first-entry journey (OB-201, OB-203; {@code contracts/onboarding}):
 * <ul>
 *   <li>{@code learning.onboarding.sample-deck.enabled} (default false): switches the creation of new sample decks. With it off {@code POST /api/onboarding/sample-deck} is
 *       {@code 409 SAMPLE_DECK_UNAVAILABLE} and the view says {@code available=false}; a copy the account already has keeps working.</li>
 *   <li>{@code learning.onboarding.sample-deck.template-id}: the editorial deck that is copied (a UUID, or empty for none). Marked {@code SAMPLE_TEMPLATE} at startup
 *       ({@link SampleTemplateMarker}). A value that is not a UUID stops startup: a typo must not silently switch the branch off.</li>
 *   <li>{@code learning.onboarding.sample-deck.free-ai-checks} (default 3, 0 to 3): the AI answer checks an account gets for free in its sample deck. 0 switches the
 *       sponsorship off. Counted per account, never per copy.</li>
 * </ul>
 *
 * <p>Not final so that an integration test can name its template after the editorial deck exists (the deck gets its id when it is created).
 */
@Component
public class SampleDeckSettings {
    /** The most free checks an account can be given: the contract says 0 to 3 and the client text assumes at most three. */
    public static final int MAX_FREE_CHECKS = 3;

    private final boolean enabled;
    private final UUID templateId;
    private final int freeAiChecks;

    SampleDeckSettings(@Value("${learning.onboarding.sample-deck.enabled:false}") boolean enabled,
                       @Value("${learning.onboarding.sample-deck.template-id:}") String templateId,
                       @Value("${learning.onboarding.sample-deck.free-ai-checks:3}") int freeAiChecks) {
        this.enabled = enabled;
        this.templateId = parse(templateId);
        if (freeAiChecks < 0 || freeAiChecks > MAX_FREE_CHECKS) {
            throw new IllegalArgumentException("Invalid onboarding settings: sample-deck.free-ai-checks must be between 0 and " + MAX_FREE_CHECKS);
        }
        this.freeAiChecks = freeAiChecks;
    }

    private static UUID parse(String text) {
        if (text == null || text.isBlank()) return null;
        String value = text.strip();
        try {
            UUID id = UUID.fromString(value);
            if (value.length() != 36 || !id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return UuidPolicy.requireEntityId(id, "templateId");
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid onboarding settings: sample-deck.template-id must be a UUID or empty");
        }
    }

    boolean enabled() {
        return enabled;
    }

    /** The configured template, whether or not it exists. */
    Optional<UUID> templateId() {
        return Optional.ofNullable(templateId);
    }

    /** Whether new sample decks can be made at all: the switch is on and a template is configured. */
    boolean configured() {
        return enabled && templateId != null;
    }

    public int freeAiChecks() {
        return freeAiChecks;
    }
}
