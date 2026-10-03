package app.mnema.learning.generation;

import app.mnema.learning.catalog.content.NativeDocumentPreview;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.generation.GenerationStateConflictException.Reason;
import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.Revision;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.Rows.Turn;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.usage.AdmissionPricing;
import app.mnema.learning.usage.Reservation;
import app.mnema.learning.usage.ReservationScope;
import app.mnema.learning.usage.UsageLedger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The two commands that change a proposal's text: {@code editArtifact} and {@code revertArtifact}
 * ({@code contracts/generation/http.json}, decision 15). The order of evaluation is the contract's: ownership (404), receipt replay,
 * request validation (400), preconditions (412), the target against the revision (400), state (409: the session, the artifact, an edit
 * in progress, the capability), limits (422) and usage (409, last, in the admission transaction, so a refusal leaves nothing).
 *
 * <p>A rewrite ({@code REWRITE}, {@code FREE}) reserves its own hold, writes a QUEUED turn and an {@code EDIT} step and makes the artifact
 * REVISING; the worker does the rest ({@link EditExecutor}). {@code REMOVE_MEDIA} is deterministic and free and finishes in the request
 * transaction: a new revision without the media nodes, their slots REMOVED, the turn APPLIED. A revert only moves the current-revision
 * pointer; it creates no revision and deletes no history.
 */
@Service
class ArtifactEdits {
    private static final String SCOPE = "generation.sessions";
    /** The contract's limits of one artifact: instructions (turns) and revisions. */
    static final int MAX_TURNS = 50;
    static final int MAX_REVISIONS = 30;
    static final int MAX_TARGETS = 50;
    static final int MAX_INSTRUCTION = 2_000;
    private static final Set<String> ACTIONS = Set.of("REWRITE", "IMAGE_SEARCH", "IMAGE_GENERATE", "AUDIO_REGENERATE", "FREE", "REMOVE_MEDIA");
    private static final Set<String> PRESETS = Set.of("SIMPLER", "SHORTER", "EXAMPLE", "LONGER");
    /** Sessions in which an edit can be made; a CANCELLED one only lets media go ({@code REMOVE_MEDIA}). */
    private static final Set<String> EDITABLE = Set.of("RUNNING", "REVIEW");

    /** A request after strict parsing; {@code targets} is null when the body had none. */
    private record Request(UUID expectedRevision, String action, String preset, String instruction, List<UUID> targets) { }

    private final GenerationRepository repository;
    private final StepRepository steps;
    private final SessionLifecycle lifecycle;
    private final EditLifecycle edits;
    private final EditContexts contexts;
    private final CommandReceiptService receipts;
    private final UsageLedger ledger;
    private final AdmissionPricing pricing;
    private final GenerationGate gate;
    private final ObjectProvider<StepDispatcher> dispatcher;

    ArtifactEdits(GenerationRepository repository, StepRepository steps, SessionLifecycle lifecycle, EditLifecycle edits,
                  EditContexts contexts, CommandReceiptService receipts, UsageLedger ledger, AdmissionPricing pricing,
                  GenerationGate gate, ObjectProvider<StepDispatcher> dispatcher) {
        this.repository = repository;
        this.steps = steps;
        this.lifecycle = lifecycle;
        this.edits = edits;
        this.contexts = contexts;
        this.receipts = receipts;
        this.ledger = ledger;
        this.pricing = pricing;
        this.gate = gate;
        this.dispatcher = dispatcher;
    }

    // ----------------------------------------------------------------------- edit

    /** {@code editArtifact}: answers {@code {turn, artifact}}; a rewrite is QUEUED, {@code REMOVE_MEDIA} is already APPLIED. */
    ReviewService.Result edit(UUID owner, UUID deckId, UUID sessionId, UUID artifactId, byte[] raw) {
        session(owner, deckId, sessionId);
        Artifact known = repository.artifact(sessionId, artifactId).orElseThrow(ResourceNotFoundException::new);
        JsonNode body = Commands.read(raw);
        Commands.fields(body, Set.of("commandId", "expectedRevisionId", "action"), Set.of("target", "preset", "instruction"));
        UUID commandId = Commands.commandId(body);
        ObjectNode envelope = envelope(deckId, sessionId, body).put("artifactId", artifactId.toString());
        CommandIdentity identity = new CommandIdentity(commandId, owner, SCOPE, "artifact.edit");
        Optional<JsonNode> replay = receipts.replay(identity, envelope);
        if (replay.isPresent()) return new ReviewService.Result(replay.get(), true, null);
        Request request = parse(body, known);

        boolean[] applied = {false};
        JsonNode answer = receipts.execute(identity, envelope, () -> {
            applied[0] = true;
            return admit(sessionId, artifactId, request);
        });
        return new ReviewService.Result(answer, !applied[0], null);
    }

    private Request parse(JsonNode body, Artifact known) {
        UUID expectedRevision = Commands.entity(body, "expectedRevisionId");
        JsonNode actionNode = body.get("action");
        if (actionNode == null || !actionNode.isString() || !ACTIONS.contains(actionNode.stringValue())) throw new InvalidRequestException();
        String action = actionNode.stringValue();
        String preset = optionalText(body, "preset");
        if (preset != null && (!PRESETS.contains(preset) || !action.equals("REWRITE"))) throw new InvalidRequestException();
        String instruction = optionalText(body, "instruction");
        if (instruction != null && (instruction.isBlank() || instruction.codePointCount(0, instruction.length()) > MAX_INSTRUCTION)) {
            throw new InvalidRequestException();
        }
        if (action.equals("FREE") && instruction == null) throw new InvalidRequestException();
        List<UUID> targets = targets(body.get("target"));
        // a material is edited by blocks; an exercise has no blocks (its edit flow is AI-16)
        if (known.targetKind().equals("ITEM") && targets == null) throw new InvalidRequestException();
        return new Request(expectedRevision, action, preset, action.equals("REMOVE_MEDIA") ? null : instruction, targets);
    }

    /** A string member that may be absent or null. */
    private static String optionalText(JsonNode body, String name) {
        JsonNode node = body.get(name);
        if (node == null || node.isNull()) return null;
        if (!node.isString()) throw new InvalidRequestException();
        return node.stringValue();
    }

    /** {@code {nodeIds: [1..50 distinct ids]}}, or null for an absent or null target. */
    private static List<UUID> targets(JsonNode target) {
        if (target == null || target.isNull()) return null;
        if (!target.isObject()) throw new InvalidRequestException();
        Commands.fields(target, Set.of("nodeIds"), Set.of());
        JsonNode ids = target.get("nodeIds");
        if (!ids.isArray() || ids.isEmpty() || ids.size() > MAX_TARGETS) throw new InvalidRequestException();
        List<UUID> result = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (JsonNode id : ids) {
            if (!id.isString()) throw new InvalidRequestException();
            UUID node;
            try {
                node = UUID.fromString(id.stringValue());
            } catch (IllegalArgumentException malformed) {
                throw new InvalidRequestException();
            }
            if (!node.toString().equals(id.stringValue()) || !seen.add(node)) throw new InvalidRequestException();
            result.add(node);
        }
        return result;
    }

    private JsonNode admit(UUID sessionId, UUID artifactId, Request request) {
        SessionLifecycle.Tx tx = lifecycle.lock(sessionId);
        if (tx == null) throw new ResourceNotFoundException();
        Artifact artifact = repository.artifact(sessionId, artifactId).orElseThrow(ResourceNotFoundException::new);
        if (!request.expectedRevision().equals(artifact.currentRevisionId())) throw new VersionConflictException();

        boolean material = artifact.targetKind().equals("ITEM");
        Revision revision = material ? repository.revision(artifactId, artifact.currentRevisionId()).orElseThrow() : null;
        EditTarget target = material ? target(revision, request) : null;

        boolean removal = request.action().equals("REMOVE_MEDIA");
        if (!(EDITABLE.contains(tx.state) || removal && tx.state.equals("CANCELLED")) || !material) {
            throw new GenerationStateConflictException(Reason.ILLEGAL_STATE);
        }
        if (artifact.state().equals("REVISING")) {
            throw repository.openTurn(artifactId).<RuntimeException>map(turn -> new EditInProgressException(turn.turnId()))
                    .orElseGet(() -> new GenerationStateConflictException(Reason.ILLEGAL_STATE));
        }
        if (!artifact.state().equals("PROPOSED")) throw new GenerationStateConflictException(Reason.ILLEGAL_STATE);
        gate.requireEdit(request.action());
        if (!removal && repository.countedTurns(artifactId) >= MAX_TURNS) throw limit("TURNS_PER_ARTIFACT");
        if (artifact.revisionCount() >= MAX_REVISIONS) throw limit("REVISIONS_PER_ARTIFACT");

        return removal ? removeMedia(tx, artifact, revision, target, request) : rewrite(tx, artifact, target, request);
    }

    /**
     * The blocks the request names, checked against the revision (400): top-level blocks that are consecutive; for a rewrite at least one
     * block the model can be given; for a media action only media blocks of that kind.
     */
    private EditTarget target(Revision revision, Request request) {
        EditTarget target = EditTarget.resolve(revision.payload().path("document"), request.targets()).orElseThrow(InvalidRequestException::new);
        switch (request.action()) {
            case "REWRITE", "FREE" -> {
                if (target.text().isEmpty() || !contexts.editable(target)) throw new InvalidRequestException();
            }
            case "REMOVE_MEDIA" -> {
                if (!target.onlyMedia()) throw new InvalidRequestException();
            }
            default -> {
                String type = request.action().equals("AUDIO_REGENERATE") ? "audio" : "image";
                if (!target.onlyMedia() || target.blocks().stream().anyMatch(block -> !block.path("type").stringValue("").equals(type))) {
                    throw new InvalidRequestException();
                }
            }
        }
        return target;
    }

    private static ResourceLimitExceededException limit(String name) {
        return new ResourceLimitExceededException(ProblemExtension.builder().put("limit", name)
                .put("limits", Map.of("maxTurnsPerArtifact", MAX_TURNS, "maxRevisionsPerArtifact", MAX_REVISIONS)).build());
    }

    // -------------------------------------------------------------------- rewrite

    private JsonNode rewrite(SessionLifecycle.Tx tx, Artifact artifact, EditTarget target, Request request) {
        Session session = tx.session;
        UUID turnId = UUID.randomUUID();
        int credits = pricing.credits(EditLifecycle.OPERATION);
        // usage is last: a refusal rolls this transaction back, so nothing above has changed
        Reservation reservation = ledger.reserve(session.ownerId(), ReservationScope.TURN, session.sessionId(), turnId, Math.max(1, credits));

        UUID stepId = UUID.randomUUID();
        List<UUID> nodeIds = target.blocks().stream().map(EditDocument::id).toList();
        repository.insertTurn(new Turn(turnId, artifact.artifactId(), session.sessionId(), session.ownerId(), "QUEUED", request.action(),
                request.preset(), request.instruction(), nodeIds, stepId, null, null, true, null));
        ObjectNode input = Json.object().put("turnId", turnId.toString()).put("action", request.action())
                .put("revisionId", artifact.currentRevisionId().toString()).put("operation", EditLifecycle.OPERATION)
                .put("credits", credits).put("reservationId", reservation.reservationId().toString());
        steps.insert(stepId, session.sessionId(), artifact.artifactId(), session.ownerId(), EditExecutor.KIND, "TEXT", input,
                "edit:" + turnId);
        Artifact revising = repository.transition(artifact, "REVISING", null, null, null, artifact.revisionCount());
        tx.events.add(SessionLifecycle.artifactEvent(revising));
        tx.events.add(lifecycle.usageEvent(session, null));
        lifecycle.flush(tx);
        wakeAfterCommit();
        return answer(repository.turn(turnId).orElseThrow(), revising);
    }

    // --------------------------------------------------------------- REMOVE_MEDIA

    /**
     * Free and deterministic: the media nodes leave the document in a new revision (cause MEDIA), their slots are REMOVED (which approval
     * accepts as resolved) and the hold on their assets ends. The turn is recorded as APPLIED and does not count toward the 50.
     */
    private JsonNode removeMedia(SessionLifecycle.Tx tx, Artifact artifact, Revision current, EditTarget target, Request request) {
        Session session = tx.session;
        Set<UUID> removed = new HashSet<>(target.blocks().stream().map(EditDocument::id).toList());
        JsonNode document = EditDocument.without(current.payload().path("document"), removed);
        String title;
        try {
            title = NativeDocumentPreview.title(new NativeDocumentReader().read(document.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException rejected) {
            throw new InvalidRequestException();
        }
        UUID revisionId = UUID.randomUUID();
        int revisionNo = artifact.revisionCount() + 1;
        ObjectNode payload = Json.object().put("kind", "NATIVE_DOCUMENT");
        payload.set("document", document);
        ObjectNode handles = Json.object();
        EditDocument.handles(document).forEach((handle, nodeId) -> handles.put(handle, nodeId.toString()));
        repository.insertRevision(new Revision(revisionId, artifact.artifactId(), revisionNo, "MEDIA", payload, handles,
                current.promptVersion(), "deterministic", current.validation(), Instant.now()), session.sessionId(), session.ownerId());
        List<Rows.EventDraft> slotEvents = edits.followSlots(artifact, revisionId, EditDocument.idSet(document), false);
        Artifact proposed = repository.transition(artifact, "PROPOSED", null, revisionId, title, revisionNo);

        UUID turnId = UUID.randomUUID();
        List<UUID> nodeIds = target.blocks().stream().map(EditDocument::id).toList();
        repository.insertTurn(new Turn(turnId, artifact.artifactId(), session.sessionId(), session.ownerId(), "APPLIED", request.action(),
                null, null, nodeIds, null, revisionId, null, false, null));
        tx.events.add(SessionLifecycle.artifactEvent(proposed));
        tx.events.addAll(slotEvents);
        lifecycle.flush(tx);
        return answer(repository.turn(turnId).orElseThrow(), proposed);
    }

    private JsonNode answer(Turn turn, Artifact artifact) {
        ObjectNode body = Json.object();
        body.set("turn", SessionViews.turn(turn));
        body.set("artifact", SessionViews.artifactSummary(artifact, repository.slotCounts(List.of(artifact.artifactId())).get(artifact.artifactId())));
        return body;
    }

    // --------------------------------------------------------------------- revert

    /**
     * {@code revertArtifact}: the current-revision pointer moves to an earlier (or later) revision of the same artifact. No revision is
     * created and none is deleted, so the history stays and the move can be undone by another revert. Refused while a turn runs.
     */
    ReviewService.Result revert(UUID owner, UUID deckId, UUID sessionId, UUID artifactId, byte[] raw) {
        session(owner, deckId, sessionId);
        repository.artifact(sessionId, artifactId).orElseThrow(ResourceNotFoundException::new);
        JsonNode body = Commands.read(raw);
        Commands.fields(body, Set.of("commandId", "expectedArtifactVersion", "toRevisionId"), Set.of());
        UUID commandId = Commands.commandId(body);
        ObjectNode envelope = envelope(deckId, sessionId, body).put("artifactId", artifactId.toString());
        CommandIdentity identity = new CommandIdentity(commandId, owner, SCOPE, "artifact.revert");
        Optional<JsonNode> replay = receipts.replay(identity, envelope);
        if (replay.isPresent()) return new ReviewService.Result(replay.get(), true, null);
        long expected = Commands.version(body, "expectedArtifactVersion");
        UUID to = Commands.entity(body, "toRevisionId");

        boolean[] applied = {false};
        JsonNode summary = receipts.execute(identity, envelope, () -> {
            applied[0] = true;
            return moveTo(sessionId, artifactId, expected, to);
        });
        return new ReviewService.Result(summary, !applied[0], applied[0] ? summary.path("rowVersion").stringValue(null) : null);
    }

    private JsonNode moveTo(UUID sessionId, UUID artifactId, long expected, UUID to) {
        SessionLifecycle.Tx tx = lifecycle.lock(sessionId);
        if (tx == null) throw new ResourceNotFoundException();
        Artifact artifact = repository.artifact(sessionId, artifactId).orElseThrow(ResourceNotFoundException::new);
        if (artifact.rowVersion() != expected) throw new VersionConflictException();
        // a turn in flight (REVISING) is never undone under its feet; an exercise has no edit history to walk (AI-16)
        if (!EDITABLE.contains(tx.state) || !artifact.state().equals("PROPOSED") || !artifact.targetKind().equals("ITEM")) {
            throw new GenerationStateConflictException(Reason.ILLEGAL_STATE);
        }
        Revision target = repository.revision(artifactId, to).orElseThrow(ResourceNotFoundException::new);
        if (to.equals(artifact.currentRevisionId())) return summary(artifact);

        JsonNode document = target.payload().path("document");
        String title;
        try {
            title = NativeDocumentPreview.title(new NativeDocumentReader().read(document.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException unreadable) {
            throw new IllegalStateException("A stored revision is not readable");
        }
        List<Rows.EventDraft> slotEvents = edits.followSlots(artifact, to, EditDocument.idSet(document), true);
        Artifact moved = repository.transition(artifact, "PROPOSED", null, to, title, artifact.revisionCount());
        tx.events.add(SessionLifecycle.artifactEvent(moved));
        tx.events.addAll(slotEvents);
        lifecycle.flush(tx);
        return summary(moved);
    }

    // -------------------------------------------------------------------- helpers

    private Session session(UUID owner, UUID deckId, UUID sessionId) {
        return repository.session(owner, deckId, sessionId).orElseThrow(ResourceNotFoundException::new);
    }

    private JsonNode summary(Artifact artifact) {
        return SessionViews.artifactSummary(artifact, repository.slotCounts(List.of(artifact.artifactId())).get(artifact.artifactId()));
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

    /** The receipt envelope of a command: the path ids and the whole body, so any change of either is a different command. */
    private static ObjectNode envelope(UUID deckId, UUID sessionId, JsonNode body) {
        ObjectNode envelope = Json.object().put("deckId", deckId.toString()).put("sessionId", sessionId.toString());
        envelope.set("body", body);
        return envelope;
    }
}
