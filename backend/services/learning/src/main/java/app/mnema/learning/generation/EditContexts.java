package app.mnema.learning.generation;

import app.mnema.learning.ai.TokenCounter;
import app.mnema.learning.ai.prompt.AssembledPrompt;
import app.mnema.learning.ai.prompt.PromptAssembler;
import app.mnema.learning.ai.prompt.PromptBlock;
import app.mnema.learning.ai.prompt.PromptBlocks;
import app.mnema.learning.ai.prompt.PromptTask;
import app.mnema.learning.ai.prompt.PromptValues;
import app.mnema.learning.ai.prompt.Redactor;
import app.mnema.learning.generation.ContextBuilder.SourceGoneException;
import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.Revision;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.Rows.Turn;
import app.mnema.learning.generation.mbm.MbmOptions;
import app.mnema.learning.generation.mbm.MbmRenderer;
import app.mnema.learning.generation.mbm.MbmRendering;
import app.mnema.learning.generation.mbm.MbmUnsupportedContentException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the prompt of one EDIT step ({@code ai/prompts/v1/edit.md}, architecture section 7) from the database: the same cacheable
 * prefix as the material's own text step (core, style, skills, deck brief), then the task, whose {@code document} is the outline
 * of the material (every top-level block as {@code [[bN]]} and its first line, at most 200 lines) and, in full MBM with
 * the handles of the current revision, the blocks to rewrite between one neighbour before and one after. Only the blocks of the
 * target are rewritten and only they are written back; the outline and the neighbours are context.
 *
 * <p>The history lists the last five finished instructions of the artifact (preset and text); the preset and the instruction of
 * this turn come last. Everything a user wrote is untrusted data that the prompt layer redacts and escapes; a block whose text
 * the redaction would change cannot be edited at all, because the rewritten text would replace the user's own number or address with
 * a placeholder ({@link #editable}).
 */
@Component
class EditContexts {
    static final double TEMPERATURE = 0.7;
    private static final int HISTORY = 5;
    private static final int OUTLINE_LINES = 200;
    private static final int FIRST_LINE = 120;
    private static final int MIN_TOKENS = 600;
    private static final int MAX_TOKENS = 4_500;
    /** The most the blocks to rewrite may weigh: the answer holds them again (and a longer rewrite), within the output bound. */
    static final int MAX_TARGET_TOKENS = (MAX_TOKENS - 400) / 2;
    private static final Map<String, String> PRESETS = Map.of("SIMPLER", "Проще", "SHORTER", "Короче", "EXAMPLE", "Пример",
            "LONGER", "Подробнее");
    private static final Set<String> BLOCK_TYPES = Set.of("paragraph", "heading", "list_item", "bullet_list", "ordered_list",
            "blockquote", "code_block", "table");
    private static final MbmRenderer.Options PLAIN = new MbmRenderer.Options(false, 1, Set.of());

    /**
     * Everything one EDIT run needs, read before any provider call.
     *
     * @param options the compile options of an edit: the handles of the rewritten blocks, the links the user already has there and
     *                those of the session, and no media
     */
    record EditContext(AssembledPrompt prompt, MbmOptions options, int maxTokens, double temperature, EditTarget target) { }

    /** The rewritten blocks as MBM with their handles, the links they carry and the text the redaction would see. */
    record Rendered(String text, Map<String, MbmOptions.Handle> handles, Set<String> links) { }

    private final ContextBuilder builder;
    private final GenerationRepository repository;
    private final PromptAssembler assembler;
    private final MbmRenderer renderer = new MbmRenderer();

    EditContexts(ContextBuilder builder, GenerationRepository repository, PromptAssembler assembler) {
        this.builder = builder;
        this.repository = repository;
        this.assembler = assembler;
    }

    // ------------------------------------------------------------------ editable

    /**
     * Why the model cannot be given this target, empty when it can: {@code TARGET_UNSUPPORTED_BLOCK} (a node it has no MBM syntax for,
     * an attribute it cannot carry or an opaque payload) or {@code TARGET_PERSONAL_DATA} (the text holds what the prompt layer would
     * redact, an e-mail address, a telephone or a card number, so the rewrite would replace it with a placeholder). Both are refused
     * before any model call.
     */
    Optional<String> refusal(EditTarget target) {
        try {
            String text = render(target).text();
            return Redactor.redact(text).equals(text) ? Optional.empty() : Optional.of("TARGET_PERSONAL_DATA");
        } catch (MbmUnsupportedContentException unsupported) {
            return Optional.of("TARGET_UNSUPPORTED_BLOCK");
        }
    }

    /**
     * The estimated tokens of the blocks to rewrite, which is what the output must hold again: a target above
     * {@link #MAX_TARGET_TOKENS} does not fit the output bound and is refused at admission.
     *
     * @throws MbmUnsupportedContentException a block MBM cannot express (refused earlier by {@link #refusal})
     */
    int tokens(EditTarget target) {
        return TokenCounter.estimate(render(target).text());
    }

    /**
     * The blocks to rewrite as MBM, each with its own handle {@code b(index + 1)} so that the handles name the same blocks as the outline.
     *
     * @throws MbmUnsupportedContentException a block MBM cannot express
     */
    Rendered render(EditTarget target) {
        List<String> texts = new ArrayList<>();
        Map<String, MbmOptions.Handle> handles = new LinkedHashMap<>();
        Set<String> links = new LinkedHashSet<>();
        for (EditTarget.Indexed indexed : target.text()) {
            MbmRendering rendering = renderer.renderBlocks(List.of(indexed.block()),
                    new MbmRenderer.Options(true, indexed.index() + 1, Set.of()));
            texts.add(rendering.text());
            handles.putAll(rendering.handles());
            links.addAll(rendering.links());
        }
        return new Rendered(String.join("\n\n", texts), handles, links);
    }

    // ---------------------------------------------------------------------- build

    /**
     * @throws SourceGoneException the deck is gone
     * @throws IllegalStateException the turn's target is not a run of the revision (it was validated at admission, so this is a bug)
     * @throws MbmUnsupportedContentException the target cannot be expressed as MBM (validated at admission, so this is a bug)
     * @throws app.mnema.learning.ai.prompt.PromptException the prompt exceeds its budget
     */
    EditContext build(Session session, Artifact artifact, Revision revision, Turn turn) {
        MaterialsSpec spec = MaterialsSpec.read(session.spec());
        JsonNode document = revision.payload().path("document");
        EditTarget target = EditTarget.resolve(document, turn.targetNodeIds())
                .orElseThrow(() -> new IllegalStateException("The target of the turn is not a run of the revision"));
        Rendered rendered = render(target);
        List<JsonNode> blocks = EditDocument.blocks(document);

        List<String> sources = builder.sourceTextsLenient(session, artifact);
        String request = spec.prompt().isBlank() ? "по источникам выше" : spec.prompt();
        PromptBlock context = PromptBlocks.join(List.of(outline(blocks, target),
                PromptBlocks.document(neighbour(blocks, target.from() - 1), rendered.text(), neighbour(blocks, target.to() + 1))));
        PromptValues values = builder.briefValues(session, spec, sources, request)
                .block("document", context)
                .text("history", history(artifact, turn))
                .text("instruction", turn.instruction() == null || turn.instruction().isBlank()
                        ? "без дополнительных указаний" : turn.instruction());
        if (turn.preset() != null) values.text("preset", PRESETS.get(turn.preset()));
        AssembledPrompt prompt = assembler.assemble(PromptTask.EDIT, values);

        Set<String> links = new LinkedHashSet<>(ContextBuilder.links(sources));
        links.addAll(rendered.links());
        MbmOptions options = MbmOptions.edit(rendered.handles()).withAllowedLinks(List.copyOf(links)).withMaxMedia(0);
        int tokens = TokenCounter.estimate(rendered.text()) * 2 + 400;
        return new EditContext(prompt, options, Math.max(MIN_TOKENS, Math.min(MAX_TOKENS, tokens)), TEMPERATURE, target);
    }

    /** The material as {@code [[bN]] first line}, at most 200 lines around the target. */
    private static PromptBlock outline(List<JsonNode> blocks, EditTarget target) {
        int from = 0;
        int to = blocks.size();
        if (blocks.size() > OUTLINE_LINES) {
            from = Math.max(0, Math.min(target.from() - (OUTLINE_LINES - target.blocks().size()) / 2, blocks.size() - OUTLINE_LINES));
            to = Math.min(blocks.size(), from + OUTLINE_LINES);
        }
        List<PromptBlocks.HandleLine> lines = new ArrayList<>();
        for (int index = from; index < to; index++) lines.add(new PromptBlocks.HandleLine("b" + (index + 1), firstLine(blocks.get(index))));
        return PromptBlocks.material("doc", lines);
    }

    /** The neighbour as MBM (it may be empty at the edge of the material); a block MBM cannot express is shown by its first line. */
    private String neighbour(List<JsonNode> blocks, int index) {
        if (index < 0 || index >= blocks.size()) return "";
        JsonNode block = blocks.get(index);
        if (EditDocument.isMedia(block)) return mediaLabel(block);
        try {
            return renderer.renderBlocks(List.of(block), PLAIN).text();
        } catch (MbmUnsupportedContentException unsupported) {
            return firstLine(block);
        }
    }

    /** The last finished instructions of the artifact, oldest first: {@code «Проще»: text}. */
    private String history(Artifact artifact, Turn turn) {
        List<Turn> recent = new ArrayList<>(repository.recentTurns(artifact.artifactId(), turn.turnId(), HISTORY));
        java.util.Collections.reverse(recent);
        List<String> lines = new ArrayList<>();
        for (Turn earlier : recent) {
            String preset = earlier.preset() == null ? "" : "«" + PRESETS.get(earlier.preset()) + "»";
            String text = earlier.instruction() == null ? "" : earlier.instruction().strip();
            String line = (preset + " " + text).strip();
            if (!line.isEmpty()) lines.add(line);
        }
        return lines.isEmpty() ? "нет предыдущих правок" : String.join("\n", lines);
    }

    // -------------------------------------------------------------------- helpers

    private static String firstLine(JsonNode block) {
        if (EditDocument.isMedia(block)) return mediaLabel(block);
        StringBuilder text = new StringBuilder();
        plain(block, text);
        String line = text.toString().strip().lines().findFirst().orElse("").strip();
        return line.length() <= FIRST_LINE ? line : line.substring(0, FIRST_LINE);
    }

    private static String mediaLabel(JsonNode block) {
        return switch (block.path("type").stringValue("")) {
            case "audio" -> "[аудио]";
            case "image" -> "[изображение]";
            default -> "[видео]";
        };
    }

    private static void plain(JsonNode node, StringBuilder out) {
        switch (node.path("type").stringValue("")) {
            case "text" -> out.append(node.path("attrs").path("text").stringValue(""));
            case "ruby" -> out.append(node.path("attrs").path("base").stringValue(""));
            case "code_block" -> out.append(node.path("attrs").path("source").stringValue(""));
            default -> {
                for (JsonNode child : node.path("content")) {
                    plain(child, out);
                    if (isBlockLevel(child)) out.append('\n');
                }
            }
        }
    }

    private static boolean isBlockLevel(JsonNode node) {
        return BLOCK_TYPES.contains(node.path("type").stringValue(""));
    }
}
