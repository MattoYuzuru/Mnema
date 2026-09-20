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
import java.util.Base64;
import java.util.List;
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
                issueNext(inserted, generation, source, now, false);
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
                issueNext(session, generation, source, now, false);
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
        Instant now = repository.now();
        requireCurrent(session, now);
        if (session.status().equals("ACTIVE") && repository.pendingPresentations(actor, sessionId,
                session.batchStart(), Math.max(1, session.batchSize())).isEmpty()) {
            StudySessionRepository.Generation generation = repository.generationForUpdate(deckId,
                    session.generationId()).orElseThrow(IllegalStateException::new);
            StudySessionRepository.Session source = session.sourceSessionId() == null ? null
                    : repository.replaySource(actor, deckId, session.sourceSessionId(), session.localStudyDate())
                    .orElseThrow(InvalidRequestException::new);
            issueNext(session, generation, source, now, false);
            session = repository.session(actor, deckId, sessionId).orElseThrow();
        }
        return response(session);
    }

    @Transactional(readOnly = true, timeout = 10)
    public ObjectNode replaySources(UUID actor, UUID deckId, String trustedTimezone) {
        own(actor, deckId);
        Instant now = repository.now();
        LocalDate studyDate = now.atZone(ZoneId.of(timezone(trustedTimezone))).toLocalDate();
        ObjectNode response = JsonNodeFactory.instance.objectNode().put("asOf", now.toString())
                .put("localStudyDate", studyDate.toString());
        var items = response.putArray("items");
        repository.replaySources(actor, deckId, studyDate, 20).forEach(source -> items.addObject()
                .put("sessionId", source.sessionId().toString()).put("completedAt", source.completedAt().toString())
                .put("presentationCount", source.presentationCount()));
        return response;
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

    private void issueNext(StudySessionRepository.Session session, StudySessionRepository.Generation generation,
                           StudySessionRepository.Session source, Instant now, boolean refill) {
        if (session.mode() == StudySessionCommand.Mode.REPLAY) {
            issueReplay(session, source, now, refill);
            return;
        }
        if (generation.candidateCount() == 0) {
            if (refill) repository.complete(session, now);
            else repository.updateSessionBatch(session, "EMPTY", 0, 0, 0, 0, true);
            return;
        }
        int remaining = session.budget() - session.issuedCount();
        if (remaining <= 0) { repository.complete(session, now); return; }
        int target = Math.min(BoundedCandidatePlanner.PRESENTATION_LIMIT, remaining);
        int start = session.issuedCount() == 0 ? Math.floorMod(session.seed(), generation.candidateCount())
                : session.scanCursor();
        List<StudySessionRepository.Candidate> candidates = repository.eligibleCandidates(session, start,
                target * BoundedCandidatePlanner.SCAN_MULTIPLIER, now);
        int batch = 0;
        int cursor = session.scanCursor();
        for (StudySessionRepository.Candidate candidate : candidates) {
            cursor = candidate.ordinal() + 1;
            if (cursor >= generation.candidateCount()) cursor = 0;
            insert(session, candidate, session.issuedCount() + batch++, now);
            if (batch == target) break;
        }
        if (batch == 0) {
            if (refill) repository.complete(session, now);
            else repository.updateSessionBatch(session, "EMPTY", 0, 0, 0, cursor, true);
            return;
        }
        boolean exhausted = batch < target;
        repository.updateSessionBatch(session, "ACTIVE", session.issuedCount() + batch,
                session.issuedCount(), batch, cursor, exhausted);
    }

    private void issueReplay(StudySessionRepository.Session session, StudySessionRepository.Session source, Instant now,
                             boolean refill) {
        if (source == null) throw new InvalidRequestException();
        int remaining = session.budget() - session.issuedCount();
        if (remaining <= 0) { repository.complete(session, now); return; }
        int target = Math.min(BoundedCandidatePlanner.PRESENTATION_LIMIT, remaining);
        List<StudySessionRepository.Presentation> originals = repository.presentations(session.accountId(),
                source.sessionId(), session.issuedCount(), target);
        int ordinal = session.issuedCount();
        for (StudySessionRepository.Presentation original : originals) {
            repository.copyPresentation(session.accountId(), source.sessionId(), session.sessionId(), session.deckId(),
                    session.generationId(), original, UUID.randomUUID(), ordinal++, nonce(), now,
                    now.plus(SESSION_LIFETIME));
        }
        if (originals.isEmpty()) {
            if (refill) repository.complete(session, now);
            else repository.updateSessionBatch(session, "EMPTY", 0, 0, 0, 0, true);
            return;
        }
        repository.updateSessionBatch(session, "ACTIVE", session.issuedCount() + originals.size(),
                session.issuedCount(), originals.size(), session.issuedCount() + originals.size(),
                originals.size() < target);
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
        long epoch = session.mode() == StudySessionCommand.Mode.SCHEDULED
                ? repository.ensureState(session.accountId(), session.deckId(), candidate.objectiveId(),
                        session.configId(), now)
                : repository.stateEpoch(session.accountId(), session.deckId(), candidate.objectiveId()).orElse(0L);
        UUID presentation = UUID.randomUUID();
        repository.insertPresentation(session.accountId(), session.sessionId(), session.deckId(),
                session.generationId(), candidate, presentation, ordinal, nonce(), epoch, prompt, options, bindings,
                now, now.plus(SESSION_LIFETIME));
        if (session.mode() == StudySessionCommand.Mode.SCHEDULED) {
            repository.insertExposure(session.accountId(), session.sessionId(), presentation, session.deckId(),
                    candidate.objectiveId(), epoch, now);
        }
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
        repository.pendingPresentations(session.accountId(), session.sessionId(), session.batchStart(),
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
                .put("learningEpoch", Long.toString(row.learningEpoch()))
                .put("reference", reference(row.answerContract()));
        result.set("prompt", row.prompt().deepCopy());
        result.set("options", row.options().deepCopy());
        result.set("bindings", row.bindings().deepCopy());
        result.set("evaluator", row.evaluator().deepCopy());
        return result;
    }

    private static String reference(JsonNode answerContract) {
        JsonNode accepted = answerContract.path("accepted");
        if (!accepted.isArray() || accepted.isEmpty() || !accepted.get(0).isTextual()) {
            throw new IllegalStateException("Pinned answer contract has no reference answer");
        }
        return accepted.get(0).textValue();
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
