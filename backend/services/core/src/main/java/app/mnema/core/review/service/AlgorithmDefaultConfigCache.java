package app.mnema.core.review.service;

import app.mnema.core.review.entity.SrAlgorithmEntity;
import app.mnema.core.review.repository.SrAlgorithmRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AlgorithmDefaultConfigCache {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final SrAlgorithmRepository algorithmRepository;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public AlgorithmDefaultConfigCache(SrAlgorithmRepository algorithmRepository) {
        this.algorithmRepository = algorithmRepository;
    }

    public JsonNode getDefaultConfig(String algorithmId) {
        Instant now = Instant.now();
        CacheEntry entry = cache.get(algorithmId);
        if (entry != null && now.isBefore(entry.expiresAt())) {
            return entry.config();
        }

        JsonNode config = algorithmRepository.findById(algorithmId)
                .map(SrAlgorithmEntity::getDefaultConfig)
                .orElse(null);
        cache.put(algorithmId, new CacheEntry(config, now.plus(TTL)));
        return config;
    }

    private record CacheEntry(JsonNode config, Instant expiresAt) {
    }
}
