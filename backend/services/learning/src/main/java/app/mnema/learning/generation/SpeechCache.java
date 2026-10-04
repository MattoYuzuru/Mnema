package app.mnema.learning.generation;

import app.mnema.learning.ai.SpeechSettings;
import app.mnema.learning.ai.SpeechSynthesis;
import app.mnema.learning.media.GeneratedMediaStager.VerifiedMedia;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The speech cache (architecture §9, #297): {@code speech_cache}, keyed by the SHA-256 of the canonical JSON of {@code {schema, text, lang, provider, model,
 * modelVersion, voice, format, take}} with the text normalised (NFC, whitespace collapsed; no case change and no {@code ё→е}, which would change the
 * speech). It has no account: a hit gives another owner a new asset on the already verified blobs, with no provider call and no debit.
 *
 * <p>Two steps never synthesise one key twice: {@link #claim} is one short transaction of {@code INSERT ... ON CONFLICT DO NOTHING}, so exactly one caller
 * gets {@link Claim.Won} (and a lease token); the others see {@link Claim.Hit} once the entry is READY, or {@link Claim.Busy} while the lease runs, and
 * take the entry over only after the lease expired (the winner died). Nothing here is held across a provider call or a wait.
 */
@Component
class SpeechCache {
    static final int SCHEMA = 1;

    /** The key of one clip and what it was derived from. */
    record Key(byte[] hash, SpeechSynthesis.Identity identity, String lang, int take) { }

    /** What {@link #claim} found. */
    sealed interface Claim {
        /** The entry is READY: adopt {@code media}. */
        record Hit(VerifiedMedia media) implements Claim { }

        /** The caller owns the entry and must {@link #publish} or {@link #abandon} it with {@code token}. */
        record Won(UUID token) implements Claim { }

        /** Another step is synthesising it. */
        record Busy() implements Claim { }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final SpeechSettings settings;

    SpeechCache(JdbcClient jdbc, PlatformTransactionManager transactions, SpeechSettings settings) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(10);
        this.settings = settings;
    }

    /** NFC, runs of whitespace collapsed to one space, trimmed. */
    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("\\s+", " ").strip();
    }

    static Key key(String text, String lang, SpeechSynthesis.Identity identity, int take) {
        String language = lang.toLowerCase(Locale.ROOT);
        ObjectNode canonical = Json.object().put("schema", SCHEMA).put("text", normalize(text)).put("lang", language)
                .put("provider", identity.provider()).put("model", identity.model()).put("modelVersion", identity.modelVersion())
                .put("voice", identity.voice()).put("format", identity.format()).put("take", take);
        try {
            return new Key(MessageDigest.getInstance("SHA-256").digest(Json.write(canonical).getBytes(StandardCharsets.UTF_8)), identity, language, take);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    Claim claim(Key key) {
        return transaction.execute(status -> {
            UUID token = UUID.randomUUID();
            int inserted = jdbc.sql("INSERT INTO app_learning.speech_cache(cache_key,provider,model,voice,lang,take,state,lease_token,lease_until,created_at,last_used_at) "
                            + "VALUES (:key,:provider,:model,:voice,:lang,:take,'PENDING',:token,CURRENT_TIMESTAMP + (:lease * interval '1 second'),"
                            + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) ON CONFLICT (cache_key) DO NOTHING")
                    .param("key", key.hash()).param("provider", key.identity().provider()).param("model", key.identity().model())
                    .param("voice", key.identity().voice()).param("lang", key.lang()).param("take", key.take()).param("token", token)
                    .param("lease", settings.lease().toSeconds()).update();
            if (inserted == 1) return new Claim.Won(token);
            var row = jdbc.sql("SELECT state,verified::text AS verified,lease_until<CURRENT_TIMESTAMP AS expired FROM app_learning.speech_cache "
                            + "WHERE cache_key=:key FOR UPDATE").param("key", key.hash())
                    .query((result, ignored) -> new Found(result.getString("state"), result.getString("verified"), result.getBoolean("expired")))
                    .optional().orElse(null);
            if (row == null) return new Claim.Busy();
            if (row.state().equals("READY")) {
                jdbc.sql("UPDATE app_learning.speech_cache SET last_used_at=CURRENT_TIMESTAMP WHERE cache_key=:key").param("key", key.hash()).update();
                return new Claim.Hit(read(Json.read(row.verified())));
            }
            if (!row.expired()) return new Claim.Busy();
            jdbc.sql("UPDATE app_learning.speech_cache SET lease_token=:token,lease_until=CURRENT_TIMESTAMP + (:lease * interval '1 second') "
                            + "WHERE cache_key=:key").param("token", token).param("lease", settings.lease().toSeconds()).param("key", key.hash()).update();
            return new Claim.Won(token);
        });
    }

    /** Extends the lease of the winner while it waits for the verification of its clip; false when the entry is no longer its own. */
    boolean renew(Key key, UUID token) {
        return jdbc.sql("UPDATE app_learning.speech_cache SET lease_until=CURRENT_TIMESTAMP + (:lease * interval '1 second') "
                        + "WHERE cache_key=:key AND lease_token=:token AND state='PENDING'")
                .param("lease", settings.lease().toSeconds()).param("key", key.hash()).param("token", token).update() == 1;
    }

    /**
     * The clip passed the media pipeline: the entry becomes READY on its blobs. False when the entry is no longer the caller's, or a blob is gone (then
     * the entry is dropped), in which case nothing was published.
     */
    boolean publish(Key key, UUID token, VerifiedMedia media, long durationMs, long byteLength) {
        Boolean published = transaction.execute(status -> {
            List<UUID> ids = media.blobIds();
            int present = jdbc.sql("SELECT count(*) FROM (SELECT 1 FROM app_learning.media_blob WHERE blob_id IN (:ids) ORDER BY blob_id FOR SHARE) locked")
                    .param("ids", ids).query(Integer.class).single();
            if (present != ids.size()) return false;
            return jdbc.sql("UPDATE app_learning.speech_cache SET state='READY',lease_token=NULL,lease_until=NULL,verified=CAST(:verified AS jsonb),"
                            + "blob_ids=CAST(:blobs AS uuid[]),duration_ms=:duration,byte_length=:bytes,last_used_at=CURRENT_TIMESTAMP "
                            + "WHERE cache_key=:key AND lease_token=:token AND state='PENDING'")
                    .param("verified", Json.write(write(media))).param("blobs", "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}")
                    .param("duration", durationMs).param("bytes", byteLength).param("key", key.hash()).param("token", token).update() == 1;
        });
        return Boolean.TRUE.equals(published);
    }

    /** The synthesis failed or its clip was rejected: the winner's entry goes, so the next step starts clean. */
    void abandon(Key key, UUID token) {
        jdbc.sql("DELETE FROM app_learning.speech_cache WHERE cache_key=:key AND lease_token=:token AND state='PENDING'")
                .param("key", key.hash()).param("token", token).update();
    }

    /** A READY entry whose blobs could not be adopted (reclaimed meanwhile) goes, so the next claim synthesises again. */
    void drop(Key key) {
        jdbc.sql("DELETE FROM app_learning.speech_cache WHERE cache_key=:key AND state='READY'").param("key", key.hash()).update();
    }

    /** One bounded eviction batch: READY entries unused for {@code cache-ttl}, and PENDING ones whose lease ran out long ago. Returns the rows deleted. */
    int evict(int batch) {
        Integer deleted = transaction.execute(status -> jdbc.sql("DELETE FROM app_learning.speech_cache WHERE cache_key IN ("
                        + "SELECT cache_key FROM app_learning.speech_cache WHERE (state='READY' AND last_used_at < CURRENT_TIMESTAMP - (:ttl * interval '1 second')) "
                        + "OR (state='PENDING' AND lease_until < CURRENT_TIMESTAMP - interval '1 day') ORDER BY last_used_at LIMIT :batch FOR UPDATE SKIP LOCKED)")
                .param("ttl", settings.cacheTtl().toSeconds()).param("batch", batch).update());
        return deleted == null ? 0 : deleted;
    }

    private record Found(String state, String verified, boolean expired) { }

    private static ObjectNode write(VerifiedMedia media) {
        ObjectNode node = Json.object().put("sourceBlobId", media.sourceBlob().toString());
        ArrayNode variants = node.putArray("variants");
        for (VerifiedMedia.Variant variant : media.variants()) {
            ObjectNode entry = variants.addObject().put("purpose", variant.purpose()).put("profile", variant.profile()).put("blobId", variant.blob().toString());
            if (variant.width() == null) entry.putNull("width"); else entry.put("width", variant.width());
            if (variant.height() == null) entry.putNull("height"); else entry.put("height", variant.height());
            if (variant.durationMs() == null) entry.putNull("durationMs"); else entry.put("durationMs", variant.durationMs());
        }
        return node;
    }

    private static VerifiedMedia read(JsonNode node) {
        List<VerifiedMedia.Variant> variants = new ArrayList<>();
        for (JsonNode entry : node.path("variants")) {
            variants.add(new VerifiedMedia.Variant(entry.path("purpose").stringValue(""), entry.path("profile").stringValue(""),
                    UUID.fromString(entry.path("blobId").stringValue("")), entry.path("width").isNull() ? null : entry.path("width").intValue(),
                    entry.path("height").isNull() ? null : entry.path("height").intValue(),
                    entry.path("durationMs").isNull() ? null : entry.path("durationMs").longValue()));
        }
        return new VerifiedMedia(UUID.fromString(node.path("sourceBlobId").stringValue("")), List.copyOf(variants));
    }
}
