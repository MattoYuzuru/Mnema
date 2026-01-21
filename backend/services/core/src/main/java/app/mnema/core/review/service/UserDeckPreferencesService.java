package app.mnema.core.review.service;

import app.mnema.core.review.entity.UserDeckPreferencesEntity;
import app.mnema.core.review.repository.UserDeckPreferencesRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class UserDeckPreferencesService {

    private static final int DEFAULT_LEARNING_HORIZON_MINUTES = 120;
    private static final int DEFAULT_MAX_NEW_PER_DAY = 20;
    private static final int DEFAULT_DAY_CUTOFF_MINUTES = 0;
    private static final int MAX_DAY_CUTOFF_MINUTES = 24 * 60 - 1;

    private final UserDeckPreferencesRepository repository;

    public UserDeckPreferencesService(UserDeckPreferencesRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public PreferencesSnapshot getSnapshot(UUID userDeckId, Instant now) {
        UserDeckPreferencesEntity entity = getOrCreate(userDeckId);
        ReviewDayBounds reviewDay = reviewDay(entity.getTimeZoneId(), entity.getDayCutoffMinutes(), now);
        boolean reset = ensureCountersForDate(entity, reviewDay.date());
        if (reset) {
            repository.save(entity);
        }
        return toSnapshot(entity);
    }

    @Transactional
    public void incrementCounters(UUID userDeckId, boolean newCardAnswered, Instant now) {
        UserDeckPreferencesEntity entity = getOrCreateForUpdate(userDeckId);
        ReviewDayBounds reviewDay = reviewDay(entity.getTimeZoneId(), entity.getDayCutoffMinutes(), now);
        ensureCountersForDate(entity, reviewDay.date());
        if (newCardAnswered) {
            entity.setNewSeenToday(entity.getNewSeenToday() + 1);
        } else {
            entity.setReviewSeenToday(entity.getReviewSeenToday() + 1);
        }
        repository.save(entity);
    }

    private UserDeckPreferencesEntity getOrCreate(UUID userDeckId) {
        return repository.findById(userDeckId)
                .orElseGet(() -> createDefaultWithRetry(userDeckId));
    }

    private UserDeckPreferencesEntity getOrCreateForUpdate(UUID userDeckId) {
        return repository.findByUserDeckId(userDeckId)
                .orElseGet(() -> createDefaultWithRetry(userDeckId));
    }

    private static PreferencesSnapshot toSnapshot(UserDeckPreferencesEntity entity) {
        return new PreferencesSnapshot(
                entity.getUserDeckId(),
                Duration.ofMinutes(Math.max(1, entity.getLearningHorizonMinutes())),
                entity.getMaxNewPerDay(),
                entity.getMaxReviewPerDay(),
                entity.getNewSeenToday(),
                entity.getReviewSeenToday(),
                entity.getTimeZoneId(),
                normalizeDayCutoff(entity.getDayCutoffMinutes())
        );
    }

    private static boolean ensureCountersForDate(UserDeckPreferencesEntity entity, LocalDate today) {
        if (entity.getCounterDate() != null && entity.getCounterDate().equals(today)) {
            return false;
        }
        entity.setCounterDate(today);
        entity.setNewSeenToday(0);
        entity.setReviewSeenToday(0);
        return true;
    }

    private static ReviewDayBounds reviewDay(String timeZoneId, int dayCutoffMinutes, Instant now) {
        ZoneId zoneId = resolveZoneId(timeZoneId);
        int cutoffMinutes = normalizeDayCutoff(dayCutoffMinutes);
        LocalTime cutoff = LocalTime.of(cutoffMinutes / 60, cutoffMinutes % 60);

        var zoned = now.atZone(zoneId);
        LocalDate date = zoned.toLocalDate();
        if (cutoffMinutes > 0 && zoned.toLocalTime().isBefore(cutoff)) {
            date = date.minusDays(1);
        }
        Instant start = date.atTime(cutoff).atZone(zoneId).toInstant();
        Instant end = date.plusDays(1).atTime(cutoff).atZone(zoneId).toInstant();
        return new ReviewDayBounds(date, start, end);
    }

    private static ZoneId resolveZoneId(String timeZoneId) {
        if (timeZoneId == null || timeZoneId.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(timeZoneId);
        } catch (DateTimeException ex) {
            return ZoneOffset.UTC;
        }
    }

    private static int normalizeDayCutoff(int dayCutoffMinutes) {
        return Math.max(0, Math.min(MAX_DAY_CUTOFF_MINUTES, dayCutoffMinutes));
    }

    private UserDeckPreferencesEntity createDefaultWithRetry(UUID userDeckId) {
        try {
            return repository.save(buildDefault(userDeckId));
        } catch (DataIntegrityViolationException ex) {
            return repository.findById(userDeckId).orElseThrow(() -> ex);
        }
    }

    private static UserDeckPreferencesEntity buildDefault(UUID userDeckId) {
        UserDeckPreferencesEntity entity = new UserDeckPreferencesEntity();
        entity.setUserDeckId(userDeckId);
        entity.setLearningHorizonMinutes(DEFAULT_LEARNING_HORIZON_MINUTES);
        entity.setMaxNewPerDay(DEFAULT_MAX_NEW_PER_DAY);
        entity.setMaxReviewPerDay(null);
        entity.setDayCutoffMinutes(DEFAULT_DAY_CUTOFF_MINUTES);
        entity.setTimeZoneId(null);
        entity.setNewSeenToday(0);
        entity.setReviewSeenToday(0);
        entity.setCounterDate(null);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(entity.getCreatedAt());
        return entity;
    }

    @Transactional
    public PreferencesSnapshot updatePreferences(UUID userDeckId,
                                                 Integer maxNewPerDay,
                                                 Integer learningHorizonHours,
                                                 Integer maxReviewPerDay,
                                                 Integer dayCutoffHour,
                                                 String timeZoneId) {
        UserDeckPreferencesEntity entity = getOrCreateForUpdate(userDeckId);
        boolean changed = false;

        if (maxNewPerDay != null) {
            entity.setMaxNewPerDay(Math.max(0, maxNewPerDay));
            changed = true;
        }

        if (learningHorizonHours != null) {
            int minutes = Math.max(1, learningHorizonHours) * 60;
            entity.setLearningHorizonMinutes(minutes);
            changed = true;
        }

        if (maxReviewPerDay != null) {
            entity.setMaxReviewPerDay(Math.max(0, maxReviewPerDay));
            changed = true;
        }

        if (dayCutoffHour != null) {
            int minutes = Math.max(0, Math.min(23, dayCutoffHour)) * 60;
            entity.setDayCutoffMinutes(minutes);
            changed = true;
        }

        if (timeZoneId != null) {
            String normalized = normalizeTimeZoneId(timeZoneId);
            if (normalized != null || timeZoneId.isBlank()) {
                entity.setTimeZoneId(normalized);
                changed = true;
            }
        }

        if (changed) {
            repository.save(entity);
        }
        return toSnapshot(entity);
    }

    public record PreferencesSnapshot(UUID userDeckId,
                                      Duration learningHorizon,
                                      Integer maxNewPerDay,
                                      Integer maxReviewPerDay,
                                      int newSeenToday,
                                      int reviewSeenToday,
                                      String timeZoneId,
                                      int dayCutoffMinutes) {
        public long remainingNewQuota() {
            if (maxNewPerDay == null || maxNewPerDay <= 0) {
                return Long.MAX_VALUE;
            }
            return Math.max(0, (long) maxNewPerDay - newSeenToday);
        }

        public long remainingReviewQuota() {
            if (maxReviewPerDay == null || maxReviewPerDay <= 0) {
                return Long.MAX_VALUE;
            }
            return Math.max(0, (long) maxReviewPerDay - reviewSeenToday);
        }

        public ReviewDayBounds reviewDay(Instant now) {
            return UserDeckPreferencesService.reviewDay(timeZoneId, dayCutoffMinutes, now);
        }
    }

    public record ReviewDayBounds(LocalDate date, Instant start, Instant end) {
    }

    private static String normalizeTimeZoneId(String timeZoneId) {
        if (timeZoneId == null || timeZoneId.isBlank()) {
            return null;
        }
        try {
            ZoneId.of(timeZoneId);
            return timeZoneId;
        } catch (DateTimeException ex) {
            return null;
        }
    }
}
