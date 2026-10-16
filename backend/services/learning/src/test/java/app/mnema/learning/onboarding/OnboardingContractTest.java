package app.mnema.learning.onboarding;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import app.mnema.learning.support.MalformedJsonBodies;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static app.mnema.learning.onboarding.OnboardingFixture.CONTRACT;
import static app.mnema.learning.onboarding.OnboardingFixture.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Java side of {@code contracts/onboarding/journey.json}: the step registry, the transition table, the views and the command parser all equal the fixture. */
class OnboardingContractTest {
    private static final Instant OFFERED_AT = Instant.parse("2026-10-20T09:00:00Z");

    @Test
    void theStepRegistryIsTheFixtureRegistryInOrder() {
        Map<String, String> expected = new LinkedHashMap<>();
        CONTRACT.path("steps").forEach(step -> expected.put(step.path("key").stringValue(), step.path("branch").stringValue(null)));

        Map<String, String> actual = new LinkedHashMap<>();
        JourneySteps.all().forEach((key, branch) -> actual.put(key, branch == null ? null : branch.name()));

        assertThat(actual).containsExactlyEntriesOf(expected);
        assertThat(JourneySteps.TOUR_VERSION).isEqualTo(CONTRACT.path("tourVersion").intValue());
    }

    @Test
    void theConstantsAreTheFixtureConstants() {
        JsonNode constants = CONTRACT.path("constants");
        assertThat(names(constants.path("states"))).containsExactly(names(JourneyState.values()).toArray(String[]::new));
        assertThat(names(constants.path("actions"))).containsExactly(names(JourneyAction.values()).toArray(String[]::new));
        assertThat(names(constants.path("branches"))).containsExactly(names(JourneyBranch.values()).toArray(String[]::new));
        assertThat(names(constants.path("experiences"))).containsExactly(names(OnboardingRepository.Experience.values()).toArray(String[]::new));
        assertThat(constants.path("experiment").path("key").stringValue()).isEqualTo(OnboardingService.EXPERIMENT);
        assertThat(constants.path("experiment").path("holdoutVariant").stringValue()).isEqualTo("control");
        assertThat(constants.path("report").path("minimumGroup").intValue()).isEqualTo(OnboardingReport.MINIMUM_GROUP);
        assertThat(constants.path("report").path("activationDays").intValue()).isEqualTo(OnboardingReport.ACTIVATION_DAYS);
        assertThat(constants.path("report").path("returnDays").intValue()).isEqualTo(OnboardingReport.RETURN_DAYS);
        assertThat(constants.path("report").path("maxWindowDays").intValue()).isEqualTo(90);
    }

    @Test
    void theTransitionTableIsTheFixtureTable() {
        JsonNode table = CONTRACT.path("transitions");
        for (JourneyAction action : JourneyAction.values()) {
            JsonNode rule = table.path(action.name());
            Map<String, String> from = new LinkedHashMap<>();
            rule.path("from").properties().forEach(entry -> from.put(entry.getKey(), entry.getValue().stringValue()));
            Map<String, String> actual = new LinkedHashMap<>();
            action.transitions().forEach((state, target) -> actual.put(state.name(), target.name()));
            assertThat(actual).as(action + " from").isEqualTo(from);
            assertThat(action.stepKey().name().toLowerCase(java.util.Locale.ROOT)).as(action + " stepKey").isEqualTo(rule.path("stepKey").stringValue());
            assertThat(action.branch().name().toLowerCase(java.util.Locale.ROOT)).as(action + " branch").isEqualTo(rule.path("branch").stringValue());
            assertThat(action.finish() == JourneyAction.Finish.SET).as(action + " sets finishedAt").isEqualTo(contains(rule.path("sets"), "finishedAt"));
            assertThat(action.finish() == JourneyAction.Finish.CLEAR).as(action + " clears finishedAt").isEqualTo(contains(rule.path("clears"), "finishedAt"));
        }
        assertThat(table.propertyNames().stream().filter(name -> !name.equals("note")).toList()).containsExactlyInAnyOrder(names(JourneyAction.values()).toArray(String[]::new));
    }

    @Test
    void theViewsAreTheFixtureViews() {
        JsonNode views = CONTRACT.path("views");
        assertThat(OnboardingView.of(row(OnboardingRepository.Cohort.TOUR, JourneyState.OFFERED, null, null, null, null, 0))).isEqualTo(views.path("offered"));
        assertThat(OnboardingView.of(row(OnboardingRepository.Cohort.CONTROL, JourneyState.OFFERED, null, null, null, null, 0))).isEqualTo(views.path("holdout"));
        assertThat(OnboardingView.notOffered()).isEqualTo(views.path("notOffered"));
        assertThat(OnboardingView.of(new OnboardingRepository.Row(1, JourneyState.PAUSED, OnboardingRepository.Cohort.TOUR, JourneyBranch.OWN, "own.material",
                OnboardingRepository.Experience.NEW, 3, OFFERED_AT, null, 0, 7))).isEqualTo(views.path("paused"));
        assertThat(OnboardingView.disabled()).isEqualTo(views.path("disabled"));
    }

    @Test
    void aRowCreatedByReplayIsNotEligibleAndNeverAutoOffered() {
        ObjectNode journey = (ObjectNode) OnboardingView.of(new OnboardingRepository.Row(1, JourneyState.OFFERED, null, null, null, null, null, OFFERED_AT, null, 1, 0))
                .path("journey");
        assertThat(journey.path("eligible").booleanValue()).isFalse();
        assertThat(journey.path("autoOffer").booleanValue()).isFalse();
    }

    @Test
    void theExampleCommandsParseToTheirFields() {
        OnboardingCommands.Journey start = OnboardingCommands.journey(bytes(CONTRACT.path("commands").path("start")));
        assertThat(start.action()).isEqualTo(JourneyAction.START);
        assertThat(start.rowVersion()).isZero();
        assertThat(start.stepKey()).isEqualTo("survey.goal");
        assertThat(start.branch()).isNull();

        OnboardingCommands.Journey choose = OnboardingCommands.journey(bytes(CONTRACT.path("commands").path("chooseBranch")));
        assertThat(choose.action()).isEqualTo(JourneyAction.STEP);
        assertThat(choose.rowVersion()).isEqualTo(5);
        assertThat(choose.branch()).isEqualTo(JourneyBranch.OWN);
        assertThat(OnboardingCommands.journey(bytes(CONTRACT.path("commands").path("pause"))).stepKey()).isNull();
        assertThat(OnboardingCommands.journey(bytes(CONTRACT.path("commands").path("replay"))).action()).isEqualTo(JourneyAction.REPLAY);

        OnboardingCommands.Preferences preferences = OnboardingCommands.preferences(bytes(CONTRACT.path("commands").path("preferences")));
        assertThat(preferences.rowVersion()).isEqualTo(3);
        assertThat(preferences.experience()).isEqualTo(OnboardingRepository.Experience.FAMILIAR);
        assertThat(preferences.weeklyTarget()).isEqualTo(5);
        assertThat(preferences.setExperience()).isTrue();
    }

    @Test
    void everyInvalidExampleIsRefusedByBothParsers() {
        CONTRACT.path("invalid").properties().forEach(entry -> {
            assertThatThrownBy(() -> OnboardingCommands.journey(bytes(entry.getValue()))).as(entry.getKey() + " as a tour command").isInstanceOf(InvalidRequestException.class);
            assertThatThrownBy(() -> OnboardingCommands.preferences(bytes(entry.getValue()))).as(entry.getKey() + " as preferences").isInstanceOf(InvalidRequestException.class);
        });
    }

    @Test
    void malformedAndMistypedBodiesAreInvalidRequestsNeverRawFailures() {
        for (MalformedJsonBodies.Body body : MalformedJsonBodies.all()) {
            assertThatThrownBy(() -> OnboardingCommands.journey(new ByteArrayInputStream(body.bytes()))).as(body.name()).isInstanceOf(InvalidRequestException.class);
            assertThatThrownBy(() -> OnboardingCommands.preferences(new ByteArrayInputStream(body.bytes()))).as(body.name()).isInstanceOf(InvalidRequestException.class);
        }
        String id = "018f1d98-5c10-7abc-8abc-0123456789ab";
        for (String json : new String[] {
                "{\"commandId\":1,\"rowVersion\":\"0\",\"action\":\"PAUSE\"}",
                "{\"commandId\":\"not-a-uuid\",\"rowVersion\":\"0\",\"action\":\"PAUSE\"}",
                "{\"commandId\":\"" + id + "\",\"rowVersion\":\"01\",\"action\":\"PAUSE\"}",
                "{\"commandId\":\"" + id + "\",\"rowVersion\":0,\"action\":\"PAUSE\"}",
                "{\"commandId\":\"" + id + "\",\"rowVersion\":\"-1\",\"action\":\"PAUSE\"}",
                "{\"commandId\":\"" + id + "\",\"rowVersion\":\"1\",\"action\":\"pause\"}",
                "{\"commandId\":\"" + id + "\",\"rowVersion\":\"1\",\"action\":7}",
                "{\"commandId\":\"" + id + "\",\"rowVersion\":\"1\",\"action\":\"STEP\",\"stepKey\":\"choose\",\"branch\":\"OTHER\"}",
                "{\"commandId\":\"" + id + "\",\"rowVersion\":\"1\",\"action\":\"STEP\",\"stepKey\":5}",
                "{\"commandId\":\"" + id + "\",\"rowVersion\":\"1\",\"action\":\"STEP\",\"stepKey\":\"choose\",\"branch\":\"OWN\"}",
                "{\"commandId\":\"" + id + "\",\"rowVersion\":\"1\",\"action\":\"RESUME\",\"branch\":\"OWN\"}",
                "{\"commandId\":\"" + id + "\",\"rowVersion\":\"1\",\"action\":\"STEP\"}"}) {
            assertThatThrownBy(() -> OnboardingCommands.journey(new ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).as(json)
                    .isInstanceOf(InvalidRequestException.class);
        }
        for (String json : new String[] {
                "{\"commandId\":\"" + id + "\",\"rowVersion\":\"1\",\"experience\":\"EXPERT\"}",
                "{\"commandId\":\"" + id + "\",\"rowVersion\":\"1\",\"experience\":1}",
                "{\"commandId\":\"" + id + "\",\"rowVersion\":\"1\",\"weeklyTarget\":0}",
                "{\"commandId\":\"" + id + "\",\"rowVersion\":\"1\",\"weeklyTarget\":2.5}",
                "{\"commandId\":\"" + id + "\",\"rowVersion\":\"1\",\"weeklyTarget\":\"3\"}",
                "{\"commandId\":\"" + id + "\",\"rowVersion\":\"1\",\"goal\":\"EXAMS\"}"}) {
            assertThatThrownBy(() -> OnboardingCommands.preferences(new ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).as(json)
                    .isInstanceOf(InvalidRequestException.class);
        }
    }

    @Test
    void aMissingRowVersionIsAPreconditionFailureAndNullClearsAPreference() {
        String id = "018f1d98-5c10-7abc-8abc-0123456789ab";
        assertThatThrownBy(() -> OnboardingCommands.journey(new ByteArrayInputStream(("{\"commandId\":\"" + id + "\",\"action\":\"PAUSE\"}").getBytes())))
                .isInstanceOf(VersionPreconditionRequiredException.class);
        assertThatThrownBy(() -> OnboardingCommands.preferences(new ByteArrayInputStream(("{\"commandId\":\"" + id + "\",\"weeklyTarget\":3}").getBytes())))
                .isInstanceOf(VersionPreconditionRequiredException.class);

        OnboardingCommands.Preferences clear = OnboardingCommands.preferences(new ByteArrayInputStream(
                ("{\"commandId\":\"" + id + "\",\"rowVersion\":\"2\",\"experience\":null}").getBytes()));
        assertThat(clear.setExperience()).isTrue();
        assertThat(clear.experience()).isNull();
        assertThat(clear.setWeeklyTarget()).isFalse();
    }

    @Test
    void theFlagNeedsAReleaseInstantAndTheTourOpensAtIt() {
        assertThatThrownBy(() -> new OnboardingSettings(true, "")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eligible-from");
        assertThatThrownBy(() -> new OnboardingSettings(true, "2026-10-16")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OnboardingSettings(true, "tomorrow")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new OnboardingSettings(false, "garbage").enabled()).isFalse();
        assertThat(new OnboardingSettings(false, "").opens(Instant.parse("2999-01-01T00:00:00Z"))).isFalse();

        OnboardingSettings settings = new OnboardingSettings(true, " 2026-10-16T00:00:00Z ");
        assertThat(settings.opens(Instant.parse("2026-10-15T23:59:59.999Z"))).isFalse();
        assertThat(settings.opens(Instant.parse("2026-10-16T00:00:00Z"))).isTrue();
        assertThat(settings.opens(Instant.parse("2026-10-16T00:00:00.001Z"))).isTrue();
    }

    private static OnboardingRepository.Row row(OnboardingRepository.Cohort cohort, JourneyState state, JourneyBranch branch, String step,
                                                OnboardingRepository.Experience experience, Integer weeklyTarget, long version) {
        return new OnboardingRepository.Row(1, state, cohort, branch, step, experience, weeklyTarget, OFFERED_AT, null, 0, version);
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode element : array) if (element.stringValue("").equals(value)) return true;
        return false;
    }

    private static List<String> names(JsonNode array) {
        List<String> names = new ArrayList<>();
        array.forEach(name -> names.add(name.stringValue()));
        return names;
    }

    private static List<String> names(Enum<?>[] values) {
        List<String> names = new ArrayList<>();
        for (Enum<?> value : values) names.add(value.name());
        return names;
    }
}
