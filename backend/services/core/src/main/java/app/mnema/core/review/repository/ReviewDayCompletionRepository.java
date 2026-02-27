package app.mnema.core.review.repository;

import app.mnema.core.review.entity.ReviewDayCompletionEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface ReviewDayCompletionRepository extends Repository<ReviewDayCompletionEntity, ReviewDayCompletionEntity.ReviewDayCompletionId> {

    interface CompletionProjection {
        int getCompletionsCount();

        Instant getFirstCompletedAt();

        Instant getLastCompletedAt();
    }

    @Query(value = """
            with upsert as (
                insert into app_core.review_day_completions (
                    user_id,
                    review_day,
                    first_completed_at,
                    last_completed_at,
                    completions_count
                ) values (
                    :userId,
                    :reviewDay,
                    :completedAt,
                    :completedAt,
                    1
                )
                on conflict (user_id, review_day)
                do update set
                    last_completed_at = excluded.last_completed_at,
                    completions_count = app_core.review_day_completions.completions_count + 1
                returning completions_count, first_completed_at, last_completed_at
            )
            select completions_count, first_completed_at, last_completed_at
            from upsert
            """, nativeQuery = true)
    CompletionProjection registerCompletion(@Param("userId") UUID userId,
                                            @Param("reviewDay") LocalDate reviewDay,
                                            @Param("completedAt") Instant completedAt);
}
