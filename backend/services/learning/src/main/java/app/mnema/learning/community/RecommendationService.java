package app.mnema.learning.community;

import app.mnema.learning.library.LanguageDetector;
import app.mnema.learning.library.PublicReadAdmission;
import app.mnema.learning.library.PublicReadLimiter;
import app.mnema.learning.library.Viewer;

import jakarta.servlet.http.HttpServletRequest;

import app.mnema.learning.library.PublicReadBusyException;
import app.mnema.learning.library.TopicDirectory;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** Private recommendations, fixed rankVersion; score, interest weights and source ids never leave the server. */
@Service
class RecommendationService {
    static final String VERSION = "recs-v1";
    private final PublicReadAdmission admission;
    private final PublicReadLimiter limiter;
    private final InterestService interestService;
    private final RecommendationSettings settings;
    private final CatalogRouteSettings catalog;
    private final RecommendationRepository repository;
    private final InterestRepository profiles;
    private final CatalogRanks ranks;
    private final CatalogQueries queries;
    private final CatalogCards cards;
    private final TopicDirectory topics;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final Semaphore searches;
    private final MeterRegistry meters;

    @Autowired
    RecommendationService(RecommendationSettings settings, CatalogRouteSettings catalog, RecommendationRepository repository, InterestRepository profiles,
                CatalogRanks ranks, CatalogQueries queries, CatalogCards cards, TopicDirectory topics, PlatformTransactionManager transactions,
                MeterRegistry meters, PublicReadAdmission admission, PublicReadLimiter limiter, InterestService interestService) {
        this(settings, catalog, repository, profiles, ranks, queries, cards, topics, transactions, meters, admission, limiter, interestService, Clock.systemUTC());
    }

    RecommendationService(RecommendationSettings settings, CatalogRouteSettings catalog, RecommendationRepository repository, InterestRepository profiles,
                CatalogRanks ranks, CatalogQueries queries, CatalogCards cards, TopicDirectory topics, PlatformTransactionManager transactions,
                MeterRegistry meters, PublicReadAdmission admission, PublicReadLimiter limiter, InterestService interestService, Clock clock) {
        this.admission = admission;
        this.limiter = limiter;
        this.interestService = interestService;
        this.settings = settings;
        this.catalog = catalog;
        this.repository = repository;
        this.profiles = profiles;
        this.ranks = ranks;
        this.queries = queries;
        this.cards = cards;
        this.topics = topics;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout((int)catalog.queryTimeout.toSeconds());
        this.searches = new Semaphore(catalog.maxConcurrentSearches);
        this.meters = meters;
    }

    void requireEnabled() {
        if (!settings.forYouEnabled || !catalog.enabled) throw new ResourceNotFoundException();
        admission.requireEnabled("catalog_recommendations");
    }

    ObjectNode page(UUID account, HttpServletRequest request) {
        requireEnabled();
        Viewer viewer = Viewer.account(account, null);
        limiter.admit(viewer);
        // Private chain has no public-account filter: count this account exactly once here.
        return admission.within("catalog_recommendations", viewer, () -> pageQuery(account, CatalogQuery.recommendations(request, topics)));
    }

    private ObjectNode pageQuery(UUID account, CatalogQuery query) {
        if (query.search() && !searches.tryAcquire()) throw new PublicReadBusyException();
        try {
            return transaction.execute(status -> read(account, query));
        } catch (RuntimeException failure) {
            if (CatalogService.timedOut(failure)) throw new PublicReadBusyException();
            throw failure;
        } finally {
            if (query.search()) searches.release();
        }
    }

    private ObjectNode read(UUID account, CatalogQuery query) {
        var now = clock.instant();
        InterestRepository.Profile profile = profiles.lock(account);
        interestService.initialize(profile);
        profile = profiles.lock(account);
        profiles.active(account);
        RecommendationCursor cursor = RecommendationCursor.decode(query.cursor());
        long generation = ranks.current().orElse(0);
        RecommendationRepository.Snapshot snapshot;
        if (cursor != null) {
            snapshot = repository.snapshot(cursor.snapshot()).orElseThrow(VersionConflictException::new);
            if (!snapshot.account().equals(account) || !snapshot.hash().equals(query.hash())) throw new InvalidRequestException();
            if (snapshot.version() != profile.version() || now.isAfter(snapshot.created().plus(Duration.ofHours(24))) || (snapshot.generation() != 0 && !ranks.kept(snapshot.generation()))) throw new VersionConflictException();
            generation = snapshot.generation();
        } else {
            repository.discardExpired(account, profile.version(), generation, query.hash(), now);
            snapshot = repository.snapshot(account, profile.version(), generation, query.hash()).orElse(null);
            if (snapshot == null) {
                Map<String, Double> interests = profiles.interests(account);
                List<String> strongest = interests.entrySet().stream().sorted(Map.Entry.<String,
                        Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey)).limit(3).map(Map.Entry::getKey).toList();
                List<UUID> search = query.search() ? search(query, generation) : null;
                List<RecommendationRanker.Candidate> candidates = interests.isEmpty() ? List.of() : repository.candidates(repository.candidatesSql(account, generation, strongest,
                        strongest.stream().filter(t -> !topics.childrenOf(t).isEmpty()).collect(java.util.stream.Collectors.toSet()), query, now, search));
                RecommendationRanker.Result result = RecommendationRanker.rank(candidates, interests, repository.languages(profile), now);
                snapshot = repository.freeze(account, profile.version(), generation, query.hash(), result, candidates.stream().map(c -> c.deck().publicCode()).toList(), now);
            }
        }

        if (generation != 0 && !ranks.lockKept(generation)) throw new VersionConflictException();
        Map<String, PublicDeckRow> eligible = new HashMap<>();
        repository.eligible(account, snapshot.codes()).forEach(d -> eligible.put(d.publicCode(), d));
        // Recheck current ownership/visibility and strict first-ten diversity without changing the frozen code offsets.
        List<PublicDeckRow> live = snapshot.codes().stream().filter(eligible::containsKey).map(eligible::get).toList();
        List<PublicDeckRow> first = RecommendationRanker.diverse(live.stream().limit(10).toList());
        java.util.Set<String> allowed = new java.util.HashSet<>();
        first.forEach(d -> allowed.add(d.publicCode()));
        if (live.size() == 12 && first.size() == 10) live.stream().skip(10).forEach(d -> allowed.add(d.publicCode()));
        Map<String, Double> currentInterests = profiles.interests(account);
        long matching = repository.eligible(account, snapshot.candidates()).stream().filter(d -> RecommendationRanker.interest(d, currentInterests) > 0).limit(4).count();
        int total = matching < 4 ? 0 : allowed.size();
        ObjectNode answer = JsonNodeFactory.instance.objectNode().put("rankVersion", VERSION);
        var decks = answer.putArray("decks");
        int offset = cursor == null ? 0 : cursor.offset();
        List<PublicDeckRow> shown = new ArrayList<>();
        if (total >= 4) {
            while (offset < snapshot.codes().size() && shown.size()<query.limit()) {
                String code = snapshot.codes().get(offset++);
                if (allowed.contains(code)) shown.add(eligible.get(code));
            }

        } else {
            total = 0;
            offset = snapshot.codes().size();
        }

        var counts = generation == 0 ? Map.<UUID, CatalogRanks.Counts>of() : ranks.counts(generation, shown.stream().map(PublicDeckRow::deckId).toList());
        shown.forEach(d -> decks.add(cards.card(d, counts)));
        boolean more = snapshot.codes().subList(offset, snapshot.codes().size()).stream().anyMatch(allowed::contains);
        answer.put("total", total).put("totalCapped", false);
        if (more) answer.put("nextCursor", new RecommendationCursor(snapshot.id(), offset).encode());
        else answer.putNull("nextCursor");
        String explanation = snapshot.explanation();
        if (total == 0 || explanation == null || live.stream().noneMatch(d -> (explanation.equals(d.topicId()) || explanation.equals(d.parentTopicId())))) answer.putNull("explanationTopic");
        else answer.set("explanationTopic", cards.topic(explanation));
        meters.counter("mnema_community_recommendations_total", "rank_version", VERSION, "outcome", total == 0 ? "empty" : "ok").increment();
        return answer;
    }

    private List<UUID> search(CatalogQuery request, long generation) {
        if (request.normalizedQuery().isEmpty() || request.q().codePoints().noneMatch(Character::isLetterOrDigit)) return List.of();
        CatalogQuery query = new CatalogQuery(request.q(), request.topic(), request.topicIsParent(), request.learn(), request.level(), request.size(), request.mediaMask(),
                request.mechanicsMask(), request.fresh(), null, 200, null);
        queries.prepareSearch(catalog.similarityThreshold);
        var named = topics.withTopics(topics.named(query.normalizedQuery()), queries.similarTopics(CatalogService.typoCandidates(query.normalizedQuery())));
        List<String> parents = new ArrayList<>(), leaves = new ArrayList<>();
        named.topics().forEach(t -> (!topics.childrenOf(t).isEmpty() ? parents : leaves).add(t));
        return queries.searchPage(query, clock.instant(), named, parents, leaves, LanguageDetector.detect(query.q()).orElse(null),
                CatalogService.trigramUseful(query.normalizedQuery()), generation, null, null, 0, 200)
                .hits().stream().map(h -> h.row().deckId()).toList();
    }
}
