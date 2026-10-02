package app.mnema.learning.usage;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rate card, the allowances, the entitlement configuration and their agreement with {@code contracts/usage}. */
class UsageCatalogTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UsagePolicy POLICY = new UsagePolicy("rc-v1", "Europe/Moscow", new BigDecimal("0.35"),
            "13,13,12,12", 80, Duration.ofHours(2));
    private final RateCard rateCard = new RateCard(POLICY);
    private final AllowanceCatalog catalog = new AllowanceCatalog(POLICY);

    static JsonNode contract(String file) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/usage/" + file))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot find repository root");
        try {
            return JSON.readTree(Files.readString(root.resolve("contracts/usage/" + file)));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void theClasspathCopiesAreTheContractFilesVerbatim() throws IOException {
        for (String file : new String[] {"rate-card-v1.json", "allowances-v1.json"}) {
            try (var stream = getClass().getClassLoader().getResourceAsStream("usage/" + file)) {
                assertThat(stream).isNotNull();
                assertThat(JSON.readTree(stream)).isEqualTo(contract(file));
            }
        }
    }

    @Test
    void weightsAndAvailabilityAreTheContractsRcV1() {
        assertThat(rateCard.version()).isEqualTo("rc-v1");
        assertThat(rateCard.operation("MATERIAL_SHORT").credits()).isEqualTo(4);
        assertThat(rateCard.operation("MATERIAL_MEDIUM").credits()).isEqualTo(10);
        assertThat(rateCard.operation("MATERIAL_DETAILED").credits()).isEqualTo(22);
        assertThat(rateCard.operation("TTS_CLIP_30S").credits()).isEqualTo(10);
        assertThat(rateCard.operation("PODCAST_3MIN").cap()).isEqualTo(Bucket.PODCASTS);
        assertThat(rateCard.operation("PODCAST_3MIN").credits()).isEqualTo(75);
        assertThat(rateCard.operation("FACTCHECK_HIGH").cap()).isEqualTo(Bucket.HIGH_FACTCHECK);
        assertThat(rateCard.operation("IMAGE_GENERATE_QUALITY").availability()).isEqualTo(RateCard.Availability.LATER);
        assertThat(rateCard.operation("ASSESSMENT_ANSWER").pricing()).isEqualTo(RateCard.Pricing.FAIR_USE);
        assertThat(rateCard.operation("ASSESSMENT_ANSWER").credits()).isNull();
        // The deferred video clip is a range and an unmodelled cap: priced by nothing.
        assertThat(rateCard.operation("VIDEO_5S").credits()).isNull();
        assertThat(rateCard.operation("VIDEO_5S").cap()).isNull();
        assertThat(rateCard.operation("VIDEO_5S").availability()).isEqualTo(RateCard.Availability.DEFERRED);
        assertThatThrownBy(() -> rateCard.operation("NOPE")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rateCard.credits("STT_MINUTE", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exercisesAreProRatedPerExerciseAndRoundedUp() {
        assertThat(rateCard.credits("EXERCISES_PER_MATERIAL", 1)).isEqualTo(2);
        assertThat(rateCard.credits("EXERCISES_PER_MATERIAL", 3)).isEqualTo(5);
        assertThat(rateCard.credits("EXERCISES_PER_MATERIAL", 5)).isEqualTo(8);
        assertThat(rateCard.credits("EXERCISES_PER_MATERIAL", 7)).isEqualTo(12);
        assertThat(rateCard.credits("EXERCISES_PER_MATERIAL", 60)).isEqualTo(96);
        assertThat(rateCard.exactCredits("EXERCISES_PER_MATERIAL", 3)).isEqualByComparingTo("4.8");
        assertThat(rateCard.credits("TTS_CLIP_30S", 3)).isEqualTo(30);
    }

    @Test
    void editActionsMapToOperationsAndRemoveMediaIsFree() {
        assertThat(rateCard.forAction("REWRITE").orElseThrow().id()).isEqualTo("EDIT_SELECTION");
        assertThat(rateCard.forAction("FREE").orElseThrow().id()).isEqualTo("EDIT_SELECTION");
        assertThat(rateCard.forAction("IMAGE_SEARCH").orElseThrow().id()).isEqualTo("IMAGE_SEARCH");
        assertThat(rateCard.forAction("IMAGE_GENERATE").orElseThrow().id()).isEqualTo("IMAGE_GENERATE_ECONOMY");
        assertThat(rateCard.forAction("AUDIO_REGENERATE").orElseThrow().id()).isEqualTo("TTS_CLIP_30S");
        assertThat(rateCard.forAction("REMOVE_MEDIA")).isEmpty();
        assertThatThrownBy(() -> rateCard.forAction("DESCRIPTION")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingOrMismatchedRateCardFailsAtStartup() {
        assertThatThrownBy(() -> new RateCard(new UsagePolicy("rc-v9", "UTC", BigDecimal.ONE, "13,13,12,12", 80,
                Duration.ofHours(1)))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RateCard(contract("rate-card-v1.json"), "rc-v2")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void thePlansAreTheContractsAllowances() {
        Allowance free = catalog.allowance(Plan.FREE);
        assertThat(free.credits()).isEqualTo(50);
        assertThat(free.portions()).containsExactly(13, 13, 12, 12);
        assertThat(free.burstFraction()).isNull();
        assertThat(free.sttMonth()).isEqualTo(3_600L);
        assertThat(free.sttDay()).isEqualTo(600L);
        assertThat(free.assessmentMonth()).isEqualTo(50L);
        assertThat(free.assessmentDay()).isEqualTo(5L);
        assertThat(free.podcasts() + free.qualityImages() + free.highFactcheck() + free.smartPlanLimit()).isZero();

        Allowance plus = catalog.allowance(Plan.PLUS);
        assertThat(plus.credits()).isEqualTo(360);
        assertThat(plus.portions()).isNull();
        assertThat(plus.burstFraction()).isEqualByComparingTo("0.35");
        assertThat(plus.sttMonth()).isEqualTo(18_000L);
        assertThat(plus.sttDay()).isEqualTo(1_800L);
        assertThat(plus.podcasts()).isEqualTo(2);
        assertThat(plus.highFactcheck()).isEqualTo(2);
        assertThat(plus.smartPlanLimit()).isEqualTo(4);
        assertThat(plus.smartPlanWindow()).isEqualTo(Window.MONTH);

        Allowance pro = catalog.allowance(Plan.PRO);
        assertThat(pro.credits()).isEqualTo(820);
        assertThat(pro.qualityImages()).isEqualTo(10);
        assertThat(pro.smartPlanLimit()).isEqualTo(1);
        assertThat(pro.smartPlanWindow()).isEqualTo(Window.WEEK);

        Allowance max = catalog.allowance(Plan.MAX);
        assertThat(max.credits()).isEqualTo(1_780);
        assertThat(max.sttMonth()).isNull();
        assertThat(max.sttDay()).isEqualTo(7_200L);
        assertThat(max.podcasts()).isEqualTo(15);
        assertThat(max.smartPlanLimit()).isEqualTo(8);
        assertThat(max.limits(Bucket.STT)).containsExactly(new Allowance.WindowLimit(Window.MONTH, null),
                new Allowance.WindowLimit(Window.DAY, 7_200L));
        assertThatThrownBy(() -> max.limits(Bucket.CREDITS)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void freePortionsMustAddUpToTheBar() {
        UsagePolicy wrong = new UsagePolicy("rc-v1", "UTC", new BigDecimal("0.35"), "13,13,12", 80, Duration.ofHours(1));
        assertThatThrownBy(() -> new AllowanceCatalog(wrong)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void configuredPlansComeFromTheDefaultAndPerAccountOverrides() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UsageCalendar calendar = new UsageCalendar(POLICY);
        var environment = new MockEnvironment().withProperty("learning.usage.entitlements.overrides." + owner, "PRO");
        var source = new ConfigEntitlementSource(environment, calendar, Plan.FREE);

        Entitlement pro = source.current(owner, Instant.parse("2026-10-02T09:00:42Z"));
        assertThat(pro).isEqualTo(new Entitlement(Plan.PRO, Entitlement.Source.CONFIG, Instant.parse("2026-10-31T21:00:00Z")));
        assertThat(source.current(other, Instant.parse("2026-10-02T09:00:42Z")).plan()).isEqualTo(Plan.FREE);
        assertThat(new ConfigEntitlementSource(new MockEnvironment(), calendar, Plan.PLUS).current(owner, Instant.now())
                .plan()).isEqualTo(Plan.PLUS);
        assertThatThrownBy(() -> new ConfigEntitlementSource(new MockEnvironment()
                .withProperty("learning.usage.entitlements.overrides.not-a-uuid", "PRO"), calendar, Plan.FREE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfigEntitlementSource(new MockEnvironment()
                .withProperty("learning.usage.entitlements.overrides." + owner, "GOLD"), calendar, Plan.FREE))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void wireHelpersRoundHalfUpAndClamp() {
        assertThat(Wire.percent(52, 360)).isEqualTo(14);
        assertThat(Wire.percent(13, 360)).isEqualTo(4);
        assertThat(Wire.percent(21, 360)).isEqualTo(6);
        assertThat(Wire.percent(1, 2)).isEqualTo(50);
        assertThat(Wire.percent(1, 8)).isEqualTo(13);
        assertThat(Wire.percent(400, 360)).isEqualTo(100);
        assertThat(Wire.percent(5, 0)).isZero();
        assertThat(Wire.time(Instant.parse("2026-10-02T09:00:42.789Z"))).isEqualTo("2026-10-02T09:00:42Z");
        assertThat(Wire.time(null)).isNull();
    }
}
