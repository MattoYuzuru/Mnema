package app.mnema.learning.generation;

import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The most active sessions an owner may have (contract decision 3): the room every admission checks first, under the admission lock. */
@Component
class AdmissionLimits {
    private final GenerationRepository repository;
    private final GenerationSettings settings;

    AdmissionLimits(GenerationRepository repository, GenerationSettings settings) {
        this.repository = repository;
        this.settings = settings;
    }

    /** The 422 of a full house: the owner already has the most active sessions, named in the problem. */
    void requireRoom(UUID owner) {
        List<UUID> active = repository.activeSessionIds(owner);
        if (active.size() >= settings.maxActiveSessions()) {
            throw new ResourceLimitExceededException(ProblemExtension.builder().put("limit", "ACTIVE_SESSIONS")
                    .put("limits", Map.of("maxActiveSessions", settings.maxActiveSessions()))
                    .put("activeSessionIds", active.stream().map(UUID::toString).toList()).build());
        }
    }
}
