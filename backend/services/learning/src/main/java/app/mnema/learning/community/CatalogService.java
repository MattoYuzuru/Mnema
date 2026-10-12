package app.mnema.learning.community;

import app.mnema.learning.library.DeckSlug;
import app.mnema.learning.library.LanguageDetector;
import app.mnema.learning.library.PublicReadAdmission;
import app.mnema.learning.library.PublicReadBusyException;
import app.mnema.learning.library.TopicDirectory;
import app.mnema.learning.library.Viewer;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.id.UuidPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * The public catalogue (Community/2 #449 search and browse, Community/6 #453 the decks of an author, Community/7 #454 shelves and sitemap; contract
 * {@code contracts/community/catalog.json}). Every read is admitted like a public deck read ({@link PublicReadAdmission}: the kill switch of the public routes, the guest limit, the
 * bulkhead) after its own kill switch {@code learning.community.catalog.enabled} (off: 404 as if absent, before anything is parsed, counted or limited), and runs in one read-only
 * transaction of a few seconds. Nothing here writes. Failures are the house schema: 400 for a bad query or cursor, 412 {@code VERSION_CONFLICT} for a cursor of a rank generation that is
 * no longer kept (the client restarts the list), 404 when disabled.
 *
 * <p>What is read: {@code public_deck} (listed rows for facets, shelves, browse and search; every public row of an author), the rank generation (order of the popular and learning sorts,
 * the popularity of a search, the audience counts of the cards), and, for an author's list seen by a signed-in viewer, the publication and grant rows of his invitation-only decks.
 * No log line carries a query: the search text is user content and stays out of every log, error and metric.
 */
@Service
public class CatalogService {
    private static final Logger LOG = LoggerFactory.getLogger(CatalogService.class);
    static final int SHELF_MIN = 4;
    static final int SHELF_MAX = 12;
    static final int TOPIC_SHELVES_MAX = 6;
    static final int SITEMAP_MAX_URLS = 50_000;

    /** Facets of one moment: the document, the number of listed decks and the listed decks of each leaf topic (for the topic shelves). */
    record FacetSnapshot(Instant loadedAt, ObjectNode document, int listed, List<Map.Entry<String, Integer>> leafCounts) { }

    private record CachedSitemap(Instant loadedAt, byte[] body) { }

    private final CatalogRouteSettings settings;
    private final PublicReadAdmission admission;
    private final CatalogQueries queries;
    private final AuthorQueries authors;
    private final CatalogRanks ranks;
    private final CatalogCards cards;
    private final TopicDirectory topics;
    private final MeterRegistry meters;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final RankSettings rank;
    private final Semaphore searches;
    private final Object facetsLock = new Object();
    private final Object sitemapLock = new Object();
    private volatile FacetSnapshot facets;
    private volatile CachedSitemap sitemap;

    @Autowired
    CatalogService(CatalogRouteSettings settings, PublicReadAdmission admission, CatalogQueries queries, AuthorQueries authors, CatalogRanks ranks, CatalogCards cards,
                   TopicDirectory topics, PlatformTransactionManager transactions, MeterRegistry meters, RankSettings rank) {
        this(settings, admission, queries, authors, ranks, cards, topics, transactions, meters, rank, Clock.systemUTC());
    }

    CatalogService(CatalogRouteSettings settings, PublicReadAdmission admission, CatalogQueries queries, AuthorQueries authors, CatalogRanks ranks, CatalogCards cards,
                   TopicDirectory topics, PlatformTransactionManager transactions, MeterRegistry meters, RankSettings rank, Clock clock) {
        this.rank = rank;
        this.settings = settings;
        this.admission = admission;
        this.queries = queries;
        this.authors = authors;
        this.ranks = ranks;
        this.cards = cards;
        this.topics = topics;
        this.meters = meters;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setReadOnly(true);
        // One snapshot for the whole request: the check that a cursor's generation is still kept and the page that is cut from it must agree (an expired generation is 412, never an
        // empty page because the generation was pruned between the two statements).
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.transaction.setTimeout((int) Math.max(1, settings.queryTimeout.toSeconds()));
        this.searches = new Semaphore(settings.maxConcurrentSearches);
    }

    /** Forgets the cached facets and sitemap (tests, and an operator who wants a fresh page now: a restart does it too). */
    void invalidate() {
        facets = null;
        sitemap = null;
    }

    /** The kill switch, then the admission (limit and bulkhead) of the public reads; {@code work} runs inside a place of the bulkhead. */
    private <T> T admit(String route, Viewer viewer, Supplier<T> work) {
        if (!settings.enabled) {
            admission.count(route, "disabled");
            throw new ResourceNotFoundException();
        }
        return admission.within(route, viewer, work);
    }

    /**
     * One read-only REPEATABLE READ transaction of {@code query-timeout}. A statement or a transaction that runs out of time is counted ({@code outcome=timeout}) and answered as
     * {@code 503 PUBLIC_READ_BUSY} with {@code Retry-After}: the instance is too busy for this question right now, which is not an error of the service and leaves no stack trace.
     */
    private <T> T inTransaction(String route, Supplier<T> work) {
        Timer.Sample sample = Timer.start(meters);
        try {
            T result = transaction.execute(status -> work.get());
            admission.count(route, "ok");
            return result;
        } catch (RuntimeException failure) {
            if (timedOut(failure)) {
                admission.count(route, "timeout");
                throw new PublicReadBusyException();
            }
            throw failure;
        } finally {
            sample.stop(meters.timer("mnema_catalog_request_seconds", "route", route));
        }
    }

    /** Whether the failure is the clock of the transaction, the statement timeout of the driver or PostgreSQL's "canceling statement" (SQLSTATE 57014). */
    static boolean timedOut(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof TransactionTimedOutException || cause instanceof org.springframework.dao.QueryTimeoutException) return true;
            if (cause instanceof java.sql.SQLException sql && "57014".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    private <T> T serve(String route, Viewer viewer, Supplier<T> work) {
        return admit(route, viewer, () -> inTransaction(route, work));
    }

    // ---- facets ----

    ObjectNode facets(Viewer viewer) {
        // the cache is looked at BEFORE a transaction (a connection) is opened; the admission above it still applies
        return admit("catalog_facets", viewer, () -> {
            FacetSnapshot known = freshFacets();
            if (known != null) {
                admission.count("catalog_facets", "ok");
                return known.document().deepCopy();
            }
            return inTransaction("catalog_facets", () -> facetSnapshot().document().deepCopy());
        });
    }

    private FacetSnapshot freshFacets() {
        FacetSnapshot known = facets;
        return known != null && clock.instant().isBefore(known.loadedAt().plus(settings.facetsTtl)) ? known : null;
    }

    private FacetSnapshot facetSnapshot() {
        FacetSnapshot known = freshFacets();
        if (known != null) return known;
        synchronized (facetsLock) {
            known = freshFacets();
            if (known != null) return known;
            FacetSnapshot fresh = loadFacets(clock.instant());
            facets = fresh;
            return fresh;
        }
    }

    private FacetSnapshot loadFacets(Instant now) {
        CatalogQueries.Counted counted = queries.counts();
        List<TopicDirectory.Entry> directory = topics.entries();
        Map<String, Integer> own = counted.topics();
        Map<String, Integer> total = new HashMap<>(own);
        for (TopicDirectory.Entry entry : directory) {
            if (entry.parentId() != null) total.merge(entry.parentId(), own.getOrDefault(entry.id(), 0), Integer::sum);
        }
        ObjectNode document = JsonNodeFactory.instance.objectNode();
        document.put("listedCount", counted.listed());
        ArrayNode list = document.putArray("topics");
        List<TopicDirectory.Entry> tops = directory.stream().filter(entry -> entry.parentId() == null && total.getOrDefault(entry.id(), 0) > 0)
                .sorted(byCount(total)).toList();
        List<Map.Entry<String, Integer>> leaves = new ArrayList<>();
        for (TopicDirectory.Entry top : tops) {
            addTopic(list, top, total.get(top.id()));
            List<TopicDirectory.Entry> children = directory.stream().filter(entry -> top.id().equals(entry.parentId()) && own.getOrDefault(entry.id(), 0) > 0)
                    .sorted(byCount(own)).toList();
            for (TopicDirectory.Entry child : children) {
                addTopic(list, child, own.get(child.id()));
                leaves.add(Map.entry(child.id(), own.get(child.id())));
            }
            if (topics.childrenOf(top.id()).isEmpty()) leaves.add(Map.entry(top.id(), total.get(top.id())));
        }
        ArrayNode languages = document.putArray("languages");
        counted.languages().entrySet().stream().sorted(byCount()).forEach(entry -> languages.addObject().put("code", entry.getKey()).put("count", entry.getValue()));
        ArrayNode levels = document.putArray("levels");
        counted.levels().entrySet().stream().sorted(byCount()).forEach(entry -> levels.addObject().put("level", entry.getKey()).put("count", entry.getValue()));
        leaves.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey));
        return new FacetSnapshot(now, document, counted.listed(), List.copyOf(leaves));
    }

    private static java.util.Comparator<TopicDirectory.Entry> byCount(Map<String, Integer> counts) {
        return java.util.Comparator.<TopicDirectory.Entry>comparingInt(entry -> -counts.getOrDefault(entry.id(), 0)).thenComparing(TopicDirectory.Entry::id);
    }

    private static java.util.Comparator<Map.Entry<String, Integer>> byCount() {
        return Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey);
    }

    private static void addTopic(ArrayNode list, TopicDirectory.Entry entry, int count) {
        ObjectNode node = list.addObject().put("id", entry.id());
        if (entry.parentId() == null) node.putNull("parentId");
        else node.put("parentId", entry.parentId());
        node.put("name", entry.nameRu()).put("count", count);
    }

    // ---- shelves ----

    ObjectNode shelves(Viewer viewer) {
        return serve("catalog_shelves", viewer, () -> {
            FacetSnapshot snapshot = facetSnapshot();
            ObjectNode document = JsonNodeFactory.instance.objectNode();
            document.put("listedCount", snapshot.listed());
            ArrayNode shelves = document.putArray("shelves");
            if (snapshot.listed() < SHELF_MIN) return document;
            OptionalLong generation = ranks.current();
            Map<String, List<PublicDeckRow>> rows = new LinkedHashMap<>();
            rows.put("editorial", queries.editorialShelf(SHELF_MAX));
            if (generation.isPresent()) rows.put("learning", queries.learningShelf(generation.getAsLong(), SHELF_MAX));
            rows.put("new", queries.newShelf(SHELF_MAX));
            List<String> topicShelves = new ArrayList<>();
            if (generation.isPresent()) {
                for (Map.Entry<String, Integer> leaf : snapshot.leafCounts()) {
                    if (topicShelves.size() == TOPIC_SHELVES_MAX || leaf.getValue() < SHELF_MIN) break;
                    topicShelves.add(leaf.getKey());
                    rows.put("topic:" + leaf.getKey(), queries.topicShelf(generation.getAsLong(), leaf.getKey(), SHELF_MAX));
                }
            }
            Map<UUID, CatalogRanks.Counts> counts = counts(generation, rows.values().stream().flatMap(List::stream).map(PublicDeckRow::deckId).distinct().toList());
            for (Map.Entry<String, List<PublicDeckRow>> shelf : rows.entrySet()) {
                if (shelf.getValue().size() < SHELF_MIN) continue;
                ObjectNode node = shelves.addObject();
                String key = shelf.getKey();
                if (key.startsWith("topic:")) {
                    String topic = key.substring("topic:".length());
                    node.put("kind", "topic").set("topic", cards.topic(topic));
                } else {
                    node.put("kind", key);
                }
                ArrayNode decks = node.putArray("decks");
                shelf.getValue().forEach(row -> decks.add(cards.card(row, counts)));
                if (key.equals("learning")) node.putObject("more").put("sort", "learning");
                else if (key.equals("new")) node.putObject("more").put("sort", "new");
                else if (key.startsWith("topic:")) node.putObject("more").put("topic", key.substring("topic:".length())).put("sort", "popular");
            }
            return document;
        });
    }

    private Map<UUID, CatalogRanks.Counts> counts(OptionalLong generation, List<UUID> decks) {
        return generation.isPresent() ? ranks.counts(generation.getAsLong(), decks) : Map.of();
    }

    // ---- browse and search ----

    ObjectNode decks(Viewer viewer, HttpServletRequest request) {
        return admit("catalog_decks", viewer, () -> {
            CatalogQuery query = CatalogQuery.parse(request, topics);
            if (!query.search()) return inTransaction("catalog_decks", () -> browse(query));
            // a text search is the expensive statement: at most max-concurrent-searches at once on this instance, taken BEFORE a connection is, so a burst of searches cannot hold
            // every place of the public bulkhead and starve the cheap reads
            if (!searches.tryAcquire()) {
                admission.count("catalog_decks", "busy");
                throw new PublicReadBusyException();
            }
            try {
                return inTransaction("catalog_decks", () -> search(query));
            } finally {
                searches.release();
            }
        });
    }

    private ObjectNode browse(CatalogQuery query) {
        Instant now = clock.instant();
        String kind = query.kind();
        CatalogCursor cursor = CatalogCursor.decode(query.cursor(), kind, query.hash());
        boolean ranked = kind.equals("popular") || kind.equals("learning");
        CatalogQueries.Page page;
        OptionalLong generation;
        if (ranked) {
            if (cursor != null) {
                cursor.requireRank();
                if (!ranks.kept(cursor.generation())) throw new VersionConflictException();
                generation = OptionalLong.of(cursor.generation());
            } else {
                generation = ranks.current();
            }
            // Before the first generation exists nothing is ranked: the popular and learning lists are empty, «Новое» and «Обновлённые» already work.
            page = generation.isEmpty() ? new CatalogQueries.Page(List.of(), 0)
                    : queries.rankPage(kind.equals("popular") ? "rank" : "learning_rank", generation.getAsLong(), query, now, cursor == null ? 0 : cursor.position(), query.limit());
        } else {
            if (cursor != null) cursor.requireTime();
            generation = ranks.current();
            page = queries.timePage(kind.equals("new") ? "first_listed_at" : "content_updated_at", query, now, cursor == null ? null : cursor.at(),
                    cursor == null ? null : cursor.code(), query.limit());
        }
        List<CatalogQueries.Hit> hits = page.hits();
        boolean more = hits.size() > query.limit();
        List<CatalogQueries.Hit> shown = more ? hits.subList(0, query.limit()) : hits;
        String next = null;
        if (more) {
            CatalogQueries.Hit last = shown.getLast();
            next = (ranked ? new CatalogCursor(kind, query.hash(), generation.getAsLong(), last.position(), null, null, null)
                    : new CatalogCursor(kind, query.hash(), null, null, kind.equals("new") ? last.row().firstListedAt() : last.row().contentUpdatedAt(), last.row().publicCode(), null)).encode();
        }
        int total = Math.min(page.total(), CatalogQueries.BROWSE_COUNT_CAP);
        return answer(shown, counts(generation, shown.stream().map(hit -> hit.row().deckId()).toList()), total, page.total() > CatalogQueries.BROWSE_COUNT_CAP, next);
    }

    private ObjectNode search(CatalogQuery query) {
        Instant now = clock.instant();
        CatalogCursor cursor = CatalogCursor.decode(query.cursor(), "search", query.hash());
        String normalized = query.normalizedQuery();
        // A text without a single letter or digit (punctuation, operators, emoji) or that normalizes to nothing searches nothing: an empty page, no statement
        if (normalized.isEmpty() || query.q().codePoints().noneMatch(Character::isLetterOrDigit)) return answer(List.of(), Map.of(), 0, false, null);
        boolean sorted = query.sort() != null;
        OptionalLong generation;
        if (cursor != null) {
            // relevance: (relevance in millionths, public code of the last deck); a sort: the position in the ordered list of the best 200 (1..200); never both
            boolean valid = cursor.position() != null && (sorted ? cursor.position() <= CatalogQueries.SEARCH_CANDIDATES && cursor.code() == null : cursor.code() != null);
            if (!valid) throw new InvalidRequestException();
            if (cursor.generation() != null && !ranks.kept(cursor.generation())) throw new VersionConflictException();
            generation = cursor.generation() == null ? OptionalLong.empty() : OptionalLong.of(cursor.generation());
        } else {
            generation = ranks.current();
        }
        queries.prepareSearch(settings.similarityThreshold);
        // the topics the text names: exactly by their synonyms, and by a typo of a synonym (word similarity to topic_alias with the same threshold; a description or a tag is not
        // typo-tolerant in v1: only the titles are, by trigrams)
        TopicDirectory.Named exact = topics.named(normalized);
        TopicDirectory.Named named = topics.withTopics(exact, queries.similarTopics(typoCandidates(normalized)));
        List<String> parents = new ArrayList<>();
        List<String> leaves = new ArrayList<>();
        for (String topic : named.topics()) (!topics.childrenOf(topic).isEmpty() ? parents : leaves).add(topic);
        // null when the detector is not sure: then no deck is lowered for its language
        String language = LanguageDetector.detect(query.q()).orElse(null);
        CatalogQueries.Page page = queries.searchPage(query, now, named, parents, leaves, language, trigramUseful(normalized), generation.orElse(0),
                !sorted && cursor != null ? cursor.position() : null, !sorted && cursor != null ? cursor.code() : null, sorted && cursor != null ? cursor.position().intValue() : 0, query.limit());
        List<CatalogQueries.Hit> hits = page.hits();
        boolean more = hits.size() > query.limit();
        List<CatalogQueries.Hit> shown = more ? hits.subList(0, query.limit()) : hits;
        String next = null;
        if (more) {
            CatalogQueries.Hit last = shown.getLast();
            Long g = generation.isPresent() ? generation.getAsLong() : null;
            next = (sorted ? new CatalogCursor("search", query.hash(), g, (cursor == null ? 0L : cursor.position()) + shown.size(), null, null, null)
                    : new CatalogCursor("search", query.hash(), g, last.position(), null, last.row().publicCode(), null)).encode();
        }
        return answer(shown, counts(generation, shown.stream().map(hit -> hit.row().deckId()).toList()), page.total(), page.total() >= CatalogQueries.SEARCH_CANDIDATES, next);
    }

    /** The words of a normalized text that may be a typo of a topic synonym: five code points or more (a shorter word is too close to too many), at most eight. */
    static List<String> typoCandidates(String normalized) {
        return java.util.Arrays.stream(normalized.split("[^\\p{L}\\p{N}]+")).filter(word -> word.codePointCount(0, word.length()) >= 5).distinct().limit(8).toList();
    }

    /**
     * Whether the title trigram arm is worth running: a normalized text of three code points or more. Shorter ones have one or two trigrams (two letters of a script written with
     * spaces match half the catalogue; in a script written without spaces the boundary trigrams of two characters occur in no title at all, so the arm would scan the index for nothing).
     * Such a text is found by the text arms and by the topics it names. Consequence for v1: a two-character Chinese, Japanese or Korean query finds nothing; four characters (a word)
     * reach the similarity threshold.
     */
    static boolean trigramUseful(String normalized) {
        return normalized.codePointCount(0, normalized.length()) >= 3;
    }

    private ObjectNode answer(List<CatalogQueries.Hit> shown, Map<UUID, CatalogRanks.Counts> counts, int total, boolean capped, String next) {
        ObjectNode document = JsonNodeFactory.instance.objectNode();
        ArrayNode decks = document.putArray("decks");
        shown.forEach(hit -> decks.add(cards.card(hit.row(), counts)));
        document.put("total", total).put("totalCapped", capped);
        if (next == null) document.putNull("nextCursor");
        else document.put("nextCursor", next);
        return document;
    }

    // ---- the decks of an author ----

    ObjectNode author(Viewer viewer, String accountId, HttpServletRequest request) {
        return serve("catalog_author", viewer, () -> {
            UUID author = author(accountId);
            Map<String, String[]> raw = request.getParameterMap();
            for (Map.Entry<String, String[]> entry : raw.entrySet()) {
                if (!Set.of("limit", "cursor").contains(entry.getKey()) || entry.getValue().length != 1) throw new InvalidRequestException();
            }
            int limit = CatalogQuery.PAGE_DEFAULT;
            String size = first(raw, "limit");
            if (size != null) {
                if (!size.matches("[1-9][0-9]{0,2}") || Integer.parseInt(size) > CatalogQuery.PAGE_MAX) throw new InvalidRequestException();
                limit = Integer.parseInt(size);
            }
            String hash = CatalogQuery.digest("author\n" + author);
            CatalogCursor cursor = CatalogCursor.decode(first(raw, "cursor"), "author", hash);
            if (cursor != null && (cursor.part() == null || !Set.of("public", "grants").contains(cursor.part())
                    || (cursor.at() != null && cursor.code() == null) || (cursor.at() == null && cursor.part().equals("public")))) throw new InvalidRequestException();
            OptionalLong generation = ranks.current();
            boolean publicPart = cursor == null || cursor.part().equals("public");
            Instant afterAt = cursor == null ? null : cursor.at();
            String afterCode = cursor == null ? null : cursor.code();
            List<PublicDeckRow> own = publicPart ? authors.publicDecks(author, afterAt, afterCode, limit + 1) : List.of();
            List<AuthorQueries.Granted> granted = List.of();
            int room = limit - Math.min(limit, own.size());
            boolean grants = !viewer.isGuest() && !viewer.accountId().equals(author);
            // the grant part begins when the public part has ended inside this page (or the cursor says so)
            if (grants && own.size() <= limit) {
                granted = authors.granted(author, viewer.accountId(), publicPart ? null : afterAt, publicPart ? null : afterCode, room + 1);
            }
            Map<UUID, CatalogRanks.Counts> counts = counts(generation, own.stream().limit(limit).map(PublicDeckRow::deckId).toList());
            ObjectNode document = JsonNodeFactory.instance.objectNode();
            // distinct learners of the window across his public decks (not a sum of per-deck counts), live, then the display rule of every audience number
            long learners = authors.learningNow(author, clock.instant().minus(java.time.Duration.ofDays(rank.windowDays)));
            Integer shown = AudienceCounts.shown((int) Math.min(learners, Integer.MAX_VALUE));
            if (shown == null) document.putNull("learningNowTotal");
            else document.put("learningNowTotal", shown.intValue());
            ArrayNode decks = document.putArray("decks");
            String next = null;
            if (own.size() > limit) {
                own.stream().limit(limit).forEach(row -> decks.addObject().put("access", "PUBLIC").set("deck", cards.card(row, counts)));
                PublicDeckRow last = own.get(limit - 1);
                next = new CatalogCursor("author", hash, null, null, last.contentUpdatedAt(), last.publicCode(), "public").encode();
            } else {
                own.forEach(row -> decks.addObject().put("access", "PUBLIC").set("deck", cards.card(row, counts)));
                List<AuthorQueries.Granted> page = granted.size() > room ? granted.subList(0, room) : granted;
                page.forEach(deck -> decks.addObject().put("access", "GRANTEE").set("deck", cards.granted(deck)));
                if (granted.size() > room) {
                    // the last deck on this page, or (room 0) the start of the grant part
                    AuthorQueries.Granted last = page.isEmpty() ? null : page.getLast();
                    next = new CatalogCursor("author", hash, null, null, last == null ? null : last.publishedAt(), last == null ? null : last.code(), "grants").encode();
                }
            }
            if (next == null) document.putNull("nextCursor");
            else document.put("nextCursor", next);
            return document;
        });
    }

    private static UUID author(String value) {
        try {
            if (value.length() != 36) throw new InvalidRequestException();
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw new InvalidRequestException();
            return UuidPolicy.requireEntityId(id, "accountId");
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    private static String first(Map<String, String[]> raw, String name) {
        String[] values = raw.get(name);
        return values == null || values[0].isEmpty() ? null : values[0];
    }

    // ---- the sitemap ----

    /** The sitemap document (sitemaps.org 0.9), cached per instance for {@code sitemap-ttl}. */
    byte[] sitemap(Viewer viewer) {
        return admit("catalog_sitemap", viewer, () -> {
            CachedSitemap known = freshSitemap();
            if (known != null) {
                admission.count("catalog_sitemap", "ok");
                return known.body();
            }
            return inTransaction("catalog_sitemap", () -> {
                synchronized (sitemapLock) {
                    CachedSitemap again = freshSitemap();
                    if (again != null) return again.body();
                    byte[] body = renderSitemap();
                    sitemap = new CachedSitemap(clock.instant(), body);
                    return body;
                }
            });
        });
    }

    private CachedSitemap freshSitemap() {
        CachedSitemap known = sitemap;
        return known != null && clock.instant().isBefore(known.loadedAt().plus(settings.sitemapTtl)) ? known : null;
    }

    private byte[] renderSitemap() {
        List<CatalogQueries.SitemapEntry> entries = queries.sitemap(SITEMAP_MAX_URLS);
        int decks = SITEMAP_MAX_URLS - 1;
        if (entries.size() > decks) {
            LOG.warn("catalog_sitemap_truncated urls={} kept={} reason=above_sitemap_limit", entries.size(), decks);
            entries = entries.subList(0, decks);
        }
        StringBuilder xml = new StringBuilder(128 + entries.size() * 140);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        xml.append("<url><loc>").append(escape(settings.origin + "/community")).append("</loc></url>\n");
        for (CatalogQueries.SitemapEntry entry : entries) {
            xml.append("<url><loc>").append(escape(settings.origin + "/d/" + entry.code() + "/" + DeckSlug.of(entry.title()))).append("</loc><lastmod>")
                    .append(CatalogCards.seconds(entry.updatedAt())).append("</lastmod></url>\n");
        }
        xml.append("</urlset>\n");
        return xml.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            switch (value) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(value);
            }
        }
        return out.toString();
    }
}
