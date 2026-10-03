package app.mnema.learning.generation;

import app.mnema.learning.catalog.content.NativeDocumentPreview;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.generation.ContextBuilder.SourceGoneException;
import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.EventDraft;
import app.mnema.learning.generation.Rows.Revision;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.Rows.Slot;
import app.mnema.learning.generation.Rows.Source;
import app.mnema.learning.generation.Rows.Turn;
import app.mnema.learning.generation.exercise.ExerciseValidator;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.usage.AdmissionPricing;
import app.mnema.learning.usage.Reservation;
import app.mnema.learning.usage.ReservationScope;
import app.mnema.learning.usage.UsageLedger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The admission of a {@code REVISE_ITEM} or {@code REVISE_EXERCISE} session ({@code contracts/generation/README.md}, decision 16): one
 * artifact that starts as a <em>copy</em> of what exists (the current document of the material, the current command of the exercise, no
 * model call), a first turn queued on it and the hold of that turn. The session has no batch reservation: its holds are the turns' own,
 * one {@code EDIT_SELECTION} for a rewrite and, for the redo of an exercise's audio, one text-to-speech clip that waits for the rewrite
 * (it is a step of the session in {@code WAITING_DEPENDENCIES}, promoted by {@link EditLifecycle#succeed}).
 *
 * <p>Everything that can refuse comes before usage, which is last: an owner's refusal (404), a target that is no longer the head (409
 * {@code SOURCE_UNAVAILABLE}, by the capability gate), a material or an exercise the model cannot be given (400 with a {@code reason})
 * or one that is too long (422 {@code EDIT_TARGET_SIZE}). A refusal rolls the whole admission back.
 */
@Component
class ReviseAdmission {
    private final GenerationRepository repository;
    private final StepRepository steps;
    private final SessionLifecycle lifecycle;
    private final SessionViews views;
    private final UsageLedger ledger;
    private final AdmissionPricing pricing;
    private final GenerationSettings settings;
    private final AdmissionLimits limits;
    private final ItemService items;
    private final ExerciseService exercises;
    private final PinnedMaterials materials;
    private final EditContexts editContexts;
    private final ExerciseContexts exerciseContexts;
    private final ExerciseValidator validator;
    private final ObjectProvider<StepDispatcher> dispatcher;

    ReviseAdmission(GenerationRepository repository, StepRepository steps, SessionLifecycle lifecycle, SessionViews views,
                    UsageLedger ledger, AdmissionPricing pricing, GenerationSettings settings, AdmissionLimits limits, ItemService items,
                    ExerciseService exercises, PinnedMaterials materials, EditContexts editContexts, ExerciseContexts exerciseContexts,
                    ExerciseValidator validator, ObjectProvider<StepDispatcher> dispatcher) {
        this.repository = repository;
        this.steps = steps;
        this.lifecycle = lifecycle;
        this.views = views;
        this.ledger = ledger;
        this.pricing = pricing;
        this.settings = settings;
        this.limits = limits;
        this.items = items;
        this.exercises = exercises;
        this.materials = materials;
        this.editContexts = editContexts;
        this.exerciseContexts = exerciseContexts;
        this.validator = validator;
        this.dispatcher = dispatcher;
    }

    /** The session detail of the new session, in the admission transaction. */
    JsonNode admit(UUID owner, UUID deckId, JsonNode spec, ReviseSpec parsed, AdmissionPricing.Hold hold) {
        repository.lockAdmission(owner);
        limits.requireRoom(owner);
        return parsed.kind().equals(ReviseSpec.ITEM) ? admitItem(owner, deckId, spec, parsed, hold)
                : admitExercise(owner, deckId, spec, parsed, hold);
    }

    // ------------------------------------------------------------------------ REVISE_ITEM

    private JsonNode admitItem(UUID owner, UUID deckId, JsonNode spec, ReviseSpec parsed, AdmissionPricing.Hold hold) {
        JsonNode head = items.read(owner, deckId, parsed.memberKey(), null);
        // the target was the head when the spec was checked; it must still be (a second device may have saved since)
        if (!parsed.itemRevisionId().toString().equals(head.path("itemRevisionId").stringValue(""))) {
            throw new SourceUnavailableException(List.of(new SourceUnavailableException.Unavailable("ITEM", parsed.memberKey())));
        }
        JsonNode document = head.path("document");
        List<UUID> blocks = EditDocument.ids(document);
        // the whole material is the target: every top-level block, consecutive by construction
        EditTarget target = EditTarget.resolve(document, blocks).orElseThrow(() -> InvalidRequestException.because("TARGET_MEDIA_ONLY"));
        if (target.text().isEmpty()) throw InvalidRequestException.because("TARGET_MEDIA_ONLY");
        editContexts.refusal(target).ifPresent(reason -> {
            throw InvalidRequestException.because(reason);
        });
        if (editContexts.tokens(target) > EditContexts.MAX_TARGET_TOKENS) throw ArtifactEdits.limit("EDIT_TARGET_SIZE");

        hold.requireFits();
        UUID sessionId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        int credits = pricing.credits(EditLifecycle.OPERATION);
        Reservation reservation = ledger.reserve(owner, ReservationScope.TURN, sessionId, turnId, Math.max(1, credits));
        insertSession(owner, deckId, sessionId, ReviseSpec.ITEM, spec, new Source(0, "SOURCE", "ITEM", null, null, parsed.memberKey(),
                parsed.itemRevisionId()));

        UUID artifactId = UUID.randomUUID();
        repository.insertArtifact(artifactId, sessionId, owner, "ITEM", 0, Json.array().add(itemRef(parsed.memberKey(), parsed.itemRevisionId())));
        UUID revisionId = UUID.randomUUID();
        ObjectNode payload = Json.object().put("kind", "NATIVE_DOCUMENT");
        payload.set("document", document.deepCopy());
        ObjectNode handles = Json.object();
        EditDocument.handles(document).forEach((handle, nodeId) -> handles.put(handle, nodeId.toString()));
        String title = NativeDocumentPreview.title(new NativeDocumentReader().read(document.toString().getBytes(StandardCharsets.UTF_8)));
        repository.insertRevision(new Revision(revisionId, artifactId, 1, "INITIAL", payload, handles, "v1", "deterministic", validation(), Instant.now()),
                sessionId, owner);
        Artifact revising = repository.transition(repository.artifact(sessionId, artifactId).orElseThrow(), "REVISING", null, revisionId, title, 1);

        UUID stepId = UUID.randomUUID();
        repository.insertTurn(new Turn(turnId, artifactId, sessionId, owner, "QUEUED", "FREE", null, parsed.instruction(), blocks, stepId, null,
                null, true, null, null));
        steps.insert(stepId, sessionId, artifactId, owner, EditExecutor.KIND, "TEXT", editInput(turnId, revisionId, credits, reservation), "edit:" + turnId);
        return started(sessionId, List.of(SessionLifecycle.artifactEvent(revising)));
    }

    // -------------------------------------------------------------------- REVISE_EXERCISE

    private JsonNode admitExercise(UUID owner, UUID deckId, JsonNode spec, ReviseSpec parsed, AdmissionPricing.Hold hold) {
        JsonNode stored = exercises.read(owner, deckId, parsed.exerciseId(), null);
        if (!parsed.exerciseRevisionId().toString().equals(stored.path("exerciseRevisionId").stringValue(""))) {
            throw new SourceUnavailableException(List.of(new SourceUnavailableException.Unavailable("EXERCISE", parsed.exerciseId())));
        }
        JsonNode objective = stored.path("objective");
        ObjectNode exercise = Json.object().put("type", stored.path("type").stringValue("")).put("schemaVersion", 2)
                .put("enabled", stored.path("enabled").booleanValue(true));
        exercise.set("subject", stored.path("subject").deepCopy());
        exercise.set("content", stored.path("content").deepCopy());
        exercise.set("answerKey", stored.path("answerKey").deepCopy());
        exercise.set("evaluatorPolicy", stored.path("evaluatorPolicy").deepCopy());
        UUID member = UUID.fromString(exercise.path("subject").path("memberKey").stringValue(""));
        UUID pinned = followHead(owner, deckId, objective, exercise, member);

        ObjectNode command = Json.object();
        ObjectNode reuse = command.putObject("objective");
        reuse.put("operation", "reuse").put("objectiveId", objective.path("objectiveId").stringValue(""))
                .put("objectiveRevisionId", objective.path("objectiveRevisionId").stringValue(""));
        command.set("exercise", exercise);

        UUID sessionId = UUID.randomUUID();
        List<String> audioAssets = new ArrayList<>();
        audioBlocks(exercise.path("content"), audioAssets);
        if (parsed.hasMedia() && audioAssets.isEmpty()) throw InvalidRequestException.because("TARGET_NO_AUDIO");
        if (parsed.hasInstruction()) {
            // the model is shown the exercise in the output form: one it has no such form for is edited by hand
            Session probe = new Session(sessionId, owner, deckId, ReviseSpec.EXERCISE, "RUNNING", null, spec, null, 0, 0, null, null, null);
            try {
                exerciseContexts.buildEdit(probe, member, pinned, command, parsed.instruction(), parsed.outputLanguage());
            } catch (ExerciseContexts.Refusal refused) {
                throw InvalidRequestException.because(refused.reason());
            } catch (SourceGoneException gone) {
                throw new SourceUnavailableException(List.of(new SourceUnavailableException.Unavailable("ITEM", member)));
            } catch (app.mnema.learning.ai.prompt.PromptException tooBig) {
                throw ArtifactEdits.limit("EDIT_TARGET_SIZE");
            }
        }

        hold.requireFits();
        UUID editTurn = UUID.randomUUID();
        UUID mediaTurn = UUID.randomUUID();
        Reservation editHold = null;
        int editCredits = 0;
        if (parsed.hasInstruction()) {
            editCredits = pricing.credits(EditLifecycle.OPERATION);
            editHold = ledger.reserve(owner, ReservationScope.TURN, sessionId, editTurn, Math.max(1, editCredits));
        }
        Reservation mediaHold = null;
        int mediaCredits = 0;
        if (parsed.hasMedia()) {
            mediaCredits = pricing.credits(EditLifecycle.MEDIA_OPERATION);
            mediaHold = ledger.reserve(owner, ReservationScope.TURN, sessionId, mediaTurn, Math.max(1, mediaCredits));
        }
        ArrayNode refs = Json.array().add(itemRef(member, pinned));
        refs.addObject().put("type", "EXERCISE").put("exerciseId", parsed.exerciseId().toString())
                .put("exerciseRevisionId", parsed.exerciseRevisionId().toString());
        insertSession(owner, deckId, sessionId, ReviseSpec.EXERCISE, spec, new Source(0, "SOURCE", "ITEM", null, null, member, pinned));

        UUID artifactId = UUID.randomUUID();
        repository.insertArtifact(artifactId, sessionId, owner, "EXERCISE", 0, refs);
        UUID revisionId = UUID.randomUUID();
        ObjectNode payload = Json.object().put("kind", "EXERCISE_COMMAND");
        payload.set("command", command);
        repository.insertRevision(new Revision(revisionId, artifactId, 1, "INITIAL", payload, Json.object(), "v1", "deterministic", validation(),
                Instant.now()), sessionId, owner);
        String title = ExerciseValidator.title(exercise, objective.path("title").stringValue(""));
        Artifact revising = repository.transition(repository.artifact(sessionId, artifactId).orElseThrow(), "REVISING", null, revisionId, title, 1);

        List<EventDraft> events = new ArrayList<>();
        events.add(SessionLifecycle.artifactEvent(revising));
        int number = 1;
        for (String asset : audioAssets) {
            UUID nodeId = UUID.randomUUID();
            ObjectNode slotSpec = Json.object().put("mode", "existing");
            slotSpec.putNull("voice");
            repository.insertSlot(new Slot(artifactId, "audio" + number, revisionId, nodeId, "AUDIO", slotSpec, UUID.fromString(asset), "READY", null),
                    sessionId, owner);
            events.add(SessionLifecycle.slotEvent(artifactId, "audio" + number++, "AUDIO", "READY", UUID.fromString(asset)));
        }

        UUID mediaStep = UUID.randomUUID();
        if (parsed.hasInstruction()) {
            UUID editStep = UUID.randomUUID();
            repository.insertTurn(new Turn(editTurn, artifactId, sessionId, owner, "QUEUED", "FREE", null, parsed.instruction(), List.of(), editStep,
                    null, null, true, null, null));
            steps.insert(editStep, sessionId, artifactId, owner, EditExecutor.KIND, "TEXT", editInput(editTurn, revisionId, editCredits, editHold),
                    "edit:" + editTurn);
            if (parsed.hasMedia()) {
                // the audio is redone on the text as it will be: the media turn starts when the rewrite is applied
                steps.insertWaiting(mediaStep, sessionId, artifactId, owner, "TTS", "TTS", mediaInput(mediaTurn, parsed.voice(), mediaCredits, mediaHold),
                        "media:" + mediaTurn, editStep);
            }
        } else {
            repository.insertTurn(new Turn(mediaTurn, artifactId, sessionId, owner, "QUEUED", "AUDIO_REGENERATE", null, null, List.of(), mediaStep,
                    null, null, true, null, parsed.voice()));
            steps.insert(mediaStep, sessionId, artifactId, owner, "TTS", "TTS", mediaInput(mediaTurn, parsed.voice(), mediaCredits, mediaHold),
                    "media:" + mediaTurn);
        }
        return started(sessionId, events);
    }

    /**
     * The exercise must stand on the head of its material, as a publication demands: when the material was revised since the exercise was
     * published, its subject and quotes are moved to the head, which is possible only when every quoted block still reads as text there
     * and the exercise passes the checks that read the material, the publication parser and the probes again (the re-pin of a proposal
     * does the same). A quote of another material that is not at its head, or a material that is gone, is {@code SOURCE_UNAVAILABLE}.
     *
     * @return the revision of the subject material the exercise now stands on
     */
    private UUID followHead(UUID owner, UUID deckId, JsonNode objective, ObjectNode exercise, UUID member) {
        Set<UUID> members = new LinkedHashSet<>();
        members.add(member);
        SessionViews.quoted(exercise.path("content"), quote -> members.add(UUID.fromString(quote.path("memberKey").stringValue(""))));
        Map<UUID, UUID> heads = repository.headRevisions(owner, deckId, members);
        for (UUID each : members) {
            if (!heads.containsKey(each)) {
                throw new SourceUnavailableException(List.of(new SourceUnavailableException.Unavailable("ITEM", each)));
            }
        }
        UUID head = heads.get(member);
        boolean[] moved = {!head.toString().equals(exercise.path("subject").path("itemRevisionId").stringValue(""))};
        boolean[] foreign = {false};
        SessionViews.quoted(exercise.path("content"), quote -> {
            UUID quoted = UUID.fromString(quote.path("memberKey").stringValue(""));
            if (!heads.get(quoted).toString().equals(quote.path("itemRevisionId").stringValue(""))) {
                if (quoted.equals(member)) moved[0] = true;
                else foreign[0] = true;
            }
        });
        if (foreign[0]) throw new SourceUnavailableException(List.of(new SourceUnavailableException.Unavailable("ITEM", member)));
        if (!moved[0]) return head;

        PinnedMaterials.Pinned current = materials.read(owner, deckId, member, head)
                .orElseThrow(() -> new SourceUnavailableException(List.of(new SourceUnavailableException.Unavailable("ITEM", member))));
        ((ObjectNode) exercise.path("subject")).put("itemRevisionId", head.toString());
        boolean[] followable = {true};
        SessionViews.quoted(exercise.path("content"), quote -> {
            if (!current.quotable(UUID.fromString(quote.path("nodeId").stringValue("")))) followable[0] = false;
            else ((ObjectNode) quote).put("itemRevisionId", head.toString());
        });
        if (followable[0]) {
            ObjectNode full = Json.object().put("commandId", ExerciseContexts.PLACEHOLDER_COMMAND.toString())
                    .put("expectedDeckRevisionId", ExerciseContexts.PLACEHOLDER_DECK_REVISION.toString());
            ObjectNode reuse = full.putObject("objective");
            reuse.put("operation", "reuse").put("objectiveId", objective.path("objectiveId").stringValue(""))
                    .put("objectiveRevisionId", objective.path("objectiveRevisionId").stringValue(""));
            full.set("exercise", exercise);
            List<String> blockTexts = new ArrayList<>();
            current.blocks().forEach(block -> blockTexts.add(block.text()));
            followable[0] = validator.revalidate(full, current::text, blockTexts).isEmpty();
        }
        if (!followable[0]) throw new SourceUnavailableException(List.of(new SourceUnavailableException.Unavailable("ITEM", member)));
        return head;
    }

    // ------------------------------------------------------------------------ helpers

    private void insertSession(UUID owner, UUID deckId, UUID sessionId, String kind, JsonNode spec, Source source) {
        repository.insertSession(new Session(sessionId, owner, deckId, kind, "RUNNING", null, spec, null, 0, 0, null, null, null),
                settings.sessionRetention());
        repository.insertSource(sessionId, owner, source);
    }

    /** The events of the new session and its detail; the artifact's own events come first, the session's state and usage around them. */
    private JsonNode started(UUID sessionId, List<EventDraft> artifactEvents) {
        Session session = repository.session(sessionId).orElseThrow();
        List<EventDraft> events = new ArrayList<>();
        ObjectNode running = Json.object().put("state", "RUNNING").put("rowVersion", "1");
        running.set("artifactCounts", SessionViews.counts(repository.artifactCounts(sessionId)));
        events.add(new EventDraft("SESSION_STATE", null, running));
        events.addAll(artifactEvents);
        events.add(lifecycle.usageEvent(session, null));
        long[] allocated = repository.update(sessionId, "RUNNING", null, true, events.size(), settings.sessionRetention());
        repository.insertEvents(sessionId, allocated[0], events);
        wakeAfterCommit();
        return views.detail(repository.session(sessionId).orElseThrow());
    }

    private static ObjectNode editInput(UUID turnId, UUID revisionId, int credits, Reservation reservation) {
        return Json.object().put("turnId", turnId.toString()).put("action", "FREE").put("revisionId", revisionId.toString())
                .put("operation", EditLifecycle.OPERATION).put("credits", credits).put("reservationId", reservation.reservationId().toString());
    }

    private static ObjectNode mediaInput(UUID turnId, String voice, int credits, Reservation reservation) {
        return Json.object().put("turnId", turnId.toString()).put("action", "AUDIO_REGENERATE").put("voice", voice)
                .put("operation", EditLifecycle.MEDIA_OPERATION).put("credits", credits)
                .put("reservationId", reservation.reservationId().toString());
    }

    private static ObjectNode itemRef(UUID memberKey, UUID itemRevisionId) {
        return Json.object().put("type", "ITEM").put("memberKey", memberKey.toString()).put("itemRevisionId", itemRevisionId.toString());
    }

    private static ObjectNode validation() {
        ObjectNode validation = Json.object();
        validation.putArray("warnings");
        return validation;
    }

    /** The asset of every {@code AUDIO} block of an exercise's content, in document order. */
    static void audioBlocks(JsonNode node, List<String> assets) {
        if (node.isObject() && node.path("kind").stringValue("").equals("AUDIO")) assets.add(node.path("assetId").stringValue(""));
        node.forEach(child -> audioBlocks(child, assets));
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
}
