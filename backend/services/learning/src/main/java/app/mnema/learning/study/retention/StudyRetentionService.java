package app.mnema.learning.study.retention;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudyRetentionService {
    static final int BATCH_SIZE = 500;
    private final StudyRetentionRepository repository;

    public StudyRetentionService(StudyRetentionRepository repository) { this.repository = repository; }

    @Transactional(timeout = 10)
    public PurgeResult purgeBatch() {
        var asOf = repository.now();
        return new PurgeResult(repository.purgeRaw(asOf, BATCH_SIZE),
                repository.expireCompactOutcomes(asOf, BATCH_SIZE));
    }

    public record PurgeResult(int rawResponses, int compactOutcomes) { }
}
