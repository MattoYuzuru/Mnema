package app.mnema.learning.usage;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The port through which the estimate learns what a generation spec costs: which operations and how many of each a spec
 * implies. Usage owns the rate-card math; the generation module owns the meaning of a spec, and replaces the default
 * implementation when it lands (AI-04 and later), also supplying the personal-data scan as {@link Warning}s.
 */
public interface GenerationSpecInterpreter {
    /** One operation of the rate card and how many times the spec implies it. */
    record Line(String operation, int count) { }

    /** A fragment of a source looks like personal data; the fragment itself is never echoed. */
    record Warning(String code, Map<String, String> sourceRef, List<Range> ranges) { }

    /** UTF-16 offsets into the source text. */
    record Range(int start, int end) { }

    /**
     * @param lines         operations to price, in the order they are shown
     * @param budgetPercent {@code settings.budgetPercent} or null: the share of the remaining budget to spend
     */
    record Interpretation(List<Line> lines, Integer budgetPercent, List<Warning> warnings) { }

    /**
     * @param remainingCredits what the owner can spend now; a spec sized by budget needs it to resolve its quantity
     * @throws app.mnema.learning.platform.api.InvalidRequestException           malformed or unknown field
     * @throws SpecNotSupportedException                                         a kind that is not supported yet
     * @throws app.mnema.learning.platform.api.ResourceLimitExceededException    a session limit is exceeded
     */
    Interpretation interpret(UUID owner, UUID deckId, JsonNode spec, int remainingCredits);

    /** The same interpretation for the admission of a session, which also refuses stale pins. */
    default Interpretation interpretForAdmission(UUID owner, UUID deckId, JsonNode spec, int remainingCredits) {
        return interpret(owner, deckId, spec, remainingCredits);
    }
}
