package app.mnema.learning.generation;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.catalog.exercise.ExerciseCommand;
import app.mnema.learning.generation.GenerationRepository.DeckHead;
import app.mnema.learning.generation.GenerationStateConflictException.Reason;
import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.Revision;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.Rows.Slot;
import app.mnema.learning.generation.SourceDrift.Drift;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The commands that take a proposal out of review ({@code contracts/generation/http.json}): approve (one and bulk), reject
 * and undo, hand-off, retry, delete, and the archival of the notes a session used. The order of evaluation is the
 * contract's: ownership (404), receipt replay, request validation (400), preconditions (428, 412), state (409) and usage
 * (409, last, inside the transaction that changes state, so a refusal leaves nothing).
 *
 * <p>Approval is one transaction with the catalog command: the catalog's publication calls {@link #apply} as its
 * completion, so the artifact's {@code PUBLISHED}, the {@code published_ref}, the provenance and the event commit with the
 * material or not at all. What approval checks <em>before</em> that transaction (preconditions, state, source drift) is
 * repeated inside it for the parts a concurrent command could change.
 */
@Service
class ReviewService {
    private static final String SCOPE = "generation.sessions";
    /** Sessions in which a proposal can still be approved, rejected, handed off or taken back (states.json). */
    private static final Set<String> REVIEWABLE = Set.of("RUNNING", "REVIEW", "CANCELLED");
    /** Sessions that can still generate: only there is a retry meaningful. */
    private static final Set<String> RETRYABLE = Set.of("RUNNING", "REVIEW");
    /** The most artifacts one approval command may name (contract decision 5). */
    static final int MAX_BULK = 20;
    /** An approval with exercises is one transaction of up to 20 publications of the catalog. */
    private static final int CHAIN_TIMEOUT_SECONDS = 120;

    /** The answer of a command: the body, whether a stored receipt gave it, and the {@code ETag} to send (null on a replay). */
    record Result(JsonNode body, boolean replayed, String etag) { }

    /** One artifact of an approval and the versions the client expects; {@code replacement} is the owner's edited exercise, or null. */
    private record Plan(UUID artifactId, long artifactVersion, UUID revisionId, JsonNode replacement) { }

    private final GenerationRepository repository;
    private final StepRepository steps;
    private final SessionLifecycle lifecycle;
    private final SessionViews views;
    private final CommandReceiptService receipts;
    private final GeneratedItemPublisher publisher;
    private final GeneratedExercisePublisher exercisePublisher;
    private final ExerciseRepin repins;
    private final PlatformTransactionManager transactions;
    private final GeneratedDraftOpener drafts;
    private final SourceDrift drift;
    private final NoteArchival notes;
    private final UsageLedger ledger;
    private final AdmissionPricing pricing;
    private final GenerationGate gate;
    private final ObjectProvider<StepDispatcher> dispatcher;
    private final TransactionTemplate transaction;
    private final GenerationSettings settings;

    ReviewService(GenerationRepository repository, StepRepository steps, SessionLifecycle lifecycle, SessionViews views,
                  CommandReceiptService receipts, GeneratedItemPublisher publisher, GeneratedExercisePublisher exercisePublisher,
                  ExerciseRepin repins, GeneratedDraftOpener drafts,
                  SourceDrift drift, NoteArchival notes, UsageLedger ledger, AdmissionPricing pricing, GenerationGate gate,
                  ObjectProvider<StepDispatcher> dispatcher, PlatformTransactionManager transactions, GenerationSettings settings) {
        this.settings = settings;
        this.repository = repository;
        this.steps = steps;
        this.lifecycle = lifecycle;
        this.views = views;
        this.receipts = receipts;
        this.publisher = publisher;
        this.exercisePublisher = exercisePublisher;
        this.repins = repins;
        this.transactions = transactions;
        this.drafts = drafts;
        this.drift = drift;
        this.notes = notes;
        this.ledger = ledger;
        this.pricing = pricing;
        this.gate = gate;
        this.dispatcher = dispatcher;
        this.transaction = new TransactionTemplate(transactions);
    }

    // ------------------------------------------------------------------- approve

    /**
     * {@code approveArtifact}: one proposal into the deck, in one transaction with the catalog command. An {@code EXERCISE}
     * artifact may carry a {@code replacement} (the exercise the owner edited in the editor before saving): it is read by
     * {@code ExerciseCommand.readCreate} only (a human edit is not linted), must keep the proposal's subject material and is
     * published instead of the revision payload.
     */
    Result approve(UUID owner, UUID deckId, UUID sessionId, UUID artifactId, List<String> ifMatch, byte[] raw) {
        Session session = session(owner, deckId, sessionId);
        Artifact known = repository.artifact(sessionId, artifactId).orElseThrow(ResourceNotFoundException::new);
        JsonNode body = Commands.read(raw);
        Commands.fields(body, Set.of("commandId", "expectedArtifactVersion", "expectedRevisionId", "expectedDeckRevisionId"),
                Set.of("replacement"));
        UUID commandId = Commands.commandId(body);
        ObjectNode envelope = envelope(deckId, sessionId, body).put("artifactId", artifactId.toString())
                .put("ifMatch", Commands.raw(ifMatch));
        CommandIdentity identity = new CommandIdentity(commandId, owner, SCOPE, "artifact.approve");
        Optional<JsonNode> replay = receipts.replay(identity, envelope);
        if (replay.isPresent()) return new Result(replay.get(), true, null);

        JsonNode replacement = null;
        if (body.has("replacement")) {
            // only a proposed exercise can be replaced by an edited one: a material or a bulk entry has no such member
            if (!known.targetKind().equals("EXERCISE")) throw new InvalidRequestException();
            replacement = replacement(body.get("replacement"), known);
        }
        Plan plan = new Plan(artifactId, Commands.version(body, "expectedArtifactVersion"), Commands.entity(body, "expectedRevisionId"),
                replacement);
        UUID deckRevision = Commands.entity(body, "expectedDeckRevisionId");
        long deckVersion = Commands.ifMatch(ifMatch);
        return publish(session, identity, envelope, commandId, deckVersion, deckRevision, List.of(plan), false,
                Commands.derive(commandId, artifactId.toString()));
    }

    /**
     * The edited exercise of an approval: exactly {@code {objective, exercise}}, parsed by the publication parser (with the
     * placeholders the approval supplies) and about the same subject material as the proposal. Any failure is a 400.
     */
    private JsonNode replacement(JsonNode edited, Artifact artifact) {
        if (!edited.isObject()) throw new InvalidRequestException();
        Commands.fields(edited, Set.of("objective", "exercise"), Set.of());
        ObjectNode command = Json.object().put("commandId", ExerciseContexts.PLACEHOLDER_COMMAND.toString())
                .put("expectedDeckRevisionId", ExerciseContexts.PLACEHOLDER_DECK_REVISION.toString());
        command.set("objective", edited.get("objective").deepCopy());
        command.set("exercise", edited.get("exercise").deepCopy());
        ExerciseCommand parsed;
        try {
            parsed = ExerciseCommand.readCreate(new java.io.ByteArrayInputStream(command.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException rejected) {
            throw new InvalidRequestException();
        }
        String member = artifact.sourceRefs().path(0).path("memberKey").stringValue("");
        if (!parsed.exercise().subject().memberKey().toString().equals(member)) throw new InvalidRequestException();
        return edited;
    }

    /** {@code approveArtifacts}: up to 20 proposals as one atomic bulk publication. */
    Result approveMany(UUID owner, UUID deckId, UUID sessionId, List<String> ifMatch, byte[] raw) {
        Session session = session(owner, deckId, sessionId);
        JsonNode body = Commands.read(raw);
        Commands.fields(body, Set.of("commandId", "expectedDeckRevisionId", "artifacts"), Set.of());
        UUID commandId = Commands.commandId(body);
        ObjectNode envelope = envelope(deckId, sessionId, body).put("ifMatch", Commands.raw(ifMatch));
        CommandIdentity identity = new CommandIdentity(commandId, owner, SCOPE, "artifact.approve-many");
        Optional<JsonNode> replay = receipts.replay(identity, envelope);
        if (replay.isPresent()) return new Result(replay.get(), true, null);

        JsonNode listed = body.get("artifacts");
        if (listed == null || !listed.isArray() || listed.isEmpty() || listed.size() > MAX_BULK) throw new InvalidRequestException();
        List<Plan> plans = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (JsonNode entry : listed) {
            if (!entry.isObject()) throw new InvalidRequestException();
            // an edited exercise ("replacement") is a single approval: a bulk entry has exactly these members
            Commands.fields(entry, Set.of("artifactId", "expectedArtifactVersion", "expectedRevisionId"), Set.of());
            UUID artifactId = Commands.entity(entry, "artifactId");
            if (!seen.add(artifactId)) throw new InvalidRequestException();
            plans.add(new Plan(artifactId, Commands.version(entry, "expectedArtifactVersion"), Commands.entity(entry, "expectedRevisionId"), null));
        }
        // an artifact that is not this session's is the opaque 404, before the remaining body fields and the preconditions
        for (Plan plan : plans) repository.artifact(sessionId, plan.artifactId()).orElseThrow(ResourceNotFoundException::new);
        UUID deckRevision = Commands.entity(body, "expectedDeckRevisionId");
        long deckVersion = Commands.ifMatch(ifMatch);
        // the materials of a bulk approval are one publication named by the materials' ids alone, so a bulk of materials is the
        // command it always was; each exercise is its own publication, named by its artifact
        String name = "bulk:" + plans.stream().filter(plan -> isItem(sessionId, plan)).map(plan -> plan.artifactId().toString()).sorted()
                .collect(Collectors.joining(","));
        return publish(session, identity, envelope, commandId, deckVersion, deckRevision, plans, true, Commands.derive(commandId, name));
    }

    private boolean isItem(UUID sessionId, Plan plan) {
        return repository.artifact(sessionId, plan.artifactId()).orElseThrow().targetKind().equals("ITEM");
    }

    /** The published thing of one artifact: its reference, the catalog command that made it and whether the owner edited it first. */
    private record Published(JsonNode reference, UUID command, boolean edited) { }

    /** Where the deck stands after a publication of an approval: the next publication must name exactly this. */
    private record Chain(long version, UUID revisionId) { }

    private Result publish(Session session, CommandIdentity identity, ObjectNode envelope, UUID commandId, long deckVersion,
                           UUID deckRevision, List<Plan> requested, boolean bulk, UUID publicationCommand) {
        DeckHead head = repository.deckHead(session.ownerId(), session.deckId()).orElseThrow(ResourceNotFoundException::new);
        if (head.version() != deckVersion || !head.revisionId().equals(deckRevision)) throw new VersionConflictException();
        List<Plan> plans = new ArrayList<>(requested);
        Map<UUID, Artifact> artifacts = new LinkedHashMap<>();
        for (Plan plan : plans) {
            artifacts.put(plan.artifactId(), repository.artifact(session.sessionId(), plan.artifactId())
                    .orElseThrow(ResourceNotFoundException::new));
        }
        Session current = repository.session(session.sessionId()).orElseThrow(ResourceNotFoundException::new);
        check(current, plans, artifacts, bulk, true);

        boolean exercises = artifacts.values().stream().anyMatch(artifact -> artifact.targetKind().equals("EXERCISE"));
        if (exercises) return publishWithExercises(session, identity, envelope, commandId, deckVersion, deckRevision, plans, artifacts,
                bulk, publicationCommand);

        List<GeneratedItemPublisher.Material> materials = new ArrayList<>();
        for (Plan plan : plans) {
            Artifact artifact = artifacts.get(plan.artifactId());
            Revision revision = repository.revision(artifact.artifactId(), artifact.currentRevisionId()).orElseThrow();
            materials.add(new GeneratedItemPublisher.Material(Commands.derive(publicationCommand, "member:" + artifact.artifactId()),
                    document(revision.payload().path("document"))));
        }
        JsonNode[] acknowledgement = new JsonNode[1];
        boolean[] applied = {false};
        publisher.create(session.ownerId(), session.deckId(), deckVersion, publicationCommand, deckRevision, materials,
                (publication, replayed) -> acknowledgement[0] = receipts.execute(identity, envelope, () -> {
                    applied[0] = true;
                    Map<UUID, Published> published = new LinkedHashMap<>();
                    JsonNode changes = publication.path("changes");
                    if (changes.size() != plans.size()) throw new IllegalStateException("Publication does not match the approval");
                    for (int index = 0; index < plans.size(); index++) {
                        JsonNode change = changes.get(index);
                        published.put(plans.get(index).artifactId(), new Published(Json.object().put("kind", "ITEM")
                                .put("memberKey", change.path("memberKey").stringValue(null))
                                .put("itemRevisionId", change.path("itemRevisionId").stringValue(null))
                                .put("ordinal", change.path("ordinal").intValue()), publicationCommand, false));
                    }
                    return apply(session.sessionId(), commandId, plans, published, new Chain(
                            Long.parseLong(publication.path("deckVersion").stringValue("0")),
                            UUID.fromString(publication.path("deckRevisionId").stringValue(""))), bulk);
                }));
        String etag = applied[0] ? acknowledgement[0].path("deckVersion").stringValue(null) : null;
        return new Result(acknowledgement[0], !applied[0], etag);
    }

    /**
     * An approval that includes exercises: ONE transaction. The materials go first, as one bulk publication (the catalog's own
     * preparation may not run inside a transaction that already wrote, and the exercises only reference materials that exist);
     * then each exercise through its own publication with a child command id and the deck revision and version the previous one
     * left; then the artifacts' move to PUBLISHED. Any failure rolls the whole chain back.
     */
    private Result publishWithExercises(Session session, CommandIdentity identity, ObjectNode envelope, UUID commandId, long deckVersion,
                                        UUID deckRevision, List<Plan> plans, Map<UUID, Artifact> artifacts, boolean bulk,
                                        UUID itemsCommand) {
        boolean[] applied = {false};
        TransactionTemplate chain = new TransactionTemplate(transactions);
        chain.setTimeout(CHAIN_TIMEOUT_SECONDS);
        JsonNode acknowledgement;
        try {
            acknowledgement = publishChain(session, identity, envelope, commandId, deckVersion, deckRevision, plans, artifacts, bulk, itemsCommand,
                    chain, applied);
        } catch (SourceMoved moved) {
            // the transaction rolled back with everything in it; the proposal that no longer has its source is made stale in a short one
            Plan plan = plans.stream().filter(candidate -> candidate.artifactId().equals(moved.artifactId)).findFirst().orElseThrow();
            List<UUID> stale = drift.markStale(session.sessionId(), Map.of(plan.artifactId(), plan.artifactVersion()));
            throw conflict(Reason.SOURCE_STALE, stale.isEmpty() ? List.of(plan.artifactId()) : stale, bulk);
        }
        String etag = applied[0] ? acknowledgement.path("deckVersion").stringValue(null) : null;
        return new Result(acknowledgement, !applied[0], etag);
    }

    /** An exercise could not be published because what it stands on is gone (a material pin, a reused objective). */
    private static final class SourceMoved extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient UUID artifactId;

        SourceMoved(UUID artifactId) {
            super("The source of a proposal moved", null, false, false);
            this.artifactId = artifactId;
        }
    }

    private JsonNode publishChain(Session session, CommandIdentity identity, ObjectNode envelope, UUID commandId, long deckVersion,
                                  UUID deckRevision, List<Plan> plans, Map<UUID, Artifact> artifacts, boolean bulk, UUID itemsCommand,
                                  TransactionTemplate chain, boolean[] applied) {
        return chain.execute(status -> receipts.execute(identity, envelope, () -> {
            applied[0] = true;
            Map<UUID, Published> published = new LinkedHashMap<>();
            Chain position = new Chain(deckVersion, deckRevision);
            List<Plan> materialPlans = plans.stream().filter(plan -> artifacts.get(plan.artifactId()).targetKind().equals("ITEM")).toList();
            if (!materialPlans.isEmpty()) {
                List<GeneratedItemPublisher.Material> materials = new ArrayList<>();
                for (Plan plan : materialPlans) {
                    Artifact artifact = artifacts.get(plan.artifactId());
                    Revision revision = repository.revision(artifact.artifactId(), artifact.currentRevisionId()).orElseThrow();
                    materials.add(new GeneratedItemPublisher.Material(Commands.derive(itemsCommand, "member:" + artifact.artifactId()),
                            document(revision.payload().path("document"))));
                }
                JsonNode[] publication = new JsonNode[1];
                publisher.create(session.ownerId(), session.deckId(), position.version(), itemsCommand, position.revisionId(), materials,
                        (result, replayed) -> publication[0] = result);
                JsonNode changes = publication[0].path("changes");
                if (changes.size() != materialPlans.size()) throw new IllegalStateException("Publication does not match the approval");
                for (int index = 0; index < materialPlans.size(); index++) {
                    JsonNode change = changes.get(index);
                    published.put(materialPlans.get(index).artifactId(), new Published(Json.object().put("kind", "ITEM")
                            .put("memberKey", change.path("memberKey").stringValue(null))
                            .put("itemRevisionId", change.path("itemRevisionId").stringValue(null))
                            .put("ordinal", change.path("ordinal").intValue()), itemsCommand, false));
                }
                position = new Chain(Long.parseLong(publication[0].path("deckVersion").stringValue("0")),
                        UUID.fromString(publication[0].path("deckRevisionId").stringValue("")));
            }
            for (Plan plan : plans) {
                Artifact artifact = artifacts.get(plan.artifactId());
                if (!artifact.targetKind().equals("EXERCISE")) continue;
                JsonNode command = plan.replacement() != null ? plan.replacement()
                        : repository.revision(artifact.artifactId(), artifact.currentRevisionId()).orElseThrow().payload().path("command");
                UUID child = Commands.derive(commandId, artifact.artifactId().toString());
                JsonNode ack;
                try {
                    ack = exercisePublisher.create(session.ownerId(), session.deckId(), position.version(), child, position.revisionId(),
                            command.path("objective"), command.path("exercise"));
                } catch (ResourceNotFoundException | GeneratedExercisePublisher.ObjectiveUnavailableException gone) {
                    // the catalog answers an opaque 404 for a pin that is not the head any more: for this command it is a moved source
                    throw new SourceMoved(artifact.artifactId());
                }
                published.put(artifact.artifactId(), new Published(Json.object().put("kind", "EXERCISE")
                        .put("exerciseId", ack.path("exerciseId").stringValue(null))
                        .put("exerciseRevisionId", ack.path("exerciseRevisionId").stringValue(null))
                        .put("objectiveId", ack.path("objectiveId").stringValue(null))
                        .put("objectiveRevisionId", ack.path("objectiveRevisionId").stringValue(null)), child, plan.replacement() != null));
                position = new Chain(Long.parseLong(ack.path("deckVersion").stringValue("0")),
                        UUID.fromString(ack.path("deckRevisionId").stringValue("")));
            }
            return apply(session.sessionId(), commandId, plans, published, position, bulk);
        }));
    }

    /**
     * The checks that decide whether the artifacts can be approved, in the contract's order: stale versions (412), a state that
     * forbids it (409 ILLEGAL_STATE), a source that moved (409 SOURCE_STALE) and a media slot that is not ready (409
     * MEDIA_NOT_READY). With {@code detectDrift} (before the publish transaction) PROPOSED artifacts whose sources drifted
     * are dealt with in short transactions of their own that commit before the 409 is raised: a proposed exercise is re-pinned to
     * the new revision of its material when it can follow it (then {@code plans} and {@code artifacts} name the re-pinned artifact
     * and the approval goes on), every other one becomes STALE.
     */
    private void check(Session session, List<Plan> plans, Map<UUID, Artifact> artifacts, boolean bulk, boolean detectDrift) {
        List<UUID> staleVersions = new ArrayList<>();
        for (Plan plan : plans) {
            Artifact artifact = artifacts.get(plan.artifactId());
            if (artifact.rowVersion() != plan.artifactVersion() || !Objects.equals(artifact.currentRevisionId(), plan.revisionId())) {
                staleVersions.add(artifact.artifactId());
            }
        }
        if (!staleVersions.isEmpty()) throw new StaleArtifactsException(bulk ? staleVersions : List.of());

        List<UUID> all = plans.stream().map(Plan::artifactId).toList();
        if (!REVIEWABLE.contains(session.state())) throw conflict(Reason.ILLEGAL_STATE, all, bulk);
        List<UUID> illegal = new ArrayList<>();
        List<UUID> stale = new ArrayList<>();
        Map<UUID, Long> proposed = new LinkedHashMap<>();
        for (Plan plan : plans) {
            Artifact artifact = artifacts.get(plan.artifactId());
            switch (artifact.state()) {
                case "STALE" -> stale.add(artifact.artifactId());
                case "PROPOSED" -> proposed.put(artifact.artifactId(), artifact.rowVersion());
                default -> illegal.add(artifact.artifactId());
            }
        }
        if (!illegal.isEmpty()) throw conflict(Reason.ILLEGAL_STATE, illegal, bulk);
        if (detectDrift) {
            Map<UUID, Long> drifted = new LinkedHashMap<>();
            for (Map.Entry<UUID, Long> entry : proposed.entrySet()) {
                Artifact artifact = artifacts.get(entry.getKey());
                if (drift.drifted(session, artifact).isEmpty()) continue;
                Plan plan = plans.stream().filter(candidate -> candidate.artifactId().equals(artifact.artifactId())).findFirst().orElseThrow();
                if (artifact.targetKind().equals("EXERCISE") && plan.replacement() == null) {
                    // a pure re-pin: the exercise is the same, its pins follow the material, so the user's approval just works
                    Optional<Artifact> repinned = repins.repin(session.sessionId(), artifact.artifactId(), artifact.rowVersion());
                    if (repinned.isPresent()) {
                        replace(plans, artifacts, plan, repinned.get());
                    } else {
                        stale.add(artifact.artifactId());
                    }
                } else if (plan.replacement() != null && followsHead(session, artifact, plan.replacement())) {
                    // the owner's edit was written against the head as it is now: the artifact's own pin is of no concern
                    continue;
                } else {
                    drifted.put(entry.getKey(), entry.getValue());
                }
            }
            if (!drifted.isEmpty()) stale.addAll(drift.markStale(session.sessionId(), drifted));
        }
        if (!stale.isEmpty()) throw conflict(Reason.SOURCE_STALE, stale, bulk);
        List<UUID> unready = new ArrayList<>();
        for (Map.Entry<UUID, Artifact> entry : artifacts.entrySet()) {
            Artifact artifact = entry.getValue();
            if (!artifact.state().equals("PROPOSED") || !artifact.targetKind().equals("ITEM")) continue;
            if (repository.slots(artifact.artifactId(), artifact.currentRevisionId()).stream().anyMatch(slot -> !resolved(slot))) {
                unready.add(artifact.artifactId());
            }
        }
        if (!unready.isEmpty()) throw conflict(Reason.MEDIA_NOT_READY, unready, bulk);
    }

    /** Whether the edited exercise stands on the head of its material: its subject and every quoted material are pinned at it. */
    private boolean followsHead(Session session, Artifact artifact, JsonNode replacement) {
        List<Drift> drifted = drift.drifted(session, artifact);
        if (drifted.size() != 1 || drifted.getFirst().gone()) return false;
        String head = drifted.getFirst().current().path("itemRevisionId").stringValue("");
        JsonNode exercise = replacement.path("exercise");
        boolean[] pinned = {exercise.path("subject").path("itemRevisionId").stringValue("").equals(head)};
        SessionViews.quoted(exercise.path("content"), quote -> pinned[0] &= quote.path("itemRevisionId").stringValue("").equals(head));
        return pinned[0];
    }

    /** The plan of a re-pinned artifact now names its new version and revision (the user saw this exercise: it only follows its material). */
    private static void replace(List<Plan> plans, Map<UUID, Artifact> artifacts, Plan plan, Artifact repinned) {
        plans.set(plans.indexOf(plan), new Plan(plan.artifactId(), repinned.rowVersion(), repinned.currentRevisionId(), plan.replacement()));
        artifacts.put(repinned.artifactId(), repinned);
    }

    /**
     * A slot approval can live with: its media is READY or the user explicitly removed it. Invariant for AI-11: a REMOVED slot
     * means its node is gone from the current revision, so the edit that removes media must remove the node together with the
     * slot (a REMOVED slot whose node stays would make approval publish a reference to an asset that never exists).
     */
    private static boolean resolved(Slot slot) {
        return slot.state().equals("READY") || slot.state().equals("REMOVED");
    }

    private static GenerationStateConflictException conflict(Reason reason, List<UUID> ids, boolean bulk) {
        return new GenerationStateConflictException(reason, bulk ? ids : List.of());
    }

    /**
     * The completion of the catalog publication, in its transaction: re-checks what a concurrent command could have changed,
     * marks each artifact PUBLISHED (state, reference, provenance, event), releases its media holds and closes the session when
     * nothing is left to review. Any failure rolls the material or the exercise back with it.
     *
     * @param after the deck revision and version the last publication of the approval left
     */
    private JsonNode apply(UUID sessionId, UUID commandId, List<Plan> plans, Map<UUID, Published> published, Chain after, boolean bulk) {
        SessionLifecycle.Tx tx = lifecycle.lock(sessionId);
        if (tx == null) throw new ResourceNotFoundException();
        Map<UUID, Artifact> artifacts = new LinkedHashMap<>();
        for (Plan plan : plans) {
            artifacts.put(plan.artifactId(), repository.artifact(sessionId, plan.artifactId()).orElseThrow(ResourceNotFoundException::new));
        }
        check(tx.session, plans, artifacts, bulk, false);
        ObjectNode acknowledgement = Json.object().put("commandId", commandId.toString())
                .put("deckId", tx.session.deckId().toString())
                .put("deckRevisionId", after.revisionId().toString())
                .put("deckVersion", Long.toString(after.version()));
        ArrayNode listed = acknowledgement.putArray("artifacts");
        for (Plan plan : plans) {
            Artifact artifact = artifacts.get(plan.artifactId());
            Published result = published.get(plan.artifactId());
            Artifact done = repository.publish(artifact, result.reference(), result.command());
            repository.insertProvenance(tx.session.ownerId(), sessionId, artifact.artifactId(), artifact.currentRevisionId(), result.reference(),
                    repository.provenance(artifact.artifactId()), result.edited());
            // the catalog holds the assets through the revision it just stored; the Workshop's hold is no longer needed
            repository.releaseMediaHolds(artifact.artifactId());
            tx.events.add(SessionLifecycle.artifactEvent(done));
            listed.addObject().put("artifactId", artifact.artifactId().toString()).put("state", "PUBLISHED").set("publishedRef", result.reference());
        }
        lifecycle.closeIfDone(tx);
        lifecycle.flush(tx);
        return acknowledgement;
    }

    private static NativeDocument document(JsonNode document) {
        return new NativeDocumentReader().read(document.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- reject / undo

    /** {@code rejectArtifact}: reversible disapproval of a proposed or stale artifact. */
    Result reject(UUID owner, UUID deckId, UUID sessionId, UUID artifactId, byte[] raw) {
        session(owner, deckId, sessionId);
        repository.artifact(sessionId, artifactId).orElseThrow(ResourceNotFoundException::new);
        JsonNode body = Commands.read(raw);
        Commands.fields(body, Set.of("commandId", "expectedArtifactVersion"), Set.of());
        UUID commandId = Commands.commandId(body);
        ObjectNode envelope = envelope(deckId, sessionId, body).put("artifactId", artifactId.toString());
        CommandIdentity identity = new CommandIdentity(commandId, owner, SCOPE, "artifact.reject");
        Optional<JsonNode> replay = receipts.replay(identity, envelope);
        if (replay.isPresent()) return new Result(replay.get(), true, null);
        long expected = Commands.version(body, "expectedArtifactVersion");

        boolean[] applied = {false};
        JsonNode summary = receipts.execute(identity, envelope, () -> {
            applied[0] = true;
            SessionLifecycle.Tx tx = lock(sessionId);
            Artifact artifact = reviewable(tx, sessionId, artifactId, expected, null, false, "PROPOSED", "STALE");
            Artifact rejected = repository.transition(artifact, "REJECTED", null, null, null, artifact.revisionCount());
            tx.events.add(SessionLifecycle.artifactEvent(rejected));
            lifecycle.closeIfDone(tx);
            lifecycle.flush(tx);
            return summary(rejected);
        });
        return new Result(summary, !applied[0], applied[0] ? summary.path("rowVersion").stringValue(null) : null);
    }

    /** {@code undoRejectArtifact}: REJECTED back to PROPOSED while the session is not closed; {@code If-Match} is the artifact version. */
    Result undoReject(UUID owner, UUID deckId, UUID sessionId, UUID artifactId, List<String> ifMatch) {
        session(owner, deckId, sessionId);
        repository.artifact(sessionId, artifactId).orElseThrow(ResourceNotFoundException::new);
        long expected = Commands.ifMatch(ifMatch);
        JsonNode summary = Objects.requireNonNull(transaction.execute(status -> {
            SessionLifecycle.Tx tx = lock(sessionId);
            Artifact artifact = reviewable(tx, sessionId, artifactId, expected, null, tx.state.equals("CLOSED"), "REJECTED");
            Artifact proposed = repository.transition(artifact, "PROPOSED", null, null, null, artifact.revisionCount());
            tx.events.add(SessionLifecycle.artifactEvent(proposed));
            // taking back the rejection that closed the session reopens it (a cancelled one stays cancelled); flush refreshes
            // the activity and the expiry
            if (tx.state.equals("CLOSED")) tx.state = tx.endReason == null ? "REVIEW" : "CANCELLED";
            lifecycle.flush(tx);
            return summary(proposed);
        }));
        return new Result(summary, false, summary.path("rowVersion").stringValue(null));
    }

    // -------------------------------------------------------------------- hand-off

    /** {@code handoffArtifact}: opens the current revision as an ordinary {@code EditingDraft}; the artifact is {@code HANDED_OFF}. */
    Result handoff(UUID owner, UUID deckId, UUID sessionId, UUID artifactId, byte[] raw) {
        session(owner, deckId, sessionId);
        Artifact known = repository.artifact(sessionId, artifactId).orElseThrow(ResourceNotFoundException::new);
        JsonNode body = Commands.read(raw);
        Commands.fields(body, Set.of("commandId", "expectedArtifactVersion", "expectedRevisionId"), Set.of());
        UUID commandId = Commands.commandId(body);
        ObjectNode envelope = envelope(deckId, sessionId, body).put("artifactId", artifactId.toString());
        CommandIdentity identity = new CommandIdentity(commandId, owner, SCOPE, "artifact.handoff");
        Optional<JsonNode> replay = receipts.replay(identity, envelope);
        if (replay.isPresent()) return new Result(replay.get(), true, null);
        long expected = Commands.version(body, "expectedArtifactVersion");
        UUID expectedRevision = Commands.entity(body, "expectedRevisionId");
        // an exercise hand-off is defined by AI-13; here it is not a valid request
        if (!known.targetKind().equals("ITEM")) throw new InvalidRequestException();

        boolean[] applied = {false};
        String[] etag = new String[1];
        JsonNode acknowledgement = receipts.execute(identity, envelope, () -> {
            applied[0] = true;
            SessionLifecycle.Tx tx = lock(sessionId);
            Artifact artifact = reviewable(tx, sessionId, artifactId, expected, expectedRevision, false, "PROPOSED", "STALE");
            JsonNode draft;
            try {
                draft = drafts.open(tx.session.ownerId(), deckId, Commands.derive(commandId, "draft:" + artifactId),
                        handoffDocument(artifact));
            } catch (ResourceLimitExceededException limit) {
                throw new ResourceLimitExceededException(ProblemExtension.builder().put("limit", "EDITING_DRAFTS").build());
            }
            Artifact handedOff = repository.transition(artifact, "HANDED_OFF", null, null, null, artifact.revisionCount());
            repository.releaseMediaHolds(artifact.artifactId());
            // the artifact no longer changes: its waiting media steps stop and slots still open are settled as cancelled
            steps.cancelMedia(artifact.artifactId());
            for (Slot slot : repository.failOpenSlotsOf(artifact.artifactId(), "CANCELLED")) {
                tx.events.add(new Rows.EventDraft("MEDIA_SLOT_STATE", artifact.artifactId(), Json.object()
                        .put("slotKey", slot.slotKey()).put("kind", slot.kind()).put("state", "FAILED")
                        .put("assetId", slot.assetId().toString()).put("errorCode", "CANCELLED")));
            }
            tx.events.add(SessionLifecycle.artifactEvent(handedOff));
            lifecycle.closeIfDone(tx);
            lifecycle.flush(tx);
            JsonNode created = draft.path("draft");
            etag[0] = created.path("rowVersion").stringValue(null);
            ObjectNode result = Json.object();
            result.set("artifact", summary(handedOff));
            ObjectNode projected = result.putObject("draft");
            projected.put("draftId", created.path("draftId").stringValue(null)).put("deckId", deckId.toString());
            projected.put("memberKey", created.path("memberKey").stringValue(null));
            projected.put("baseRevisionId", created.path("baseRevisionId").stringValue(null));
            return result;
        });
        return new Result(acknowledgement, !applied[0], applied[0] ? etag[0] : null);
    }

    /**
     * The document the editor opens: the current revision without the media nodes whose assets are not ready (a placeholder
     * node cannot be saved: its asset does not exist). Ready media stays and the draft holds it like any draft.
     */
    private NativeDocument handoffDocument(Artifact artifact) {
        Revision revision = repository.revision(artifact.artifactId(), artifact.currentRevisionId()).orElseThrow();
        JsonNode document = revision.payload().path("document").deepCopy();
        Set<String> missing = repository.slots(artifact.artifactId(), revision.revisionId()).stream()
                .filter(slot -> !slot.state().equals("READY")).map(slot -> slot.nodeId().toString()).collect(Collectors.toSet());
        if (!missing.isEmpty()) dropNodes(document.path("root"), missing);
        return document(document);
    }

    private static void dropNodes(JsonNode node, Set<String> ids) {
        JsonNode content = node.path("content");
        if (!content.isArray()) return;
        ArrayNode children = (ArrayNode) content;
        for (int index = children.size() - 1; index >= 0; index--) {
            if (ids.contains(children.get(index).path("id").stringValue(""))) children.remove(index);
            else dropNodes(children.get(index), ids);
        }
    }

    // ----------------------------------------------------------------------- retry

    /**
     * {@code retryArtifact}: a FAILED artifact (not a refusal) or a STALE one is written again, against the pins as they are
     * now for a stale one. A new reservation is made first; the usage refusal leaves nothing.
     */
    Result retry(UUID owner, UUID deckId, UUID sessionId, UUID artifactId, byte[] raw) {
        session(owner, deckId, sessionId);
        repository.artifact(sessionId, artifactId).orElseThrow(ResourceNotFoundException::new);
        JsonNode body = Commands.read(raw);
        Commands.fields(body, Set.of("commandId", "expectedArtifactVersion"), Set.of());
        UUID commandId = Commands.commandId(body);
        ObjectNode envelope = envelope(deckId, sessionId, body).put("artifactId", artifactId.toString());
        CommandIdentity identity = new CommandIdentity(commandId, owner, SCOPE, "artifact.retry");
        Optional<JsonNode> replay = receipts.replay(identity, envelope);
        if (replay.isPresent()) return new Result(replay.get(), true, null);
        long expected = Commands.version(body, "expectedArtifactVersion");

        boolean[] applied = {false};
        JsonNode summary = receipts.execute(identity, envelope, () -> {
            applied[0] = true;
            // the admission lock first (as createSession takes it), so the count of active sessions below cannot race a create
            repository.lockAdmission(owner);
            return requeue(sessionId, artifactId, expected);
        });
        return new Result(summary, !applied[0], applied[0] ? summary.path("rowVersion").stringValue(null) : null);
    }

    private JsonNode requeue(UUID sessionId, UUID artifactId, long expected) {
        SessionLifecycle.Tx tx = lock(sessionId);
        Artifact artifact = repository.artifact(sessionId, artifactId).orElseThrow(ResourceNotFoundException::new);
        if (artifact.rowVersion() != expected) throw new VersionConflictException();
        boolean stale = artifact.state().equals("STALE");
        if (!RETRYABLE.contains(tx.state) || !(stale || artifact.state().equals("FAILED"))) {
            throw new GenerationStateConflictException(Reason.ILLEGAL_STATE);
        }
        if (!stale && "REFUSAL".equals(artifact.errorCode())) throw new GenerationStateConflictException(Reason.NOT_RETRYABLE);

        Session session = tx.session;
        List<Drift> drifted = drift.drifted(session, artifact);
        // a stale artifact is regenerated against what the sources are now; a failed one needs its pins to hold
        // an exercise that failed while its material moved is written again against the head, as a stale one is: it has no pins to hold
        boolean exercise = artifact.targetKind().equals("EXERCISE");
        if (drifted.stream().anyMatch(Drift::gone) || (!stale && !exercise && !drifted.isEmpty())) {
            throw new SourceUnavailableException(drifted.stream().map(Drift::source).toList());
        }
        if (exercise) return requeueExercise(tx, artifact, !drifted.isEmpty(), drifted);
        MaterialsSpec spec = MaterialsSpec.read(session.spec());
        MaterialsSpec.Effective effective = spec.forArtifact(artifact.sourceRefs());
        gate.requireFor(effective);
        requireRoomWhenReopened(tx, session);
        String operation = AdmissionPricing.materialOperation(effective.workingEffort());
        int credits = pricing.credits(operation);
        // usage is last: a refusal rolls this transaction back, so nothing above has changed
        Reservation reservation = ledger.reserve(session.ownerId(), ReservationScope.STEP, session.sessionId(), null, Math.max(1, credits));

        repository.dropMedia(artifactId);
        repository.cancelTurns(artifactId);
        steps.cancelMedia(artifactId);
        JsonNode pins = stale ? drift.repinned(artifact, drifted) : artifact.sourceRefs();
        // a re-pinned note is read at its new version from a snapshot taken now (a note that moved again meanwhile is a 409)
        for (Drift moved : drifted) {
            if (moved.current() != null && moved.current().path("type").stringValue("").equals("NOTE")
                    && !repository.snapshotNote(sessionId, session.ownerId(), moved.source().id(),
                    Long.parseLong(moved.current().path("noteRowVersion").stringValue("0")))) {
                throw new SourceUnavailableException(List.of(moved.source()));
            }
        }
        Artifact queued = repository.requeue(artifact, pins);
        ObjectNode input = Json.object().put("effort", effective.workingEffort()).put("operation", operation).put("credits", credits)
                .put("reservationId", reservation.reservationId().toString());
        steps.insert(UUID.randomUUID(), sessionId, artifactId, session.ownerId(), TextDraftExecutor.KIND, "TEXT", input,
                "draft:" + artifactId + ":" + (steps.draftCount(artifactId) + 1));
        tx.events.add(SessionLifecycle.artifactEvent(queued));
        tx.events.add(lifecycle.usageEvent(session, null));
        if (tx.state.equals("REVIEW")) tx.state = "RUNNING";
        lifecycle.flush(tx);
        wakeAfterCommit();
        return summary(queued);
    }

    /** A REVIEW session with only failed or rejected leftovers does not count as active; retrying makes it count again. */
    private void requireRoomWhenReopened(SessionLifecycle.Tx tx, Session session) {
        if (tx.state.equals("REVIEW") && repository.artifactCounts(session.sessionId()).entrySet().stream()
                .noneMatch(entry -> Set.of("PROPOSED", "REVISING", "STALE").contains(entry.getKey()) && entry.getValue() > 0)) {
            List<UUID> active = repository.activeSessionIds(session.ownerId());
            if (active.size() >= settings.maxActiveSessions()) {
                throw new ResourceLimitExceededException(ProblemExtension.builder().put("limit", "ACTIVE_SESSIONS")
                        .put("limits", Map.of("maxActiveSessions", settings.maxActiveSessions()))
                        .put("activeSessionIds", active.stream().map(UUID::toString).toList()).build());
            }
        }
    }

    /**
     * Retry of an {@code EXERCISE} artifact: one new step writes this one exercise again, against the pins as they are now for a stale
     * one (the artifact's pin follows the material's head). One exercise is priced as one exercise: a step reservation of its credits is
     * made first and a refusal changes nothing.
     */
    private JsonNode requeueExercise(SessionLifecycle.Tx tx, Artifact artifact, boolean moved, List<Drift> drifted) {
        Session session = tx.session;
        gate.requireText();
        requireRoomWhenReopened(tx, session);
        int credits = pricing.exerciseCredits(1);
        // usage is last: a refusal rolls this transaction back, so nothing above has changed
        Reservation reservation = ledger.reserve(session.ownerId(), ReservationScope.STEP, session.sessionId(), null, Math.max(1, credits));
        repository.cancelTurns(artifact.artifactId());
        JsonNode pins = moved ? drift.repinned(artifact, drifted) : artifact.sourceRefs();
        Artifact queued = repository.requeue(artifact, pins);
        ObjectNode input = Json.object().put("operation", ExerciseDraftExecutor.OPERATION)
                .put("memberKey", pins.path(0).path("memberKey").stringValue(""))
                .put("itemRevisionId", pins.path(0).path("itemRevisionId").stringValue("")).put("count", 1).put("credits", credits)
                .put("reservationId", reservation.reservationId().toString());
        input.putArray("artifactIds").add(artifact.artifactId().toString());
        steps.insert(UUID.randomUUID(), session.sessionId(), artifact.artifactId(), session.ownerId(), TextDraftExecutor.KIND, "TEXT", input,
                "draft:" + artifact.artifactId() + ":" + (steps.draftCount(artifact.artifactId()) + 1));
        tx.events.add(SessionLifecycle.artifactEvent(queued));
        tx.events.add(lifecycle.usageEvent(session, null));
        if (tx.state.equals("REVIEW")) tx.state = "RUNNING";
        lifecycle.flush(tx);
        wakeAfterCommit();
        return summary(queued);
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

    // ---------------------------------------------------------------------- delete

    /**
     * {@code deleteSession}: hold-to-delete. The unpublished rows (artifacts, revisions, slots, holds, steps, events) go
     * with the session and its credit holds are released; what was published or handed off stays in the catalog and in the
     * editor. The first call answers 204 and later calls 404.
     */
    void delete(UUID owner, UUID deckId, UUID sessionId) {
        session(owner, deckId, sessionId);
        transaction.executeWithoutResult(status -> {
            SessionLifecycle.Tx tx = lock(sessionId);
            lifecycle.releaseHolds(tx.session);
            repository.deleteSession(sessionId);
        });
    }

    // ----------------------------------------------------------------- note archival

    /** {@code archiveUsedNotes}: archives the notes the session used by their pinned versions; idempotent. */
    Result archiveNotes(UUID owner, UUID deckId, UUID sessionId, byte[] raw) {
        session(owner, deckId, sessionId);
        JsonNode body = Commands.read(raw);
        Commands.fields(body, Set.of("commandId"), Set.of());
        UUID commandId = Commands.commandId(body);
        ObjectNode envelope = envelope(deckId, sessionId, body);
        CommandIdentity identity = new CommandIdentity(commandId, owner, SCOPE, "session.note-archival");
        Optional<JsonNode> replay = receipts.replay(identity, envelope);
        if (replay.isPresent()) return new Result(replay.get(), true, null);
        boolean[] applied = {false};
        JsonNode result = receipts.execute(identity, envelope, () -> {
            applied[0] = true;
            SessionLifecycle.Tx tx = lock(sessionId);
            return notes.archive(tx.session);
        });
        return new Result(result, !applied[0], null);
    }

    // --------------------------------------------------------------------- helpers

    private Session session(UUID owner, UUID deckId, UUID sessionId) {
        return repository.session(owner, deckId, sessionId).orElseThrow(ResourceNotFoundException::new);
    }

    private SessionLifecycle.Tx lock(UUID sessionId) {
        SessionLifecycle.Tx tx = lifecycle.lock(sessionId);
        if (tx == null) throw new ResourceNotFoundException();
        return tx;
    }

    /**
     * The artifact of a command that moves one proposal: its version and, when the command names one, its revision (412)
     * first, then the states the session and the artifact allow (409 ILLEGAL_STATE).
     */
    private Artifact reviewable(SessionLifecycle.Tx tx, UUID sessionId, UUID artifactId, long expected, UUID expectedRevision,
                                boolean closedToo, String... states) {
        Artifact artifact = repository.artifact(sessionId, artifactId).orElseThrow(ResourceNotFoundException::new);
        if (artifact.rowVersion() != expected
                || (expectedRevision != null && !expectedRevision.equals(artifact.currentRevisionId()))) {
            throw new VersionConflictException();
        }
        if (!(REVIEWABLE.contains(tx.state) || closedToo) || !Set.of(states).contains(artifact.state())) {
            throw new GenerationStateConflictException(Reason.ILLEGAL_STATE);
        }
        return artifact;
    }

    private JsonNode summary(Artifact artifact) {
        return SessionViews.artifactSummary(artifact, repository.slotCounts(List.of(artifact.artifactId())).get(artifact.artifactId()));
    }

    /** The receipt envelope of a command: the path ids and the whole body, so any change of either is a different command. */
    private static ObjectNode envelope(UUID deckId, UUID sessionId, JsonNode body) {
        ObjectNode envelope = Json.object().put("deckId", deckId.toString()).put("sessionId", sessionId.toString());
        envelope.set("body", body);
        return envelope;
    }
}
