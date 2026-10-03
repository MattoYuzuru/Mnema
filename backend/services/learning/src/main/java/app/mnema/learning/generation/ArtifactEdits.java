package app.mnema.learning.generation;

import app.mnema.learning.catalog.content.NativeDocumentPreview;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.generation.GenerationStateConflictException.Reason;
import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.Revision;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.Rows.Turn;
import app.mnema.learning.generation.exercise.ExerciseValidator;
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
    /** What {@code REMOVE_MEDIA} may reach (it is exempt from {@link #MAX_REVISIONS}); the bound of the tables. */
    static final int MAX_REVISIONS_REMOVAL = 40;
    static final int MAX_TARGETS = 50;
    static final int MAX_INSTRUCTION = 2_000;
    private static final Set<String> ACTIONS = Set.of("REWRITE", "IMAGE_SEARCH", "IMAGE_GENERATE", "AUDIO_REGENERATE", "FREE", "REMOVE_MEDIA");
    private static final Set<String> PRESETS = Set.of("SIMPLER", "SHORTER", "EXAMPLE", "LONGER");
    /** Sessions in which an edit can be made; a CANCELLED one only lets media go ({@code REMOVE_MEDIA}). */
    private static final Set<String> EDITABLE = Set.of("RUNNING", "REVIEW");

    /** A request after strict parsing; {@code targets} is null when the body had none, {@code voice} when the action is not a redo of audio. */
    private record Request(UUID expectedRevision, String action, String preset, String instruction, List<UUID> targets, String voice) { }

    /** The actions an exercise of a REVISE_EXERCISE session accepts: a free rewrite and the redo of its audio. */
    private static final Set<String> EXERCISE_ACTIONS = Set.of("FREE", "AUDIO_REGENERATE");

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
        Commands.fields(body, Set.of("commandId", "expectedRevisionId", "action"), Set.of("target", "preset", "instruction", "voice"));
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
        String voice = optionalText(body, "voice");
        if (voice != null && !(voice.equals("female") || voice.equals("male"))) throw new InvalidRequestException();
        if (known.targetKind().equals("ITEM")) {
            // a material is edited by blocks, and has no voice to change
            if (targets == null || voice != null) throw new InvalidRequestException();
        } else {
            // an exercise has no blocks: the whole exercise is rewritten (FREE) or its audio redone (AUDIO_REGENERATE, which needs a voice)
            if (targets != null || !EXERCISE_ACTIONS.contains(action) || action.equals("AUDIO_REGENERATE") != (voice != null)) {
                throw new InvalidRequestException();
            }
        }
        return new Request(expectedRevision, action, preset, action.equals("REMOVE_MEDIA") || action.equals("AUDIO_REGENERATE") ? null : instruction,
                targets, voice);
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
            if (!node.toString().equals(id.stringValue())) throw new InvalidRequestException();
            if (!seen.add(node)) throw InvalidRequestException.because("TARGET_NOT_CONTIGUOUS");
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
        // an exercise is edited only in a session that revises it (AI-16): the proposals of an EXERCISES session are written once
        if (!(EDITABLE.contains(tx.state) || removal && tx.state.equals("CANCELLED")) || !(material || tx.session.kind().equals(ReviseSpec.EXERCISE))) {
            throw new GenerationStateConflictException(Reason.ILLEGAL_STATE);
        }
        if (artifact.state().equals("REVISING")) {
            throw repository.openTurn(artifactId).<RuntimeException>map(turn -> new EditInProgressException(turn.turnId()))
                    .orElseGet(() -> new GenerationStateConflictException(Reason.ILLEGAL_STATE));
        }
        if (!artifact.state().equals("PROPOSED")) throw new GenerationStateConflictException(Reason.ILLEGAL_STATE);
        if (material) gate.requireEdit(request.action());
        else if (request.action().equals("FREE")) gate.requireText();
        else gate.requireVoiceRevision();
        if (!material && request.action().equals("AUDIO_REGENERATE") && repository.slotsOf(artifactId).stream()
                .noneMatch(slot -> slot.kind().equals("AUDIO") && !slot.state().equals("REMOVED"))) {
            throw InvalidRequestException.because("TARGET_NO_AUDIO");
        }
        if (!removal && repository.countedTurns(artifactId) >= MAX_TURNS) throw limit("TURNS_PER_ARTIFACT");
        // a removal only takes media away (at most eight directives), so it may use the headroom the table leaves above the cap
        if (artifact.revisionCount() >= (removal ? MAX_REVISIONS_REMOVAL : MAX_REVISIONS)) throw limit("REVISIONS_PER_ARTIFACT");
        if (!removal && material && contexts.tokens(target) > EditContexts.MAX_TARGET_TOKENS) throw limit("EDIT_TARGET_SIZE");

        if (removal) return removeMedia(tx, artifact, revision, target, request);
        return request.action().equals("AUDIO_REGENERATE") && !material ? redoAudio(tx, artifact, request) : rewrite(tx, artifact, target, request);
    }

    /**
     * The blocks the request names, checked against the revision (400): top-level blocks that are consecutive; for a rewrite at least one
     * block the model can be given; for a media action only media blocks of that kind.
     */
    private EditTarget target(Revision revision, Request request) {
        EditTarget target = EditTarget.resolve(revision.payload().path("document"), request.targets())
                .orElseThrow(() -> InvalidRequestException.because("TARGET_NOT_CONTIGUOUS"));
        switch (request.action()) {
            case "REWRITE", "FREE" -> {
                if (target.text().isEmpty()) throw InvalidRequestException.because("TARGET_MEDIA_ONLY");
                Optional<String> refusal = contexts.refusal(target);
                if (refusal.isPresent()) throw InvalidRequestException.because(refusal.get());
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

    static ResourceLimitExceededException limit(String name) {
        return new ResourceLimitExceededException(ProblemExtension.builder().put("limit", name)
                .put("limits", Map.of("maxTurnsPerArtifact", MAX_TURNS, "maxRevisionsPerArtifact", MAX_REVISIONS,
                        "maxEditTargetTokens", EditContexts.MAX_TARGET_TOKENS)).build());
    }

    // -------------------------------------------------------------------- rewrite

    private JsonNode rewrite(SessionLifecycle.Tx tx, Artifact artifact, EditTarget target, Request request) {
        Session session = tx.session;
        UUID turnId = UUID.randomUUID();
        int credits = pricing.credits(EditLifecycle.OPERATION);
        // usage is last: a refusal rolls this transaction back, so nothing above has changed
        Reservation reservation = ledger.reserve(session.ownerId(), ReservationScope.TURN, session.sessionId(), turnId, Math.max(1, credits));

        UUID stepId = UUID.randomUUID();
        // an exercise is rewritten whole: its turn names no blocks
        List<UUID> nodeIds = target == null ? List.of() : target.blocks().stream().map(EditDocument::id).toList();
        repository.insertTurn(new Turn(turnId, artifact.artifactId(), session.sessionId(), session.ownerId(), "QUEUED", request.action(),
                request.preset(), request.instruction(), nodeIds, stepId, null, null, true, null, null));
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

    // ----------------------------------------------------------- AUDIO_REGENERATE

    /**
     * The redo of the audio of an exercise ({@code REVISE_EXERCISE}, #294): a turn with its own hold (one text-to-speech clip) and a
     * {@code TTS} step that the Stub speech executor runs until real synthesis comes with AI-09 (#297). The artifact is REVISING until it ends.
     */
    private JsonNode redoAudio(SessionLifecycle.Tx tx, Artifact artifact, Request request) {
        Session session = tx.session;
        UUID turnId = UUID.randomUUID();
        int credits = pricing.credits(EditLifecycle.MEDIA_OPERATION);
        // usage is last: a refusal rolls this transaction back, so nothing above has changed
        Reservation reservation = ledger.reserve(session.ownerId(), ReservationScope.TURN, session.sessionId(), turnId, Math.max(1, credits));
        UUID stepId = UUID.randomUUID();
        repository.insertTurn(new Turn(turnId, artifact.artifactId(), session.sessionId(), session.ownerId(), "QUEUED", request.action(), null, null,
                List.of(), stepId, null, null, true, null, request.voice()));
        ObjectNode input = Json.object().put("turnId", turnId.toString()).put("action", request.action()).put("voice", request.voice())
                .put("operation", EditLifecycle.MEDIA_OPERATION).put("credits", credits)
                .put("reservationId", reservation.reservationId().toString());
        steps.insert(stepId, session.sessionId(), artifact.artifactId(), session.ownerId(), "TTS", "TTS", input, "media:" + turnId);
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
                null, null, nodeIds, null, revisionId, null, false, null, null));
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
     * created and none is deleted, so the history stays and the move can be undone by another revert. Refused while a turn runs, and for a
     * revision from before the artifact was last retried.
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
        // a turn in flight (REVISING) is never undone under its feet; the proposals of an EXERCISES session have no edit history to walk
        boolean material = artifact.targetKind().equals("ITEM");
        if (!EDITABLE.contains(tx.state) || !artifact.state().equals("PROPOSED") || !(material || tx.session.kind().equals(ReviseSpec.EXERCISE))) {
            throw new GenerationStateConflictException(Reason.ILLEGAL_STATE);
        }
        Revision target = repository.revision(artifactId, to).orElseThrow(ResourceNotFoundException::new);
        // a retry wrote the artifact again from other pins: the revisions before that draft stand on sources it no longer has, so they are
        // history (readable) but never restored (that would bypass the drift protection and leave media nodes without slots)
        if (target.revisionNo() < repository.generationStart(artifactId)) throw new GenerationStateConflictException(Reason.ILLEGAL_STATE);
        if (to.equals(artifact.currentRevisionId())) return summary(artifact);

        String title;
        List<Rows.EventDraft> slotEvents;
        if (material) {
            JsonNode document = target.payload().path("document");
            try {
                title = NativeDocumentPreview.title(new NativeDocumentReader().read(document.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (IllegalArgumentException unreadable) {
                throw new IllegalStateException("A stored revision is not readable");
            }
            slotEvents = edits.followSlots(artifact, to, EditDocument.idSet(document), true);
        } else {
            // an exercise: its title is the first text of its prompt (or its objective's), and its slots (the audio) follow the revision
            JsonNode command = target.payload().path("command");
            JsonNode objective = command.path("objective");
            title = ExerciseValidator.title(command.path("exercise"), repository.objectiveTitle(tx.session.ownerId(), tx.session.deckId(),
                    UUID.fromString(objective.path("objectiveId").stringValue("")),
                    UUID.fromString(objective.path("objectiveRevisionId").stringValue(""))).orElse(""));
            repository.attachAllSlots(artifactId, to);
            slotEvents = List.of();
        }
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
