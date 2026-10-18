package app.mnema.learning.onboarding;

import app.mnema.learning.capability.CapabilityFlags;
import app.mnema.learning.community.CatalogAvailability;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Which milestones of the path an operator's configuration hides ({@code visibility} of {@code contracts/onboarding/milestones.json}): the switch of the capability a milestone
 * stands on ({@link CapabilityFlags}: AI assessment, speech to text, AI generation), the community catalogue being on and listing at least one deck
 * ({@link CatalogAvailability}), and the rhythm, which has no writer in registry version 1. Never the runtime circuit breaker or the budget: a capability that is temporarily
 * unavailable is still part of the path. A hidden milestone is named in {@code path.hidden}, counts in neither {@code done} nor {@code total} and is not announced.
 */
@Component
final class PathVisibility {
    private final CapabilityFlags flags;
    private final CatalogAvailability catalog;

    PathVisibility(CapabilityFlags flags, CatalogAvailability catalog) {
        this.flags = flags;
        this.catalog = catalog;
    }

    /** @return the hidden milestones; the catalogue is asked at most once per call (and a switched-off one without a query) */
    Set<MilestoneKey> hidden() {
        Set<MilestoneKey> hidden = EnumSet.noneOf(MilestoneKey.class);
        for (MilestoneKey key : MilestoneKey.values()) {
            if (!visible(key.visibility())) hidden.add(key);
        }
        return hidden;
    }

    private boolean visible(MilestoneKey.Visibility visibility) {
        return switch (visibility) {
            case ALWAYS -> true;
            case AI_ASSESSMENT -> flags.aiAssessment().enabled();
            case SPEECH_TO_TEXT -> flags.speechToText().enabled();
            case AI_GENERATION -> flags.aiGeneration().enabled();
            case COMMUNITY_CATALOG -> catalog.listsDecks();
            // OB-401 writes RHYTHM_WEEK and switches this on; registry version 1 has no writer, so a bar that can never fill is not shown
            case RHYTHM -> false;
        };
    }
}
