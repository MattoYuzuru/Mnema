package app.mnema.learning.study.session;

import app.mnema.learning.catalog.content.storage.NativeSnapshotDecoder;
import app.mnema.learning.catalog.content.storage.NativeStorageBatches;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class StudySessionService {
    private static final Duration SESSION_LIFETIME = Duration.ofHours(24);

    private final StudySessionRepository repository;
    private final CommandReceiptService receipts;
    private final NativeStorageBatches nativeBatches;

    public StudySessionService(StudySessionRepository repository, CommandReceiptService receipts,
                               ImmutableStorage storage) {
        this.repository = repository;
        this.receipts = receipts;
        this.nativeBatches = new NativeStorageBatches(storage);
    }

    @Transactional(timeout = 10)
    public StartResult start(UUID actor, UUID deckId, String trustedTimezone, StudySessionCommand command) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deckId, "deckId");
        StudySessionRepository.DeckHead currentDeck = own(actor, deckId);
        String timezone = timezone(trustedTimezone);
        CommandIdentity identity = new CommandIdentity(command.commandId(), actor, "deck.study", "study.start");
        ObjectNode envelope = command.envelope(deckId);
        var replay = receipts.replay(identity, envelope);
        if (replay.isPresent()) {
            JsonNode response = replay.orElseThrow();
            return new StartResult(response, true, "PREPARING".equals(response.path("status").textValue()));
        }
        boolean[] applied = {false};
        JsonNode response = receipts.execute(identity, envelope, () -> {
            applied[0] = true;
            Instant now = repository.now();
            LocalDate studyDate = now.atZone(ZoneId.of(timezone)).toLocalDate();
            StudySessionRepository.Session source = null;
            StudySessionRepository.DeckHead snapshot = currentDeck;
            StudySessionRepository.Generation generation;
            if (command.mode() == StudySessionCommand.Mode.REPLAY) {
                source = repository.replaySource(actor, deckId, command.sourceSessionId(), studyDate)
                        .orElseThrow(InvalidRequestException::new);
                generation = repository.generationForUpdate(deckId, source.generationId())
                        .orElseThrow(IllegalStateException::new);
            } else {
                generation = generation(currentDeck, now);
            }
            UUID sessionId = UUID.randomUUID();
            long seed = randomSeed();
            String status = generation.status().equals("READY") ? "ACTIVE" : "PREPARING";
            UUID deckRevision = source == null ? snapshot.revisionId() : source.deckRevisionId();
            long deckSequence = source == null ? snapshot.sequence() : source.deckSequence();
            repository.insertSession(actor, sessionId, command.commandId(), command.mode(), status, timezone, studyDate,
                    deckId, deckRevision, deckSequence, generation.generationId(), seed, command,
                    source == null ? null : source.sessionId(), now, now.plus(SESSION_LIFETIME));
            StudySessionRepository.Session inserted = repository.sessionForUpdate(actor, deckId, sessionId).orElseThrow();
            if (generation.status().equals("READY")) {
                issueInitial(inserted, generation, source, now);
                inserted = repository.session(actor, deckId, sessionId).orElseThrow();
            }
            return response(inserted);
        });
        return new StartResult(response, !applied[0], "PREPARING".equals(response.path("status").textValue()));
    }

    @Transactional(timeout = 10)
    public ObjectNode read(UUID actor, UUID deckId, UUID sessionId) {
        own(actor, deckId);
        StudySessionRepository.Session session = repository.sessionForUpdate(actor, deckId, sessionId)
                .orElseThrow(ResourceNotFoundException::new);
        Instant now = repository.now();
        requireCurrent(session, now);
        if (session.status().equals("PREPARING")) {
            StudySessionRepository.Generation generation = advancePreparation(session, now);
            if (generation.status().equals("READY")) {
                StudySessionRepository.Session source = session.sourceSessionId() == null ? null
                        : repository.replaySource(actor, deckId, session.sourceSessionId(), session.localStudyDate())
                        .orElseThrow(InvalidRequestException::new);
                issueInitial(session, generation, source, now);
                session = repository.session(actor, deckId, sessionId).orElseThrow();
            }
        }
        return response(session);
    }

    @Transactional(timeout = 10)
    public ObjectNode presentations(UUID actor, UUID deckId, UUID sessionId) {
        own(actor, deckId);
        StudySessionRepository.Session session = repository.sessionForUpdate(actor, deckId, sessionId)
                .orElseThrow(ResourceNotFoundException::new);
        requireCurrent(session, repository.now());
        // Until the attempt slice terminalizes the current batch, refill is a deterministic resume.
        return response(session);
    }

    private StudySessionRepository.Generation generation(StudySessionRepository.DeckHead deck, Instant now) {
        var existing = repository.generation(deck.deckId(), deck.exercisesRootId());
        if (existing.isPresent()) return existing.orElseThrow();
        UUID id = UUID.randomUUID();
        repository.insertGeneration(deck, id, now);
        return repository.generation(deck.deckId(), deck.exercisesRootId()).orElseThrow();
    }

    private StudySessionRepository.Generation advancePreparation(StudySessionRepository.Session session, Instant now) {
        StudySessionRepository.Generation generation = repository.generationForUpdate(session.deckId(),
                session.generationId()).orElseThrow(IllegalStateException::new);
        if (!generation.status().equals("PREPARING")) return generation;
        List<StudySessionRepository.SourceExercise> sources = repository.sourceBatch(generation,
                BoundedCandidatePlanner.PREPARATION_LIMIT);
        int candidate = generation.candidateCount();
        for (StudySessionRepository.SourceExercise source : sources) {
            if (source.enabled()) repository.insertCandidate(generation.generationId(), candidate++, generation, source);
        }
        int scanned = sources.size();
        int enabled = candidate - generation.candidateCount();
        int totalScanned = generation.scannedCount() + scanned;
        boolean ready = totalScanned == generation.expectedExerciseCount();
        if (!ready && (sources.isEmpty() || totalScanned > generation.expectedExerciseCount())) {
            throw new IllegalStateException("Candidate source projection is inconsistent");
        }
        UUID cursor = sources.isEmpty() ? generation.sourceCursor() : sources.getLast().exerciseId();
        repository.advanceGeneration(generation, cursor, scanned, enabled, ready, now);
        return repository.generationForUpdate(session.deckId(), generation.generationId()).orElseThrow();
    }

    private void issueInitial(StudySessionRepository.Session session, StudySessionRepository.Generation generation,
                              StudySessionRepository.Session source, Instant now) {
        if (session.mode() == StudySessionCommand.Mode.REPLAY) {
            issueReplay(session, source, now);
            return;
        }
        if (session.mode() == StudySessionCommand.Mode.PRACTICE && !session.includeNew()) {
            repository.updateSessionBatch(session, "EMPTY", 0, 0, 0, 0, false);
            return;
        }
        if (generation.candidateCount() == 0) {
            repository.updateSessionBatch(session, "EMPTY", 0, 0, 0, 0, false);
            return;
        }
        BoundedCandidatePlanner.Window window = BoundedCandidatePlanner.window(session.seed(),
                generation.candidateCount(), session.budget());
        int target = window.target();
        int start = window.start();
        List<StudySessionRepository.Candidate> candidates = select(generation, window);
        Set<UUID> objectives = new HashSet<>();
        int issued = 0;
        int cursor = start;
        boolean wrapped = false;
        for (StudySessionRepository.Candidate candidate : candidates) {
            cursor = candidate.ordinal() + 1;
            if (cursor >= generation.candidateCount()) { cursor = 0; wrapped = true; }
            if (!objectives.add(candidate.objectiveId())) continue;
            insert(session, candidate, issued++, now);
            if (issued == target) break;
        }
        repository.updateSessionBatch(session, issued == 0 ? "EMPTY" : "ACTIVE", issued, 0, issued,
                cursor, wrapped);
    }

    private List<StudySessionRepository.Candidate> select(StudySessionRepository.Generation generation,
                                                           BoundedCandidatePlanner.Window window) {
        int start = window.start();
        int scanLimit = window.scanLimit();
        List<StudySessionRepository.Candidate> values = new ArrayList<>(
                repository.candidates(generation.generationId(), start, scanLimit));
        if (values.size() < scanLimit && start > 0) {
            values.addAll(repository.candidates(generation.generationId(), 0, scanLimit - values.size()));
        }
        return List.copyOf(values);
    }

    private void issueReplay(StudySessionRepository.Session session, StudySessionRepository.Session source, Instant now) {
        if (source == null) throw new InvalidRequestException();
        List<StudySessionRepository.Presentation> originals = repository.presentations(session.accountId(),
                source.sessionId(), 0, Math.min(BoundedCandidatePlanner.PRESENTATION_LIMIT, session.budget()));
        int ordinal = 0;
        for (StudySessionRepository.Presentation original : originals) {
            repository.copyPresentation(session.accountId(), source.sessionId(), session.sessionId(), session.deckId(),
                    session.generationId(), original, UUID.randomUUID(), ordinal++, nonce(), now,
                    now.plus(SESSION_LIFETIME));
        }
        repository.updateSessionBatch(session, originals.isEmpty() ? "EMPTY" : "ACTIVE", originals.size(), 0,
                originals.size(), originals.size(), false);
    }

    private void insert(StudySessionRepository.Session session, StudySessionRepository.Candidate candidate,
                        int ordinal, Instant now) {
        List<JsonNode> bindingRows = repository.bindings(session.deckId(), candidate.exerciseId(),
                candidate.exerciseRevisionId());
        ArrayNode bindings = JsonNodeFactory.instance.arrayNode();
        bindingRows.forEach(binding -> bindings.add(binding.deepCopy()));
        ObjectNode prompt = resolvePrompt(session.deckId(), candidate.prompt());
        ArrayNode options = JsonNodeFactory.instance.arrayNode();
        if (candidate.type().equals("SINGLE_CHOICE")) {
            bindingRows.stream().filter(binding -> binding.path("role").textValue().equals("OPTION"))
                    .forEach(binding -> options.addObject().put("optionId", binding.path("bindingId").textValue())
                            .put("text", resolveBindingText(session.deckId(), binding)));
        }
        repository.insertPresentation(session.accountId(), session.sessionId(), session.deckId(),
                session.generationId(), candidate, UUID.randomUUID(), ordinal, nonce(), prompt, options, bindings,
                now, now.plus(SESSION_LIFETIME));
    }

    private ObjectNode resolvePrompt(UUID deck, JsonNode spec) {
        if (spec.path("kind").textValue().equals("CUSTOM_TEXT")) {
            return JsonNodeFactory.instance.objectNode().put("kind", "TEXT").put("text", spec.path("text").textValue());
        }
        String text = resolveNodeText(deck, UUID.fromString(spec.path("memberKey").textValue()),
                UUID.fromString(spec.path("itemRevisionId").textValue()),
                UUID.fromString(spec.path("nodeId").textValue()));
        return JsonNodeFactory.instance.objectNode().put("kind", "TEXT").put("text", text);
    }

    private String resolveBindingText(UUID deck, JsonNode binding) {
        JsonNode ids = binding.path("nodeIds");
        if (ids.isArray() && !ids.isEmpty()) {
            return resolveNodeText(deck, UUID.fromString(binding.path("memberKey").textValue()),
                    UUID.fromString(binding.path("itemRevisionId").textValue()),
                    UUID.fromString(ids.get(0).textValue()));
        }
        return binding.path("display").path("text").asText("");
    }

    private String resolveNodeText(UUID deck, UUID member, UUID revision, UUID nodeId) {
        StudySessionRepository.Material material = repository.material(deck, member, revision)
                .orElseThrow(IllegalStateException::new);
        NativeSnapshotDecoder decoder = new NativeSnapshotDecoder(new ObjectRef(material.scopeId(), material.contentRootId()));
        while (!decoder.isComplete()) nativeBatches.readNext(decoder);
        ArrayDeque<JsonNode> pending = new ArrayDeque<>();
        pending.add(decoder.snapshot().document().toJson().path("root"));
        while (!pending.isEmpty()) {
            JsonNode node = pending.removeLast();
            if (node.path("id").textValue().equals(nodeId.toString())) return textContent(node).strip();
            node.path("content").forEach(pending::add);
        }
        throw new IllegalStateException("Pinned node is missing");
    }

    private static String textContent(JsonNode root) {
        StringBuilder result = new StringBuilder();
        ArrayDeque<JsonNode> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            JsonNode node = pending.removeFirst();
            if (node.path("type").textValue().equals("text") && node.path("attrs").path("text").isTextual()) {
                if (!result.isEmpty()) result.append(' ');
                result.append(node.path("attrs").path("text").textValue());
            }
            node.path("content").forEach(pending::addLast);
        }
        return result.toString();
    }

    private ObjectNode response(StudySessionRepository.Session session) {
        if (session.status().equals("PREPARING")) {
            return JsonNodeFactory.instance.objectNode().put("sessionId", session.sessionId().toString())
                    .put("mode", session.mode().name()).put("status", "PREPARING")
                    .put("statusUrl", "/api/decks/" + session.deckId() + "/study-sessions/" + session.sessionId());
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("sessionId", session.sessionId().toString())
                .put("deckId", session.deckId().toString()).put("mode", session.mode().name())
                .put("status", session.status()).put("timezone", session.timezone())
                .put("localStudyDate", session.localStudyDate().toString())
                .put("deckRevisionId", session.deckRevisionId().toString())
                .put("exerciseGenerationId", session.generationId().toString())
                .put("selectionPolicyVersion", session.policyVersion())
                .put("seed", Long.toUnsignedString(session.seed())).put("expiresAt", session.expiresAt().toString());
        result.set("reducer", JsonNodeFactory.instance.objectNode().put("id", session.reducerId())
                .put("version", session.reducerVersion()).put("configId", session.configId().toString())
                .put("configHash", session.configHash()));
        if (session.status().equals("ACTIVE") && session.issuedCount() < session.budget()) {
            result.put("nextCursor", cursor(session));
        } else result.putNull("nextCursor");
        ArrayNode values = result.putArray("presentations");
        repository.presentations(session.accountId(), session.sessionId(), session.batchStart(),
                Math.max(1, session.batchSize())).stream().limit(session.batchSize())
                .forEach(row -> values.add(presentation(row)));
        return result;
    }

    private static ObjectNode presentation(StudySessionRepository.Presentation row) {
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("presentationId", row.presentationId().toString())
                .put("nonce", row.nonce()).put("ordinal", row.ordinal())
                .put("exerciseRevisionId", row.exerciseRevisionId().toString()).put("type", row.type())
                .put("objectiveId", row.objectiveId().toString())
                .put("objectiveRevisionId", row.objectiveRevisionId().toString())
                .put("learningEpoch", Long.toString(row.learningEpoch()));
        result.set("prompt", row.prompt().deepCopy());
        result.set("options", row.options().deepCopy());
        result.set("bindings", row.bindings().deepCopy());
        result.set("evaluator", row.evaluator().deepCopy());
        return result;
    }

    private void requireCurrent(StudySessionRepository.Session session, Instant now) {
        if (!session.expiresAt().isAfter(now)) throw new StudySessionExpiredException();
    }

    private StudySessionRepository.DeckHead own(UUID actor, UUID deck) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deck, "deckId");
        return repository.deck(actor, deck).orElseThrow(ResourceNotFoundException::new);
    }

    private static String timezone(String value) {
        try { return ZoneId.of(value == null ? "UTC" : value).getId(); }
        catch (RuntimeException exception) { return "UTC"; }
    }

    private static long randomSeed() {
        UUID value = UUID.randomUUID();
        return value.getMostSignificantBits() ^ value.getLeastSignificantBits();
    }

    private static String nonce() {
        UUID value = UUID.randomUUID();
        byte[] bytes = ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String cursor(StudySessionRepository.Session session) {
        String value = session.sessionId() + ":" + session.issuedCount() + ":" + session.rowVersion();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.US_ASCII));
    }

    public record StartResult(JsonNode body, boolean replayed, boolean preparing) {
        public StartResult { body = body.deepCopy(); }
        @Override public JsonNode body() { return body.deepCopy(); }
    }
}
