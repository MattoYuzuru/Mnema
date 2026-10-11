package app.mnema.learning.study.session;

import app.mnema.learning.capability.LearningCapabilities;
import app.mnema.learning.catalog.content.NativeNodeIndex;
import app.mnema.learning.catalog.content.storage.NativeStorageBatches;
import app.mnema.learning.catalog.exercise.AnswerKey;
import app.mnema.learning.catalog.exercise.ExerciseNewMarks;
import app.mnema.learning.catalog.exercise.ExerciseType;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class StudySessionService {
    private static final Duration SESSION_LIFETIME = Duration.ofHours(24);

    private final StudySessionRepository repository;
    private final CommandReceiptService receipts;
    private final NativeStorageBatches nativeBatches;
    private final ExerciseManifestReader manifest;
    private final LearningCapabilities capabilities;
    private final ExerciseNewMarks newMarks;
    // MATCH order must not be reproducible from identifiers a client holds.
    private final RandomGenerator shuffle = new SecureRandom();

    public StudySessionService(StudySessionRepository repository, CommandReceiptService receipts,
                               ImmutableStorage storage, LearningCapabilities capabilities, ExerciseNewMarks newMarks) {
        this.repository = repository;
        this.receipts = receipts;
        this.nativeBatches = new NativeStorageBatches(storage);
        this.manifest = new ExerciseManifestReader(storage);
        this.capabilities = capabilities;
        this.newMarks = newMarks;
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
            return new StartResult(response, true, "PREPARING".equals(response.path("status").stringValue(null)));
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
        return new StartResult(response, !applied[0], "PREPARING".equals(response.path("status").stringValue(null)));
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

    /** Records a deliberate accessibility accommodation before disclosing pinned transcript text. */
    @Transactional(timeout = 10)
    public ObjectNode revealTranscript(UUID actor, UUID deckId, UUID sessionId, UUID presentationId, String nonce) {
        StudySessionRepository.Presentation row = pendingForReveal(actor, deckId, sessionId, presentationId, nonce);
        if (!LearnerContent.hasTranscript(row.content())) throw new InvalidRequestException();
        repository.recordAccommodation(actor, sessionId, presentationId, repository.now());
        return JsonNodeFactory.instance.objectNode().put("presentationId", presentationId.toString())
                .put("transcriptRevealed", true).set("content", LearnerContent.view(row.content(), true));
    }

    /**
     * Records a first-letter hint on one blank before disclosing it. The server is the only authority on
     * hint use: evidence later reads these rows, never a client claim. A repeat returns the same letter.
     */
    @Transactional(timeout = 10)
    public ObjectNode revealHint(UUID actor, UUID deckId, UUID sessionId, UUID presentationId,
                                 StudyHintCommand command) {
        StudySessionRepository.Presentation row = pendingForReveal(actor, deckId, sessionId, presentationId,
                command.nonce());
        if (!row.type().equals(ExerciseType.CLOZE.name())) throw new InvalidRequestException();
        String letter = hintableBlanks(row).get(command.blankId());
        if (letter == null) throw new InvalidRequestException();
        repository.recordHint(actor, sessionId, presentationId, command.blankId(), repository.now());
        return JsonNodeFactory.instance.objectNode().put("presentationId", presentationId.toString())
                .put("blankId", command.blankId().toString()).put("firstLetter", letter);
    }

    private StudySessionRepository.Presentation pendingForReveal(UUID actor, UUID deckId, UUID sessionId,
                                                                 UUID presentationId, String nonce) {
        own(actor, deckId);
        StudySessionRepository.Session session = repository.sessionForUpdate(actor, deckId, sessionId)
                .orElseThrow(ResourceNotFoundException::new);
        Instant now = repository.now();
        requireCurrent(session, now);
        if (!session.status().equals("ACTIVE")) throw new ResourceNotFoundException();
        return repository.pendingForReveal(actor, deckId, sessionId, presentationId, nonce, now)
                .orElseThrow(ResourceNotFoundException::new);
    }

    /** Blank id to first letter, for blanks whose author enabled a first-letter hint. */
    private static Map<UUID, String> hintableBlanks(StudySessionRepository.Presentation row) {
        return LearnerContent.firstLetterHints(row.content(),
                (AnswerKey.Cloze) AnswerKey.parse(ExerciseType.CLOZE, row.answerKey()));
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

    /**
     * One bounded step of candidate preparation: the next manifest entries after {@code scanned_count} (an ordinal into the
     * pinned exercises root), their pinned revisions and ASSESSED bindings. Every entry must resolve; disabled revisions are
     * scanned but are not candidates.
     */
    private StudySessionRepository.Generation advancePreparation(StudySessionRepository.Session session, Instant now) {
        StudySessionRepository.Generation generation = repository.generationForUpdate(session.deckId(),
                session.generationId()).orElseThrow(IllegalStateException::new);
        if (!generation.status().equals("PREPARING")) return generation;
        List<StudySessionRepository.ManifestEntry> entries = manifest.entries(generation.scopeId(),
                generation.exercisesRootId(), generation.expectedExerciseCount(), generation.scannedCount(),
                BoundedCandidatePlanner.PREPARATION_LIMIT);
        List<StudySessionRepository.SourceExercise> sources = repository.sourceBatch(generation, entries);
        if (sources.size() != entries.size()) throw new IllegalStateException("Candidate source projection is inconsistent");
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
        repository.advanceGeneration(generation, scanned, enabled, ready, now);
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
            else repository.updateSessionBatch(session, "EMPTY", 0, 0, 0, 0, 0, true);
            return;
        }
        int remaining = session.budget() - session.issuedCount();
        if (remaining <= 0) { repository.complete(session, now); return; }
        int target = Math.min(BoundedCandidatePlanner.PRESENTATION_LIMIT, remaining);
        int start = session.issuedCount() == 0 ? Math.floorMod(session.seed(), generation.candidateCount())
                : session.scanCursor();
        List<StudySessionRepository.Candidate> candidates = repository.eligibleCandidates(session, start,
                target * BoundedCandidatePlanner.SCAN_MULTIPLIER, now,
                capabilities.aiAssessment().available());
        int batch = 0;
        int newObjectives = 0;
        // One decoded snapshot per pinned item revision for the whole batch.
        LearnerContent.TextSource texts = materialText(session.deckId());
        int scanSize = Math.min(generation.candidateCount(), target * BoundedCandidatePlanner.SCAN_MULTIPLIER);
        int cursor = (int) (((long) start + scanSize) % generation.candidateCount());
        // «Новое» is decided once, when the presentation is issued, and stored with it
        Set<UUID> fresh = newMarks.fresh(session.accountId(), session.deckId(),
                candidates.stream().map(StudySessionRepository.Candidate::exerciseId).toList());
        for (StudySessionRepository.Candidate candidate : candidates) {
            insert(session, candidate, session.issuedCount() + batch++, now, texts, fresh.contains(candidate.exerciseId()));
            // Only scheduled sessions have a new-objective budget; practice may include unseen material freely.
            if (session.mode() == StudySessionCommand.Mode.SCHEDULED && !candidate.introduced()) newObjectives++;
            if (batch == target) break;
        }
        if (batch == 0) {
            if (refill) repository.complete(session, now);
            else repository.updateSessionBatch(session, "EMPTY", 0, 0, 0, 0, cursor, true);
            return;
        }
        boolean exhausted = batch < target;
        repository.updateSessionBatch(session, "ACTIVE", session.issuedCount() + batch,
                session.issuedNewObjectives() + newObjectives, session.issuedCount(), batch, cursor, exhausted);
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
            else repository.updateSessionBatch(session, "EMPTY", 0, 0, 0, 0, 0, true);
            return;
        }
        repository.updateSessionBatch(session, "ACTIVE", session.issuedCount() + originals.size(),
                session.issuedNewObjectives(), session.issuedCount(), originals.size(),
                session.issuedCount() + originals.size(),
                originals.size() < target);
    }

    private void insert(StudySessionRepository.Session session, StudySessionRepository.Candidate candidate,
                        int ordinal, Instant now, LearnerContent.TextSource texts, boolean isNew) {
        ExerciseType type = ExerciseType.fromWire(candidate.type()).orElseThrow(IllegalStateException::new);
        UUID presentation = UUID.randomUUID();
        // Resolved and shuffled once: reads and replays return exactly what was persisted here.
        LearnerContent.Resolved learner = LearnerContent.issue(type, candidate.content(),
                AnswerKey.parse(type, candidate.answerKey()), shuffle, texts);
        long epoch = session.mode() == StudySessionCommand.Mode.SCHEDULED
                ? repository.ensureState(session.accountId(), session.deckId(), candidate.objectiveId(),
                        session.configId(), now)
                : repository.stateEpoch(session.accountId(), session.deckId(), candidate.objectiveId()).orElse(0L);
        repository.insertPresentation(session.accountId(), session.sessionId(), session.deckId(),
                session.generationId(), candidate, presentation, ordinal, nonce(), epoch, learner.content(),
                learner.reveal(), isNew, now, now.plus(SESSION_LIFETIME));
        if (session.mode() == StudySessionCommand.Mode.SCHEDULED) {
            repository.insertExposure(session.accountId(), session.sessionId(), presentation, session.deckId(),
                    candidate.objectiveId(), epoch, now);
        }
    }

    /** Quoted material text comes from the pinned item revision; each snapshot is read at most once. */
    private LearnerContent.TextSource materialText(UUID deck) {
        Map<List<UUID>, NativeNodeIndex> indexes = new HashMap<>();
        return material -> indexes.computeIfAbsent(List.of(material.memberKey(), material.itemRevisionId()), key -> {
            StudySessionRepository.Material pinned = repository.material(deck, material.memberKey(),
                    material.itemRevisionId()).orElseThrow(IllegalStateException::new);
            return NativeNodeIndex.load(nativeBatches, new ObjectRef(pinned.scopeId(), pinned.contentRootId()));
        }).text(material.nodeId()).orElseThrow(() -> new IllegalStateException("Pinned node is missing"));
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
                .put("seed", Long.toUnsignedString(session.seed())).put("issuedCount", session.issuedCount())
                .put("expiresAt", session.expiresAt().toString());
        result.putObject("budget").put("maxPresentations", session.budget())
                .put("maxNewObjectives", session.maxNewObjectives());
        result.set("reducer", JsonNodeFactory.instance.objectNode().put("id", session.reducerId())
                .put("version", session.reducerVersion()).put("configId", session.configId().toString())
                .put("configHash", session.configHash()));
        if (session.status().equals("ACTIVE") && session.issuedCount() < session.budget()) {
            result.put("nextCursor", cursor(session));
        } else result.putNull("nextCursor");
        ArrayNode values = result.putArray("presentations");
        // an answer that is being assessed by the model, or waits for the learner's own rating, keeps its presentation pending (the
        // terminal receipt is not written yet) and names its attempt, so that a reload resumes it instead of answering again
        Map<UUID, JsonNode> assessments = repository.pendingAssessments(session.accountId(), session.sessionId());
        repository.pendingPresentations(session.accountId(), session.sessionId(), session.batchStart(),
                Math.max(1, session.batchSize())).stream().limit(session.batchSize()).forEach(row -> {
                    ObjectNode projected = presentation(row);
                    JsonNode assessment = assessments.get(row.presentationId());
                    if (assessment != null) projected.set("assessment", assessment.deepCopy());
                    values.add(projected);
                });
        return result;
    }

    /** The only learner-facing projection: no key, no accepted text, no titles, no unrevealed transcripts. */
    private static ObjectNode presentation(StudySessionRepository.Presentation row) {
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("presentationId", row.presentationId().toString())
                .put("nonce", row.nonce()).put("ordinal", row.ordinal())
                .put("exerciseRevisionId", row.exerciseRevisionId().toString()).put("type", row.type())
                .put("objectiveId", row.objectiveId().toString())
                .put("objectiveRevisionId", row.objectiveRevisionId().toString())
                .put("learningEpoch", Long.toString(row.learningEpoch())).put("isNew", row.isNew());
        result.set("content", LearnerContent.view(row.content(), row.transcriptRevealed()));
        result.put("transcriptRevealed", row.transcriptRevealed());
        ArrayNode hints = result.putArray("hints");
        if (!row.hintedBlanks().isEmpty()) {
            Map<UUID, String> letters = hintableBlanks(row);
            row.hintedBlanks().forEach(blank -> hints.addObject().put("blankId", blank.toString())
                    .put("firstLetter", letters.get(blank)));
        }
        result.set("evaluator", JsonNodeFactory.instance.objectNode().put("id", row.evaluator().path("id").stringValue(null))
                .put("version", row.evaluator().path("version").stringValue(null)));
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
