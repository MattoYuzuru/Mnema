package app.mnema.learning.community;

import app.mnema.learning.platform.api.InvalidRequestException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Set;

/**
 * The opaque position of a page in a catalogue list: base64url of a small JSON object, bound to the question it answers (the kind of list and a hash of the normalized text and
 * filters, {@link CatalogQuery#hash}) and, for the lists that are ordered by a rank generation (popular, learning, search), to that generation. It grants nothing and is not secret, but it
 * is built from public things only: the integer position in a rank order, the relevance rounded to six digits, a public deck code and a time. The exact score, the popularity percentile and
 * the deck id never enter it.
 *
 * <pre>
 *   v  version (1)            k  popular | learning | new | updated | search | author
 *   h  hash of the query      g  rank generation (popular, learning, search; absent when none existed)
 *   p  position: rank (popular), learning rank (learning), relevance in millionths (search)
 *   t  time in microseconds since the epoch (new, updated, author)
 *   c  public code of the last deck (tiebreak of search, new, updated, author)
 *   x  author list only: the part, "public" or "grants"
 * </pre>
 *
 * A cursor is rejected with 400 when it is malformed or not exactly what {@link #encode} writes, or belongs to another list or query; the 412 for a generation that was pruned is the
 * service's decision ({@link CatalogRanks#kept}).
 */
record CatalogCursor(String kind, String hash, Long generation, Long position, Instant at, String code, String part) {
    static final int VERSION = 1;
    private static final int MAX_LENGTH = 300;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> KINDS = Set.of("popular", "learning", "new", "updated", "search", "author");
    private static final Set<String> KEYS = Set.of("v", "k", "h", "g", "p", "t", "c", "x");

    String encode() {
        ObjectNode node = JSON.createObjectNode();
        node.put("v", VERSION).put("k", kind).put("h", hash);
        if (generation != null) node.put("g", generation);
        if (position != null) node.put("p", position);
        if (at != null) node.put("t", ChronoUnit.MICROS.between(Instant.EPOCH, at));
        if (code != null) node.put("c", code);
        if (part != null) node.put("x", part);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(node.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @return the cursor, or null for none
     * @throws InvalidRequestException the cursor is malformed, or is not one of {@code kind} for the query with this {@code hash}
     */
    static CatalogCursor decode(String encoded, String kind, String hash) {
        if (encoded == null) return null;
        if (encoded.length() > MAX_LENGTH || !encoded.matches("[A-Za-z0-9_-]+")) throw new InvalidRequestException();
        try {
            JsonNode node = JSON.readTree(Base64.getUrlDecoder().decode(encoded));
            if (node == null || !node.isObject()) throw new InvalidRequestException();
            node.propertyNames().forEach(name -> {
                if (!KEYS.contains(name)) throw new InvalidRequestException();
            });
            if (!node.path("v").isInt() || node.path("v").intValue() != VERSION || !node.path("k").isString() || !node.path("h").isString()) throw new InvalidRequestException();
            String found = node.path("k").stringValue(null);
            if (!KINDS.contains(found) || !found.equals(kind) || !hash.equals(node.path("h").stringValue(null))) throw new InvalidRequestException();
            CatalogCursor cursor = new CatalogCursor(found, hash, number(node, "g"), number(node, "p"),
                    node.has("t") ? micros(number(node, "t")) : null, text(node, "c"), text(node, "x"));
            if (!cursor.encode().equals(encoded)) throw new InvalidRequestException();
            return cursor;
        } catch (IllegalArgumentException | JacksonException | java.time.DateTimeException failure) {
            throw new InvalidRequestException();
        }
    }

    private static Long number(JsonNode node, String name) {
        if (!node.has(name)) return null;
        JsonNode value = node.get(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw new InvalidRequestException();
        return value.longValue();
    }

    private static String text(JsonNode node, String name) {
        if (!node.has(name)) return null;
        JsonNode value = node.get(name);
        if (!value.isString() || value.stringValue(null).length() > 32) throw new InvalidRequestException();
        return value.stringValue(null);
    }

    private static Instant micros(Long value) {
        return Instant.EPOCH.plus(value, ChronoUnit.MICROS);
    }

    /** The first position of a list that is not the first page: required keys must be there. */
    CatalogCursor requireRank() {
        if (generation == null || position == null || position < 1) throw new InvalidRequestException();
        return this;
    }

    CatalogCursor requireTime() {
        if (at == null || code == null || !code.matches("[1-9A-HJ-NP-Za-km-z]{10}")) throw new InvalidRequestException();
        return this;
    }
}
