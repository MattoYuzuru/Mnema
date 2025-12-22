package app.mnema.core.review.service;

import app.mnema.core.review.entity.UserDeckPreferencesEntity;
import app.mnema.core.review.repository.UserDeckPreferencesRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class UserDeckPreferencesService {

    private static final int DEFAULT_LEARNING_HORIZON_MINUTES = 120;
    private static final int DEFAULT_MAX_NEW_PER_DAY = 20;

    private final UserDeckPreferencesRepository repository;

    public UserDeckPreferencesService(UserDeckPreferencesRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public PreferencesSnapshot getSnapshot(UUID userDeckId, Instant now) {
        UserDeckPreferencesEntity entity = getOrCreate(userDeckId);
        boolean reset = ensureCountersForDate(entity, today(now));
        if (reset) {
            repository.save(entity);
        }
        return toSnapshot(entity);
    }

    @Transactional
    public void incrementCounters(UUID userDeckId, boolean newCardAnswered, Instant now) {
        UserDeckPreferencesEntity entity = getOrCreateForUpdate(userDeckId);
        ensureCountersForDate(entity, today(now));
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
                entity.getReviewSeenToday()
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

    private static LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC);
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
        entity.setNewSeenToday(0);
        entity.setReviewSeenToday(0);
        entity.setCounterDate(null);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(entity.getCreatedAt());
        return entity;
    }

    public record PreferencesSnapshot(UUID userDeckId,
                                      Duration learningHorizon,
                                      Integer maxNewPerDay,
                                      Integer maxReviewPerDay,
                                      int newSeenToday,
                                      int reviewSeenToday) {
        public long remainingNewQuota() {
            if (maxNewPerDay == null || maxNewPerDay <= 0) {
                return Long.MAX_VALUE;
            }
            return Math.max(0, (long) maxNewPerDay - newSeenToday);
        }
    }
}
