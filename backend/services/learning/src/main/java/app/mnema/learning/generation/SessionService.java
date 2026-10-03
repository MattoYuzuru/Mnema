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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final ObjectProvider<StepDispatcher> dispatcher;

    SessionService(GenerationRepository repository, StepRepository steps, SessionLifecycle lifecycle, SessionViews views,
                   UsageLedger ledger, AdmissionPricing pricing, CommandReceiptService receipts, GenerationSettings settings,
                   ObjectProvider<StepDispatcher> dispatcher) {
        this.repository = repository;
        this.steps = steps;
        this.lifecycle = lifecycle;
        this.views = views;
        this.ledger = ledger;
        this.pricing = pricing;
        this.receipts = receipts;
        this.settings = settings;
        this.dispatcher = dispatcher;
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
        if (!kind.equals("MATERIALS")) throw new SpecNotSupportedException(kind);
        MaterialsSpec parsed = MaterialsSpec.read(spec);

        boolean[] applied = {false};
        JsonNode acknowledgement = receipts.execute(identity, envelope, () -> {
            applied[0] = true;
            return admit(owner, deckId, spec, parsed, hold);
        });
        return new Written(acknowledgement, !applied[0]);
    }

    private JsonNode admit(UUID owner, UUID deckId, JsonNode spec, MaterialsSpec parsed, AdmissionPricing.Hold hold) {
        repository.lockAdmission(owner);
        List<UUID> active = repository.activeSessionIds(owner);
        if (active.size() >= settings.maxActiveSessions()) {
            throw new ResourceLimitExceededException(ProblemExtension.builder().put("limit", "ACTIVE_SESSIONS")
                    .put("limits", Map.of("maxActiveSessions", settings.maxActiveSessions()))
                    .put("activeSessionIds", active.stream().map(UUID::toString).toList()).build());
        }
        UUID sessionId = UUID.randomUUID();
        // Usage is strictly last: a full cap or a hold too small for one material, then the reservation itself: a refusal rolls this whole transaction back, so nothing has changed (contract step 6).
        hold.requireFits();
        Reservation reservation = ledger.reserve(owner, ReservationScope.SESSION, sessionId, null, Math.max(1, hold.credits()));
        Session session = new Session(sessionId, owner, deckId, "MATERIALS", "RUNNING", null, spec, reservation.reservationId(),
                0, 0, null, null, null);
        repository.insertSession(session, settings.sessionRetention());
        for (Source source : parsed.sources()) repository.insertSource(sessionId, owner, source);

        String effort = parsed.workingEffort();
        String operation = AdmissionPricing.materialOperation(effort);
        int credits = pricing.credits(operation);
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

    @Transactional(readOnly = true)
    ObjectNode artifact(UUID owner, UUID deckId, UUID sessionId, UUID artifactId, UUID revisionId) {
        Session session = repository.session(owner, deckId, sessionId).orElseThrow(ResourceNotFoundException::new);
        Artifact artifact = repository.artifact(sessionId, artifactId).orElseThrow(ResourceNotFoundException::new);
        UUID wanted = revisionId == null ? artifact.currentRevisionId() : revisionId;
        Revision revision = wanted == null ? null
                : repository.revision(artifactId, wanted).orElseThrow(ResourceNotFoundException::new);
        return views.artifactDetail(session, artifact, revision);
    }
}
