package app.mnema.learning.generation;

import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.EventDraft;
import app.mnema.learning.generation.Rows.Revision;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.Rows.Source;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
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
    private final AdmissionLimits limits;
    private final ReviseAdmission revisions;
    private final Plans plans;
    private final TransactionTemplate reading;
    private final TransactionTemplate quoting;

    SessionService(GenerationRepository repository, StepRepository steps, SessionLifecycle lifecycle, SessionViews views,
                   UsageLedger ledger, AdmissionPricing pricing, CommandReceiptService receipts, GenerationSettings settings,
                   ContextRepository context, ObjectProvider<StepDispatcher> dispatcher, AdmissionLimits limits,
                   ReviseAdmission revisions, Plans plans, PlatformTransactionManager transactions) {
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
        this.limits = limits;
        this.revisions = revisions;
        this.plans = plans;
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
            case "REVISE_ITEM", "REVISE_EXERCISE" -> {
                ReviseSpec parsed = ReviseSpec.read(spec);
                acknowledgement = receipts.execute(identity, envelope, () -> {
                    applied[0] = true;
                    return revisions.admit(owner, deckId, spec, parsed, hold);
                });
            }
            default -> throw new SpecNotSupportedException(kind);
        }
        return new Written(acknowledgement, !applied[0]);
    }

    /** The spec asks for the plan first ({@code settings.planFirst}); the interpreter has already refused it when the planner is off. */
    private static boolean planFirst(JsonNode spec) {
        return spec.path("settings").path("planFirst").asBoolean(false);
    }

    /**
     * Admits an {@code EXERCISES} session ({@code contracts/generation/exercises}, decision 14): the targets are pinned as
     * {@code SOURCE} items; the resolved quantity of the spec is spread over the targets in their processing order (uncovered
     * materials first with {@code UNCOVERED_FIRST}, the request order with {@code BALANCED}); one QUEUED artifact exists per
     * exercise and one {@code TEXT_DRAFT} step per target fills them ({@link #queueExercises}). With {@code planFirst} nothing is queued:
     * the session is PLANNING and a PLAN step proposes the plan (decision 17).
     */
    private JsonNode admitExercises(UUID owner, UUID deckId, JsonNode spec, ExercisesSpec parsed, AdmissionPricing.Hold hold) {
        repository.lockAdmission(owner);
        limits.requireRoom(owner);
        UUID sessionId = UUID.randomUUID();
        hold.requireFits();
        if (planFirst(spec)) return admitPlan(owner, deckId, spec, "EXERCISES", sessionId, parsed.targets(), hold);
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
        List<ExerciseWork> work = new ArrayList<>();
        for (int index = 0; index < order.size(); index++) work.add(new ExerciseWork(order.get(index), counts[index], null));
        ObjectNode queued = Json.object();
        for (String state : GenerationRepository.ARTIFACT_STATES) queued.put(state, state.equals("QUEUED") ? total : 0);
        ObjectNode started = Json.object().put("state", "RUNNING").put("rowVersion", "1");
        started.set("artifactCounts", queued);
        List<EventDraft> events = new ArrayList<>();
        events.add(new EventDraft("SESSION_STATE", null, started));
        queueExercises(owner, sessionId, work, events);
        long[] allocated = repository.update(sessionId, "RUNNING", null, true, events.size(), settings.sessionRetention());
        repository.insertEvents(sessionId, allocated[0], events);
        wakeAfterCommit();
        return views.detail(repository.session(sessionId).orElseThrow());
    }

    /**
     * One run of exercises over one target. {@code mechanics} is the plan's choice for this target (null: the spec's allowed set decides, as
     * for every session without a plan).
     */
    private record ExerciseWork(Source target, int count, List<String> mechanics) { }

    /**
     * The artifacts and steps of an {@code EXERCISES} session, from the unplanned admission and from an approved plan alike: one QUEUED artifact
     * per exercise (ordinals in the order of {@code work}, then index) and one {@code TEXT_DRAFT} step per target. A step's share of the hold is
     * the difference of the rounded-up price of the exercises up to it and before it, so the shares add up to the reservation.
     */
    private void queueExercises(UUID owner, UUID sessionId, List<ExerciseWork> work, List<EventDraft> events) {
        int ordinal = 0;
        int planned = 0;
        for (ExerciseWork item : work) {
            Source target = item.target();
            ArrayNode artifactIds = Json.array();
            for (int exercise = 0; exercise < item.count(); exercise++) {
                UUID artifactId = UUID.randomUUID();
                artifactIds.add(artifactId.toString());
                repository.insertArtifact(artifactId, sessionId, owner, "EXERCISE", ordinal++, Json.array().add(itemRef(target)));
                events.add(new EventDraft("ARTIFACT_STATE", artifactId, queuedPayload()));
            }
            int share = pricing.exerciseCredits(planned + item.count()) - pricing.exerciseCredits(planned);
            planned += item.count();
            ObjectNode input = Json.object().put("operation", ExerciseDraftExecutor.OPERATION)
                    .put("memberKey", target.memberKey().toString()).put("itemRevisionId", target.itemRevisionId().toString())
                    .put("count", item.count()).put("credits", share);
            input.set("artifactIds", artifactIds);
            // the mechanics the plan chose for this material: the step asks for these and the lint refuses any other
            if (item.mechanics() != null) input.putArray("mechanics").addAll(item.mechanics().stream().map(Json.NODES::stringNode).toList());
            steps.insert(UUID.randomUUID(), sessionId, UUID.fromString(artifactIds.get(0).stringValue("")), owner, "TEXT_DRAFT", "TEXT",
                    input, "draft:" + artifactIds.get(0).stringValue("") + ":1");
        }
    }

    private JsonNode admit(UUID owner, UUID deckId, JsonNode spec, MaterialsSpec parsed, AdmissionPricing.Hold hold) {
        repository.lockAdmission(owner);
        limits.requireRoom(owner);
        UUID sessionId = UUID.randomUUID();
        // Usage is strictly last: a full cap or a hold too small for one material, then the reservation itself: a refusal rolls this whole transaction back, so nothing has changed (contract step 6).
        hold.requireFits();
        if (planFirst(spec)) return admitPlan(owner, deckId, spec, "MATERIALS", sessionId, parsed.sources(), hold);
        Reservation reservation = ledger.reserve(owner, ReservationScope.SESSION, sessionId, null, Math.max(1, hold.credits()));
        Session session = new Session(sessionId, owner, deckId, "MATERIALS", "RUNNING", null, spec, reservation.reservationId(),
                0, 0, null, null, null);
        repository.insertSession(session, settings.sessionRetention());
        pinSources(owner, sessionId, parsed.sources());

        List<Source> notes = parsed.notes();
        List<EventDraft> events = new ArrayList<>();
        int count = parsed.artifactCount();
        ObjectNode counts = Json.object();
        for (String state : GenerationRepository.ARTIFACT_STATES) counts.put(state, state.equals("QUEUED") ? count : 0);
        ObjectNode started = Json.object().put("state", "RUNNING").put("rowVersion", "1");
        started.set("artifactCounts", counts);
        events.add(new EventDraft("SESSION_STATE", null, started));
        List<MaterialWork> work = new ArrayList<>();
        for (int ordinal = 0; ordinal < count; ordinal++) {
            ArrayNode refs = Json.array();
            if (parsed.mergeNotes() || notes.isEmpty()) {
                notes.forEach(note -> refs.add(noteRef(note)));
            } else {
                refs.add(noteRef(notes.get(ordinal)));
            }
            parsed.items().forEach(item -> refs.add(itemRef(item)));
            // each material is charged and written at its own effective settings (the note's overrides over the session's)
            work.add(new MaterialWork(refs, parsed.forArtifact(refs).workingEffort()));
        }
        queueMaterials(owner, sessionId, work, events);
        long[] allocated = repository.update(sessionId, "RUNNING", null, true, events.size(), settings.sessionRetention());
        repository.insertEvents(sessionId, allocated[0], events);
        wakeAfterCommit();
        return views.detail(repository.session(sessionId).orElseThrow());
    }

    /** The pins of a session: every source, and the text of each note at its pinned version (a note that moved since the check is a 409, not a silent re-pin). */
    private void pinSources(UUID owner, UUID sessionId, List<Source> sources) {
        for (Source source : sources) {
            repository.insertSource(sessionId, owner, source);
            if (source.type().equals("NOTE") && !repository.snapshotNote(sessionId, owner, source.noteId(), source.noteRowVersion())) {
                throw new SourceUnavailableException(List.of(new SourceUnavailableException.Unavailable("NOTE", source.noteId())));
            }
        }
    }

    /** One material to write: the pins it is written from and the effort it is written and charged at. */
    private record MaterialWork(ArrayNode refs, String effort) { }

    /** The artifacts and steps of a {@code MATERIALS} session, from the unplanned admission and from an approved plan alike: one QUEUED artifact and one step each. */
    private void queueMaterials(UUID owner, UUID sessionId, List<MaterialWork> work, List<EventDraft> events) {
        int ordinal = 0;
        for (MaterialWork item : work) {
            UUID artifactId = UUID.randomUUID();
            repository.insertArtifact(artifactId, sessionId, owner, ordinal++, item.refs());
            String operation = AdmissionPricing.materialOperation(item.effort());
            ObjectNode input = Json.object().put("effort", item.effort()).put("operation", operation).put("credits", pricing.credits(operation));
            steps.insert(UUID.randomUUID(), sessionId, artifactId, owner, "TEXT_DRAFT", "TEXT", input, "draft:" + artifactId + ":1");
            events.add(new EventDraft("ARTIFACT_STATE", artifactId, queuedPayload()));
        }
    }

    // ------------------------------------------------------------------------ planner

    /**
     * Admits a plan-first session (decision 17): PLANNING, no artifact yet, the sources pinned, two holds (the batch exactly as it would be without the
     * plan, which is why the plan always fits it, and the plan's own price, held by the PLAN step and debited apart when the plan is ready) and one
     * {@code PLAN} step whose input is the budget (the batch hold) the plan must fit.
     */
    private JsonNode admitPlan(UUID owner, UUID deckId, JsonNode spec, String kind, UUID sessionId, List<Source> pins,
                               AdmissionPricing.Hold hold) {
        Reservation batch = ledger.reserve(owner, ReservationScope.SESSION, sessionId, null, Math.max(1, hold.credits()));
        Reservation plan = ledger.reserve(owner, ReservationScope.STEP, sessionId, null, Math.max(1, hold.planCredits()));
        Session session = new Session(sessionId, owner, deckId, kind, "PLANNING", null, spec, batch.reservationId(), 0, 0, null, null, null);
        repository.insertSession(session, settings.sessionRetention());
        pinSources(owner, sessionId, pins);
        ObjectNode input = Json.object().put("operation", kind).put("credits", Math.max(1, hold.planCredits()))
                .put("budgetCredits", Math.max(1, hold.credits())).put("reservationId", plan.reservationId().toString());
        steps.insert(UUID.randomUUID(), sessionId, null, owner, PlanExecutor.KIND, "TEXT", input, "plan:" + sessionId);
        ObjectNode started = Json.object().put("state", "PLANNING").put("rowVersion", "1");
        started.set("artifactCounts", SessionViews.counts(Map.of()));
        List<EventDraft> events = List.of(new EventDraft("SESSION_STATE", null, started));
        long[] allocated = repository.update(sessionId, "PLANNING", null, true, events.size(), settings.sessionRetention());
        repository.insertEvents(sessionId, allocated[0], events);
        wakeAfterCommit();
        return views.detail(repository.session(sessionId).orElseThrow());
    }

    /**
     * {@code approvePlan} ({@code POST .../plan-approval}, decision 17): the plan the owner approved (the model's, with whatever they edited) becomes the
     * session's work. Evaluation: ownership (404), receipt replay, shape and identifiers (400), {@code expectedSessionVersion} (412), the state
     * (409 {@code ILLEGAL_STATE}: only PLAN_READY), the limits (422, never clamped), then usage (409, last): the batch hold is re-sized to the cost of the
     * plan as approved (the surplus returns to the balance; a cost above the hold extends it, and what the balance cannot pay is
     * {@code USAGE_LIMIT_REACHED} with nothing changed). In one transaction it creates exactly the planned artifacts and steps through the same code
     * as an unplanned admission and moves the session to RUNNING.
     */
    Written approvePlan(UUID owner, UUID deckId, UUID sessionId, byte[] raw) {
        Session probe = repository.session(owner, deckId, sessionId).orElseThrow(ResourceNotFoundException::new);
        JsonNode body = Commands.read(raw);
        Commands.fields(body, Set.of("commandId", "expectedSessionVersion", "plan"), Set.of());
        UUID commandId = Commands.commandId(body);
        long expected = Commands.version(body, "expectedSessionVersion");
        JsonNode plan = body.get("plan");
        if (!plan.isObject()) throw new InvalidRequestException();
        ObjectNode envelope = Json.object().put("deckId", deckId.toString()).put("sessionId", sessionId.toString());
        envelope.set("body", body);
        CommandIdentity identity = new CommandIdentity(commandId, owner, SCOPE, "session.plan-approval");
        Optional<JsonNode> replay = receipts.replay(identity, envelope);
        if (replay.isPresent()) return new Written(replay.get(), true);

        // only a session that plans can be approved; any other is an illegal state, like one past PLAN_READY
        if (!probe.kind().equals("EXERCISES") && !probe.kind().equals("MATERIALS")) {
            throw new GenerationStateConflictException(GenerationStateConflictException.Reason.ILLEGAL_STATE);
        }
        Plans.Basis basis = plans.basis(probe);
        JsonNode shown = repository.plan(sessionId).orElse(null);
        Plans.Draft draft = plans.fromOwner(plan, basis, shown == null ? Map.of() : Plans.whyOf(shown));
        boolean[] applied = {false};
        JsonNode acknowledgement = receipts.execute(identity, envelope, () -> {
            applied[0] = true;
            return doApprovePlan(sessionId, expected, basis, draft);
        });
        return new Written(acknowledgement, !applied[0]);
    }

    private JsonNode doApprovePlan(UUID sessionId, long expected, Plans.Basis basis, Plans.Draft draft) {
        SessionLifecycle.Tx tx = lifecycle.lock(sessionId);
        if (tx == null) throw new ResourceNotFoundException();
        if (tx.session.rowVersion() != expected) throw new VersionConflictException();
        if (!tx.state.equals("PLAN_READY")) throw new GenerationStateConflictException(GenerationStateConflictException.Reason.ILLEGAL_STATE);
        plans.requireWithinLimits(draft);
        UUID owner = tx.session.ownerId();
        Session session = tx.session;
        JsonNode shown = repository.plan(sessionId).orElseThrow(() -> new IllegalStateException("A PLAN_READY session has a plan"));

        List<EventDraft> events = new ArrayList<>();
        if (basis.kind().equals("EXERCISES")) {
            List<ExerciseWork> work = new ArrayList<>();
            for (Plans.ExerciseItem item : draft.exercises()) {
                Source target = basis.targets().stream().filter(candidate -> candidate.memberKey().equals(item.memberKey())).findFirst().orElseThrow();
                work.add(new ExerciseWork(target, item.count(), item.mechanics()));
            }
            queueExercises(owner, sessionId, work, events);
        } else {
            List<MaterialWork> work = new ArrayList<>();
            for (Plans.MaterialItem item : draft.materials()) {
                ArrayNode refs = Json.array();
                if (item.noteId() != null) refs.add(noteRef(basis.note(item.noteId()).orElseThrow()));
                else basis.notes().forEach(note -> refs.add(noteRef(note)));
                basis.materials().items().forEach(source -> refs.add(itemRef(source)));
                work.add(new MaterialWork(refs, item.effort()));
            }
            queueMaterials(owner, sessionId, work, events);
        }

        // Usage is last: the batch hold becomes the cost of the plan as approved. The old hold is released first, so what it held counts toward
        // the new one; what the balance cannot pay is a 409 that rolls all of this back.
        int cost = plans.cost(basis, draft);
        ledger.release(owner, session.reservationId());
        Reservation reservation = ledger.reserve(owner, ReservationScope.SESSION, sessionId, null, Math.max(1, cost));
        repository.setReservation(sessionId, reservation.reservationId());
        int planCredits = shown.path("cost").path("planCredits").asInt(0);
        repository.setPlan(sessionId, plans.wire(basis, draft, shown.path("targets"), shown.path("sources"), planCredits, cost,
                pricing.barCredits(owner), true));

        tx.state = "RUNNING";
        tx.events.addAll(events);
        tx.events.add(lifecycle.usageEvent(repository.session(sessionId).orElseThrow(), null));
        lifecycle.flush(tx);
        wakeAfterCommit();
        return views.detail(repository.session(sessionId).orElseThrow());
    }

    static ObjectNode noteRef(Source note) {
        return Json.object().put("type", "NOTE").put("noteId", note.noteId().toString())
                .put("noteRowVersion", Long.toString(note.noteRowVersion()));
    }

    static ObjectNode itemRef(Source item) {
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
