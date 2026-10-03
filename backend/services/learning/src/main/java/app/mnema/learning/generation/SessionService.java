package app.mnema.learning.generation;

import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.EventDraft;
import app.mnema.learning.generation.Rows.Revision;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.Rows.Source;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.usage.AdmissionPricing;
import app.mnema.learning.usage.Reservation;
import app.mnema.learning.usage.ReservationScope;
import app.mnema.learning.usage.SpecNotSupportedException;
import app.mnema.learning.usage.UsageLedger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The commands and reads of {@code /api/decks/{deckId}/generation-sessions} that exist in this task: create, cancel, list,
 * read, events and artifact. The evaluation order of {@code contracts/generation/http.json} is: authentication and
 * ownership (404), receipt replay, request validation (400), preconditions, state (409, 422) and usage (409, last, inside
 * the admission transaction, which then changes nothing when it refuses).
 */
@Service
class SessionService {
    private static final String SCOPE = "generation.sessions";

    /** A command's answer; {@code replayed} is true when a stored receipt answered it. */
    record Written(JsonNode body, boolean replayed) { }

    /** One page of the newest-first session list. */
    record Page(List<ObjectNode> items, String nextCursor) { }

    private final GenerationRepository repository;
    private final StepRepository steps;
    private final SessionLifecycle lifecycle;
    private final SessionViews views;
    private final UsageLedger ledger;
    private final AdmissionPricing pricing;
    private final CommandReceiptService receipts;
    private final GenerationSettings settings;
    private final ContextRepository context;
    private final ObjectProvider<StepDispatcher> dispatcher;
    private final TransactionTemplate reading;
    private final TransactionTemplate quoting;

    SessionService(GenerationRepository repository, StepRepository steps, SessionLifecycle lifecycle, SessionViews views,
                   UsageLedger ledger, AdmissionPricing pricing, CommandReceiptService receipts, GenerationSettings settings,
                   ContextRepository context, ObjectProvider<StepDispatcher> dispatcher, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.steps = steps;
        this.lifecycle = lifecycle;
        this.views = views;
        this.ledger = ledger;
        this.pricing = pricing;
        this.receipts = receipts;
        this.settings = settings;
        this.context = context;
        this.dispatcher = dispatcher;
        this.reading = new TransactionTemplate(transactions);
        reading.setReadOnly(true);
        this.quoting = new TransactionTemplate(transactions);
    }

    // --------------------------------------------------------------------- create

    /**
     * {@code createSession}: validates the spec, reserves the initial batch and creates the session, its artifacts and their
     * steps in one transaction. The raw bytes were read by the controller before this call, so no connection waits on a
     * slow client.
     */
    Written create(UUID owner, UUID deckId, byte[] raw) {
        if (!repository.deckOwned(owner, deckId)) throw new ResourceNotFoundException();
        JsonNode body = Commands.read(raw);
        Commands.fields(body, Set.of("commandId", "spec"), Set.of());
        UUID commandId = Commands.commandId(body);
        JsonNode spec = body.get("spec");
        if (!spec.isObject()) throw new InvalidRequestException();
        ObjectNode envelope = Json.object().put("deckId", deckId.toString()).put("commandId", commandId.toString());
        envelope.set("spec", spec);
        CommandIdentity identity = new CommandIdentity(commandId, owner, SCOPE, "session.create");
        Optional<JsonNode> replay = receipts.replay(identity, envelope);
        if (replay.isPresent()) return new Written(replay.get(), true);

        // Shape, limits, ownership of the sources (404) and capabilities (409): the same interpretation as the estimate.
        AdmissionPricing.Hold hold = pricing.hold(owner, deckId, spec);
        String kind = spec.path("kind").stringValue("");
        boolean[] applied = {false};
        JsonNode acknowledgement;
        switch (kind) {
            case "MATERIALS" -> {
                MaterialsSpec parsed = MaterialsSpec.read(spec);
                acknowledgement = receipts.execute(identity, envelope, () -> {
                    applied[0] = true;
                    return admit(owner, deckId, spec, parsed, hold);
                });
            }
            case "EXERCISES" -> {
                ExercisesSpec parsed = ExercisesSpec.read(spec);
                acknowledgement = receipts.execute(identity, envelope, () -> {
                    applied[0] = true;
                    return admitExercises(owner, deckId, spec, parsed, hold);
                });
            }
            default -> throw new SpecNotSupportedException(kind);
        }
        return new Written(acknowledgement, !applied[0]);
    }

    /** The 422 of a full house: the owner already has the most active sessions, named in the problem. */
    private void requireRoomForASession(UUID owner) {
        List<UUID> active = repository.activeSessionIds(owner);
        if (active.size() >= settings.maxActiveSessions()) {
            throw new ResourceLimitExceededException(ProblemExtension.builder().put("limit", "ACTIVE_SESSIONS")
                    .put("limits", Map.of("maxActiveSessions", settings.maxActiveSessions()))
                    .put("activeSessionIds", active.stream().map(UUID::toString).toList()).build());
        }
    }

    /**
     * Admits an {@code EXERCISES} session ({@code contracts/generation/exercises}, decision 14): the targets are pinned as
     * {@code SOURCE} items; the resolved quantity of the spec is spread over the targets in their processing order (uncovered
     * materials first with {@code UNCOVERED_FIRST}, the request order with {@code BALANCED}); one QUEUED artifact exists per
     * exercise and one {@code TEXT_DRAFT} step per target fills them. A step's share of the hold is the difference of the
     * rounded-up price of the exercises up to it and before it, so the shares add up to the reservation.
     */
    private JsonNode admitExercises(UUID owner, UUID deckId, JsonNode spec, ExercisesSpec parsed, AdmissionPricing.Hold hold) {
        repository.lockAdmission(owner);
        requireRoomForASession(owner);
        UUID sessionId = UUID.randomUUID();
        hold.requireFits();
        Reservation reservation = ledger.reserve(owner, ReservationScope.SESSION, sessionId, null, Math.max(1, hold.credits()));
        Session session = new Session(sessionId, owner, deckId, "EXERCISES", "RUNNING", null, spec, reservation.reservationId(),
                0, 0, null, null, null);
        repository.insertSession(session, settings.sessionRetention());
        for (Source target : parsed.targets()) repository.insertSource(sessionId, owner, target);

        List<Source> order = new ArrayList<>(parsed.targets());
        if (parsed.priority().equals(ExercisesSpec.UNCOVERED_FIRST)) {
            Map<UUID, Integer> covered = context.exerciseCounts(deckId, order.stream().map(Source::memberKey).toList());
            // a stable sort: materials with the same number of exercises keep the order of the request
            order.sort(java.util.Comparator.comparingInt(target -> covered.getOrDefault(target.memberKey(), 0)));
        }
        int total = Math.max(hold.exercises(), order.size());
        int[] counts = ExercisesSpec.spread(total, order.size());
        ObjectNode queued = Json.object();
        for (String state : GenerationRepository.ARTIFACT_STATES) queued.put(state, state.equals("QUEUED") ? total : 0);
        ObjectNode started = Json.object().put("state", "RUNNING").put("rowVersion", "1");
        started.set("artifactCounts", queued);
        List<EventDraft> events = new ArrayList<>();
        events.add(new EventDraft("SESSION_STATE", null, started));
        int ordinal = 0;
        int planned = 0;
        for (int index = 0; index < order.size(); index++) {
            Source target = order.get(index);
            int count = counts[index];
            ArrayNode artifactIds = Json.array();
            for (int exercise = 0; exercise < count; exercise++) {
                UUID artifactId = UUID.randomUUID();
                artifactIds.add(artifactId.toString());
                repository.insertArtifact(artifactId, sessionId, owner, "EXERCISE", ordinal++, Json.array().add(itemRef(target)));
                events.add(new EventDraft("ARTIFACT_STATE", artifactId, queuedPayload()));
            }
            int share = pricing.exerciseCredits(planned + count) - pricing.exerciseCredits(planned);
            planned += count;
            ObjectNode input = Json.object().put("operation", ExerciseDraftExecutor.OPERATION)
                    .put("memberKey", target.memberKey().toString()).put("itemRevisionId", target.itemRevisionId().toString())
                    .put("count", count).put("credits", share);
            input.set("artifactIds", artifactIds);
            steps.insert(UUID.randomUUID(), sessionId, UUID.fromString(artifactIds.get(0).stringValue("")), owner, "TEXT_DRAFT", "TEXT",
                    input, "draft:" + artifactIds.get(0).stringValue("") + ":1");
        }
        long[] allocated = repository.update(sessionId, "RUNNING", null, true, events.size(), settings.sessionRetention());
        repository.insertEvents(sessionId, allocated[0], events);
        wakeAfterCommit();
        return views.detail(repository.session(sessionId).orElseThrow());
    }

    private JsonNode admit(UUID owner, UUID deckId, JsonNode spec, MaterialsSpec parsed, AdmissionPricing.Hold hold) {
        repository.lockAdmission(owner);
        requireRoomForASession(owner);
        UUID sessionId = UUID.randomUUID();
        // Usage is strictly last: a full cap or a hold too small for one material, then the reservation itself: a refusal rolls this whole transaction back, so nothing has changed (contract step 6).
        hold.requireFits();
        Reservation reservation = ledger.reserve(owner, ReservationScope.SESSION, sessionId, null, Math.max(1, hold.credits()));
        Session session = new Session(sessionId, owner, deckId, "MATERIALS", "RUNNING", null, spec, reservation.reservationId(),
                0, 0, null, null, null);
        repository.insertSession(session, settings.sessionRetention());
        for (Source source : parsed.sources()) {
            repository.insertSource(sessionId, owner, source);
            // the text is pinned with the row version: a note that moved since the check above is a 409, not a silent re-pin
            if (source.type().equals("NOTE") && !repository.snapshotNote(sessionId, owner, source.noteId(), source.noteRowVersion())) {
                throw new SourceUnavailableException(List.of(new SourceUnavailableException.Unavailable("NOTE", source.noteId())));
            }
        }

        List<Source> notes = parsed.notes();
        List<EventDraft> events = new ArrayList<>();
        int count = parsed.artifactCount();
        ObjectNode counts = Json.object();
        for (String state : GenerationRepository.ARTIFACT_STATES) counts.put(state, state.equals("QUEUED") ? count : 0);
        ObjectNode started = Json.object().put("state", "RUNNING").put("rowVersion", "1");
        started.set("artifactCounts", counts);
        events.add(new EventDraft("SESSION_STATE", null, started));
        for (int ordinal = 0; ordinal < count; ordinal++) {
            UUID artifactId = UUID.randomUUID();
            ArrayNode refs = Json.array();
            if (parsed.mergeNotes() || notes.isEmpty()) {
                notes.forEach(note -> refs.add(noteRef(note)));
            } else {
                refs.add(noteRef(notes.get(ordinal)));
            }
            parsed.items().forEach(item -> refs.add(itemRef(item)));
            repository.insertArtifact(artifactId, sessionId, owner, ordinal, refs);
            // each material is charged and written at its own effective settings (the note's overrides over the session's)
            String effort = parsed.forArtifact(refs).workingEffort();
            String operation = AdmissionPricing.materialOperation(effort);
            int credits = pricing.credits(operation);
            ObjectNode input = Json.object().put("effort", effort).put("operation", operation).put("credits", credits);
            steps.insert(UUID.randomUUID(), sessionId, artifactId, owner, "TEXT_DRAFT", "TEXT", input, "draft:" + artifactId + ":1");
            events.add(new EventDraft("ARTIFACT_STATE", artifactId, queuedPayload()));
        }
        long[] allocated = repository.update(sessionId, "RUNNING", null, true, events.size(), settings.sessionRetention());
        repository.insertEvents(sessionId, allocated[0], events);
        wakeAfterCommit();
        return views.detail(repository.session(sessionId).orElseThrow());
    }

    private static ObjectNode noteRef(Source note) {
        return Json.object().put("type", "NOTE").put("noteId", note.noteId().toString())
                .put("noteRowVersion", Long.toString(note.noteRowVersion()));
    }

    private static ObjectNode itemRef(Source item) {
        return Json.object().put("type", "ITEM").put("memberKey", item.memberKey().toString())
                .put("itemRevisionId", item.itemRevisionId().toString());
    }

    private static ObjectNode queuedPayload() {
        ObjectNode payload = Json.object().put("state", "QUEUED").put("artifactVersion", "0");
        payload.putNull("currentRevisionId");
        payload.putNull("errorCode");
        payload.putNull("repinStatus");
        return payload;
    }

    private void wakeAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatcher.ifAvailable(StepDispatcher::wake);
            }
        });
    }

    // --------------------------------------------------------------------- cancel

    /** {@code cancelSession}: state-idempotent, no precondition; a CLOSED or EXPIRED session is a 409. */
    Written cancel(UUID owner, UUID deckId, UUID sessionId, byte[] raw) {
        if (repository.session(owner, deckId, sessionId).isEmpty()) throw new ResourceNotFoundException();
        JsonNode body = Commands.read(raw);
        Commands.fields(body, Set.of("commandId"), Set.of());
        UUID commandId = Commands.commandId(body);
        ObjectNode envelope = Json.object().put("deckId", deckId.toString()).put("sessionId", sessionId.toString())
                .put("commandId", commandId.toString());
        CommandIdentity identity = new CommandIdentity(commandId, owner, SCOPE, "session.cancel");
        Optional<JsonNode> replay = receipts.replay(identity, envelope);
        if (replay.isPresent()) return new Written(replay.get(), true);
        boolean[] applied = {false};
        JsonNode acknowledgement = receipts.execute(identity, envelope, () -> {
            applied[0] = true;
            return doCancel(sessionId);
        });
        return new Written(acknowledgement, !applied[0]);
    }

    private JsonNode doCancel(UUID sessionId) {
        SessionLifecycle.Tx tx = lifecycle.lock(sessionId);
        if (tx == null) throw new ResourceNotFoundException();
        switch (tx.state) {
            case "CANCELLED" -> { }
            case "CLOSED", "EXPIRED" -> throw new GenerationStateConflictException(GenerationStateConflictException.Reason.ILLEGAL_STATE);
            default -> {
                lifecycle.cancel(tx);
                lifecycle.flush(tx);
            }
        }
        return views.detail(repository.session(sessionId).orElseThrow());
    }

    // ---------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    ObjectNode read(UUID owner, UUID deckId, UUID sessionId) {
        Session session = repository.session(owner, deckId, sessionId).orElseThrow(ResourceNotFoundException::new);
        return views.detail(session);
    }

    /** {@code listSessions} (one deck) and {@code listActiveSessions} (the account): newest first by activity. */
    @Transactional(readOnly = true)
    Page list(UUID owner, UUID deckId, boolean activeOnly, int limit, String cursor) {
        if (deckId != null && !repository.deckOwned(owner, deckId)) throw new ResourceNotFoundException();
        ListCursor position = ListCursor.decode(cursor);
        List<Session> rows = repository.page(owner, deckId, activeOnly, position == null ? null : position.lastActivityAt(),
                position == null ? null : position.sessionId(), limit + 1);
        boolean more = rows.size() > limit;
        List<Session> page = more ? rows.subList(0, limit) : rows;
        String next = more ? new ListCursor(page.getLast().lastActivityAt(), page.getLast().sessionId()).encode() : null;
        return new Page(views.summaries(page), next);
    }

    /**
     * {@code listEvents}: one snapshot for the events, the session and the active steps, so a poll can stop on the state
     * it returns. The cursor never skips an event: numbers are allocated under the session lock, in commit order.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    ObjectNode events(UUID owner, UUID deckId, UUID sessionId, long after, int limit) {
        Session session = repository.session(owner, deckId, sessionId).orElseThrow(ResourceNotFoundException::new);
        return views.eventEnvelope(session, after, repository.events(sessionId, after, limit), steps.activeSteps(sessionId));
    }

    /**
     * {@code getArtifact}: a read-only transaction. The proposal of an exercise also shows the text of the material nodes it quotes
     * ({@code display}); reading an immutable storage snapshot takes {@code FOR KEY SHARE} row locks, which PostgreSQL refuses in a
     * read-only transaction, so that part runs in a second, ordinary transaction. Nothing is written by either.
     */
    ObjectNode artifact(UUID owner, UUID deckId, UUID sessionId, UUID artifactId, UUID revisionId) {
        Loaded loaded = Objects.requireNonNull(reading.execute(status -> {
            Session session = repository.session(owner, deckId, sessionId).orElseThrow(ResourceNotFoundException::new);
            Artifact artifact = repository.artifact(sessionId, artifactId).orElseThrow(ResourceNotFoundException::new);
            UUID wanted = revisionId == null ? artifact.currentRevisionId() : revisionId;
            Revision revision = wanted == null ? null : repository.revision(artifactId, wanted).orElseThrow(ResourceNotFoundException::new);
            return new Loaded(views.artifactDetail(session, artifact, revision), session, artifact, revision);
        }));
        if (loaded.revision() != null && loaded.artifact().targetKind().equals("EXERCISE")) {
            loaded.detail().set("display", Objects.requireNonNull(quoting.execute(status -> views.display(loaded.session(), loaded.revision()))));
        }
        return loaded.detail();
    }

    private record Loaded(ObjectNode detail, Session session, Artifact artifact, Revision revision) { }
}
