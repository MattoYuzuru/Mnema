package app.mnema.core.review.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class DeckAlgorithmUpdateBuffer {

    private static final Duration FLUSH_INTERVAL = Duration.ofSeconds(12);
    private static final int FLUSH_BATCH_SIZE = 6;
    private static final Duration ENTRY_TTL = Duration.ofMinutes(30);

    private final ConcurrentMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    public JsonNode applyPending(UUID userDeckId, String algorithmId, JsonNode deckConfig, Instant now) {
        Entry entry = entries.get(userDeckId);
        if (entry == null) {
            return deckConfig;
        }
        if (isExpired(entry, now) || !entry.algorithmId().equals(algorithmId)) {
            entries.remove(userDeckId);
            return deckConfig;
        }
        entry.touch(now);
        return entry.pendingConfig() == null ? deckConfig : entry.pendingConfig();
    }

    public Optional<JsonNode> recordUpdate(UUID userDeckId, String algorithmId, JsonNode updatedConfig, Instant now) {
        Entry entry = entries.compute(userDeckId, (id, existing) -> {
            if (existing == null || isExpired(existing, now) || !existing.algorithmId().equals(algorithmId)) {
                return Entry.newEntry(algorithmId, updatedConfig, now);
            }
            existing.touch(now);
            if (!Objects.equals(existing.pendingConfig(), updatedConfig)) {
                existing.setPending(updatedConfig);
                existing.increment();
            }
            return existing;
        });

        if (entry == null || entry.pendingConfig() == null) {
            return Optional.empty();
        }

        if (shouldFlush(entry, now)) {
            entry.flush(now);
            return Optional.of(entry.pendingConfig());
        }
        return Optional.empty();
    }

    public Optional<JsonNode> flushIfPending(UUID userDeckId, String algorithmId, Instant now) {
        Entry entry = entries.get(userDeckId);
        if (entry == null || isExpired(entry, now) || !entry.algorithmId().equals(algorithmId)) {
            return Optional.empty();
        }
        entry.touch(now);
        if (entry.pendingConfig() == null || entry.pendingCount() == 0) {
            return Optional.empty();
        }
        entry.flush(now);
        return Optional.of(entry.pendingConfig());
    }

    private static boolean shouldFlush(Entry entry, Instant now) {
        if (entry.pendingCount() >= FLUSH_BATCH_SIZE) {
            return true;
        }
        return Duration.between(entry.lastPersistedAt(), now).compareTo(FLUSH_INTERVAL) >= 0;
    }

    private static boolean isExpired(Entry entry, Instant now) {
        return Duration.between(entry.lastTouchedAt(), now).compareTo(ENTRY_TTL) > 0;
    }

    private static final class Entry {
        private final String algorithmId;
        private JsonNode pendingConfig;
        private int pendingCount;
        private Instant lastPersistedAt;
        private Instant lastTouchedAt;

        private Entry(String algorithmId,
                      JsonNode pendingConfig,
                      int pendingCount,
                      Instant lastPersistedAt,
                      Instant lastTouchedAt) {
            this.algorithmId = algorithmId;
            this.pendingConfig = pendingConfig;
            this.pendingCount = pendingCount;
            this.lastPersistedAt = lastPersistedAt;
            this.lastTouchedAt = lastTouchedAt;
        }

        static Entry newEntry(String algorithmId, JsonNode pendingConfig, Instant now) {
            return new Entry(algorithmId, pendingConfig, 1, now, now);
        }

        String algorithmId() {
            return algorithmId;
        }

        JsonNode pendingConfig() {
            return pendingConfig;
        }

        int pendingCount() {
            return pendingCount;
        }

        Instant lastPersistedAt() {
            return lastPersistedAt;
        }

        Instant lastTouchedAt() {
            return lastTouchedAt;
        }

        void increment() {
            pendingCount++;
        }

        void setPending(JsonNode pendingConfig) {
            this.pendingConfig = pendingConfig;
        }

        void flush(Instant now) {
            pendingCount = 0;
            lastPersistedAt = now;
        }

        void touch(Instant now) {
            lastTouchedAt = now;
        }
    }
}
