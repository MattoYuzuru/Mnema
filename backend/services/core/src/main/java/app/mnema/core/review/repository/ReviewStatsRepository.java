package app.mnema.core.review.repository;

import app.mnema.core.review.entity.SrReviewLogEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@org.springframework.stereotype.Repository
public interface ReviewStatsRepository extends Repository<SrReviewLogEntity, Long> {

    interface OverviewProjection {
        long getReviewCount();

        long getUniqueCardCount();

        long getAgainCount();

        long getTotalResponseMs();

        Double getAvgResponseMs();

        Double getMedianResponseMs();
    }

    interface DailyProjection {
        LocalDate getBucketDate();

        long getReviewCount();

        long getUniqueCardCount();

        long getAgainCount();

        long getHardCount();

        long getGoodCount();

        long getEasyCount();

        long getTotalResponseMs();
    }

    interface HourlyProjection {
        int getHourOfDay();

        long getReviewCount();

        long getAgainCount();

        Double getAvgResponseMs();
    }

    interface RatingProjection {
        int getRatingCode();

        long getReviewCount();
    }

    interface SourceProjection {
        String getSource();

        long getReviewCount();
    }

    interface ForecastProjection {
        LocalDate getBucketDate();

        long getDueCount();
    }

    interface SnapshotProjection {
        long getActiveCards();

        long getTrackedCards();

        long getNewCards();

        long getSuspendedCards();

        long getDueNow();

        long getDueToday();

        long getDueInOneDay();

        long getDueInSevenDays();

        long getOverdue();
    }

    interface StreakProjection {
        long getCurrentStreakDays();

        long getLongestStreakDays();

        long getTodayStreakDays();

        boolean getActiveToday();

        LocalDate getCurrentStreakStartDate();

        LocalDate getCurrentStreakEndDate();

        LocalDate getLastActiveDate();
    }

    interface SessionDayProjection {
        LocalDate getBucketDate();

        long getSessionCount();

        Instant getFirstSessionStartAt();

        Instant getLastSessionEndAt();

        long getStudiedMinutes();

        long getReviewCount();

        long getTotalResponseMs();
    }

    interface SessionWindowProjection {
        Instant getSessionStartedAt();

        Instant getSessionEndedAt();

        long getDurationMinutes();

        long getReviewCount();

        long getTotalResponseMs();
    }

    @Query(value = """
            select
                count(*) as review_count,
                count(distinct l.user_card_id) as unique_card_count,
                count(*) filter (where l.rating = 0) as again_count,
                coalesce(sum(l.response_ms), 0) as total_response_ms,
                coalesce(avg(l.response_ms), 0)::double precision as avg_response_ms,
                coalesce(percentile_cont(0.5) within group (order by l.response_ms), 0)::double precision as median_response_ms
            from app_core.sr_review_logs l
            join app_core.user_cards uc on uc.user_card_id = l.user_card_id
            join app_core.user_decks ud on ud.user_deck_id = uc.subscription_id
            where uc.user_id = :userId
              and uc.is_deleted = false
              and ud.is_archived = false
              and (:deckId is null or uc.subscription_id = :deckId)
              and l.reviewed_at >= :fromInstant
              and l.reviewed_at < :toInstant
            """, nativeQuery = true)
    OverviewProjection loadOverview(@Param("userId") UUID userId,
                                    @Param("deckId") UUID deckId,
                                    @Param("fromInstant") Instant fromInstant,
                                    @Param("toInstant") Instant toInstant);

    @Query(value = """
            select
                ((l.reviewed_at at time zone :timeZone) - make_interval(mins => :dayCutoffMinutes))::date as bucket_date,
                count(*) as review_count,
                count(distinct l.user_card_id) as unique_card_count,
                count(*) filter (where l.rating = 0) as again_count,
                count(*) filter (where l.rating = 1) as hard_count,
                count(*) filter (where l.rating = 2) as good_count,
                count(*) filter (where l.rating = 3) as easy_count,
                coalesce(sum(l.response_ms), 0) as total_response_ms
            from app_core.sr_review_logs l
            join app_core.user_cards uc on uc.user_card_id = l.user_card_id
            join app_core.user_decks ud on ud.user_deck_id = uc.subscription_id
            where uc.user_id = :userId
              and uc.is_deleted = false
              and ud.is_archived = false
              and (:deckId is null or uc.subscription_id = :deckId)
              and l.reviewed_at >= :fromInstant
              and l.reviewed_at < :toInstant
            group by bucket_date
            order by bucket_date
            """, nativeQuery = true)
    List<DailyProjection> loadDaily(@Param("userId") UUID userId,
                                    @Param("deckId") UUID deckId,
                                    @Param("fromInstant") Instant fromInstant,
                                    @Param("toInstant") Instant toInstant,
                                    @Param("timeZone") String timeZone,
                                    @Param("dayCutoffMinutes") int dayCutoffMinutes);

    @Query(value = """
            select
                extract(hour from (l.reviewed_at at time zone :timeZone))::int as hour_of_day,
                count(*) as review_count,
                count(*) filter (where l.rating = 0) as again_count,
                coalesce(avg(l.response_ms), 0)::double precision as avg_response_ms
            from app_core.sr_review_logs l
            join app_core.user_cards uc on uc.user_card_id = l.user_card_id
            join app_core.user_decks ud on ud.user_deck_id = uc.subscription_id
            where uc.user_id = :userId
              and uc.is_deleted = false
              and ud.is_archived = false
              and (:deckId is null or uc.subscription_id = :deckId)
              and l.reviewed_at >= :fromInstant
              and l.reviewed_at < :toInstant
            group by hour_of_day
            order by hour_of_day
            """, nativeQuery = true)
    List<HourlyProjection> loadHourly(@Param("userId") UUID userId,
                                      @Param("deckId") UUID deckId,
                                      @Param("fromInstant") Instant fromInstant,
                                      @Param("toInstant") Instant toInstant,
                                      @Param("timeZone") String timeZone);

    @Query(value = """
            select
                l.rating::int as rating_code,
                count(*) as review_count
            from app_core.sr_review_logs l
            join app_core.user_cards uc on uc.user_card_id = l.user_card_id
            join app_core.user_decks ud on ud.user_deck_id = uc.subscription_id
            where uc.user_id = :userId
              and uc.is_deleted = false
              and ud.is_archived = false
              and (:deckId is null or uc.subscription_id = :deckId)
              and l.reviewed_at >= :fromInstant
              and l.reviewed_at < :toInstant
              and l.rating is not null
            group by l.rating
            order by l.rating
            """, nativeQuery = true)
    List<RatingProjection> loadRatings(@Param("userId") UUID userId,
                                       @Param("deckId") UUID deckId,
                                       @Param("fromInstant") Instant fromInstant,
                                       @Param("toInstant") Instant toInstant);

    @Query(value = """
            select
                coalesce(l.source::text, 'unknown') as source,
                count(*) as review_count
            from app_core.sr_review_logs l
            join app_core.user_cards uc on uc.user_card_id = l.user_card_id
            join app_core.user_decks ud on ud.user_deck_id = uc.subscription_id
            where uc.user_id = :userId
              and uc.is_deleted = false
              and ud.is_archived = false
              and (:deckId is null or uc.subscription_id = :deckId)
              and l.reviewed_at >= :fromInstant
              and l.reviewed_at < :toInstant
            group by source
            order by review_count desc, source asc
            """, nativeQuery = true)
    List<SourceProjection> loadSources(@Param("userId") UUID userId,
                                       @Param("deckId") UUID deckId,
                                       @Param("fromInstant") Instant fromInstant,
                                       @Param("toInstant") Instant toInstant);

    @Query(value = """
            select
                ((s.next_review_at at time zone :timeZone) - make_interval(mins => :dayCutoffMinutes))::date as bucket_date,
                count(*) as due_count
            from app_core.sr_card_states s
            join app_core.user_cards uc on uc.user_card_id = s.user_card_id
            join app_core.user_decks ud on ud.user_deck_id = uc.subscription_id
            where uc.user_id = :userId
              and uc.is_deleted = false
              and ud.is_archived = false
              and s.is_suspended = false
              and s.next_review_at is not null
              and (:deckId is null or uc.subscription_id = :deckId)
              and s.next_review_at >= :fromInstant
              and s.next_review_at < :toInstant
            group by bucket_date
            order by bucket_date
            """, nativeQuery = true)
    List<ForecastProjection> loadForecast(@Param("userId") UUID userId,
                                          @Param("deckId") UUID deckId,
                                          @Param("fromInstant") Instant fromInstant,
                                          @Param("toInstant") Instant toInstant,
                                          @Param("timeZone") String timeZone,
                                          @Param("dayCutoffMinutes") int dayCutoffMinutes);

    @Query(value = """
            select
                count(*) as active_cards,
                count(s.user_card_id) as tracked_cards,
                count(*) filter (where s.user_card_id is null) as new_cards,
                count(*) filter (where s.user_card_id is not null and s.is_suspended = true) as suspended_cards,
                count(*) filter (
                    where s.user_card_id is not null
                      and s.is_suspended = false
                      and s.next_review_at is not null
                      and s.next_review_at <= :nowInstant
                ) as due_now,
                count(*) filter (
                    where s.user_card_id is not null
                      and s.is_suspended = false
                      and s.next_review_at is not null
                      and s.next_review_at <= :reviewDayEnd
                ) as due_today,
                count(*) filter (
                    where s.user_card_id is not null
                      and s.is_suspended = false
                      and s.next_review_at is not null
                      and s.next_review_at <= :next24h
                ) as due_in_one_day,
                count(*) filter (
                    where s.user_card_id is not null
                      and s.is_suspended = false
                      and s.next_review_at is not null
                      and s.next_review_at <= :next7d
                ) as due_in_seven_days,
                count(*) filter (
                    where s.user_card_id is not null
                      and s.is_suspended = false
                      and s.next_review_at is not null
                      and s.next_review_at < :nowInstant
                ) as overdue
            from app_core.user_cards uc
            join app_core.user_decks ud on ud.user_deck_id = uc.subscription_id
            left join app_core.sr_card_states s on s.user_card_id = uc.user_card_id
            where uc.user_id = :userId
              and uc.is_deleted = false
              and ud.is_archived = false
              and (:deckId is null or uc.subscription_id = :deckId)
            """, nativeQuery = true)
    SnapshotProjection loadSnapshot(@Param("userId") UUID userId,
                                    @Param("deckId") UUID deckId,
                                    @Param("nowInstant") Instant nowInstant,
                                    @Param("reviewDayEnd") Instant reviewDayEnd,
                                    @Param("next24h") Instant next24h,
                                    @Param("next7d") Instant next7d);

    @Query(value = """
            with params as (
                select ((:nowInstant at time zone :timeZone) - make_interval(mins => :dayCutoffMinutes))::date as review_day
            ),
            activity_days as (
                select distinct ((l.reviewed_at at time zone :timeZone) - make_interval(mins => :dayCutoffMinutes))::date as activity_day
                from app_core.sr_review_logs l
                join app_core.user_cards uc on uc.user_card_id = l.user_card_id
                join app_core.user_decks ud on ud.user_deck_id = uc.subscription_id
                where uc.user_id = :userId
                  and uc.is_deleted = false
                  and ud.is_archived = false
                  and (:deckId is null or uc.subscription_id = :deckId)
            ),
            numbered as (
                select
                    activity_day,
                    activity_day - (row_number() over (order by activity_day))::int as grp
                from activity_days
            ),
            streaks as (
                select
                    min(activity_day) as start_day,
                    max(activity_day) as end_day,
                    count(*)::bigint as streak_days
                from numbered
                group by grp
            ),
            latest as (
                select s.*
                from streaks s
                order by s.end_day desc
                limit 1
            ),
            today_streak as (
                select s.*
                from streaks s
                join params p on p.review_day = s.end_day
            )
            select
                coalesce((select streak_days from latest), 0) as current_streak_days,
                coalesce((select max(streak_days) from streaks), 0) as longest_streak_days,
                coalesce((select streak_days from today_streak), 0) as today_streak_days,
                exists(select 1 from today_streak) as active_today,
                (select start_day from latest) as current_streak_start_date,
                (select end_day from latest) as current_streak_end_date,
                (select activity_day from activity_days order by activity_day desc limit 1) as last_active_date
            """, nativeQuery = true)
    StreakProjection loadStreak(@Param("userId") UUID userId,
                                @Param("deckId") UUID deckId,
                                @Param("nowInstant") Instant nowInstant,
                                @Param("timeZone") String timeZone,
                                @Param("dayCutoffMinutes") int dayCutoffMinutes);

    @Query(value = """
            with filtered as (
                select
                    l.reviewed_at,
                    coalesce(l.response_ms, 0) as response_ms,
                    ((l.reviewed_at at time zone :timeZone) - make_interval(mins => :dayCutoffMinutes))::date as bucket_date
                from app_core.sr_review_logs l
                join app_core.user_cards uc on uc.user_card_id = l.user_card_id
                join app_core.user_decks ud on ud.user_deck_id = uc.subscription_id
                where uc.user_id = :userId
                  and uc.is_deleted = false
                  and ud.is_archived = false
                  and (:deckId is null or uc.subscription_id = :deckId)
                  and l.reviewed_at >= :fromInstant
                  and l.reviewed_at < :toInstant
            ),
            tagged as (
                select
                    f.bucket_date,
                    f.reviewed_at,
                    f.response_ms,
                    case
                        when lag(f.reviewed_at) over (partition by f.bucket_date order by f.reviewed_at) is null then 1
                        when f.reviewed_at
                             - lag(f.reviewed_at) over (partition by f.bucket_date order by f.reviewed_at)
                             > make_interval(mins => :sessionGapMinutes) then 1
                        else 0
                    end as is_new_session
                from filtered f
            ),
            sessionized as (
                select
                    t.bucket_date,
                    t.reviewed_at,
                    t.response_ms,
                    sum(t.is_new_session) over (
                        partition by t.bucket_date
                        order by t.reviewed_at
                        rows between unbounded preceding and current row
                    ) as session_seq
                from tagged t
            ),
            sessions as (
                select
                    s.bucket_date,
                    s.session_seq,
                    min(s.reviewed_at) as session_started_at,
                    max(s.reviewed_at) as session_ended_at,
                    count(*)::bigint as review_count,
                    coalesce(sum(s.response_ms), 0)::bigint as total_response_ms
                from sessionized s
                group by s.bucket_date, s.session_seq
            )
            select
                ss.bucket_date,
                count(*)::bigint as session_count,
                min(ss.session_started_at) as first_session_start_at,
                max(ss.session_ended_at) as last_session_end_at,
                coalesce(sum(greatest(1, ceil(extract(epoch from (ss.session_ended_at - ss.session_started_at)) / 60.0)::bigint)), 0) as studied_minutes,
                coalesce(sum(ss.review_count), 0)::bigint as review_count,
                coalesce(sum(ss.total_response_ms), 0)::bigint as total_response_ms
            from sessions ss
            group by ss.bucket_date
            order by ss.bucket_date
            """, nativeQuery = true)
    List<SessionDayProjection> loadSessionDays(@Param("userId") UUID userId,
                                               @Param("deckId") UUID deckId,
                                               @Param("fromInstant") Instant fromInstant,
                                               @Param("toInstant") Instant toInstant,
                                               @Param("timeZone") String timeZone,
                                               @Param("dayCutoffMinutes") int dayCutoffMinutes,
                                               @Param("sessionGapMinutes") int sessionGapMinutes);

    @Query(value = """
            with filtered as (
                select
                    l.reviewed_at,
                    coalesce(l.response_ms, 0) as response_ms,
                    ((l.reviewed_at at time zone :timeZone) - make_interval(mins => :dayCutoffMinutes))::date as bucket_date
                from app_core.sr_review_logs l
                join app_core.user_cards uc on uc.user_card_id = l.user_card_id
                join app_core.user_decks ud on ud.user_deck_id = uc.subscription_id
                where uc.user_id = :userId
                  and uc.is_deleted = false
                  and ud.is_archived = false
                  and (:deckId is null or uc.subscription_id = :deckId)
                  and l.reviewed_at >= :fromInstant
                  and l.reviewed_at < :toInstant
            ),
            tagged as (
                select
                    f.bucket_date,
                    f.reviewed_at,
                    f.response_ms,
                    case
                        when lag(f.reviewed_at) over (partition by f.bucket_date order by f.reviewed_at) is null then 1
                        when f.reviewed_at
                             - lag(f.reviewed_at) over (partition by f.bucket_date order by f.reviewed_at)
                             > make_interval(mins => :sessionGapMinutes) then 1
                        else 0
                    end as is_new_session
                from filtered f
            ),
            sessionized as (
                select
                    t.bucket_date,
                    t.reviewed_at,
                    t.response_ms,
                    sum(t.is_new_session) over (
                        partition by t.bucket_date
                        order by t.reviewed_at
                        rows between unbounded preceding and current row
                    ) as session_seq
                from tagged t
            )
            select
                min(s.reviewed_at) as session_started_at,
                max(s.reviewed_at) as session_ended_at,
                greatest(1, ceil(extract(epoch from (max(s.reviewed_at) - min(s.reviewed_at))) / 60.0)::bigint) as duration_minutes,
                count(*)::bigint as review_count,
                coalesce(sum(s.response_ms), 0)::bigint as total_response_ms
            from sessionized s
            group by s.bucket_date, s.session_seq
            order by session_started_at
            """, nativeQuery = true)
    List<SessionWindowProjection> loadSessionWindows(@Param("userId") UUID userId,
                                                     @Param("deckId") UUID deckId,
                                                     @Param("fromInstant") Instant fromInstant,
                                                     @Param("toInstant") Instant toInstant,
                                                     @Param("timeZone") String timeZone,
                                                     @Param("dayCutoffMinutes") int dayCutoffMinutes,
                                                     @Param("sessionGapMinutes") int sessionGapMinutes);

    @Query(value = """
            with filtered as (
                select
                    l.reviewed_at,
                    coalesce(l.response_ms, 0) as response_ms,
                    ((l.reviewed_at at time zone :timeZone) - make_interval(mins => :dayCutoffMinutes))::date as bucket_date
                from app_core.sr_review_logs l
                join app_core.user_cards uc on uc.user_card_id = l.user_card_id
                join app_core.user_decks ud on ud.user_deck_id = uc.subscription_id
                where uc.user_id = :userId
                  and uc.is_deleted = false
                  and ud.is_archived = false
                  and (:deckId is null or uc.subscription_id = :deckId)
                  and l.reviewed_at >= :fromInstant
                  and l.reviewed_at < :toInstant
            ),
            tagged as (
                select
                    f.bucket_date,
                    f.reviewed_at,
                    f.response_ms,
                    case
                        when lag(f.reviewed_at) over (partition by f.bucket_date order by f.reviewed_at) is null then 1
                        when f.reviewed_at
                             - lag(f.reviewed_at) over (partition by f.bucket_date order by f.reviewed_at)
                             > make_interval(mins => :sessionGapMinutes) then 1
                        else 0
                    end as is_new_session
                from filtered f
            ),
            sessionized as (
                select
                    t.bucket_date,
                    t.reviewed_at,
                    t.response_ms,
                    sum(t.is_new_session) over (
                        partition by t.bucket_date
                        order by t.reviewed_at
                        rows between unbounded preceding and current row
                    ) as session_seq
                from tagged t
            )
            select
                min(s.reviewed_at) as session_started_at,
                max(s.reviewed_at) as session_ended_at,
                greatest(1, ceil(extract(epoch from (max(s.reviewed_at) - min(s.reviewed_at))) / 60.0)::bigint) as duration_minutes,
                count(*)::bigint as review_count,
                coalesce(sum(s.response_ms), 0)::bigint as total_response_ms
            from sessionized s
            group by s.bucket_date, s.session_seq
            order by session_ended_at desc
            limit 1
            """, nativeQuery = true)
    SessionWindowProjection loadLatestSessionWindow(@Param("userId") UUID userId,
                                                    @Param("deckId") UUID deckId,
                                                    @Param("fromInstant") Instant fromInstant,
                                                    @Param("toInstant") Instant toInstant,
                                                    @Param("timeZone") String timeZone,
                                                    @Param("dayCutoffMinutes") int dayCutoffMinutes,
                                                    @Param("sessionGapMinutes") int sessionGapMinutes);
}
