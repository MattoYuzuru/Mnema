package app.mnema.learning.onboarding;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The answer of {@code GET /api/onboarding} and of both commands ({@code views} of {@code contracts/onboarding/journey.json}). Every member is always present:
 * {@code milestones}, {@code path} and {@code rhythm} are reserved for OB-E3..E4 so that the client never changes shape; {@code sampleDeck} (OB-E2) is the
 * {@link SampleDeckState} of the account, which is also part of the strong ETag (the tag hashes the whole view).
 */
final class OnboardingView {
    private OnboardingView() { }

    /** The tour is turned off: no journey. */
    static ObjectNode disabled(int freeChecks) {
        return envelope(false, null, SampleDeckState.unavailable(freeChecks));
    }

    /** No row: the account was never offered the tour and did not start it. */
    static ObjectNode notOffered(SampleDeckState sample) {
        return envelope(true, journey(JourneyState.NOT_OFFERED, false, false, JourneySteps.TOUR_VERSION, null, null, null, null, null, null, 0, 0), sample);
    }

    static ObjectNode of(OnboardingRepository.Row row, SampleDeckState sample) {
        boolean eligible = row.cohort() != null;
        boolean autoOffer = eligible && row.cohort() != OnboardingRepository.Cohort.CONTROL && row.state() == JourneyState.OFFERED;
        return envelope(true, journey(row.state(), eligible, autoOffer, row.tourVersion(), row.branch(), row.stepKey(), row.experience(), row.weeklyTarget(),
                row.offeredAt(), row.finishedAt(), row.replayCount(), row.rowVersion()), sample);
    }

    private static ObjectNode journey(JourneyState state, boolean eligible, boolean autoOffer, int tourVersion, JourneyBranch branch, String stepKey,
                                      OnboardingRepository.Experience experience, Integer weeklyTarget, Instant offeredAt, Instant finishedAt,
                                      int replayCount, long rowVersion) {
        ObjectNode journey = JsonNodeFactory.instance.objectNode();
        journey.put("state", state.name()).put("eligible", eligible).put("autoOffer", autoOffer).put("tourVersion", tourVersion);
        journey.put("branch", branch == null ? null : branch.name()).put("stepKey", stepKey);
        journey.put("experience", experience == null ? null : experience.name());
        if (weeklyTarget == null) journey.putNull("weeklyTarget");
        else journey.put("weeklyTarget", weeklyTarget.intValue());
        journey.put("offeredAt", time(offeredAt)).put("finishedAt", time(finishedAt));
        return journey.put("replayCount", replayCount).put("rowVersion", Long.toString(rowVersion));
    }

    private static ObjectNode envelope(boolean enabled, ObjectNode journey, SampleDeckState sample) {
        ObjectNode view = JsonNodeFactory.instance.objectNode();
        view.put("enabled", enabled);
        if (journey == null) view.putNull("journey");
        else view.set("journey", journey);
        view.putArray("milestones");
        view.putNull("path");
        view.putNull("rhythm");
        view.putObject("sampleDeck").put("available", sample.available()).put("deckId", sample.deckId() == null ? null : sample.deckId().toString())
                .put("updateAvailable", sample.updateAvailable()).put("freeChecksLeft", sample.freeChecksLeft());
        return view;
    }

    private static String time(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
