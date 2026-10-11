package app.mnema.learning.study.retention;

import app.mnema.learning.catalog.exercise.ExerciseNewMarks;
import app.mnema.learning.study.attempt.AssessmentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The expiry steps of Study: rows that carry their own {@code expires_at}. The snapshot purge of old sessions is {@link StudySnapshotRetention}. */
@Service
public class StudyRetentionService {
    private final StudyRetentionRepository repository;
    private final ExerciseNewMarks newMarks;
    private final AssessmentService assessments;
    private final StudyRetentionSettings settings;

    public StudyRetentionService(StudyRetentionRepository repository, ExerciseNewMarks newMarks, AssessmentService assessments,
                                 StudyRetentionSettings settings) {
        this.repository = repository;
        this.newMarks = newMarks;
        this.assessments = assessments;
        this.settings = settings;
    }

    /** One bounded batch (at most {@code row-batch} rows) of every expiry step, in one transaction. */
    @Transactional(timeout = 10)
    public PurgeResult purgeBatch() {
        var asOf = repository.now();
        int batch = settings.rowBatch();
        // «Новое» marks past learning.exercise.new-mark-ttl are never shown (readers compare marked_at); this is only housekeeping
        return new PurgeResult(repository.purgeRaw(asOf, batch),
                repository.expireCompactOutcomes(asOf, batch), repository.purgePairInteractions(asOf, batch),
                newMarks.purgeExpired(batch), assessments.clearExpiredAnswers(batch));
    }

    /** {@code assessmentAnswers}: learner answers kept in AI assessment rows past the presentation's expiry. */
    public record PurgeResult(int rawResponses, int compactOutcomes, int pairInteractions, int newMarks, int assessmentAnswers) {
        /** True when some step used its whole batch, so more rows may be due. */
        boolean saturated(int batch) {
            return rawResponses >= batch || compactOutcomes >= batch || pairInteractions >= batch || newMarks >= batch
                    || assessmentAnswers >= batch;
        }

        int total() { return rawResponses + compactOutcomes + pairInteractions + newMarks + assessmentAnswers; }
    }
}
