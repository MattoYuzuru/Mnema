package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.UserDeckEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

@Repository
public interface UserDeckRepository extends JpaRepository<UserDeckEntity, UUID> {

    Page<UserDeckEntity> findByUserIdAndArchivedFalse(UUID userId, Pageable pageable);

    Optional<UserDeckEntity> findByUserIdAndPublicDeckId(UUID userId, UUID publicDeckId);

    List<UserDeckEntity> findByUserIdAndArchivedTrue(UUID userId);

    long countByPublicDeckIdAndUserDeckIdNot(UUID publicDeckId, UUID userDeckId);

    @Query("""
        select d.publicDeckId
        from UserDeckEntity d
        where d.userId = :userId
          and d.archived = false
          and d.publicDeckId is not null
        """)
    List<UUID> findPublicDeckIdsByUserId(@Param("userId") UUID userId);

    @Query(value = """
        select ud.*
        from app_core.user_decks ud
        left join app_core.public_decks pd
          on pd.deck_id = ud.public_deck_id
         and pd.version = ud.current_version
        where ud.user_id = :userId
          and ud.is_archived = false
          and to_tsvector('simple', coalesce(ud.display_name, '') || ' ' || coalesce(ud.display_description, ''))
            @@ websearch_to_tsquery('simple', :query)
          and (:tags is null or pd.tags && string_to_array(:tags, ','))
        order by ts_rank(
            to_tsvector('simple', coalesce(ud.display_name, '') || ' ' || coalesce(ud.display_description, '')),
            websearch_to_tsquery('simple', :query)
        ) desc,
        ud.created_at desc
        """, countQuery = """
        select count(*)
        from app_core.user_decks ud
        left join app_core.public_decks pd
          on pd.deck_id = ud.public_deck_id
         and pd.version = ud.current_version
        where ud.user_id = :userId
          and ud.is_archived = false
          and to_tsvector('simple', coalesce(ud.display_name, '') || ' ' || coalesce(ud.display_description, ''))
            @@ websearch_to_tsquery('simple', :query)
          and (:tags is null or pd.tags && string_to_array(:tags, ','))
        """, nativeQuery = true)
    Page<UserDeckEntity> searchUserDecks(
            @Param("userId") UUID userId,
            @Param("query") String query,
            @Param("tags") String tags,
            Pageable pageable
    );

    @Query(value = """
        select ud.*
        from app_core.user_decks ud
        join app_core.public_decks pd
          on pd.deck_id = ud.public_deck_id
         and pd.version = ud.current_version
        where ud.user_id = :userId
          and ud.is_archived = false
          and pd.tags && string_to_array(:tags, ',')
        order by ud.created_at desc
        """, countQuery = """
        select count(*)
        from app_core.user_decks ud
        join app_core.public_decks pd
          on pd.deck_id = ud.public_deck_id
         and pd.version = ud.current_version
        where ud.user_id = :userId
          and ud.is_archived = false
          and pd.tags && string_to_array(:tags, ',')
        """, nativeQuery = true)
    Page<UserDeckEntity> searchUserDecksByTags(
            @Param("userId") UUID userId,
            @Param("tags") String tags,
            Pageable pageable
    );

}
