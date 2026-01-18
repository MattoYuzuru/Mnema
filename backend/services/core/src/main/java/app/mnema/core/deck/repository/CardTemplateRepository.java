package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.CardTemplateEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CardTemplateRepository extends JpaRepository<CardTemplateEntity, UUID> {

    Page<CardTemplateEntity> findByIsPublicTrue(Pageable pageable);

    Page<CardTemplateEntity> findByIsPublicTrueOrderByCreatedAtDesc(Pageable pageable);

    Page<CardTemplateEntity> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId, Pageable pageable);

    Page<CardTemplateEntity> findByIsPublicTrueOrOwnerIdOrderByCreatedAtDesc(UUID ownerId, Pageable pageable);

    Optional<CardTemplateEntity> findByOwnerIdAndTemplateId(UUID ownerId, UUID templateId);

    @Query(value = """
        select t.*
        from app_core.card_templates t
        where t.is_public = true
          and to_tsvector('simple', coalesce(t.name, '') || ' ' || coalesce(t.description, ''))
            @@ websearch_to_tsquery('simple', :query)
        order by ts_rank(
            to_tsvector('simple', coalesce(t.name, '') || ' ' || coalesce(t.description, '')),
            websearch_to_tsquery('simple', :query)
        ) desc,
        t.created_at desc
        """, countQuery = """
        select count(*)
        from app_core.card_templates t
        where t.is_public = true
          and to_tsvector('simple', coalesce(t.name, '') || ' ' || coalesce(t.description, ''))
            @@ websearch_to_tsquery('simple', :query)
        """, nativeQuery = true)
    Page<CardTemplateEntity> searchPublicTemplates(
            @Param("query") String query,
            Pageable pageable
    );

    @Query(value = """
        select t.*
        from app_core.card_templates t
        where t.owner_id = :userId
          and to_tsvector('simple', coalesce(t.name, '') || ' ' || coalesce(t.description, ''))
            @@ websearch_to_tsquery('simple', :query)
        order by ts_rank(
            to_tsvector('simple', coalesce(t.name, '') || ' ' || coalesce(t.description, '')),
            websearch_to_tsquery('simple', :query)
        ) desc,
        t.created_at desc
        """, countQuery = """
        select count(*)
        from app_core.card_templates t
        where t.owner_id = :userId
          and to_tsvector('simple', coalesce(t.name, '') || ' ' || coalesce(t.description, ''))
            @@ websearch_to_tsquery('simple', :query)
        """, nativeQuery = true)
    Page<CardTemplateEntity> searchUserTemplates(
            @Param("userId") UUID userId,
            @Param("query") String query,
            Pageable pageable
    );

    @Query(value = """
        select t.*
        from app_core.card_templates t
        where (t.is_public = true or t.owner_id = :userId)
          and to_tsvector('simple', coalesce(t.name, '') || ' ' || coalesce(t.description, ''))
            @@ websearch_to_tsquery('simple', :query)
        order by ts_rank(
            to_tsvector('simple', coalesce(t.name, '') || ' ' || coalesce(t.description, '')),
            websearch_to_tsquery('simple', :query)
        ) desc,
        t.created_at desc
        """, countQuery = """
        select count(*)
        from app_core.card_templates t
        where (t.is_public = true or t.owner_id = :userId)
          and to_tsvector('simple', coalesce(t.name, '') || ' ' || coalesce(t.description, ''))
            @@ websearch_to_tsquery('simple', :query)
        """, nativeQuery = true)
    Page<CardTemplateEntity> searchUserAndPublicTemplates(
            @Param("userId") UUID userId,
            @Param("query") String query,
            Pageable pageable
    );
}
