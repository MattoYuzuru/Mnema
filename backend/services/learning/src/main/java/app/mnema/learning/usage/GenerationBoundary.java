package app.mnema.learning.usage;

import java.util.List;
import java.util.UUID;

/**
 * What the usage module must ask the generation module about a spec or an edit before it prices them: the owner's own
 * sources and artifacts, and the capabilities the spec needs. Usage cannot know the generation tables, and generation
 * depends on usage, so the answer arrives through this port (implemented in {@code app.mnema.learning.generation}).
 * Without an implementation (a bare unit test) nothing is checked.
 */
public interface GenerationBoundary {
    /** A pinned note: its id and the row version the client saw. */
    record NoteRef(UUID noteId, long rowVersion) { }

    /**
     * A pinned material revision.
     *
     * @param head true when the pin must still be the material's head (a {@code SOURCE}); a style example only has to exist
     */
    record ItemRef(UUID memberKey, UUID itemRevisionId, boolean head) {
        public ItemRef(UUID memberKey, UUID itemRevisionId) {
            this(memberKey, itemRevisionId, false);
        }
    }

    /**
     * The exercise a {@code REVISE_EXERCISE} spec targets: it must exist, and (an admission) {@code exerciseRevisionId} must still be
     * its head.
     */
    record ExerciseRef(UUID exerciseId, UUID exerciseRevisionId) { }

    /**
     * The facts of a validated spec that decide ownership and capabilities.
     *
     * @param kind {@code MATERIALS}, {@code EXERCISES}, {@code REVISE_ITEM} or {@code REVISE_EXERCISE}
     * @param exercises the exercise of a {@code REVISE_EXERCISE} spec
     * @param audio {@code settings.media.audio.enabled}
     * @param imageSearch {@code settings.media.imageSearch}
     * @param research the spec asks for web research (a fact check on an effort above short)
     * @param voiceRevision the spec asks to redo the audio of an exercise ({@code media.action AUDIO_REGENERATE}): a capability of its
     *                      own, not {@code audio} (which is the audio a new material declares)
     */
    record SpecFacts(String kind, List<NoteRef> notes, List<ItemRef> items, List<ExerciseRef> exercises, boolean audio,
                     boolean imageSearch, boolean research, boolean voiceRevision) {
        public SpecFacts {
            notes = List.copyOf(notes);
            items = List.copyOf(items);
            exercises = List.copyOf(exercises);
        }

        /** The facts of a spec that revises no exercise. */
        public SpecFacts(String kind, List<NoteRef> notes, List<ItemRef> items, boolean audio, boolean imageSearch,
                         boolean research) {
            this(kind, notes, items, List.of(), audio, imageSearch, research, false);
        }
    }

    /**
     * @throws app.mnema.learning.platform.api.ResourceNotFoundException      a note or material that is unknown or the
     *                                                                         owner's other deck's: one opaque 404
     * @throws app.mnema.learning.platform.api.CapabilityUnavailableException a capability the spec needs is off
     */
    void checkSpec(UUID owner, UUID deckId, SpecFacts facts, boolean admission);

    /**
     * With {@code admission} (creating a session) the order is: unknown or foreign source (404), a pin that no longer matches
     * ({@code SourceUnavailableException}, 409), then capabilities (409). The estimate skips the middle step.
     */
    default void checkSpec(UUID owner, UUID deckId, SpecFacts facts) {
        checkSpec(owner, deckId, facts, false);
    }

    /** Whether {@code artifactId} of {@code sessionId} belongs to the owner's deck; false is an opaque 404. */
    boolean ownsArtifact(UUID owner, UUID deckId, UUID sessionId, UUID artifactId);
}
