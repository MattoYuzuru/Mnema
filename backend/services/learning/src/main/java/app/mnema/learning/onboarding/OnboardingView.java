package app.mnema.learning.onboarding;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The answer of {@code GET /api/onboarding} and of both commands ({@code views} of {@code contracts/onboarding/journey.json}). Every member is always present:
 * {@code milestones} and {@code path} (OB-E3) are the {@link PathState} of the account (empty and null while the tour is switched off), {@code rhythm} is reserved for OB-E4 so
 * that the client never changes shape; {@code sampleDeck} (OB-E2) is the {@link SampleDeckState} of the account. The strong ETag hashes the whole view.
 */
final class OnboardingView {
    private OnboardingView() { }

    /** The tour is turned off: no journey. */
    static ObjectNode disabled(int freeChecks) {
        return envelope(false, null, null, SampleDeckState.unavailable(freeChecks));
    }

    /** No row: the account was never offered the tour and did not start it. */
    static ObjectNode notOffered(SampleDeckState sample, PathState path) {
        return envelope(true, journey(JourneyState.NOT_OFFERED, false, false, JourneySteps.TOUR_VERSION, null, null, null, null, null, null, 0, 0), path, sample);
    }

    static ObjectNode of(OnboardingRepository.Row row, SampleDeckState sample, PathState path) {
        boolean eligible = row.cohort() != null;
        boolean autoOffer = eligible && row.cohort() != OnboardingRepository.Cohort.CONTROL && row.state() == JourneyState.OFFERED;
        return envelope(true, journey(row.state(), eligible, autoOffer, row.tourVersion(), row.branch(), row.stepKey(), row.experience(), row.weeklyTarget(),
                row.offeredAt(), row.finishedAt(), row.replayCount(), row.rowVersion()), path, sample);
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

    private static ObjectNode envelope(boolean enabled, ObjectNode journey, PathState path, SampleDeckState sample) {
        ObjectNode view = JsonNodeFactory.instance.objectNode();
        view.put("enabled", enabled);
        if (journey == null) view.putNull("journey");
        else view.set("journey", journey);
        ArrayNode milestones = view.putArray("milestones");
        if (path == null) view.putNull("path");
        else {
            for (PathState.Milestone milestone : path.milestones()) {
                milestones.addObject().put("key", milestone.key().name()).put("state", milestone.state().name()).put("progress", milestone.progress())
                        .put("target", milestone.key().target()).put("achievedAt", time(milestone.achievedAt()));
            }
            ObjectNode summary = view.putObject("path");
            summary.put("done", path.done()).put("total", path.total());
            ArrayNode hidden = summary.putArray("hidden");
            path.hidden().forEach(key -> hidden.add(key.name()));
        }
        view.putNull("rhythm");
        view.putObject("sampleDeck").put("available", sample.available()).put("deckId", sample.deckId() == null ? null : sample.deckId().toString())
                .put("updateAvailable", sample.updateAvailable()).put("freeChecksLeft", sample.freeChecksLeft());
        return view;
    }

    private static String time(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
