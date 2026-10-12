package app.mnema.learning.community;

import app.mnema.learning.library.AliasNormalizer;
import app.mnema.learning.library.PublicationMetadata;
import app.mnema.learning.library.TopicDirectory;
import app.mnema.learning.platform.api.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The validated query of {@code GET /api/public/community/decks} (contract {@code contracts/community/catalog.json}, {@code constants} and {@code invariants}): the search text, the
 * filters, the sort and the page size. Parsing is strict and cheap and happens before anything is read: an unknown parameter, a repeated one, an unknown value, a text over
 * {@value #QUERY_MAX} code points, a limit outside 1..{@value #PAGE_MAX} or a sort next to a text is {@code 400 INVALID_REQUEST}. An EMPTY value of a parameter is the same as an absent one (a client
 * that builds the query string from a form need not drop its blank fields); the text is trimmed, a blank text is a browse.
 *
 * <p>{@link #hash()} binds a cursor to the question it was issued for: the kind of list and the normalized text and filters, never the page size or the cursor itself.
 *
 * @param q the trimmed text, empty for a browse
 * @param topic a topic id (leaf or top-level), or null
 * @param topicIsParent whether {@code topic} is a top-level topic with children (the decks pick leaves, so its decks are found by their parent column)
 * @param learn the target language, or null
 * @param level one of {@link PublicationMetadata#LEVELS}, or null
 * @param size {@code evening}, {@code month} or {@code course}, or null
 * @param mediaMask the bits of {@link CatalogMedia} every deck must have
 * @param mechanicsMask the bits of {@link CatalogMechanic} every deck must have
 * @param fresh whether only decks updated within the last {@value #FRESH_DAYS} days are wanted
 * @param sort {@code popular}, {@code learning}, {@code new} or {@code updated} for a browse; {@code null} for a search (the order is the relevance)
 * @param limit the page size
 * @param cursor the raw cursor, or null
 */
record CatalogQuery(String q, String topic, boolean topicIsParent, String learn, String level, String size, int mediaMask, int mechanicsMask, boolean fresh, String sort,
                    int limit, String cursor) {
    static final int QUERY_MAX = 100;
    static final int PAGE_DEFAULT = 24;
    static final int PAGE_MAX = 48;
    static final int FRESH_DAYS = 30;
    static final Set<String> SORTS = Set.of("popular", "learning", "new", "updated");
    static final Set<String> SIZES = Set.of("evening", "month", "course");
    private static final String DEFAULT_SORT = "popular";
    private static final Set<String> PARAMETERS = Set.of("q", "topic", "learn", "level", "size", "media", "mechanics", "fresh", "sort", "limit", "cursor");
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");
    private static final Pattern NUMBER = Pattern.compile("[1-9][0-9]{0,2}");

    boolean search() { return !q.isEmpty(); }

    /** The kind of list: {@code search} or the sort. */
    String kind() { return search() ? "search" : sort; }

    /** The text as the title index and the topic synonyms see it. */
    String normalizedQuery() { return AliasNormalizer.normalize(q); }

    static CatalogQuery parse(HttpServletRequest request, TopicDirectory topics) {
        Map<String, String[]> raw = request.getParameterMap();
        for (Map.Entry<String, String[]> entry : raw.entrySet()) {
            if (!PARAMETERS.contains(entry.getKey()) || entry.getValue().length != 1) throw new InvalidRequestException();
        }
        String q = value(raw, "q");
        q = q == null ? "" : q.strip();
        if (q.codePointCount(0, q.length()) > QUERY_MAX || q.chars().anyMatch(Character::isISOControl)) throw new InvalidRequestException();
        String sort = value(raw, "sort");
        if (sort != null && (!q.isEmpty() || !SORTS.contains(sort))) throw new InvalidRequestException();
        String topic = value(raw, "topic");
        boolean parent = false;
        if (topic != null) {
            var entry = topics.find(topic).orElseThrow(InvalidRequestException::new);
            parent = entry.parentId() == null && !topics.childrenOf(topic).isEmpty();
        }
        String learn = value(raw, "learn");
        if (learn != null && !LANGUAGE.matcher(learn).matches()) throw new InvalidRequestException();
        String level = value(raw, "level");
        if (level != null && !PublicationMetadata.LEVELS.contains(level)) throw new InvalidRequestException();
        String size = value(raw, "size");
        if (size != null && !SIZES.contains(size)) throw new InvalidRequestException();
        int media = 0;
        for (String word : list(value(raw, "media"))) media |= CatalogMedia.fromWire(word).orElseThrow(InvalidRequestException::new).bit();
        int mechanics = 0;
        for (String word : list(value(raw, "mechanics"))) mechanics |= CatalogMechanic.fromWire(word).orElseThrow(InvalidRequestException::new).bit();
        String fresh = value(raw, "fresh");
        if (fresh != null && !fresh.equals("true") && !fresh.equals("false")) throw new InvalidRequestException();
        String limit = value(raw, "limit");
        int page = PAGE_DEFAULT;
        if (limit != null) {
            if (!NUMBER.matcher(limit).matches()) throw new InvalidRequestException();
            page = Integer.parseInt(limit);
            if (page > PAGE_MAX) throw new InvalidRequestException();
        }
        String cursor = value(raw, "cursor");
        return new CatalogQuery(q, topic, parent, learn, level, size, media, mechanics, "true".equals(fresh), q.isEmpty() ? (sort == null ? DEFAULT_SORT : sort) : null, page, cursor);
    }

    /** The value of a single-valued parameter; an empty one is absent. */
    private static String value(Map<String, String[]> raw, String name) {
        String[] values = raw.get(name);
        return values == null || values[0].isEmpty() ? null : values[0];
    }

    /** A comma-separated list: no empty entries and no duplicates a client could use to make the cache key bigger. */
    private static Set<String> list(String value) {
        Set<String> words = new TreeSet<>();
        if (value == null) return words;
        for (String word : value.split(",", -1)) {
            if (word.isEmpty() || !words.add(word)) throw new InvalidRequestException();
        }
        return words;
    }

    /** What a cursor of this query is bound to. */
    String hash() {
        return digest(String.join("\n", kind(), normalizedQuery(), String.valueOf(topic), String.valueOf(learn), String.valueOf(level), String.valueOf(size),
                String.valueOf(mediaMask), String.valueOf(mechanicsMask), String.valueOf(fresh)));
    }

    /** The first 72 bits of the SHA-256 of {@code text}, base64url: what a cursor remembers of the question it answers. */
    static String digest(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(digest, 9));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
