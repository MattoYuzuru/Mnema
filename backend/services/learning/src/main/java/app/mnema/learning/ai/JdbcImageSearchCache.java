package app.mnema.learning.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** PostgreSQL cache in {@code app_learning.image_search_cache}; a small scheduled sweep deletes rows past the TTL. */
@Component
class JdbcImageSearchCache implements ImageSearchCache {
    private static final Logger LOG = LoggerFactory.getLogger(JdbcImageSearchCache.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** The column's own bound (V35 checks it too). */
    static final int MAX_BYTES = 256 * 1024;
    private final JdbcClient jdbc;
    private final ImageSearchSettings settings;

    JdbcImageSearchCache(JdbcClient jdbc, ImageSearchSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    static byte[] key(String source, String query, String lang, int page) {
        try {
            String normalized = query.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            return MessageDigest.getInstance("SHA-256").digest((source + "|" + normalized + "|" + lang + "|" + page).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public Optional<List<ImageSearch.Candidate>> get(String source, String query, String lang, int page) {
        try {
            Optional<String> stored = jdbc.sql("SELECT response::text FROM app_learning.image_search_cache WHERE cache_key=:key "
                            + "AND fetched_at > CURRENT_TIMESTAMP - (:seconds * interval '1 second')")
                    .param("key", key(source, query, lang, page)).param("seconds", settings.cacheTtl().toSeconds()).query(String.class).optional();
            return stored.map(JdbcImageSearchCache::read);
        } catch (RuntimeException failure) {
            LOG.warn("image_search_cache_read_failed error_type={}", failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void put(String source, String query, String lang, int page, List<ImageSearch.Candidate> candidates) {
        try {
            String json = write(candidates);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return;
            jdbc.sql("INSERT INTO app_learning.image_search_cache(cache_key,response,fetched_at) VALUES (:key,CAST(:response AS jsonb),CURRENT_TIMESTAMP) "
                            + "ON CONFLICT (cache_key) DO UPDATE SET response=EXCLUDED.response,fetched_at=EXCLUDED.fetched_at")
                    .param("key", key(source, query, lang, page)).param("response", json).update();
        } catch (RuntimeException failure) {
            LOG.warn("image_search_cache_write_failed error_type={}", failure.getClass().getSimpleName());
        }
    }

    /** Deletes expired rows in one bounded statement per tick. */
    @Scheduled(initialDelayString = "${learning.ai.image-search.cleanup-initial-delay:PT15M}",
            fixedDelayString = "${learning.ai.image-search.cleanup-interval:PT1H}")
    void sweep() {
        try {
            int deleted = jdbc.sql("DELETE FROM app_learning.image_search_cache WHERE cache_key IN (SELECT cache_key FROM "
                            + "app_learning.image_search_cache WHERE fetched_at < CURRENT_TIMESTAMP - (:seconds * interval '1 second') LIMIT 1000)")
                    .param("seconds", settings.cacheTtl().toSeconds()).update();
            if (deleted != 0) LOG.info("image_search_cache_sweep deleted={}", deleted);
        } catch (RuntimeException failure) {
            LOG.warn("image_search_cache_sweep_failed error_type={}", failure.getClass().getSimpleName());
        }
    }

    static String write(List<ImageSearch.Candidate> candidates) {
        ArrayNode list = JSON.createArrayNode();
        for (ImageSearch.Candidate candidate : candidates) {
            ObjectNode node = list.addObject();
            node.put("source", candidate.source().name()).put("sourceId", candidate.sourceId()).put("title", candidate.title())
                    .put("author", candidate.author()).put("license", candidate.license()).put("licenseUrl", candidate.licenseUrl())
                    .put("sourcePageUrl", candidate.sourcePageUrl()).put("shareAlike", candidate.shareAlike())
                    .put("width", candidate.width()).put("height", candidate.height()).put("downloadUrl", candidate.downloadUrl());
        }
        return list.toString();
    }

    static List<ImageSearch.Candidate> read(String json) {
        try {
            List<ImageSearch.Candidate> out = new ArrayList<>();
            for (JsonNode node : JSON.readTree(json)) {
                out.add(new ImageSearch.Candidate(ImageSearch.Source.valueOf(node.path("source").stringValue("")), node.path("sourceId").stringValue(""),
                        node.path("title").stringValue(""), node.path("author").stringValue(""), node.path("license").stringValue(""),
                        node.path("licenseUrl").stringValue(null), node.path("sourcePageUrl").stringValue(""), node.path("shareAlike").asBoolean(false),
                        node.path("width").asInt(0), node.path("height").asInt(0), node.path("downloadUrl").stringValue("")));
            }
            return out;
        } catch (JacksonException | IllegalArgumentException corrupt) {
            return List.of();
        }
    }
}
