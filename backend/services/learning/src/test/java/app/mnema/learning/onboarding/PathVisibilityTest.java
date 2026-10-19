package app.mnema.learning.onboarding;

import app.mnema.learning.capability.CapabilityFlags;
import app.mnema.learning.community.CatalogAvailability;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The operator switches that hide milestones; the rhythm is part of the path exactly while {@code learning.onboarding.rhythm.enabled} is on. */
class PathVisibilityTest {
    private final CatalogAvailability catalog = mock(CatalogAvailability.class);

    private Set<MilestoneKey> hidden(boolean assessment, boolean speech, boolean generation, boolean listed, boolean rhythm) {
        when(catalog.listsDecks()).thenReturn(listed);
        return new PathVisibility(CapabilityFlags.of(assessment, speech, generation, false, false, false, false, false), catalog, new RhythmSettings(rhythm)).hidden();
    }

    @Test
    void everythingOffHidesTheCapabilitiesTheCatalogueAndTheRhythm() {
        assertThat(hidden(false, false, false, false, false)).containsExactlyInAnyOrder(MilestoneKey.AI_EXPLANATION, MilestoneKey.VOICE_ANSWER, MilestoneKey.WORKSHOP_APPROVED,
                MilestoneKey.RHYTHM_WEEK, MilestoneKey.COMMUNITY_COPY);
    }

    @Test
    void eachSwitchShowsExactlyItsOwnMilestone() {
        assertThat(hidden(true, false, false, false, false)).doesNotContain(MilestoneKey.AI_EXPLANATION).contains(MilestoneKey.VOICE_ANSWER, MilestoneKey.WORKSHOP_APPROVED, MilestoneKey.COMMUNITY_COPY);
        assertThat(hidden(false, true, false, false, false)).doesNotContain(MilestoneKey.VOICE_ANSWER).contains(MilestoneKey.AI_EXPLANATION, MilestoneKey.WORKSHOP_APPROVED, MilestoneKey.COMMUNITY_COPY);
        assertThat(hidden(false, false, true, false, false)).doesNotContain(MilestoneKey.WORKSHOP_APPROVED).contains(MilestoneKey.AI_EXPLANATION, MilestoneKey.VOICE_ANSWER, MilestoneKey.COMMUNITY_COPY);
        assertThat(hidden(false, false, false, true, false)).doesNotContain(MilestoneKey.COMMUNITY_COPY).contains(MilestoneKey.AI_EXPLANATION, MilestoneKey.VOICE_ANSWER, MilestoneKey.WORKSHOP_APPROVED);
    }

    @Test
    void theRhythmIsHiddenWhateverElseIsOnUntilItsOwnSwitchIsOn() {
        assertThat(hidden(true, true, true, true, false)).containsExactly(MilestoneKey.RHYTHM_WEEK);
        assertThat(EnumSet.allOf(MilestoneKey.class).size() - hidden(true, true, true, true, false).size()).isEqualTo(12);
    }

    @Test
    void theRhythmSwitchShowsExactlyTheRhythmMilestone() {
        assertThat(hidden(false, false, false, false, true)).doesNotContain(MilestoneKey.RHYTHM_WEEK)
                .containsExactlyInAnyOrder(MilestoneKey.AI_EXPLANATION, MilestoneKey.VOICE_ANSWER, MilestoneKey.WORKSHOP_APPROVED, MilestoneKey.COMMUNITY_COPY);
        assertThat(hidden(true, true, true, true, true)).isEmpty();
    }
}
