package app.mnema.learning.onboarding;

import app.mnema.learning.capability.CapabilityFlags;
import app.mnema.learning.community.CatalogAvailability;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The operator switches that hide milestones; the rhythm has no writer in registry version 1 and is always hidden. */
class PathVisibilityTest {
    private final CatalogAvailability catalog = mock(CatalogAvailability.class);

    private Set<MilestoneKey> hidden(boolean assessment, boolean speech, boolean generation, boolean listed) {
        when(catalog.listsDecks()).thenReturn(listed);
        return new PathVisibility(CapabilityFlags.of(assessment, speech, generation, false, false, false, false, false), catalog).hidden();
    }

    @Test
    void everythingOffHidesTheCapabilitiesTheCatalogueAndTheRhythm() {
        assertThat(hidden(false, false, false, false)).containsExactlyInAnyOrder(MilestoneKey.AI_EXPLANATION, MilestoneKey.VOICE_ANSWER, MilestoneKey.WORKSHOP_APPROVED,
                MilestoneKey.RHYTHM_WEEK, MilestoneKey.COMMUNITY_COPY);
    }

    @Test
    void eachSwitchShowsExactlyItsOwnMilestone() {
        assertThat(hidden(true, false, false, false)).doesNotContain(MilestoneKey.AI_EXPLANATION).contains(MilestoneKey.VOICE_ANSWER, MilestoneKey.WORKSHOP_APPROVED, MilestoneKey.COMMUNITY_COPY);
        assertThat(hidden(false, true, false, false)).doesNotContain(MilestoneKey.VOICE_ANSWER).contains(MilestoneKey.AI_EXPLANATION, MilestoneKey.WORKSHOP_APPROVED, MilestoneKey.COMMUNITY_COPY);
        assertThat(hidden(false, false, true, false)).doesNotContain(MilestoneKey.WORKSHOP_APPROVED).contains(MilestoneKey.AI_EXPLANATION, MilestoneKey.VOICE_ANSWER, MilestoneKey.COMMUNITY_COPY);
        assertThat(hidden(false, false, false, true)).doesNotContain(MilestoneKey.COMMUNITY_COPY).contains(MilestoneKey.AI_EXPLANATION, MilestoneKey.VOICE_ANSWER, MilestoneKey.WORKSHOP_APPROVED);
    }

    @Test
    void theRhythmStaysHiddenWhateverElseIsOn() {
        assertThat(hidden(true, true, true, true)).containsExactly(MilestoneKey.RHYTHM_WEEK);
        assertThat(EnumSet.allOf(MilestoneKey.class).size() - hidden(true, true, true, true).size()).isEqualTo(12);
    }
}
