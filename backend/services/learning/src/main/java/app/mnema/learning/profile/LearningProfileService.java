package app.mnema.learning.profile;

import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The owner's learning goal. It tunes copy and the recommended tier on the client and nothing else: it is not read by the
 * generation or assessment modules, so it never reaches a provider.
 */
@Service
public class LearningProfileService {
    private final LearningProfileRepository repository;
    private final Clock clock;

    @Autowired
    LearningProfileService(LearningProfileRepository repository) {
        this(repository, Clock.systemUTC());
    }

    LearningProfileService(LearningProfileRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LearningProfileView read(UUID owner) {
        UuidPolicy.requireEntityId(owner, "owner");
        return repository.find(owner).map(LearningProfileService::view).orElse(new LearningProfileView(null, false, null));
    }

    /** Records an answer: a goal, or (with {@code goal} null) a skip. */
    @Transactional
    public LearningProfileView answer(UUID owner, LearningGoal goal) {
        UuidPolicy.requireEntityId(owner, "owner");
        repository.upsert(owner, goal, clock.instant().truncatedTo(ChronoUnit.SECONDS));
        return read(owner);
    }

    private static LearningProfileView view(LearningProfileRepository.Row row) {
        return new LearningProfileView(row.goal() == null ? null : row.goal().name(), row.goal() == null,
                row.answeredAt().truncatedTo(ChronoUnit.SECONDS).toString());
    }
}
