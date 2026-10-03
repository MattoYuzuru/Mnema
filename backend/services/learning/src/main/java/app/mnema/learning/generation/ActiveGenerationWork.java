package app.mnema.learning.generation;

import app.mnema.learning.notification.ActiveWorkCounter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The notification center's {@code activeWork}: the owner's generation sessions in {@code PLANNING} or {@code RUNNING}
 * ({@code contracts/notifications}). The client polls faster while it is above zero. Media processing of generated assets
 * does not count yet (an open question of the contract).
 */
@Component
class ActiveGenerationWork implements ActiveWorkCounter {
    private final GenerationRepository repository;

    ActiveGenerationWork(GenerationRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public int count(UUID owner) {
        return repository.activeWork(owner);
    }
}
