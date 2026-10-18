package app.mnema.learning.onboarding;

/**
 * The closed registry of the «Путь Mnema» milestones (OB-301, ADR-ONB-002): {@code contracts/onboarding/milestones.json} is the wire contract and {@code MilestoneContractTest}
 * holds this enum, the fixture and the CHECK of {@code account_milestone} (V74) to one list. The order is the display order. A milestone is added here, in the fixture and in the
 * CHECK together, with a call of {@link MilestoneRecorder} in its writer ({@code MilestoneInventoryIntegrationTest} fails when one of them is missing).
 *
 * <p>{@link #target()} is the progress at which the milestone is achieved: 1 for most, 2 for the two stages of {@link #CAPTURE_NOTE} and the two dates of {@link #TWO_DAYS}, 3 for the
 * exercises of {@link #OWN_EXERCISES}. The enum names are the only metric label of the milestone counter and the {@code key} param of the notification.
 */
public enum MilestoneKey {
    GOAL_TOLD(Chapter.START, 1, Visibility.ALWAYS, "PROFILE_GOAL"),
    FIRST_DECK(Chapter.START, 1, Visibility.ALWAYS, "DECKS"),
    FIRST_SESSION(Chapter.START, 1, Visibility.ALWAYS, "STUDY"),
    OWN_MATERIAL(Chapter.MATERIALS, 1, Visibility.ALWAYS, "MATERIAL_NEW"),
    MEDIA_IN_MATERIAL(Chapter.MATERIALS, 1, Visibility.ALWAYS, "QUEST_MEDIA"),
    CAPTURE_NOTE(Chapter.MATERIALS, 2, Visibility.ALWAYS, "CAPTURE"),
    OWN_EXERCISES(Chapter.EXERCISES, 3, Visibility.ALWAYS, "EXERCISE_NEW"),
    AI_EXPLANATION(Chapter.EXERCISES, 1, Visibility.AI_ASSESSMENT, "QUEST_EXPLAIN"),
    VOICE_ANSWER(Chapter.EXERCISES, 1, Visibility.SPEECH_TO_TEXT, "QUEST_VOICE"),
    WORKSHOP_APPROVED(Chapter.EXERCISES, 1, Visibility.AI_GENERATION, "QUEST_WORKSHOP"),
    RHYTHM_WEEK(Chapter.RHYTHM, 1, Visibility.RHYTHM, "PROFILE_RHYTHM"),
    TWO_DAYS(Chapter.RHYTHM, 2, Visibility.ALWAYS, "STUDY"),
    COMMUNITY_COPY(Chapter.RHYTHM, 1, Visibility.COMMUNITY_CATALOG, "COMMUNITY");

    /** The chapters of the path, in display order (the lower-case names are the fixture's). */
    public enum Chapter { START, MATERIALS, EXERCISES, RHYTHM }

    /** The condition under which a milestone is shown (the fixture's {@code visibility} table); {@link PathVisibility} evaluates it. */
    public enum Visibility { ALWAYS, AI_ASSESSMENT, SPEECH_TO_TEXT, AI_GENERATION, COMMUNITY_CATALOG, RHYTHM }

    private final Chapter chapter;
    private final int target;
    private final Visibility visibility;
    private final String route;

    MilestoneKey(Chapter chapter, int target, Visibility visibility, String route) {
        this.chapter = chapter;
        this.target = target;
        this.visibility = visibility;
        this.route = route;
    }

    public Chapter chapter() { return chapter; }

    /** The progress at which the milestone is achieved (1..3, the CHECK of {@code account_milestone.progress}). */
    public int target() { return target; }

    public Visibility visibility() { return visibility; }

    /** The key the client maps to its own place for the milestone. */
    public String route() { return route; }
}
