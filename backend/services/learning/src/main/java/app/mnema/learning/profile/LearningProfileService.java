package app.mnema.learning.profile;

import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.community.InterestService;
import app.mnema.learning.onboarding.MilestoneKey;
import app.mnema.learning.onboarding.MilestoneRecorder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The owner's learning goal. It tunes copy and the recommended tier on the client and is a local topic signal after the Recs policy activation: it is not read by the
 * generation or assessment modules, so it never reaches a provider.
 */
@Service
public class LearningProfileService {
    private final LearningProfileRepository repository;
    private final InterestService interests;
    private final MilestoneRecorder milestones;

    @Autowired
    LearningProfileService(LearningProfileRepository repository, InterestService interests, MilestoneRecorder milestones) {
        this.repository = repository;
        this.interests = interests;
        this.milestones = milestones;
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
        interests.lockSignal(owner);
        repository.upsert(owner, goal, repository.now());
        interests.changed(owner);
        // «Путь Mnema»: telling the goal and the deliberate skip are the same milestone
        milestones.reach(owner, MilestoneKey.GOAL_TOLD, 1);
        return read(owner);
    }

    private static LearningProfileView view(LearningProfileRepository.Row row) {
        return new LearningProfileView(row.goal() == null ? null : row.goal().name(), row.goal() == null,
                row.answeredAt().truncatedTo(ChronoUnit.SECONDS).toString());
    }
}
