package app.mnema.learning.generation;

import app.mnema.learning.ai.AiProperties;
import app.mnema.learning.ai.AiResult;
import app.mnema.learning.ai.AiRoute;
import app.mnema.learning.ai.OpaqueUserKey;
import app.mnema.learning.ai.OutputContract;
import app.mnema.learning.ai.TextGeneration;
import app.mnema.learning.ai.TextRequest;
import app.mnema.learning.ai.TextResponse;
import app.mnema.learning.ai.TokenCounter;
import app.mnema.learning.ai.UserKeys;
import app.mnema.learning.ai.eval.GoldenCorpus;
import app.mnema.learning.ai.eval.GoldenCorpus.Fixture;
import app.mnema.learning.ai.eval.GoldenCorpus.Kind;
import app.mnema.learning.ai.eval.GoldenText;
import app.mnema.learning.ai.prompt.AssembledPrompt;
import app.mnema.learning.ai.prompt.PromptAssembler;
import app.mnema.learning.ai.prompt.PromptBlock;
import app.mnema.learning.ai.prompt.PromptBlocks;
import app.mnema.learning.ai.prompt.PromptLibrary;
import app.mnema.learning.ai.prompt.PromptTask;
import app.mnema.learning.ai.prompt.PromptValues;
import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.catalog.content.NativeNodeIndex;
import app.mnema.learning.generation.exercise.ExerciseCode;
import app.mnema.learning.generation.exercise.ExerciseContext;
import app.mnema.learning.generation.exercise.ExerciseFinding;
import app.mnema.learning.generation.exercise.ExerciseIds;
import app.mnema.learning.generation.exercise.ExerciseOutputSchema;
import app.mnema.learning.generation.exercise.ExerciseRepairList;
import app.mnema.learning.generation.exercise.ExerciseValidator;
import app.mnema.learning.generation.mbm.MbmAutoFixer;
import app.mnema.learning.generation.mbm.MbmCode;
import app.mnema.learning.generation.mbm.MbmCompiler;
import app.mnema.learning.generation.mbm.MbmFinding;
import app.mnema.learning.generation.mbm.MbmOptions;
import app.mnema.learning.generation.mbm.MbmRenderer;
import app.mnema.learning.generation.mbm.MbmRepairList;
import app.mnema.learning.generation.mbm.MbmResult;
import app.mnema.learning.generation.mbm.MbmUnsupportedContentException;
import app.mnema.learning.generation.mbm.RandomIdAllocator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One golden-eval fixture through the real pieces of the generation pipeline, without a database: the production prompt sections
 * ({@link PromptAssembler}), the provider route ({@link TextGeneration}: Stub or live), the answer handling and repair loop of
 * {@code TextDraftExecutor}, {@code EditExecutor} and {@code ExerciseDraftExecutor} (three rounds: the fast route, a repair on it, then a
 * repair on the strong route), the MBM compiler in create and edit mode, the splice of an edit into its document, and the exercise
 * validator (schema, lint, compile, publication parser, self-evaluation). It lives in the {@code generation} package because those
 * executors keep their helpers package-private; the executors themselves need the database and are not instantiated.
 *
 * <p>What is not covered: streaming (the eval calls without a listener), the usage ledger and reservations, step claiming, the deadline of a
 * claim, notes read from the database, the deck brief of a real deck (the brief here is one exemplar and an empty outline) and media.
 */
public final class GoldenPipeline {
    private static final int ROUNDS = 3;
    private static final double TEMPERATURE_MATERIAL = 0.8;
    private static final double TEMPERATURE_EXERCISES = 0.4;
    private static final MbmRenderer.Options PLAIN = new MbmRenderer.Options(false, 1, Set.of());
    private static final Map<String, String> PRESETS = Map.of("SIMPLER", "Проще", "SHORTER", "Короче", "EXAMPLE", "Пример", "LONGER", "Подробнее");
    private static final JsonMapper JSON = GoldenCorpus.JSON;
    private static final UUID PLACEHOLDER_COMMAND = ExerciseContexts.PLACEHOLDER_COMMAND;
    private static final UUID PLACEHOLDER_DECK_REVISION = ExerciseContexts.PLACEHOLDER_DECK_REVISION;

    /**
     * The outcome of one fixture.
     *
     * @param validFirstTry the first answer passed every check of the pipeline
     * @param valid the pipeline accepted what it asked for within its three rounds
     * @param calls provider calls made (a repair is a second call)
     * @param escalated the accepted answer came from the strong route
     * @param failure the code of the first failure ({@code MBM:CODE}, {@code EXERCISE:CODE}, a provider outcome), empty when none
     * @param latenciesMillis one entry per provider call
     * @param costMicros provider cost of the whole fixture in micro-US-dollars
     * @param output what the judges read: the compiled material text, the rewritten blocks, or the accepted exercises as JSON
     * @param before the blocks before an edit (empty for the other kinds)
     * @param produced exercises accepted (1 for a material or an edit that is valid)
     * @param requested exercises asked for (1 for a material or an edit)
     * @param checks deterministic measurements by name; a missing name means the check does not apply
     */
    public record Result(Fixture fixture, boolean validFirstTry, boolean valid, int calls, boolean escalated, String failure,
                         List<Long> latenciesMillis, long costMicros, long hitTokens, long missTokens, long completionTokens,
                         String output, String before, int produced, int requested, Map<String, Double> checks, String unchangedContext) {
        public Result(Fixture fixture, boolean validFirstTry, boolean valid, int calls, boolean escalated, String failure,
                      List<Long> latenciesMillis, long costMicros, long hitTokens, long missTokens, long completionTokens,
                      String output, String before, int produced, int requested, Map<String, Double> checks) {
            this(fixture, validFirstTry, valid, calls, escalated, failure, latenciesMillis, costMicros, hitTokens, missTokens, completionTokens,
                    output, before, produced, requested, checks, "");
        }

        Result withUnchangedContext(String context) {
            return new Result(fixture, validFirstTry, valid, calls, escalated, failure, latenciesMillis, costMicros, hitTokens, missTokens, completionTokens,
                    output, before, produced, requested, checks, context);
        }
        public Result {
            latenciesMillis = List.copyOf(latenciesMillis);
            checks = Map.copyOf(checks);
        }

        public long latencyMillis() { return latenciesMillis.stream().mapToLong(Long::longValue).sum(); }

        public boolean repaired() { return calls > 1; }
    }

    private final TextGeneration text;
    private final Duration deadline;
    private final PromptAssembler assembler;
    private final Map<String, String> exemplars;
    private final MbmCompiler compiler = new MbmCompiler();
    private final MbmRenderer renderer = new MbmRenderer();
    private final ExerciseValidator validator = new ExerciseValidator(ExerciseOutputSchema.load());
    private final String compactSchema;

    public GoldenPipeline(TextGeneration text, Duration deadline) {
        this(text, deadline, "v1");
    }

    public GoldenPipeline(TextGeneration text, Duration deadline, String promptVersion) {
        this.text = text;
        this.deadline = deadline;
        this.assembler = new PromptAssembler(PromptLibrary.fromClasspath(promptVersion), new AiProperties.Prompt(promptVersion, 32_000, 25_000));
        this.exemplars = GoldenCorpus.exemplars();
        try {
            this.compactSchema = JSON.writeValueAsString(JSON.readTree(ExerciseOutputSchema.load().text()));
        } catch (JacksonException failure) {
            throw new IllegalStateException("The exercise output schema is not valid JSON", failure);
        }
    }

    public Result run(Fixture fixture) {
        return switch (fixture.kind()) {
            case MATERIAL_FROM_NOTES, MATERIAL_FROM_PROMPT -> material(fixture);
            case EDIT -> edit(fixture);
            case EXERCISE -> exercises(fixture);
        };
    }

    // ------------------------------------------------------------------------------------------------ calls

    /** Counts what the router reports for each call of one fixture. */
    private final class Tally {
        final List<Long> latencies = new ArrayList<>();
        long cost;
        long hit;
        long miss;
        long completion;

        AiResult<TextResponse> call(TextRequest request) {
            long started = System.nanoTime();
            AiResult<TextResponse> result = text.generate(request);
            latencies.add((System.nanoTime() - started) / 1_000_000);
            if (result instanceof AiResult.Ok<TextResponse> ok) {
                cost += ok.value().costMicros();
                hit += ok.value().usage().cacheHitTokens();
                miss += ok.value().usage().cacheMissTokens();
                completion += ok.value().usage().completionTokens();
            }
            return result;
        }
    }

    private TextRequest request(Fixture fixture, AiRoute route, AssembledPrompt prompt, OutputContract contract, int maxTokens, double temperature) {
        OpaqueUserKey key = UserKeys.withSecret("0123456789abcdef0123456789abcdef", "k1")
                .opaque(UUID.nameUUIDFromBytes(fixture.id().getBytes(StandardCharsets.UTF_8)));
        return new TextRequest(route, prompt.segments(), contract, maxTokens, temperature, deadline, key, null, null, 1);
    }

    private static AiRoute next(AiRoute current, int round) { return round == 0 ? current : AiRoute.TEXT_STRONG; }

    private Result result(Fixture fixture, Tally tally, boolean first, boolean valid, boolean escalated, String failure, String output, String before,
                          int produced, int requested, Map<String, Double> checks) {
        return new Result(fixture, first, valid, tally.latencies.size(), escalated, failure == null ? "" : failure, tally.latencies, tally.cost,
                tally.hit, tally.miss, tally.completion, output, before, produced, requested, checks);
    }

    // --------------------------------------------------------------------------------------------- materials

    private Result material(Fixture fixture) {
        JsonNode input = fixture.input();
        boolean notes = fixture.kind() == Kind.MATERIAL_FROM_NOTES;
        String effort = input.path("effort").stringValue("SHORT");
        List<String> sources = notes ? List.of(input.path("note").stringValue("")) : List.of();
        List<String> links = ContextBuilder.links(sources);
        PromptValues values = briefValues(fixture).block("allowed_links", PromptBlocks.allowedLinks(links))
                .block("note_blocks", notes ? PromptBlocks.note("N1", ContextBuilder.clip(sources.getFirst(), 12_000)) : PromptBlocks.empty())
                .block("search_result_blocks", PromptBlocks.empty())
                .text("request", notes ? "по источникам выше" : input.path("request").stringValue(""))
                .text("task.skill", "free").number("task.words", ContextBuilder.wordsFor(effort))
                .text("task.media", "нет, не добавляй медиа-директивы");
        AssembledPrompt prompt = assembler.assemble(PromptTask.MATERIAL, values);
        MbmOptions options = MbmOptions.create().withAllowedLinks(links).withMaxMedia(0);

        Tally tally = new Tally();
        AiRoute route = AiRoute.TEXT_FAST;
        String violations = null;
        String failure = null;
        String answer = "";
        boolean valid = false;
        boolean first = false;
        boolean escalated = false;
        for (int round = 0; round < ROUNDS; round++) {
            TextRequest request = request(fixture, route, prompt, OutputContract.MBM_TEXT, ContextBuilder.maxTokens(effort), TEMPERATURE_MATERIAL);
            if (violations != null) request = request.withRepair(violations);
            AiResult<TextResponse> result = tally.call(request);
            if (result instanceof AiResult.Failed<TextResponse> failed) {
                failure = firstOf(failure, failed.failure().outcome());
                break;
            }
            TextResponse response = ((AiResult.Ok<TextResponse>) result).value();
            if (response.finishReason() == TextResponse.FinishReason.LENGTH) {
                failure = firstOf(failure, "LENGTH");
                violations = "ответ оборван по лимиту длины: напиши короче и закончи документ";
                route = next(route, round);
                continue;
            }
            answer = MbmAutoFixer.fix(response.text()).text();
            MbmResult compiled = compiler.compile(answer, options, new RandomIdAllocator());
            if (compiled instanceof MbmResult.Success) {
                valid = true;
                first = round == 0;
                escalated = route == AiRoute.TEXT_STRONG;
                break;
            }
            List<MbmFinding> errors = ((MbmResult.Failure) compiled).errors();
            failure = firstOf(failure, "MBM:" + errors.getFirst().code().name());
            violations = MbmRepairList.format(errors);
            route = next(route, round);
        }
        Map<String, Double> checks = new LinkedHashMap<>();
        if (valid) materialChecks(fixture, answer, effort, checks);
        return result(fixture, tally, first, valid, escalated, failure, valid ? answer : "", "", valid ? 1 : 0, 1, checks);
    }

    private void materialChecks(Fixture fixture, String answer, String effort, Map<String, Double> checks) {
        checks.put("forbiddenAbsent", forbiddenAbsent(answer, fixture.expected("forbidden")));
        recall(answer, fixture.expected("mustInclude"), "termRecall", checks);
        String firstLine = answer.strip().lines().findFirst().orElse("");
        checks.put("titleOk", firstLine.startsWith("# ") ? 1.0 : 0.0);
        boolean characters = GoldenText.characterScript(fixture.outputLanguage());
        checks.put("wordsVsTarget", (double) GoldenText.units(answer, characters) / ContextBuilder.wordsFor(effort));
        String exemplar = exemplars.getOrDefault(fixture.outputLanguage(), exemplars.get("ru"));
        checks.put("copyRun", (double) GoldenText.longestCommonRun(GoldenText.tokens(answer, characters), GoldenText.tokens(exemplar, characters)));
        checks.put("copyLimit", characters ? 16.0 : 8.0);
    }

    // --------------------------------------------------------------------------------------------- the deck brief

    /** The deck brief of a fresh deck with one exemplar in the output language (the real one reads the database). */
    private PromptValues briefValues(Fixture fixture) {
        String exemplar = exemplars.getOrDefault(fixture.outputLanguage(), exemplars.get("ru"));
        return PromptValues.create()
                .text("deck.title", "Eval-колода").text("deck.description", "Колода для офлайн-прогона golden eval")
                .text("lang.output", fixture.outputLanguage()).text("lang.target", "не указан")
                .number("counts.items", 1).number("counts.exercises", 0).text("deck_terms", "нет терминов")
                .number("style_card.words", GoldenText.units(exemplar, GoldenText.characterScript(fixture.outputLanguage()))).number("style_card.headings", 0)
                .number("style_card.lists", 1).number("style_card.tables", 0).number("style_card.examples", 2).number("style_card.audio", 0)
                .block("exemplar_blocks", PromptBlocks.exemplar("E1", "starred", exemplar))
                .text("recent_material", "в колоде пока нет материалов")
                .number("outline.total", 0).number("outline.shown", 0).block("outline.lines", PromptBlocks.outline(List.of()));
    }

    // ---------------------------------------------------------------------------------------------------- edits

    private Result edit(Fixture fixture) {
        JsonNode input = fixture.input();
        MbmResult built = compiler.compile(input.path("document").stringValue(""), MbmOptions.create(), new RandomIdAllocator());
        if (!(built instanceof MbmResult.Success source)) throw new IllegalStateException("The document of " + fixture.id() + " does not compile");
        JsonNode document = source.document();
        NativeDocument native1 = new NativeDocumentReader().readRetained(document.toString().getBytes(StandardCharsets.UTF_8));
        NativeNodeIndex index = NativeNodeIndex.of(native1);
        List<JsonNode> top = EditDocument.blocks(document);
        EditTarget target = resolveTarget(fixture, document, top, index);
        EditContexts contexts = new EditContexts(null, null, assembler);
        EditContexts.Rendered rendered = contexts.render(target);

        List<PromptBlocks.HandleLine> outline = new ArrayList<>();
        for (int position = 0; position < top.size(); position++) {
            outline.add(new PromptBlocks.HandleLine("b" + (position + 1), firstLine(index.text(EditDocument.id(top.get(position))).orElse(""))));
        }
        PromptBlock context = PromptBlocks.join(List.of(PromptBlocks.material("doc", outline),
                PromptBlocks.document(neighbour(top, target.from() - 1, index), rendered.text(), neighbour(top, target.to() + 1, index))));
        String instruction = input.path("instruction").stringValue("");
        PromptValues values = briefValues(fixture).block("document", context).text("history", "нет предыдущих правок")
                .text("instruction", instruction.isBlank() ? "без дополнительных указаний" : instruction);
        if (input.has("preset")) values.text("preset", PRESETS.get(input.path("preset").stringValue("")));
        AssembledPrompt prompt = assembler.assemble(PromptTask.EDIT, values);
        MbmOptions options = MbmOptions.edit(rendered.handles()).withAllowedLinks(List.copyOf(rendered.links())).withMaxMedia(0);
        int maxTokens = Math.max(600, Math.min(4_500, TokenCounter.estimate(rendered.text()) * 2 + 400));
        String before = plain(target.blocks());

        Tally tally = new Tally();
        AiRoute route = AiRoute.TEXT_FAST;
        String violations = null;
        String failure = null;
        String after = "";
        boolean valid = false;
        boolean first = false;
        boolean escalated = false;
        for (int round = 0; round < ROUNDS; round++) {
            TextRequest request = request(fixture, route, prompt, OutputContract.MBM_TEXT, maxTokens, EditContexts.TEMPERATURE);
            if (violations != null) request = request.withRepair(violations);
            AiResult<TextResponse> result = tally.call(request);
            if (result instanceof AiResult.Failed<TextResponse> failed) {
                failure = firstOf(failure, failed.failure().outcome());
                break;
            }
            TextResponse response = ((AiResult.Ok<TextResponse>) result).value();
            if (response.finishReason() == TextResponse.FinishReason.LENGTH) {
                failure = firstOf(failure, "LENGTH");
                violations = "ответ оборван по лимиту длины: перепиши короче и закончи";
                route = next(route, round);
                continue;
            }
            MbmResult compiled = compiler.compile(MbmAutoFixer.fix(EditExecutor.unwrap(EditExecutor.unescape(response.text()))).text(), options,
                    new RandomIdAllocator());
            if (compiled instanceof MbmResult.Success success) {
                List<JsonNode> rewritten = EditDocument.blocks(success.document());
                JsonNode merged = EditDocument.replace(document, target.from(), target.to(), rewritten, target.blocks());
                boolean readable;
                try {
                    new NativeDocumentReader().read(merged.toString().getBytes(StandardCharsets.UTF_8));
                    readable = true;
                } catch (IllegalArgumentException rejected) {
                    readable = false;
                }
                if (readable) {
                    valid = true;
                    first = round == 0;
                    escalated = route == AiRoute.TEXT_STRONG;
                    after = plain(rewritten);
                    break;
                }
                failure = firstOf(failure, "SPLICE");
                violations = MbmRepairList.format(List.of(new MbmFinding(1, null, MbmCode.MBM_DOCUMENT_TOO_LARGE, null)));
            } else {
                List<MbmFinding> errors = ((MbmResult.Failure) compiled).errors();
                failure = firstOf(failure, "MBM:" + errors.getFirst().code().name());
                violations = MbmRepairList.format(errors);
            }
            route = next(route, round);
        }
        Map<String, Double> checks = new LinkedHashMap<>();
        if (valid) {
            checks.put("forbiddenAbsent", forbiddenAbsent(after, fixture.expected("forbidden")));
            recall(after, fixture.expected("preserve"), "preserveRecall", checks);
            boolean characters = GoldenText.characterScript(fixture.language());
            double ratio = (double) GoldenText.units(after, characters) / Math.max(1, GoldenText.units(before, characters));
            checks.put("wordRatio", ratio);
            // a rewrite that hands the blocks back as they were (the model declined, or found nothing to do) is valid and useless: counted apart
            checks.put("changed", normalized(before).equals(normalized(after)) ? 0.0 : 1.0);
            JsonNode bounds = fixture.expect().path("wordRatio");
            if (bounds.has("minPercent")) {
                checks.put("lengthOk", ratio * 100 >= bounds.path("minPercent").intValue() && ratio * 100 <= bounds.path("maxPercent").intValue() ? 1.0 : 0.0);
            }
        }
        List<JsonNode> outsideTarget = new ArrayList<>(top.subList(0, target.from()));
        outsideTarget.addAll(top.subList(target.to() + 1, top.size()));
        return result(fixture, tally, first, valid, escalated, failure, valid ? after : "", before, valid ? 1 : 0, 1, checks)
                .withUnchangedContext(plain(outsideTarget));
    }

    private static EditTarget resolveTarget(Fixture fixture, JsonNode document, List<JsonNode> top, NativeNodeIndex index) {
        String startsWith = fixture.input().path("target").path("startsWith").stringValue("");
        int blocks = fixture.input().path("target").path("blocks").intValue(1);
        for (int position = 0; position < top.size(); position++) {
            JsonNode block = top.get(position);
            if (block.path("type").stringValue("").equals("heading")) continue;
            if (!index.text(EditDocument.id(block)).orElse("").startsWith(startsWith)) continue;
            List<UUID> ids = new ArrayList<>();
            for (int offset = 0; offset < blocks && position + offset < top.size(); offset++) ids.add(EditDocument.id(top.get(position + offset)));
            return EditTarget.resolve(document, ids).orElseThrow(() -> new IllegalStateException("The target of " + fixture.id() + " is not a run"));
        }
        throw new IllegalStateException("The target of " + fixture.id() + " is not in its document");
    }

    private String neighbour(List<JsonNode> top, int position, NativeNodeIndex index) {
        if (position < 0 || position >= top.size()) return "";
        try {
            return renderer.renderBlocks(List.of(top.get(position)), PLAIN).text();
        } catch (MbmUnsupportedContentException unsupported) {
            return firstLine(index.text(EditDocument.id(top.get(position))).orElse(""));
        }
    }

    private String plain(List<JsonNode> blocks) {
        try {
            return renderer.renderBlocks(blocks, PLAIN).text();
        } catch (MbmUnsupportedContentException unsupported) {
            return "";
        }
    }

    private static String normalized(String text) { return text.strip().replaceAll("\\s+", " "); }

    private static String firstLine(String text) {
        String line = text.strip().lines().findFirst().orElse("").strip();
        return line.length() <= 120 ? line : line.substring(0, 120);
    }

    // ------------------------------------------------------------------------------------------------- exercises

    private Result exercises(Fixture fixture) {
        JsonNode input = fixture.input();
        MbmResult built = compiler.compile(input.path("material").stringValue(""), MbmOptions.create(), new RandomIdAllocator());
        if (!(built instanceof MbmResult.Success source)) throw new IllegalStateException("The material of " + fixture.id() + " does not compile");
        NativeDocument document = new NativeDocumentReader().readRetained(source.document().toString().getBytes(StandardCharsets.UTF_8));
        NativeNodeIndex index = NativeNodeIndex.of(document);
        Map<String, ExerciseContext.Block> blocks = new LinkedHashMap<>();
        List<PromptBlocks.HandleLine> lines = new ArrayList<>();
        int number = 1;
        for (JsonNode block : document.toJson().path("root").path("content")) {
            String handle = "b" + number++;
            UUID nodeId = UUID.fromString(block.path("id").stringValue(""));
            String blockText = index.text(nodeId).orElse("");
            if (blockText.isBlank() || blockText.length() > 4_000) continue;
            blocks.put(handle, new ExerciseContext.Block(nodeId, blockText));
            lines.add(new PromptBlocks.HandleLine(handle, blockText));
        }
        List<String> mechanics = new ArrayList<>();
        input.path("mechanics").forEach(mechanic -> mechanics.add(mechanic.stringValue("")));
        int count = input.path("count").intValue(1);
        List<String> ordered = ExerciseContext.MECHANICS_IN_ORDER.stream().filter(mechanics::contains).toList();
        ExerciseContext context = new ExerciseContext(PLACEHOLDER_COMMAND, PLACEHOLDER_DECK_REVISION,
                Map.of("m1", new ExerciseContext.Material(UUID.randomUUID(), UUID.randomUUID(), blocks)), Map.of(), Set.copyOf(mechanics));
        PromptValues values = PromptValues.create().block("schema", PromptBlocks.schema(compactSchema))
                .block("material_blocks", PromptBlocks.material("m1", lines)).block("objective_lines", PromptBlocks.lines(List.of()))
                .block("existing_exercise_lines", PromptBlocks.lines(List.of())).block("neighbor_lines", PromptBlocks.lines(List.of()))
                .number("task.count", count).text("task.mechanics", String.join(", ", ordered)).text("lang.output", fixture.outputLanguage());
        AssembledPrompt prompt = assembler.assemble(PromptTask.EXERCISES, values);
        int maxTokens = Math.min(16_000, 800 + 800 * count);

        Tally tally = new Tally();
        AiRoute route = AiRoute.TEXT_FAST;
        List<ExerciseValidator.Accepted> accepted = new ArrayList<>();
        List<JsonNode> model = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        String violations = null;
        String failure = null;
        boolean first = false;
        boolean escalated = false;
        for (int round = 0; round < ROUNDS && accepted.size() < count; round++) {
            TextRequest request = request(fixture, route, prompt, OutputContract.JSON, maxTokens, TEMPERATURE_EXERCISES);
            if (violations != null) request = request.withRepair(violations);
            AiResult<TextResponse> result = tally.call(request);
            if (result instanceof AiResult.Failed<TextResponse> failed) {
                failure = firstOf(failure, failed.failure().outcome());
                break;
            }
            TextResponse response = ((AiResult.Ok<TextResponse>) result).value();
            List<ExerciseFinding> findings = new ArrayList<>();
            if (response.finishReason() == TextResponse.FinishReason.LENGTH) {
                findings.add(new ExerciseFinding(-1, ExerciseCode.SCHEMA_INVALID, "$"));
            } else {
                validate(response.text(), count - accepted.size(), context, accepted, model, keys, findings);
            }
            if (accepted.size() >= count) {
                first = round == 0;
                escalated = route == AiRoute.TEXT_STRONG;
                break;
            }
            failure = firstOf(failure, findings.isEmpty() ? "EXERCISE:MISSING" : "EXERCISE:" + findings.getFirst().code().name());
            violations = ExerciseRepairList.format(findings, count - accepted.size(), accepted.stream().map(ExerciseValidator.Accepted::title).toList());
            route = round == 0 ? route : AiRoute.TEXT_STRONG;
        }
        boolean valid = accepted.size() >= count;
        Map<String, Double> checks = new LinkedHashMap<>();
        String output = "";
        if (!accepted.isEmpty()) {
            var wrapper = JSON.createObjectNode();
            var array = wrapper.putArray("exercises");
            model.forEach(array::add);
            output = wrapper.toPrettyString();
            checks.put("mechanicOk", accepted.stream().allMatch(entry -> mechanics.contains(entry.mechanic())) ? 1.0 : 0.0);
            checks.put("acceptedShare", (double) accepted.size() / count);
        }
        return result(fixture, tally, first, valid, escalated, failure, output, "", accepted.size(), count, checks);
    }

    /** The checks of {@code ExerciseDraftExecutor#validate}: the first {@code needed} exercises count, a duplicate prompt is dropped. */
    private void validate(String answer, int needed, ExerciseContext context, List<ExerciseValidator.Accepted> accepted, List<JsonNode> model,
                          Set<String> keys, List<ExerciseFinding> findings) {
        JsonNode root = ExerciseDraftExecutor.parse(answer);
        JsonNode exercises = root == null ? null : root.path("exercises");
        if (exercises == null || !exercises.isArray() || exercises.isEmpty()) {
            findings.add(new ExerciseFinding(-1, ExerciseCode.SCHEMA_INVALID, "$"));
            return;
        }
        int considered = Math.min(needed, exercises.size());
        for (int position = 0; position < considered; position++) {
            ExerciseValidator.Verdict verdict = validator.validate(position, exercises.get(position), context, ExerciseIds.random());
            switch (verdict) {
                case ExerciseValidator.Valid valid -> {
                    if (keys.add(ExerciseValidator.promptKey(valid.exercise().command().path("exercise").path("content")))) {
                        accepted.add(valid.exercise());
                        model.add(exercises.get(position));
                    } else {
                        findings.add(new ExerciseFinding(position, ExerciseCode.DUPLICATE_EXERCISE, null));
                    }
                }
                case ExerciseValidator.Invalid invalid -> findings.addAll(invalid.findings());
            }
        }
        if (exercises.size() < needed) findings.add(new ExerciseFinding(-1, ExerciseCode.SCHEMA_INVALID, "exercises"));
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static String firstOf(String earlier, String later) { return earlier == null ? later : earlier; }

    private static double forbiddenAbsent(String output, List<String> forbidden) {
        String lower = output.toLowerCase(Locale.ROOT);
        return forbidden.stream().noneMatch(entry -> lower.contains(entry.toLowerCase(Locale.ROOT))) ? 1.0 : 0.0;
    }

    private static void recall(String output, List<String> terms, String name, Map<String, Double> checks) {
        if (terms.isEmpty()) return;
        String lower = output.toLowerCase(Locale.ROOT);
        long found = terms.stream().filter(term -> lower.contains(term.toLowerCase(Locale.ROOT))).count();
        checks.put(name, (double) found / terms.size());
    }
}
