package app.mnema.learning.generation;

import app.mnema.learning.ai.AiFailure;
import app.mnema.learning.ai.AiResult;
import app.mnema.learning.ai.AiRoute;
import app.mnema.learning.ai.OpaqueUserKey;
import app.mnema.learning.ai.OutputContract;
import app.mnema.learning.ai.TextGeneration;
import app.mnema.learning.ai.TextRequest;
import app.mnema.learning.ai.TextResponse;
import app.mnema.learning.ai.prompt.AssembledPrompt;
import app.mnema.learning.ai.prompt.PromptAssembler;
import app.mnema.learning.ai.prompt.PromptException;
import app.mnema.learning.ai.prompt.PromptTask;
import app.mnema.learning.ai.prompt.PromptValues;
import app.mnema.learning.catalog.content.NativeDocumentPreview;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.generation.exercise.ExerciseValidator;
import app.mnema.learning.platform.api.CapabilityUnavailableException;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code POST /api/decks/{deckId}/generation-intents} ({@code contracts/generation/http.json}, decision 16): the free first step of
 * «Попросить Мнему…». One sentence of the owner, with the material or the exercise they are looking at, becomes a spec the owner can
 * change as chips and confirm; <em>nothing is reserved or debited here</em>, the credits are taken by the session that the owner starts
 * afterwards (architecture section 13).
 *
 * <p>One cheap worker-only call on the fast text route, strict JSON, temperature 0.2, the dedicated {@code intent} section of the prompt library:
 * the model chooses one operation of a closed vocabulary and the server builds the spec ({@link IntentSpecs}), so the sentence can never
 * name a target, a budget or a number above the limits. An answer outside the vocabulary is sent back once; a second one, a refusal or
 * a provider that cannot say gives {@code UNSUPPORTED} with a note in words ({@code spec: null}). A provider that is down is
 * {@code 409 CAPABILITY_UNAVAILABLE}. Order of checks: ownership of the deck (404), the body (400), the context (404), the capability
 * (409), the hourly rate limit ({@code 429 RATE_LIMITED}, {@link IntentUses}), the call. No database transaction is open during the call;
 * the unchanged HTTP response waits without a connection for an ephemeral worker request (NOTIFY plus sweep); the request is deleted on completion, interruption or deadline. The call is journaled by the provider layer ({@code ai_provider_call}, with its cost) and never metered in credits.
 */
@Service
class IntentService {
    private static final Logger LOG = LoggerFactory.getLogger(IntentService.class);
    static final int MAX_TEXT = 2_000;
    private static final int MAX_OUTPUT_TOKENS = 500;
    private static final double TEMPERATURE = 0.2;
    private static final int ROUNDS = 2;

    private final GenerationRepository repository;
    private final ItemService items;
    private final ExerciseService exercises;
    private final PromptAssembler assembler;
    private final TextGeneration text;
    private final ProviderKeys keys;
    private final GenerationGate gate;
    private final IntentUses uses;
    private final GenerationSettings settings;
    private final IntentQueue queue;
    private final ObjectProvider<IntentRunner> runner;
    private final int maxPerTarget;
    private final NativeDocumentReader reader = new NativeDocumentReader();

    IntentService(GenerationRepository repository, ItemService items, ExerciseService exercises, PromptAssembler assembler,
                  TextGeneration text, ProviderKeys keys, GenerationGate gate, IntentUses uses, GenerationSettings settings,
                  IntentQueue queue, ObjectProvider<IntentRunner> runner,
                  @Value("${learning.generation.max-exercises-per-target:10}") int maxPerTarget) {
        this.repository = repository;
        this.items = items;
        this.exercises = exercises;
        this.assembler = assembler;
        this.text = text;
        this.keys = keys;
        this.gate = gate;
        this.uses = uses;
        this.settings = settings;
        this.queue = queue;
        this.runner = runner;
        this.maxPerTarget = maxPerTarget;
    }

    /** What the owner is looking at, resolved: the context of the spec and the title the model is told. */
    private record Resolved(IntentSpecs.Context context, String title) { }

    JsonNode answer(UUID owner, UUID deckId, byte[] raw) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An intent HTTP wait must not run inside a database transaction");
        }
        if (!repository.deckOwned(owner, deckId)) throw new ResourceNotFoundException();
        JsonNode body = Commands.read(raw);
        Commands.fields(body, Set.of("context", "text"), Set.of());
        String request = request(body.get("text"));
        Resolved resolved = resolve(owner, deckId, body.get("context"));
        gate.requireText();
        uses.take(owner);

        UUID id = queue.submit(owner, deckId, resolved.context(), resolved.title(), request);
        runner.ifAvailable(IntentRunner::wake);
        long started = System.nanoTime();
        try {
            while (Duration.ofNanos(System.nanoTime() - started).compareTo(settings.intent().deadline()) < 0) {
                Optional<IntentQueue.Answer> stored = queue.read(id, owner);
                if (stored.isPresent()) {
                    IntentQueue.Answer answer = stored.orElseThrow();
                    if (answer.error() != null) throw unavailable(answer.error());
                    return answer.result();
                }
                Thread.sleep(100);
            }
            throw unavailable("TEMPORARILY_UNAVAILABLE");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable("TEMPORARILY_UNAVAILABLE");
        } finally {
            queue.discard(id, owner);
        }
    }

    /** Only {@link IntentRunner} calls this: re-check ownership and worker capability before contacting a provider. */
    JsonNode execute(IntentQueue.Job job) {
        if (!repository.deckOwned(job.owner(), job.deck())) throw new ResourceNotFoundException();
        gate.requireText();
        Duration left = Duration.between(Instant.now(), job.deadline());
        if (left.compareTo(Duration.ofMillis(250)) < 0) throw unavailable("TEMPORARILY_UNAVAILABLE");
        IntentSpecs.Built built = ask(job.owner(), new Resolved(job.context(), job.title()), job.request(), left);
        ObjectNode answer = Json.object().put("operation", built.operation());
        if (built.spec() == null) answer.putNull("spec");
        else answer.set("spec", built.spec());
        answer.set("chips", built.chips());
        answer.set("notes", built.notes());
        return answer;
    }

    // ------------------------------------------------------------------------ request

    private static String request(JsonNode node) {
        if (node == null || !node.isString()) throw new InvalidRequestException();
        String value = node.stringValue();
        if (value.isBlank() || value.codePointCount(0, value.length()) > MAX_TEXT) throw new InvalidRequestException();
        return value;
    }

    private Resolved resolve(UUID owner, UUID deckId, JsonNode context) {
        if (context == null || !context.isObject() || !context.path("kind").isString()) throw new InvalidRequestException();
        return switch (context.path("kind").stringValue("")) {
            case "MATERIAL" -> {
                Commands.fields(context, Set.of("kind", "memberKey"), Set.of());
                UUID member = Commands.entity(context, "memberKey");
                UUID head = repository.headRevisions(owner, deckId, List.of(member)).get(member);
                if (head == null) throw new ResourceNotFoundException();
                JsonNode material = items.read(owner, deckId, member, head);
                yield new Resolved(IntentSpecs.Context.material(member, head), title(material.path("document")));
            }
            case "EXERCISE" -> {
                Commands.fields(context, Set.of("kind", "exerciseId"), Set.of());
                UUID exerciseId = Commands.entity(context, "exerciseId");
                JsonNode exercise = exercises.read(owner, deckId, exerciseId, null);
                UUID member = UUID.fromString(exercise.path("subject").path("memberKey").stringValue(""));
                UUID head = repository.headRevisions(owner, deckId, List.of(member)).get(member);
                if (head == null) throw new ResourceNotFoundException();
                List<ObjectNode> audio = new ArrayList<>();
                SpeechExecutor.audioBlocks(exercise.path("content"), audio);
                // the voice is redone by speaking a block's transcript: a recording without one cannot be (see ReviseAdmission)
                boolean speakable = audio.stream().anyMatch(block -> !block.path("transcript").stringValue("").isBlank());
                yield new Resolved(IntentSpecs.Context.exercise(exerciseId, UUID.fromString(exercise.path("exerciseRevisionId").stringValue("")),
                        member, head, !audio.isEmpty(), speakable),
                        ExerciseValidator.title(exercise, exercise.path("objective").path("title").stringValue("")));
            }
            default -> throw new InvalidRequestException();
        };
    }

    private String title(JsonNode document) {
        try {
            return NativeDocumentPreview.title(reader.read(document.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException unreadable) {
            return "";
        }
    }

    // ---------------------------------------------------------------------- the call

    private IntentSpecs.Built ask(UUID owner, Resolved resolved, String request, Duration deadline) {
        AssembledPrompt prompt;
        OpaqueUserKey key;
        try {
            PromptValues values = PromptValues.create().text("context.kind", resolved.context().kind())
                    .text("context.title", resolved.title().isBlank() ? "без названия" : resolved.title())
                    .text("context.operations", String.join(", ", resolved.context().operations()))
                    .text("context.mechanics", String.join(", ", IntentSpecs.MECHANICS)).text("request", request);
            prompt = assembler.assemble(PromptTask.INTENT, values);
            key = keys.opaque(owner);
        } catch (PromptException tooBig) {
            LOG.warn("generation_intent_prompt_rejected");
            return IntentSpecs.build(resolved.context(), unsupportedAnswer(), maxPerTarget, false);
        } catch (IllegalStateException notConfigured) {
            throw unavailable("PROVIDER_NOT_CONFIGURED");
        }
        long started = System.nanoTime();
        String violations = null;
        for (int round = 0; round < ROUNDS; round++) {
            Duration left = deadline.minus(Duration.ofNanos(System.nanoTime() - started));
            if (left.compareTo(Duration.ofMillis(250)) < 0) throw unavailable("TEMPORARILY_UNAVAILABLE");
            TextRequest call = new TextRequest(AiRoute.TEXT_FAST, prompt.segments(), OutputContract.JSON, MAX_OUTPUT_TOKENS, TEMPERATURE, left, key,
                    null, null, 1);
            if (violations != null) call = call.withRepair(violations);
            AiResult<TextResponse> result = text.generate(call);
            if (result instanceof AiResult.Failed<TextResponse> failed) {
                return switch (failed.failure()) {
                    // the model will not (or cannot, after the router's own repair) classify this sentence
                    case AiFailure.Refusal ignored -> IntentSpecs.build(resolved.context(), unsupportedAnswer(), maxPerTarget, false);
                    case AiFailure.InvalidOutput ignored -> IntentSpecs.build(resolved.context(), unsupportedAnswer(), maxPerTarget, false);
                    case AiFailure.NotConfigured ignored -> throw unavailable("PROVIDER_NOT_CONFIGURED");
                    default -> throw unavailable("TEMPORARILY_UNAVAILABLE");
                };
            }
            Optional<IntentSpecs.Answer> answer = IntentSpecs.read(ExerciseDraftExecutor.parse(((AiResult.Ok<TextResponse>) result).value().text()));
            if (answer.isPresent()) {
                return IntentSpecs.build(resolved.context(), answer.get(), maxPerTarget, gate.voiceRevisionAvailable());
            }
            violations = "ответ вне формата: нужен json с полем operation из списка, mechanics, perTarget, instruction и media";
        }
        LOG.info("generation_intent_unsupported reason=invalid_output");
        return IntentSpecs.build(resolved.context(), unsupportedAnswer(), maxPerTarget, false);
    }

    private static IntentSpecs.Answer unsupportedAnswer() {
        return new IntentSpecs.Answer("UNSUPPORTED", null, null, "", null);
    }

    private static CapabilityUnavailableException unavailable(String reason) {
        return new CapabilityUnavailableException(ProblemExtension.builder().put("capability", "aiGeneration").put("reason", reason).build());
    }
}
