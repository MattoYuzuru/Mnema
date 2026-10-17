package app.mnema.learning.community;

import app.mnema.learning.library.TopicDirectory;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Local transparent interest model; all recomputation happens AFTER acquiring the account row lock. */
@Service
public class InterestService {
    private final InterestRepository repository;
    private final RecommendationSettings settings;
    private final TopicDirectory topics;
    private final CatalogCards cards;
    private final CommandReceiptService receipts;

    @Autowired
    InterestService(InterestRepository repository, RecommendationSettings settings, TopicDirectory topics, CatalogCards cards, CommandReceiptService receipts) {
        this.repository = repository;
        this.settings = settings;
        this.topics = topics;
        this.cards = cards;
        this.receipts = receipts;
    }

    void requireEnabled() {
        if (!settings.eventsEnabled && !settings.forYouEnabled) throw new ResourceNotFoundException();
    }

    @Transactional(timeout = 10)
    public ObjectNode profile(UUID account) {
        requireEnabled();
        InterestRepository.Profile profile = repository.lock(account);
        // Initial activation can use existing copies and the enum goal. A reset permanently switches to the new boundary.
        initialize(profile);
        repository.active(account);
        return document(repository.lock(account));
    }

    @Transactional(timeout = 10)
    JsonNode select(UUID account, long expected, RecommendationCommands.Selection command) {
        requireEnabled();
        if (!command.topics().stream().allMatch(topics::selectable)) throw new InvalidRequestException();
        receipts.execute(new CommandIdentity(command.commandId(), account, "community.interests", "interests.select"), command.envelope(), () -> {
            InterestRepository.Profile profile = repository.lock(account);
            if (profile.version() != expected) throw new VersionConflictException();
            repository.selection(account, command.topics());
            rebuildLocked(repository.lock(account), false);
            repository.active(account);
            return JsonNodeFactory.instance.objectNode();
        });
        return document(repository.lock(account));
    }

    @Transactional(timeout = 10)
    JsonNode reset(UUID account, long expected, UUID command, JsonNode envelope) {
        requireEnabled();
        receipts.execute(new CommandIdentity(command, account, "community.interests", "interests.reset"), envelope, () -> {
            if (repository.lock(account).version() != expected) throw new VersionConflictException();
            repository.reset(account);
            return JsonNodeFactory.instance.objectNode();
        });
        return document(repository.lock(account));
    }

    /** Acquire BEFORE a successful copy is written; keeps reset and copy's timestamp/effect in the same order. */

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockSignal(UUID account) {
        if (settings.eventsEnabled || settings.forYouEnabled) repository.lock(account);
    }

    /** Effective copy/drop/goal transitions change eligibility even when normalization leaves the weights identical. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(UUID account) {
        if (!settings.eventsEnabled && !settings.forYouEnabled) return;
        InterestRepository.Profile profile = repository.lock(account);
        rebuildLocked(profile, false);
        repository.bump(account);
        repository.active(account);
    }

    void initialize(InterestRepository.Profile profile) {
        if (profile.bootstrap() && repository.interests(profile.account()).isEmpty()) rebuildLocked(profile);
    }

    void rebuild(UUID account) {
        rebuildLocked(repository.lock(account));
    }

    void rebuildLocked(InterestRepository.Profile profile) {
        rebuildLocked(profile, true);
    }

    private void rebuildLocked(InterestRepository.Profile profile, boolean bump) {
        Map<String, Double> weights = new LinkedHashMap<>();
        profile.selected().forEach(t -> weights.merge(t, 8.0, Double::sum));
        repository.copyTopics(profile).forEach(t -> weights.merge(t, 4.0, Double::sum));
        Instant now = repository.now();
        Instant retained = now.atZone(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate().minusMonths(12).atStartOfDay(ZoneOffset.UTC).toInstant();
        repository.eventTopics(profile, retained, now).forEach((t, w) -> weights.merge(t, w, Double::sum));
        goalTopics(repository.goal(profile)).forEach(t -> weights.merge(t, 0.5, Double::sum));
        weights.keySet().removeIf(t -> topics.find(t).isEmpty());
        double max = weights.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
        Map<String, Double> normalized = new LinkedHashMap<>();
        weights.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey)).limit(20)
                .forEach(e -> normalized.put(e.getKey(), (double)(float)(e.getValue()/max)));
        if (!repository.interests(profile.account()).equals(normalized)) repository.replace(profile.account(), normalized, bump);
    }

    /** Directory-backed enum mapping. SELF and missing/unknown goals have no vote. */
    List<String> goalTopics(String goal) {
        if (goal == null) return List.of();
        List<String> roots = switch (goal) {
            case "LANGUAGE" -> List.of("languages");
            case "INTERVIEW" -> List.of("professional");
            case "EXAMS" -> List.of("exams", "school");
            case "WORK" -> List.of("professional");
            default -> List.of();
        };
        return roots.stream().filter(t -> topics.find(t).isPresent()).toList();
    }

    private ObjectNode document(InterestRepository.Profile profile) {
        ObjectNode document = JsonNodeFactory.instance.objectNode().put("rowVersion", Long.toString(profile.version())).put("onboardingComplete", profile.onboarding());
        var selected = document.putArray("selectedTopics");
        profile.selected().forEach(t -> selected.add(cards.topic(t)));
        var inferred = document.putArray("inferredTopics");
        repository.interests(profile.account()).keySet().stream().filter(t -> !profile.selected().contains(t)).forEach(t -> inferred.add(cards.topic(t)));
        var available = document.putArray("availableTopics");
        topics.entries().stream().filter(t -> topics.selectable(t.id())).forEach(t -> available.add(cards.topic(t.id())));
        return document.put("forYouEnabled", settings.forYouEnabled).put("eventsEnabled", settings.eventsEnabled);
    }
}
