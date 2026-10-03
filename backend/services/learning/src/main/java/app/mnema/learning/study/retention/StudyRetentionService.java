package app.mnema.learning.study.retention;

import app.mnema.learning.catalog.exercise.ExerciseNewMarks;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudyRetentionService {
    static final int BATCH_SIZE = 500;
    private final StudyRetentionRepository repository;
    private final ExerciseNewMarks newMarks;

    public StudyRetentionService(StudyRetentionRepository repository, ExerciseNewMarks newMarks) {
        this.repository = repository;
        this.newMarks = newMarks;
    }

    @Transactional(timeout = 10)
    public PurgeResult purgeBatch() {
        var asOf = repository.now();
        // «Новое» marks past learning.exercise.new-mark-ttl are never shown (readers compare marked_at); this is only housekeeping
        return new PurgeResult(repository.purgeRaw(asOf, BATCH_SIZE),
                repository.expireCompactOutcomes(asOf, BATCH_SIZE), repository.purgePairInteractions(asOf, BATCH_SIZE),
                newMarks.purgeExpired(BATCH_SIZE));
    }

    public record PurgeResult(int rawResponses, int compactOutcomes, int pairInteractions, int newMarks) { }
}
