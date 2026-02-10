package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.UserCardEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserCardRepository extends JpaRepository<UserCardEntity, UUID> {
    Page<UserCardEntity> findByUserDeckIdAndDeletedFalseOrderByCreatedAtAsc(
            UUID userDeckId,
            Pageable pageable
    );

    long countByUserDeckIdAndDeletedFalse(UUID userDeckId);

    List<UserCardEntity> findByUserDeckId(UUID userDeckId);

    @Query(value = """
        select uc.*
        from app_core.user_cards uc
        left join app_core.user_decks ud
          on ud.user_deck_id = uc.subscription_id
        left join app_core.public_cards pc
          on pc.card_id = uc.public_card_id
         and pc.deck_id = ud.public_deck_id
         and pc.deck_version = ud.current_version
        where uc.user_id = :userId
          and uc.subscription_id = :userDeckId
          and uc.is_deleted = false
          and (
            to_tsvector('simple', coalesce(pc.content::text, ''))
              @@ websearch_to_tsquery('simple', :query)
            or to_tsvector('simple', coalesce(uc.content_override::text, ''))
              @@ websearch_to_tsquery('simple', :query)
            or to_tsvector('simple', coalesce(uc.personal_note, ''))
              @@ websearch_to_tsquery('simple', :query)
          )
          and (:tags is null or coalesce(uc.tags, pc.tags) && string_to_array(:tags, ','))
        order by greatest(
            ts_rank(
                to_tsvector('simple', coalesce(pc.content::text, '')),
                websearch_to_tsquery('simple', :query)
            ),
            ts_rank(
                to_tsvector('simple', coalesce(uc.content_override::text, '')),
                websearch_to_tsquery('simple', :query)
            ),
            ts_rank(
                to_tsvector('simple', coalesce(uc.personal_note, '')),
                websearch_to_tsquery('simple', :query)
            )
        ) desc,
        uc.created_at desc
        """, countQuery = """
        select count(*)
        from app_core.user_cards uc
        left join app_core.user_decks ud
          on ud.user_deck_id = uc.subscription_id
        left join app_core.public_cards pc
          on pc.card_id = uc.public_card_id
         and pc.deck_id = ud.public_deck_id
         and pc.deck_version = ud.current_version
        where uc.user_id = :userId
          and uc.subscription_id = :userDeckId
          and uc.is_deleted = false
          and (
            to_tsvector('simple', coalesce(pc.content::text, ''))
              @@ websearch_to_tsquery('simple', :query)
            or to_tsvector('simple', coalesce(uc.content_override::text, ''))
              @@ websearch_to_tsquery('simple', :query)
            or to_tsvector('simple', coalesce(uc.personal_note, ''))
              @@ websearch_to_tsquery('simple', :query)
          )
          and (:tags is null or coalesce(uc.tags, pc.tags) && string_to_array(:tags, ','))
        """, nativeQuery = true)
    Page<UserCardEntity> searchUserCards(
            @Param("userId") UUID userId,
            @Param("userDeckId") UUID userDeckId,
            @Param("query") String query,
            @Param("tags") String tags,
            Pageable pageable
    );

    @Query(value = """
        select uc.*
        from app_core.user_cards uc
        left join app_core.user_decks ud
          on ud.user_deck_id = uc.subscription_id
        left join app_core.public_cards pc
          on pc.card_id = uc.public_card_id
         and pc.deck_id = ud.public_deck_id
         and pc.deck_version = ud.current_version
        where uc.user_id = :userId
          and uc.subscription_id = :userDeckId
          and uc.is_deleted = false
          and coalesce(uc.tags, pc.tags) && string_to_array(:tags, ',')
        order by uc.created_at desc
        """, countQuery = """
        select count(*)
        from app_core.user_cards uc
        left join app_core.user_decks ud
          on ud.user_deck_id = uc.subscription_id
        left join app_core.public_cards pc
          on pc.card_id = uc.public_card_id
         and pc.deck_id = ud.public_deck_id
         and pc.deck_version = ud.current_version
        where uc.user_id = :userId
          and uc.subscription_id = :userDeckId
          and uc.is_deleted = false
          and coalesce(uc.tags, pc.tags) && string_to_array(:tags, ',')
        """, nativeQuery = true)
    Page<UserCardEntity> searchUserCardsByTags(
            @Param("userId") UUID userId,
            @Param("userDeckId") UUID userDeckId,
            @Param("tags") String tags,
            Pageable pageable
    );

    @Query(value = """
        select count(*)
        from app_core.user_cards uc
        left join app_core.user_decks ud
          on ud.user_deck_id = uc.subscription_id
        left join app_core.public_cards pc
          on pc.card_id = uc.public_card_id
         and pc.deck_id = ud.public_deck_id
         and pc.deck_version = ud.current_version
        where uc.user_id = :userId
          and uc.subscription_id = :userDeckId
          and uc.is_deleted = false
          and coalesce(
                nullif(jsonb_extract_path_text(uc.content_override, :field), ''),
                nullif(jsonb_extract_path_text(pc.content, :field), ''),
                nullif(jsonb_extract_path_text(uc.content_override, :field, 'mediaId'), ''),
                nullif(jsonb_extract_path_text(pc.content, :field, 'mediaId'), '')
              ) is null
        """, nativeQuery = true)
    long countMissingField(
            @Param("userId") UUID userId,
            @Param("userDeckId") UUID userDeckId,
            @Param("field") String field
    );

    @Query(value = """
        select uc.user_card_id
        from app_core.user_cards uc
        left join app_core.user_decks ud
          on ud.user_deck_id = uc.subscription_id
        left join app_core.public_cards pc
          on pc.card_id = uc.public_card_id
         and pc.deck_id = ud.public_deck_id
         and pc.deck_version = ud.current_version
        where uc.user_id = :userId
          and uc.subscription_id = :userDeckId
          and uc.is_deleted = false
          and coalesce(
                nullif(jsonb_extract_path_text(uc.content_override, :field), ''),
                nullif(jsonb_extract_path_text(pc.content, :field), ''),
                nullif(jsonb_extract_path_text(uc.content_override, :field, 'mediaId'), ''),
                nullif(jsonb_extract_path_text(pc.content, :field, 'mediaId'), '')
              ) is null
        order by uc.created_at desc
        limit :limit
        """, nativeQuery = true)
    List<UUID> findMissingFieldCardIds(
            @Param("userId") UUID userId,
            @Param("userDeckId") UUID userDeckId,
            @Param("field") String field,
            @Param("limit") int limit
    );

    @Query("""
        select uc
        from UserCardEntity uc
        where uc.userId = :userId
          and uc.userDeckId = :userDeckId
          and uc.userCardId in :cardIds
        """)
    List<UserCardEntity> findByUserIdAndUserDeckIdAndUserCardIdIn(
            @Param("userId") UUID userId,
            @Param("userDeckId") UUID userDeckId,
            @Param("cardIds") List<UUID> cardIds
    );

    @Query(value = """
        with input_fields as (
            select f.field_name, f.ord
            from unnest(cast(:fields as text[])) with ordinality as f(field_name, ord)
        ),
        cards as (
            select
                uc.user_card_id,
                array_agg(
                    regexp_replace(
                        lower(
                            coalesce(
                                nullif(jsonb_extract_path_text(uc.content_override, f.field_name), ''),
                                nullif(jsonb_extract_path_text(pc.content, f.field_name), ''),
                                ''
                            )
                        ),
                        '[^[:alnum:]]+',
                        '',
                        'g'
                    )
                    order by f.ord
                ) as norm_values
            from app_core.user_cards uc
            left join app_core.user_decks ud
              on ud.user_deck_id = uc.subscription_id
            left join app_core.public_cards pc
              on pc.card_id = uc.public_card_id
             and pc.deck_id = ud.public_deck_id
             and pc.deck_version = ud.current_version
            join input_fields f on true
            where uc.user_id = :userId
              and uc.subscription_id = :userDeckId
              and uc.is_deleted = false
            group by uc.user_card_id
        ),
        dup_groups as (
            select
                norm_values,
                array_agg(user_card_id order by user_card_id) as card_ids,
                count(*) as cnt
            from cards
            where array_to_string(norm_values, '') <> ''
            group by norm_values
            having count(*) > 1
            order by cnt desc
            limit :limitGroups
        )
        select card_ids, cnt
        from dup_groups
        """, nativeQuery = true)
    List<DuplicateGroupProjection> findDuplicateGroups(
            @Param("userId") UUID userId,
            @Param("userDeckId") UUID userDeckId,
            @Param("fields") String[] fields,
            @Param("limitGroups") int limitGroups
    );

    interface DuplicateGroupProjection {
        UUID[] getCardIds();
        int getCnt();
    }
}
