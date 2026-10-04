package app.mnema.learning.generation;

import app.mnema.learning.capability.LearningCapabilities;
import app.mnema.learning.platform.api.CapabilityUnavailableException;
import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.usage.GenerationBoundary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The generation module's answers to the usage module's questions ({@link GenerationBoundary}): who owns what, and which
 * capabilities a spec needs. An unknown, foreign or other-deck note or material is the same opaque 404 as an absent deck:
 * there is no existence oracle in the spec's sources.
 */
@Component
class GenerationGate implements GenerationBoundary {
    private final GenerationRepository repository;
    private final LearningCapabilities capabilities;

    GenerationGate(GenerationRepository repository, LearningCapabilities capabilities) {
        this.repository = repository;
        this.capabilities = capabilities;
    }

    @Override
    @Transactional(readOnly = true)
    public void checkSpec(UUID owner, UUID deckId, SpecFacts facts, boolean admission) {
        List<UUID> noteIds = facts.notes().stream().map(NoteRef::noteId).toList();
        Map<UUID, Long> notes = repository.noteVersions(owner, deckId, noteIds);
        if (!notes.keySet().containsAll(noteIds)) throw new ResourceNotFoundException();
        for (ItemRef item : facts.items()) {
            if (!repository.itemRevisionExists(owner, deckId, item.memberKey(), item.itemRevisionId())) {
                throw new ResourceNotFoundException();
            }
        }
        for (ExerciseRef exercise : facts.exercises()) {
            if (repository.exerciseHeadRevision(owner, deckId, exercise.exerciseId()).isEmpty()) throw new ResourceNotFoundException();
        }
        if (admission) requireCurrent(owner, deckId, facts, notes);
        capabilities.requireAiGeneration();
        if (facts.voiceRevision()) requireVoiceRevision();
        if (facts.audio()) capabilities.requireTextToSpeech();
        if (facts.imageSearch()) capabilities.requireImageSearch();
        if (facts.research()) capabilities.requireWebSearch();
    }

    /** The capability a retried or revised exercise needs: text generation (its mechanics are deterministic, no media, no research). */
    void requireText() {
        capabilities.requireAiGeneration();
    }

    /**
     * The capability an edit action needs. A rewrite needs text generation, an image search the image search capability (AI-10, #296) and the redo of
     * audio the speech capability (AI-09, #297): each runs whenever its capability is available. Image generation has no executor yet, so it is refused as
     * not configured even when its provider is (the reason of the capability gate wins when it is the one that is off).
     */
    void requireEdit(String action) {
        switch (action) {
            case "REWRITE", "FREE" -> capabilities.requireAiGeneration();
            // AI-10 (#296): runnable whenever the capability is (the flag and a source or the Stub); the executor is ImageSearchExecutor
            case "IMAGE_SEARCH" -> capabilities.requireImageSearch();
            case "IMAGE_GENERATE" -> {
                capabilities.requireImageGeneration();
                throw notRunnable("imageGeneration");
            }
            // AI-09 (#297): runnable whenever the capability is (the flag and a speech route or the Stub); the executor is SpeechExecutor
            case "AUDIO_REGENERATE" -> capabilities.requireTextToSpeech();
            default -> { }
        }
    }

    private static CapabilityUnavailableException notRunnable(String capability) {
        return new CapabilityUnavailableException(ProblemExtension.builder().put("capability", capability)
                .put("reason", "PROVIDER_NOT_CONFIGURED").build());
    }

    /** The capability of redoing the audio of an exercise (a REVISE_EXERCISE media action, #294): speech synthesis, as for a material (#297). */
    void requireVoiceRevision() {
        capabilities.requireTextToSpeech();
    }

    /** Whether {@link #requireVoiceRevision} passes: the intent only offers the voice chip when the action can be run. */
    boolean voiceRevisionAvailable() {
        try {
            requireVoiceRevision();
            return true;
        } catch (CapabilityUnavailableException unavailable) {
            return false;
        }
    }

    /** The capabilities a retried material needs: text, and what its effective settings declare (audio, image search, web research). */
    void requireFor(MaterialsSpec.Effective settings) {
        capabilities.requireAiGeneration();
        if (settings.audio()) capabilities.requireTextToSpeech();
        if (settings.imageSearch()) capabilities.requireImageSearch();
        if (settings.research()) capabilities.requireWebSearch();
    }

    /** An owned note whose row version moved, or a {@code SOURCE} material that is no longer the head: 409. */
    private void requireCurrent(UUID owner, UUID deckId, SpecFacts facts, Map<UUID, Long> notes) {
        List<SourceUnavailableException.Unavailable> stale = new ArrayList<>();
        for (NoteRef note : facts.notes()) {
            if (notes.get(note.noteId()) != note.rowVersion()) stale.add(new SourceUnavailableException.Unavailable("NOTE", note.noteId()));
        }
        List<ItemRef> sources = facts.items().stream().filter(ItemRef::head).toList();
        Map<UUID, UUID> heads = repository.headRevisions(owner, deckId, sources.stream().map(ItemRef::memberKey).toList());
        for (ItemRef item : sources) {
            if (!item.itemRevisionId().equals(heads.get(item.memberKey()))) {
                stale.add(new SourceUnavailableException.Unavailable("ITEM", item.memberKey()));
            }
        }
        for (ExerciseRef exercise : facts.exercises()) {
            UUID head = repository.exerciseHeadRevision(owner, deckId, exercise.exerciseId()).orElse(null);
            if (!exercise.exerciseRevisionId().equals(head)) {
                stale.add(new SourceUnavailableException.Unavailable("EXERCISE", exercise.exerciseId()));
            }
        }
        if (!stale.isEmpty()) throw new SourceUnavailableException(stale);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean ownsArtifact(UUID owner, UUID deckId, UUID sessionId, UUID artifactId) {
        return repository.ownsArtifact(owner, deckId, sessionId, artifactId);
    }
}
