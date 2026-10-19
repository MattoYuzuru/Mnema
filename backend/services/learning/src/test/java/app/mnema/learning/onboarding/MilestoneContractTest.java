package app.mnema.learning.onboarding;

import app.mnema.learning.notification.NotificationKind;
import app.mnema.learning.notification.NotificationRoute;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static app.mnema.learning.onboarding.MilestoneFixture.MIGRATION;
import static app.mnema.learning.onboarding.MilestoneFixture.REGISTRY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Java side of {@code contracts/onboarding/milestones.json}: the enum, the fixture and the CHECK of {@code account_milestone} (V74) are one list, in one order, and the notification
 * and the examples are what the code produces.
 */
class MilestoneContractTest {
    @Test
    void theEnumIsTheFixtureRegistryInOrderWithItsChapterTargetVisibilityAndRoute() {
        List<String> expected = new ArrayList<>();
        REGISTRY.path("milestones").forEach(milestone -> expected.add(milestone.path("key").stringValue()));
        assertThat(Arrays.stream(MilestoneKey.values()).map(Enum::name)).containsExactlyElementsOf(expected);

        for (JsonNode milestone : REGISTRY.path("milestones")) {
            MilestoneKey key = MilestoneKey.valueOf(milestone.path("key").stringValue());
            assertThat(key.chapter().name().toLowerCase(Locale.ROOT)).as(key + " chapter").isEqualTo(milestone.path("chapter").stringValue());
            assertThat(key.target()).as(key + " target").isEqualTo(milestone.path("target").intValue());
            assertThat(key.visibility().name()).as(key + " visibility").isEqualTo(milestone.path("visibility").stringValue());
            assertThat(key.route()).as(key + " route").isEqualTo(milestone.path("route").stringValue());
            assertThat(REGISTRY.path("routes").has(key.route())).as(key + " route is described").isTrue();
        }
        assertThat(REGISTRY.path("routes").size()).as("every described route is used").isEqualTo((int) Arrays.stream(MilestoneKey.values()).map(MilestoneKey::route).distinct().count());
    }

    @Test
    void theFixtureVocabulariesAreTheCodes() {
        assertThat(names(REGISTRY.path("chapters"))).containsExactly(Arrays.stream(MilestoneKey.Chapter.values()).map(c -> c.name().toLowerCase(Locale.ROOT)).toArray(String[]::new));
        assertThat(names(REGISTRY.path("states"))).containsExactly(Arrays.stream(PathState.State.values()).map(Enum::name).toArray(String[]::new));
        assertThat(names(REGISTRY.path("visibility").propertyNames())).containsExactly(Arrays.stream(MilestoneKey.Visibility.values()).map(Enum::name).toArray(String[]::new));
        assertThat(names(REGISTRY.path("sources"))).containsExactly("LIVE", "BACKFILL");
        assertThat(REGISTRY.path("registryVersion").intValue()).isEqualTo(1);
    }

    @Test
    void theCheckOfTheMigrationListsTheKeysOfTheRegistryInOrder() {
        Matcher check = Pattern.compile("milestone_key\\s+TEXT\\s+NOT NULL CHECK \\(milestone_key IN \\(([^)]*)\\)\\)").matcher(MIGRATION);
        assertThat(check.find()).as("the CHECK of milestone_key").isTrue();
        List<String> keys = new ArrayList<>();
        for (String quoted : check.group(1).split(",")) keys.add(quoted.strip().replace("'", ""));
        assertThat(keys).containsExactly(Arrays.stream(MilestoneKey.values()).map(Enum::name).toArray(String[]::new));
        // the progress bound is the largest target of the registry
        assertThat(MIGRATION).contains("CHECK (progress BETWEEN 1 AND " + Arrays.stream(MilestoneKey.values()).mapToInt(MilestoneKey::target).max().orElseThrow() + ")");
    }

    @Test
    void theNotificationKindIsTheFixtureNotification() {
        JsonNode notification = REGISTRY.path("notification");
        assertThat(NotificationKind.PATH_MILESTONE.name()).isEqualTo(notification.path("kind").stringValue());
        assertThat(NotificationKind.PATH_MILESTONE.severity().name()).isEqualTo(notification.path("severity").stringValue());
        assertThat(NotificationKind.PATH_MILESTONE.route(java.util.Map.of())).isEqualTo(NotificationRoute.PATH);
        assertThat(NotificationRoute.PATH.name()).isEqualTo(notification.path("route").stringValue());
        assertThat(names(notification.path("params").propertyNames())).containsExactly("key", "done", "total");
        assertThat(notification.path("dedupeKey").stringValue()).isEqualTo("path:{key}");
        JsonNode example = REGISTRY.path("examples").path("notification");
        assertThat(example.path("kind").stringValue()).isEqualTo("PATH_MILESTONE");
        assertThat(MilestoneKey.valueOf(example.path("params").path("key").stringValue())).isNotNull();
    }

    @Test
    void theExamplesAreWhatTheCodeBuilds() {
        PathState fresh = PathState.of(java.util.Map.of(), java.util.EnumSet.of(MilestoneKey.AI_EXPLANATION, MilestoneKey.VOICE_ANSWER, MilestoneKey.WORKSHOP_APPROVED,
                MilestoneKey.RHYTHM_WEEK, MilestoneKey.COMMUNITY_COPY));
        JsonNode view = OnboardingView.notOffered(SampleDeckState.unavailable(3), fresh, null);
        JsonNode example = REGISTRY.path("examples").path("fresh");
        assertThat(view.path("milestones")).isEqualTo(example.path("milestones"));
        assertThat(view.path("path")).isEqualTo(example.path("path"));
    }


    @Test
    void theBackfillReadsExactlyTheMilestonesTheFixtureCallsRestorableAtTheRegistryVersionOfTheFixture() {
        assertThat(MilestoneKey.REGISTRY_VERSION).isEqualTo(REGISTRY.path("registryVersion").intValue());
        List<String> restorable = new ArrayList<>();
        for (JsonNode milestone : REGISTRY.path("milestones")) {
            if (!"none".equals(milestone.path("backfill").path("restorable").stringValue())) restorable.add(milestone.path("key").stringValue());
        }
        assertThat(BackfillRepository.SOURCES.stream().map(source -> source.key().name())).containsExactlyElementsOf(restorable);
    }

    private static List<String> names(JsonNode array) {
        List<String> names = new ArrayList<>();
        array.forEach(name -> names.add(name.stringValue()));
        return names;
    }

    private static List<String> names(Iterable<String> properties) {
        List<String> names = new ArrayList<>();
        properties.forEach(names::add);
        return names;
    }
}
