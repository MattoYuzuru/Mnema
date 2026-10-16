package app.mnema.learning.onboarding;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.promo.PromoBodies;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

/**
 * Strict reading of the two tour commands ({@code contracts/onboarding/journey.json}, {@code commands} and {@code invalid}): bounded JSON, exactly the named
 * members, every value of its type. All of it is independent of the stored state, so a malformed command is a 400 whatever the row looks like; a missing
 * {@code rowVersion} is a 428 (after the rest of the body has proved valid) and the stale one is the service's 412.
 */
final class OnboardingCommands {
    /** A tour command: {@code stepKey} and {@code branch} are null when absent. */
    record Journey(UUID commandId, long rowVersion, JourneyAction action, String stepKey, JourneyBranch branch, JsonNode envelope) { }

    /** A preferences command: a field that is absent is untouched, one that is {@code null} in the body is cleared ({@code setX} with a null value). */
    record Preferences(UUID commandId, long rowVersion, boolean setExperience, OnboardingRepository.Experience experience,
                       boolean setWeeklyTarget, Integer weeklyTarget, JsonNode envelope) { }

    private static final Set<String> JOURNEY_FIELDS = Set.of("commandId", "rowVersion", "action", "branch", "stepKey");
    private static final Set<String> PREFERENCE_FIELDS = Set.of("commandId", "rowVersion", "experience", "weeklyTarget");

    private OnboardingCommands() { }

    static Journey journey(InputStream input) {
        JsonNode body = PromoBodies.object(input, Set.of("commandId", "action"), JOURNEY_FIELDS);
        UUID commandId = commandId(body);
        Long rowVersion = rowVersion(body);
        JourneyAction action = enumeration(JourneyAction.class, PromoBodies.text(body, "action"));
        if (action == null) throw new InvalidRequestException();
        String stepKey = PromoBodies.text(body, "stepKey");
        JourneyBranch branch = enumeration(JourneyBranch.class, PromoBodies.text(body, "branch"));
        if (body.path("branch").isString() && branch == null) throw new InvalidRequestException();
        if (!allowed(action.stepKey(), stepKey != null) || !allowed(action.branch(), branch != null)) throw new InvalidRequestException();
        if (stepKey != null && !JourneySteps.known(stepKey)) throw new InvalidRequestException();
        // A branch travels with a step of that branch: the first step into the branch sets it.
        if (branch != null && (stepKey == null || JourneySteps.branchOf(stepKey) != branch)) throw new InvalidRequestException();
        return new Journey(commandId, requireVersion(rowVersion), action, stepKey, branch, body);
    }

    static Preferences preferences(InputStream input) {
        JsonNode body = PromoBodies.object(input, Set.of("commandId"), PREFERENCE_FIELDS);
        UUID commandId = commandId(body);
        Long rowVersion = rowVersion(body);
        boolean setExperience = body.has("experience");
        boolean setWeeklyTarget = body.has("weeklyTarget");
        if (!setExperience && !setWeeklyTarget) throw new InvalidRequestException();
        OnboardingRepository.Experience experience = enumeration(OnboardingRepository.Experience.class, PromoBodies.text(body, "experience"));
        if (body.path("experience").isString() && experience == null) throw new InvalidRequestException();
        Integer weeklyTarget = PromoBodies.integer(body, "weeklyTarget");
        if (weeklyTarget != null && (weeklyTarget < 1 || weeklyTarget > 7)) throw new InvalidRequestException();
        return new Preferences(commandId, requireVersion(rowVersion), setExperience, experience, setWeeklyTarget, weeklyTarget, body);
    }

    private static boolean allowed(JourneyAction.Presence presence, boolean present) {
        return switch (presence) {
            case REQUIRED -> present;
            case OPTIONAL -> true;
            case FORBIDDEN -> !present;
        };
    }

    private static <E extends Enum<E>> E enumeration(Class<E> type, String name) {
        if (name == null) return null;
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    private static UUID commandId(JsonNode body) {
        String text = PromoBodies.text(body, "commandId");
        try {
            if (text == null || text.length() != 36) throw new InvalidRequestException();
            UUID id = UUID.fromString(text);
            if (!id.toString().equalsIgnoreCase(text)) throw new InvalidRequestException();
            return UuidPolicy.requireCommandId(id);
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    /** One canonical decimal string ({@code "0"} for no row); null when absent (the caller answers 428 once the rest of the body is known to be valid), any other shape a 400. */
    private static Long rowVersion(JsonNode body) {
        String text = PromoBodies.text(body, "rowVersion");
        if (text == null) return null;
        if (!text.matches("0|[1-9][0-9]{0,17}")) throw new InvalidRequestException();
        return Long.parseLong(text);
    }

    /** The body shape is right, so a missing version is now the 428 of the command. */
    private static long requireVersion(Long rowVersion) {
        if (rowVersion == null) throw new VersionPreconditionRequiredException();
        return rowVersion;
    }
}
