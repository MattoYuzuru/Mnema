package app.mnema.learning.generation;

import app.mnema.learning.capability.LearningCapabilities;
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
        if (admission) requireCurrent(owner, deckId, facts, notes);
        capabilities.requireAiGeneration();
        if (facts.audio()) capabilities.requireTextToSpeech();
        if (facts.imageSearch()) capabilities.requireImageSearch();
        if (facts.research()) capabilities.requireWebSearch();
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
        if (!stale.isEmpty()) throw new SourceUnavailableException(stale);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean ownsArtifact(UUID owner, UUID deckId, UUID sessionId, UUID artifactId) {
        return repository.ownsArtifact(owner, deckId, sessionId, artifactId);
    }
}
