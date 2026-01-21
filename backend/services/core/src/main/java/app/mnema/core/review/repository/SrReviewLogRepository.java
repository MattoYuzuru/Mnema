package app.mnema.core.review.repository;

import app.mnema.core.review.entity.SrReviewLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SrReviewLogRepository extends JpaRepository<SrReviewLogEntity, Long> {
}
