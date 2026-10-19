package app.mnema.learning.onboarding;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import app.mnema.learning.support.MalformedJsonBodies;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static app.mnema.learning.onboarding.OnboardingFixture.CONTRACT;
import static app.mnema.learning.onboarding.OnboardingFixture.bytes;
import static app.mnema.learning.onboarding.OnboardingFixture.copy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Java side of {@code contracts/onboarding/journey.json}: the step registry, the transition table, the views and the command parser all equal the fixture. */
class OnboardingContractTest {
    private static final Instant OFFERED_AT = Instant.parse("2026-10-20T09:00:00Z");
    /** The path of an account with no record when the default configuration hides what it hides (the AI capabilities and the catalogue are off): the fixture's {@code fresh}. */
    private static final PathState FRESH = PathState.of(Map.of(), EnumSet.of(MilestoneKey.AI_EXPLANATION, MilestoneKey.VOICE_ANSWER, MilestoneKey.WORKSHOP_APPROVED,
            MilestoneKey.RHYTHM_WEEK, MilestoneKey.COMMUNITY_COPY));

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
    void theRhythmConstantsAreTheOnesTheCodeUses() {
        JsonNode constants = CONTRACT.path("constants");
        assertThat(constants.path("weeklyTarget").path("default").intValue()).isEqualTo(WeeklyRhythm.DEFAULT_TARGET);
        assertThat(constants.path("weeklyTarget").path("min").intValue()).isOne();
        assertThat(constants.path("weeklyTarget").path("max").intValue()).isEqualTo(7);
        assertThat(names(constants.path("rhythm").path("modes"))).containsExactlyInAnyOrderElementsOf(WeeklyRhythm.COUNTED_MODES);
        assertThat(constants.path("rhythm").path("weekStartsOn").stringValue()).isEqualTo(java.time.DayOfWeek.MONDAY.name());
        // Monday first, whatever day the date is
        assertThat(WeeklyRhythm.weekOf(LocalDate.parse("2026-10-18"))).as("a Sunday").isEqualTo(LocalDate.parse("2026-10-12"));
        assertThat(WeeklyRhythm.weekOf(LocalDate.parse("2026-10-19"))).as("the next Monday").isEqualTo(LocalDate.parse("2026-10-19"));
        assertThat(WeeklyRhythm.weekOf(LocalDate.parse("2027-01-01"))).as("across a year").isEqualTo(LocalDate.parse("2026-12-28"));
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
        SampleDeckState none = SampleDeckState.unavailable(3);
        assertThat(OnboardingView.of(row(OnboardingRepository.Cohort.TOUR, JourneyState.OFFERED, null, null, null, null, 0), none, FRESH, null, null)).isEqualTo(views.path("offered"));
        assertThat(OnboardingView.of(row(OnboardingRepository.Cohort.CONTROL, JourneyState.OFFERED, null, null, null, null, 0), none, FRESH, null, null)).isEqualTo(views.path("holdout"));
        assertThat(OnboardingView.notOffered(none, FRESH, null, null)).isEqualTo(views.path("notOffered"));
        assertThat(OnboardingView.of(new OnboardingRepository.Row(1, JourneyState.PAUSED, OnboardingRepository.Cohort.TOUR, JourneyBranch.OWN, "own.material",
                OnboardingRepository.Experience.NEW, 3, OFFERED_AT, null, 0, 7), none, FRESH, null, null)).isEqualTo(views.path("paused"));
        assertThat(OnboardingView.disabled(3)).isEqualTo(views.path("disabled"));
        // a sample deck that is ready, with its copy and two of the three free checks left
        SampleDeckState ready = new SampleDeckState(true, UUID.fromString("33333333-3333-4333-8333-333333333333"), false, 2);
        assertThat(OnboardingView.of(new OnboardingRepository.Row(1, JourneyState.IN_PROGRESS, OnboardingRepository.Cohort.TOUR, JourneyBranch.SAMPLE, "sample.hub",
                null, null, OFFERED_AT, null, 0, 6), ready, FRESH, null, null)).isEqualTo(views.path("sampleReady"));
    }

    @Test
    void theRhythmViewIsTheFixtureViewOfAnAccountWithAStoredNotOfferedRow() {
        JsonNode expected = CONTRACT.path("views").path("rhythmWeek");
        Map<MilestoneKey, MilestoneRepository.Row> records = new EnumMap<>(MilestoneKey.class);
        expected.path("milestones").forEach(milestone -> {
            int progress = milestone.path("progress").intValue();
            if (progress == 0) return;
            String achieved = milestone.path("achievedAt").stringValue(null);
            records.put(MilestoneKey.valueOf(milestone.path("key").stringValue()), new MilestoneRepository.Row(progress, achieved == null ? null : Instant.parse(achieved)));
        });
        EnumSet<MilestoneKey> hidden = EnumSet.noneOf(MilestoneKey.class);
        expected.path("path").path("hidden").forEach(key -> hidden.add(MilestoneKey.valueOf(key.stringValue())));
        PathState path = PathState.of(records, hidden);
        JsonNode week = expected.path("rhythm");
        List<LocalDate> days = new ArrayList<>();
        week.path("days").forEach(day -> days.add(LocalDate.parse(day.stringValue())));
        RhythmState rhythm = new RhythmState(LocalDate.parse(week.path("weekStart").stringValue()), LocalDate.parse(week.path("today").stringValue()),
                week.path("target").intValue(), days, week.path("weeksMet").intValue());
        // a row the preferences created (version 1): a target and nothing else, no cohort, no offer
        OnboardingRepository.Row stored = new OnboardingRepository.Row(1, JourneyState.NOT_OFFERED, null, null, null, null, 5, null, null, 0, 1);

        assertThat(OnboardingView.of(stored, SampleDeckState.unavailable(3), path, rhythm, null)).isEqualTo(expected);
        assertThat(members(week)).containsExactlyInAnyOrder("weekStart", "today", "target", "days", "weeksMet");

        // the fixture is a week the code could have produced
        assertThat(rhythm.weekStart().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
        assertThat(rhythm.today()).isBetween(rhythm.weekStart(), rhythm.weekStart().plusDays(6));
        assertThat(rhythm.days()).isSorted().doesNotHaveDuplicates().allSatisfy(day -> assertThat(day).isBetween(rhythm.weekStart(), rhythm.today()));
        assertThat(rhythm.target()).isBetween(1, 7);
        assertThat(path.done()).isEqualTo(expected.path("path").path("done").intValue());
        assertThat(path.total()).isEqualTo(expected.path("path").path("total").intValue());
    }

    /** The fixture's view of an account whose whole path is done and whose journey row is a stored NOT_OFFERED one, rebuilt from the fixture's own records with the given reward. */
    private static ObjectNode rewardView(JsonNode expected, RewardState reward) {
        Map<MilestoneKey, MilestoneRepository.Row> records = new EnumMap<>(MilestoneKey.class);
        expected.path("milestones").forEach(milestone -> {
            int progress = milestone.path("progress").intValue();
            if (progress == 0) return;
            String achieved = milestone.path("achievedAt").stringValue(null);
            records.put(MilestoneKey.valueOf(milestone.path("key").stringValue()), new MilestoneRepository.Row(progress, achieved == null ? null : Instant.parse(achieved)));
        });
        EnumSet<MilestoneKey> hidden = EnumSet.noneOf(MilestoneKey.class);
        expected.path("path").path("hidden").forEach(key -> hidden.add(MilestoneKey.valueOf(key.stringValue())));
        JsonNode week = expected.path("rhythm");
        List<LocalDate> days = new ArrayList<>();
        week.path("days").forEach(day -> days.add(LocalDate.parse(day.stringValue())));
        RhythmState rhythm = new RhythmState(LocalDate.parse(week.path("weekStart").stringValue()), LocalDate.parse(week.path("today").stringValue()),
                week.path("target").intValue(), days, week.path("weeksMet").intValue());
        long version = Long.parseLong(expected.path("journey").path("rowVersion").stringValue());
        OnboardingRepository.Row stored = new OnboardingRepository.Row(1, JourneyState.NOT_OFFERED, null, null, null, null, expected.path("journey").path("weeklyTarget").intValue(), null, null, 0, version);
        return OnboardingView.of(stored, SampleDeckState.unavailable(3), PathState.of(records, hidden), rhythm, reward);
    }

    private static RewardState rewardOf(JsonNode reward) {
        JsonNode offer = reward.path("offer");
        return new RewardState(RewardState.State.valueOf(reward.path("state").stringValue()), reward.path("code").stringValue(null),
                new RewardState.Offer(offer.path("type").stringValue(), offer.path("percent").isNull() ? null : offer.path("percent").intValue(), offer.path("plan").stringValue(null),
                        offer.path("days").isNull() ? null : offer.path("days").intValue()), Instant.parse(reward.path("issuedAt").stringValue()), Instant.parse(reward.path("expiresAt").stringValue()));
    }

    @Test
    void theRewardViewsAreTheFixtureViews() {
        JsonNode ready = CONTRACT.path("views").path("rewardReady");
        JsonNode redeemed = CONTRACT.path("views").path("rewardRedeemed");

        assertThat(rewardView(ready, rewardOf(ready.path("reward")))).isEqualTo(ready);
        assertThat(rewardView(redeemed, rewardOf(redeemed.path("reward")))).isEqualTo(redeemed);
        assertThat(ready.path("reward").path("state").stringValue()).isEqualTo("ACTIVE");
        // the code travels only while the reward is ACTIVE, and the members are exactly the contract's
        assertThat(ready.path("reward").path("code").stringValue()).matches("[A-Z2-9&&[^ILO]]{4}-[A-Z2-9&&[^ILO]]{4}-[A-Z2-9&&[^ILO]]{4}");
        assertThat(redeemed.path("reward").path("state").stringValue()).isEqualTo("REDEEMED");
        assertThat(redeemed.path("reward").path("code").isNull()).isTrue();
        assertThat(members(ready.path("reward"))).containsExactlyInAnyOrder("state", "code", "offer", "issuedAt", "expiresAt");
        assertThat(members(ready.path("reward").path("offer"))).containsExactlyInAnyOrder("type", "percent", "plan", "days");
        // the fixture is a reward the issuer could have made: the offer lasts seven days and its terms follow the shape of its type
        for (JsonNode view : new JsonNode[] {ready, redeemed}) {
            JsonNode reward = view.path("reward");
            assertThat(Duration.between(Instant.parse(reward.path("issuedAt").stringValue()), Instant.parse(reward.path("expiresAt").stringValue())))
                    .isEqualTo(Duration.ofDays(CONTRACT.path("constants").path("reward").path("validDays").intValue()));
            assertThat(names(CONTRACT.path("constants").path("reward").path("offers"))).contains(reward.path("offer").path("type").stringValue());
            assertThat(view.path("path").path("done").intValue()).isEqualTo(view.path("path").path("total").intValue());
        }
        assertThat(ready.path("reward").path("offer").path("percent").intValue()).isBetween(1, 90);
        assertThat(redeemed.path("reward").path("offer").path("days").intValue()).isBetween(1, 366);
    }

    @Test
    void everyViewCarriesTheRewardMemberAndOnlyTheRewardViewsFillIt() {
        CONTRACT.path("views").properties().forEach(view -> {
            assertThat(view.getValue().has("reward")).as(view.getKey()).isTrue();
            assertThat(view.getValue().path("reward").isNull()).as(view.getKey()).isEqualTo(!view.getKey().startsWith("reward"));
        });
        assertThat(OnboardingView.disabled(3).path("reward").isNull()).isTrue();
    }

    @Test
    void theRewardConstantsAreTheFixtureOnes() {
        JsonNode reward = CONTRACT.path("constants").path("reward");
        assertThat(reward.path("validDays").intValue()).isEqualTo(RewardIssuer.VALID_DAYS);
        assertThat(names(reward.path("states"))).containsExactly(names(RewardState.State.values()).toArray(String[]::new));
        assertThat(names(reward.path("offers"))).containsExactly("DISCOUNT_PERCENT", "TIER_DAYS");
        assertThat(reward.path("channel").stringValue()).isEqualTo(app.mnema.learning.promo.PersonalPromoCodes.CHANNEL);
        assertThat(reward.path("dailyLimit").path("min").intValue()).isOne();
        assertThat(reward.path("dailyLimit").path("max").intValue()).isEqualTo(RewardSettings.MAX_DAILY_LIMIT);
        assertThat(reward.path("dailyLimit").path("default").intValue()).isEqualTo(200);
    }

    @Test
    void aStoredNotOfferedRowReadsLikeNoRowExceptForItsAnswersAndItsVersion() {
        ObjectNode expected = copy(CONTRACT.path("views").path("notOffered"));
        ((ObjectNode) expected.path("journey")).put("experience", "FAMILIAR").put("weeklyTarget", 4).put("rowVersion", "9");
        // whatever tour data the columns hold, the row is not an offer: no cohort, no times, no replays
        OnboardingRepository.Row stored = new OnboardingRepository.Row(1, JourneyState.NOT_OFFERED, null, null, null, OnboardingRepository.Experience.FAMILIAR, 4, null, null, 0, 9);

        assertThat(OnboardingView.of(stored, SampleDeckState.unavailable(3), FRESH, null, null)).isEqualTo(expected);
    }

    @Test
    void theMidwayPathViewIsTheFixtureViewForAnAccountThatIsFarAlong() {
        JsonNode midway = CONTRACT.path("views").path("pathMidway");
        Map<MilestoneKey, MilestoneRepository.Row> records = new EnumMap<>(MilestoneKey.class);
        midway.path("milestones").forEach(milestone -> {
            int progress = milestone.path("progress").intValue();
            if (progress == 0) return;
            String achieved = milestone.path("achievedAt").stringValue(null);
            records.put(MilestoneKey.valueOf(milestone.path("key").stringValue()), new MilestoneRepository.Row(progress, achieved == null ? null : Instant.parse(achieved)));
        });
        // every capability and the catalogue are on; only the rhythm, which this fixture's configuration leaves off, is hidden
        PathState path = PathState.of(records, EnumSet.of(MilestoneKey.RHYTHM_WEEK));
        OnboardingRepository.Row journey = new OnboardingRepository.Row(1, JourneyState.COMPLETED, OnboardingRepository.Cohort.TOUR, JourneyBranch.OWN, "finale.done",
                OnboardingRepository.Experience.NEW, 3, OFFERED_AT, Instant.parse("2026-10-20T09:12:00Z"), 0, 9);
        assertThat(OnboardingView.of(journey, SampleDeckState.unavailable(3), path, null, null)).isEqualTo(midway);
        assertThat(path.done()).isEqualTo(5);
        assertThat(path.total()).isEqualTo(12);
    }

    @Test
    void aHiddenMilestoneIsOnlyNamedAndCountsInNeitherDoneNorTotalWhateverItsStoredState() {
        Map<MilestoneKey, MilestoneRepository.Row> records = new EnumMap<>(MilestoneKey.class);
        records.put(MilestoneKey.COMMUNITY_COPY, new MilestoneRepository.Row(1, Instant.parse("2026-10-21T10:00:00Z")));
        records.put(MilestoneKey.GOAL_TOLD, new MilestoneRepository.Row(1, Instant.parse("2026-10-21T10:00:00Z")));
        PathState path = PathState.of(records, EnumSet.of(MilestoneKey.COMMUNITY_COPY));
        assertThat(path.done()).isOne();
        assertThat(path.total()).isEqualTo(12);
        assertThat(path.hidden()).containsExactly(MilestoneKey.COMMUNITY_COPY);
        assertThat(path.milestones()).extracting(PathState.Milestone::key).doesNotContain(MilestoneKey.COMMUNITY_COPY);
    }

    @Test
    void aMilestoneBelowItsTargetIsPartialAndANeverWrittenOneIsTodoWithZeroProgress() {
        Map<MilestoneKey, MilestoneRepository.Row> records = new EnumMap<>(MilestoneKey.class);
        records.put(MilestoneKey.OWN_EXERCISES, new MilestoneRepository.Row(2, null));
        records.put(MilestoneKey.TWO_DAYS, new MilestoneRepository.Row(2, Instant.parse("2026-10-21T10:00:00.789Z")));
        PathState path = PathState.of(records, EnumSet.noneOf(MilestoneKey.class));
        assertThat(path.milestones()).filteredOn(m -> m.key() == MilestoneKey.OWN_EXERCISES).singleElement()
                .satisfies(m -> { assertThat(m.state()).isEqualTo(PathState.State.PARTIAL); assertThat(m.progress()).isEqualTo(2); assertThat(m.achievedAt()).isNull(); });
        assertThat(path.milestones()).filteredOn(m -> m.key() == MilestoneKey.GOAL_TOLD).singleElement()
                .satisfies(m -> { assertThat(m.state()).isEqualTo(PathState.State.TODO); assertThat(m.progress()).isZero(); });
        // the time is cut to whole seconds in the view like every other time of the contract
        JsonNode view = OnboardingView.notOffered(SampleDeckState.unavailable(3), path, null, null);
        assertThat(view.path("milestones").get(MilestoneKey.TWO_DAYS.ordinal()).path("achievedAt").stringValue()).isEqualTo("2026-10-21T10:00:00Z");
        assertThat(view.path("path").path("hidden")).isEmpty();
    }

    @Test
    void theSampleDeckMemberCarriesEveryFieldOfTheFixtureAndTheUpdateFlag() {
        JsonNode update = OnboardingView.notOffered(new SampleDeckState(true, UUID.randomUUID(), true, 0), FRESH, null, null).path("sampleDeck");
        assertThat(members(update)).containsExactlyInAnyOrder("available", "deckId", "updateAvailable", "freeChecksLeft");
        assertThat(update.path("updateAvailable").booleanValue()).isTrue();
        assertThat(update.path("freeChecksLeft").intValue()).isZero();
        // every view of the fixture has the same four members
        CONTRACT.path("views").properties().forEach(view -> assertThat(members(view.getValue().path("sampleDeck"))).as(view.getKey())
                .containsExactlyInAnyOrder("available", "deckId", "updateAvailable", "freeChecksLeft"));
    }

    @Test
    void theSampleDeckConstantsAndCommandAreTheFixtureOnes() {
        JsonNode sample = CONTRACT.path("constants").path("sampleDeck");
        assertThat(sample.path("usageOperation").stringValue()).isEqualTo(app.mnema.learning.usage.SponsoredOperation.ONBOARDING_SAMPLE_CHECK.name());
        assertThat(sample.path("freeAiChecks").intValue()).isEqualTo(3);
        assertThat(new SampleDeckSettings(false, "", 3).freeAiChecks()).isEqualTo(sample.path("freeAiChecks").intValue());
        OnboardingCommands.SampleDeck command = OnboardingCommands.sampleDeck(bytes(CONTRACT.path("commands").path("sampleDeck")));
        assertThat(command.commandId()).hasToString(CONTRACT.path("commands").path("sampleDeck").path("commandId").stringValue());
        assertThat(command.envelope().isEmpty()).isTrue();
    }

    @Test
    void theSampleDeckCommandIsStrict() {
        String id = "018f1d98-5c10-7abc-8abc-0123456789ab";
        for (String json : new String[] {"{}", "{\"commandId\":\"" + id + "\",\"rowVersion\":\"0\"}", "{\"commandId\":\"" + id + "\",\"x\":1}",
                "{\"commandId\":1}", "{\"commandId\":\"not-a-uuid\"}", "{\"commandId\":null}", "[]", "\"x\"", "{\"commandId\":\"" + id.toUpperCase(java.util.Locale.ROOT) + "x\"}"}) {
            assertThatThrownBy(() -> OnboardingCommands.sampleDeck(new ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).as(json)
                    .isInstanceOf(InvalidRequestException.class);
        }
        for (MalformedJsonBodies.Body body : MalformedJsonBodies.all()) {
            assertThatThrownBy(() -> OnboardingCommands.sampleDeck(new ByteArrayInputStream(body.bytes()))).as(body.name()).isInstanceOf(InvalidRequestException.class);
        }
    }

    @Test
    void theSampleDeckSettingsAreValidatedAtStartup() {
        assertThat(new SampleDeckSettings(true, " 33333333-3333-4333-8333-333333333333 ", 0).templateId()).contains(UUID.fromString("33333333-3333-4333-8333-333333333333"));
        assertThat(new SampleDeckSettings(true, "", 3).templateId()).isEmpty();
        assertThat(new SampleDeckSettings(true, "", 3).configured()).isFalse();
        assertThat(new SampleDeckSettings(false, "33333333-3333-4333-8333-333333333333", 3).configured()).isFalse();
        assertThat(new SampleDeckSettings(true, "33333333-3333-4333-8333-333333333333", 3).configured()).isTrue();
        for (String bad : new String[] {"nope", "33333333-3333-4333-8333", "00000000-0000-0000-0000-000000000000", "{33333333-3333-4333-8333-333333333333}"}) {
            assertThatThrownBy(() -> new SampleDeckSettings(true, bad, 3)).as(bad).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("template-id");
        }
        assertThatThrownBy(() -> new SampleDeckSettings(true, "", -1)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("free-ai-checks");
        // the contract says 0 to 3 and the client text assumes at most three
        for (int tooMany : new int[] {4, 10, 11}) {
            assertThatThrownBy(() -> new SampleDeckSettings(true, "", tooMany)).as(String.valueOf(tooMany)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("free-ai-checks");
        }
        assertThat(new SampleDeckSettings(true, "", 3).freeAiChecks()).isEqualTo(3);
        assertThat(SampleDeckSettings.MAX_FREE_CHECKS).isEqualTo(3);
    }

    private static List<String> members(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    @Test
    void anUnassignedAccountIsEligibleAndAutoOfferedButAHeldOutOneIsNot() {
        JsonNode unassigned = OnboardingView.of(row(OnboardingRepository.Cohort.UNASSIGNED, JourneyState.OFFERED, null, null, null, null, 0), SampleDeckState.unavailable(3), FRESH, null, null).path("journey");
        assertThat(unassigned.path("eligible").booleanValue()).isTrue();
        assertThat(unassigned.path("autoOffer").booleanValue()).isTrue();
        assertThat(OnboardingView.of(row(OnboardingRepository.Cohort.CONTROL, JourneyState.OFFERED, null, null, null, null, 0), SampleDeckState.unavailable(3), FRESH, null, null).path("journey").path("autoOffer").booleanValue()).isFalse();
    }

    @Test
    void aRowCreatedByReplayIsNotEligibleAndNeverAutoOffered() {
        ObjectNode journey = (ObjectNode) OnboardingView.of(new OnboardingRepository.Row(1, JourneyState.OFFERED, null, null, null, null, null, OFFERED_AT, null, 1, 0),
                SampleDeckState.unavailable(3), FRESH, null, null).path("journey");
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

        // the survey answer of an account that never saw the tour: version "0" (no row) and one field
        OnboardingCommands.Preferences withoutTour = OnboardingCommands.preferences(bytes(CONTRACT.path("commands").path("preferencesWithoutTour")));
        assertThat(withoutTour.rowVersion()).isZero();
        assertThat(withoutTour.weeklyTarget()).isEqualTo(5);
        assertThat(withoutTour.setWeeklyTarget()).isTrue();
        assertThat(withoutTour.setExperience()).isFalse();
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
    void aMalformedBodyIs400EvenWhenTheRowVersionIsAlsoMissing() {
        String id = "018f1d98-5c10-7abc-8abc-0123456789ab";
        for (String json : new String[] {"{\"commandId\":\"" + id + "\",\"action\":\"STEP\"}", "{\"commandId\":\"" + id + "\",\"action\":\"PAUSE\",\"note\":\"x\"}",
                "{\"commandId\":\"" + id + "\",\"action\":\"SKIP\",\"stepKey\":\"welcome\"}", "{\"commandId\":\"" + id + "\",\"action\":\"WAVE\"}"}) {
            assertThatThrownBy(() -> OnboardingCommands.journey(new ByteArrayInputStream(json.getBytes()))).as(json).isInstanceOf(InvalidRequestException.class);
        }
        for (String json : new String[] {"{\"commandId\":\"" + id + "\"}", "{\"commandId\":\"" + id + "\",\"weeklyTarget\":9}",
                "{\"commandId\":\"" + id + "\",\"goal\":\"EXAMS\",\"weeklyTarget\":3}"}) {
            assertThatThrownBy(() -> OnboardingCommands.preferences(new ByteArrayInputStream(json.getBytes()))).as(json).isInstanceOf(InvalidRequestException.class);
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
