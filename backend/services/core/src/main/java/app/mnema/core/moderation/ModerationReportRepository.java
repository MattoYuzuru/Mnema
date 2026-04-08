package app.mnema.core.moderation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ModerationReportRepository extends JpaRepository<ModerationReportEntity, UUID> {

    boolean existsByReporterIdAndTargetTypeAndTargetIdAndStatus(
            UUID reporterId,
            ModerationReportTargetType targetType,
            UUID targetId,
            ModerationReportStatus status
    );

    Page<ModerationReportEntity> findByStatusOrderByCreatedAtAsc(
            ModerationReportStatus status,
            Pageable pageable
    );

    Page<ModerationReportEntity> findByStatusOrderByClosedAtDescCreatedAtDesc(
            ModerationReportStatus status,
            Pageable pageable
    );

    @Query("""
        select r.status as status, count(r) as count
        from ModerationReportEntity r
        group by r.status
        """)
    List<StatusCountProjection> countByStatus();

    @Query("""
        select r.targetType as targetType, count(r) as count
        from ModerationReportEntity r
        group by r.targetType
        """)
    List<TargetTypeCountProjection> countByTargetType();

    @Query("""
        select r.reason as reason, count(r) as count
        from ModerationReportEntity r
        group by r.reason
        """)
    List<ReasonCountProjection> countByReason();

    @Query("""
        select r.closedByUserId as closedByUserId,
               r.closedByUsername as closedByUsername,
               count(r) as count
        from ModerationReportEntity r
        where r.status = app.mnema.core.moderation.ModerationReportStatus.CLOSED
          and r.closedByUserId is not null
        group by r.closedByUserId, r.closedByUsername
        order by count(r) desc, r.closedByUsername asc
        """)
    List<ResolverCountProjection> countClosedByResolver();

    interface StatusCountProjection {
        ModerationReportStatus getStatus();

        long getCount();
    }

    interface TargetTypeCountProjection {
        ModerationReportTargetType getTargetType();

        long getCount();
    }

    interface ReasonCountProjection {
        ModerationReportReason getReason();

        long getCount();
    }

    interface ResolverCountProjection {
        UUID getClosedByUserId();

        String getClosedByUsername();

        long getCount();
    }
}
