package app.mnema.core.review.repository;

import app.mnema.core.review.entity.SrAlgorithmEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SrAlgorithmRepository extends JpaRepository<SrAlgorithmEntity, String> {
}
