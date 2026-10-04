package app.mnema.learning.generation;

import app.mnema.learning.ai.TokenCounter;
import app.mnema.learning.ai.prompt.AssembledPrompt;
import app.mnema.learning.ai.prompt.PromptAssembler;
import app.mnema.learning.ai.prompt.PromptBlock;
import app.mnema.learning.ai.prompt.PromptBlocks;
import app.mnema.learning.ai.prompt.PromptTask;
import app.mnema.learning.ai.prompt.PromptValues;
import app.mnema.learning.catalog.content.ItemPreviews;
import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.generation.ContextRepository.Brief;
import app.mnema.learning.generation.ContextRepository.Head;
import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.mbm.MbmOptions;
import app.mnema.learning.generation.mbm.MbmRenderer;
import app.mnema.learning.generation.mbm.MbmUnsupportedContentException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the prompt of one material from the database: the deck brief (title, description, outline, exemplars, the most
 * recent material, deck terms) and the pinned sources, under the budgets of {@code context-and-quality} section 2.3 and
 * through {@link PromptAssembler} so that the stable layers stay a byte-identical cacheable prefix.
 *
 * <p>Budgets (estimated tokens, {@link GenerationSettings.Context}): notes and sources 12k, one exemplar 2.5k and all
 * exemplars 6k (longer ones become a skeleton of headings and first lines), the outline 5k (about 200 lines). A deck of
 * at most 200 materials is outlined whole; a larger one shows every starred material, the latest 40 and the top-K by
 * title similarity (only with {@code pg_trgm}), and says how many it left out. The outline is built from the cached
 * {@code item_preview} titles, never from full documents. Everything that came from a user (notes, materials, titles) is
 * untrusted data: it goes through {@link PromptBlocks}, which redacts personal-data patterns and escapes markup.
 */
@Component
class ContextBuilder {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'\\]\\)]+");
    private static final Pattern TERM = Pattern.compile("\\*\\*([^*\\n]{2,60})\\*\\*");
    private static final int MAX_LINKS = 50;
    private static final int MAX_TERMS = 60;
    private static final int MAX_RECENT_TOKENS = 2_500;
    private static final double TEMPERATURE = 0.8;
    private static final MbmRenderer.Options PLAIN = new MbmRenderer.Options(false, 1, Set.of());

    private final ContextRepository context;
    private final GenerationRepository repository;
    private final ItemService items;
    private final ItemPreviews previews;
    private final PromptAssembler assembler;
    private final GenerationSettings settings;
    private final Plans plans;
    private final NativeDocumentReader reader = new NativeDocumentReader();
    private final MbmRenderer renderer = new MbmRenderer();

    ContextBuilder(ContextRepository context, GenerationRepository repository, ItemService items, ItemPreviews previews,
                   PromptAssembler assembler, GenerationSettings settings, Plans plans) {
        this.context = context;
        this.repository = repository;
        this.items = items;
        this.previews = previews;
        this.assembler = assembler;
        this.settings = settings;
        this.plans = plans;
    }

    /** A pinned source of the artifact is gone or changed: the artifact fails with {@code SOURCE_UNAVAILABLE}. */
    static final class SourceGoneException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SourceGoneException() {
            super("A pinned source is no longer available", null, false, false);
        }
    }

    /** @throws SourceGoneException a pinned note or material no longer matches its pin or is gone */
    DraftContext build(Session session, Artifact artifact, MaterialsSpec spec) {
        // a material of an approved plan is written at the effort the plan chose and on the topic it named
        Optional<Plans.MaterialItem> planned = plans.plannedMaterial(session, artifact);
        MaterialsSpec.Effective effective = planned.map(item -> spec.forArtifact(artifact.sourceRefs()).withEffort(item.effort()))
                .orElseGet(() -> spec.forArtifact(artifact.sourceRefs()));
        List<String> sourceTexts = sourceTexts(session, artifact);
        String request = spec.prompt().isBlank() ? "по источникам выше" : spec.prompt();
        if (planned.isPresent()) {
            request = "Тема материала: " + planned.get().title() + (spec.prompt().isBlank() ? "" : "\nОбщая просьба: " + spec.prompt());
        }
        PromptValues values = briefValues(session, spec, sourceTexts, request)
                .block("allowed_links", PromptBlocks.allowedLinks(links(sourceTexts)))
                .block("note_blocks", noteBlocks(sourceTexts, settings.context().notesTokens()))
                .block("search_result_blocks", PromptBlocks.empty())
                .text("request", request).text("task.skill", "free").number("task.words", wordsFor(effective.workingEffort()))
                .text("task.media", media(effective));
        AssembledPrompt prompt = assembler.assemble(PromptTask.MATERIAL, values);
        MbmOptions options = MbmOptions.create().withAllowedLinks(links(sourceTexts)).withMaxMedia(effective.maxMedia());
        return new DraftContext(prompt, options, maxTokens(effective.workingEffort()), TEMPERATURE);
    }

    /**
     * The values of the deck brief (title, profile, terms, exemplars with their style card, the most recent material, the outline)
     * and of the core: the cacheable prefix every text step of a session shares, so a material and an edit of it hit the same
     * provider cache. {@code request} and {@code sourceTexts} only steer the outline of a deck too large to show whole.
     *
     * @throws SourceGoneException the deck is gone
     */
    PromptValues briefValues(Session session, MaterialsSpec spec, List<String> sourceTexts, String request) {
        UUID owner = session.ownerId();
        UUID deck = session.deckId();
        Brief brief = context.brief(owner, deck).orElseThrow(SourceGoneException::new);
        Map<UUID, String> exemplarTexts = exemplarTexts(owner, deck, spec);
        List<PromptBlock> exemplarBlocks = new ArrayList<>();
        int index = 1;
        for (String text : exemplarTexts.values()) {
            exemplarBlocks.add(PromptBlocks.exemplar("E" + index++, "starred", text));
        }
        String recent = recentMaterial(owner, deck, exemplarTexts.keySet());
        Outline outline = outline(deck, spec, request, sourceTexts, exemplarTexts.keySet());

        String exemplarJoined = String.join("\n", exemplarTexts.values());
        return PromptValues.create()
                .text("deck.title", orDash(brief.title())).text("deck.description", orDash(brief.description()))
                .text("lang.output", spec.outputLanguage()).text("lang.target", "не указан")
                .number("counts.items", brief.items()).number("counts.exercises", brief.exercises())
                .text("deck_terms", terms(exemplarJoined + "\n" + recent))
                .number("style_card.words", words(exemplarJoined)).number("style_card.headings", count(exemplarJoined, "(?m)^#{2,3} "))
                .number("style_card.lists", count(exemplarJoined, "(?m)^(?:- |\\d+\\. )")).number("style_card.tables", count(exemplarJoined, "(?m)^::table"))
                .number("style_card.examples", count(exemplarJoined, "(?m)^(?:- |\\d+\\. )")).number("style_card.audio", count(exemplarJoined, "(?m)^::audio"))
                .block("exemplar_blocks", PromptBlocks.join(exemplarBlocks))
                .text("recent_material", recent.isBlank() ? "в колоде пока нет материалов" : recent)
                .number("outline.total", outline.total()).number("outline.shown", outline.shown())
                .block("outline.lines", outline.lines());
    }

    /**
     * The text of every source of the artifact for the outline and the link allowlist of an edit: like {@link #sourceTexts} but a
     * source that is gone is skipped (an edit does not need it), so editing a proposal never fails because a note was deleted.
     */
    List<String> sourceTextsLenient(Session session, Artifact artifact) {
        try {
            return sourceTexts(session, artifact);
        } catch (SourceGoneException gone) {
            return List.of();
        }
    }

    // ---------------------------------------------------------------- sources

    /**
     * The text of every source of this artifact, in pin order. A note is read from the snapshot taken at the pinned
     * {@code row_version} (admission, or a re-pin), so an edit after the pin never reaches the prompt; a material that is no
     * longer readable is {@link SourceGoneException}.
     */
    private List<String> sourceTexts(Session session, Artifact artifact) {
        UUID owner = session.ownerId();
        UUID deck = session.deckId();
        List<String> texts = new ArrayList<>();
        for (JsonNode ref : artifact.sourceRefs()) {
            if (ref.path("type").stringValue("").equals("NOTE")) {
                UUID noteId = UUID.fromString(ref.path("noteId").stringValue(""));
                long version = Long.parseLong(ref.path("noteRowVersion").stringValue("0"));
                texts.add(repository.pinnedNoteText(session.sessionId(), noteId, version).orElseThrow(SourceGoneException::new));
            } else {
                UUID member = UUID.fromString(ref.path("memberKey").stringValue(""));
                UUID revision = UUID.fromString(ref.path("itemRevisionId").stringValue(""));
                texts.add(render(owner, deck, member, revision).orElseThrow(SourceGoneException::new));
            }
        }
        return texts;
    }

    private PromptBlock noteBlocks(List<String> texts, int budget) {
        if (texts.isEmpty()) return PromptBlocks.empty();
        int share = Math.max(100, budget / texts.size());
        List<PromptBlock> blocks = new ArrayList<>();
        int number = 1;
        for (String text : texts) blocks.add(PromptBlocks.note("N" + number++, clip(text, share)));
        return PromptBlocks.join(blocks);
    }

    /** Link targets the user wrote in their own sources: the session allowlist (research results are added by AI-14). */
    static List<String> links(List<String> texts) {
        Set<String> found = new LinkedHashSet<>();
        for (String text : texts) {
            Matcher matcher = URL.matcher(text);
            while (matcher.find() && found.size() < MAX_LINKS) found.add(matcher.group().replaceAll("[.,;:!?]+$", ""));
        }
        return List.copyOf(found);
    }

    // -------------------------------------------------------------- exemplars

    /**
     * At most two exemplars as MBM text: the pinned {@code STYLE_EXAMPLE} items first, then (with {@code similarToDeck}) the
     * starred materials of the deck, most recently marked first. One exemplar is full up to its budget, longer ones are a
     * skeleton, and all together stay within the exemplar total.
     */
    private Map<UUID, String> exemplarTexts(UUID owner, UUID deck, MaterialsSpec spec) {
        GenerationSettings.Context budgets = settings.context();
        Map<UUID, String> texts = new LinkedHashMap<>();
        int tokens = 0;
        List<UUID[]> candidates = new ArrayList<>();
        spec.styleExamples().forEach(example -> candidates.add(new UUID[] {example.memberKey(), example.itemRevisionId()}));
        if (spec.similarToDeck()) {
            context.starred(deck, 2).forEach(head -> candidates.add(new UUID[] {head.memberKey(), head.revisionId()}));
        }
        for (UUID[] candidate : candidates) {
            if (texts.size() == 2 || texts.containsKey(candidate[0])) continue;
            Optional<String> rendered = render(owner, deck, candidate[0], candidate[1]);
            if (rendered.isEmpty()) continue;
            String text = fit(rendered.get(), budgets.exemplarTokens());
            int size = TokenCounter.estimate(text);
            if (tokens + size > budgets.exemplarsTotalTokens()) continue;
            tokens += size;
            texts.put(candidate[0], text);
        }
        return texts;
    }

    /** The most recently changed material that is not an exemplar, as a skeleton when long; blank for an empty deck. */
    private String recentMaterial(UUID owner, UUID deck, Set<UUID> exemplars) {
        for (Head head : context.latest(deck, 3)) {
            if (exemplars.contains(head.memberKey())) continue;
            return render(owner, deck, head.memberKey(), head.revisionId()).map(text -> fit(text, MAX_RECENT_TOKENS)).orElse("");
        }
        return "";
    }

    /** The material revision as MBM text, or empty when it cannot be read or MBM cannot express it exactly. */
    private Optional<String> render(UUID owner, UUID deck, UUID member, UUID revision) {
        try {
            JsonNode detail = items.read(owner, deck, member, revision);
            NativeDocument document = reader.readRetained(detail.path("document").toString().getBytes(StandardCharsets.UTF_8));
            return Optional.of(renderer.render(document, PLAIN).text());
        } catch (ResourceNotFoundException | IllegalArgumentException | MbmUnsupportedContentException unreadable) {
            return Optional.empty();
        }
    }

    /** {@code text} whole when it fits {@code tokens}, else a skeleton: headings and the first line of every other block. */
    static String fit(String text, int tokens) {
        if (TokenCounter.estimate(text) <= tokens) return text;
        StringBuilder skeleton = new StringBuilder();
        for (String block : text.split("\\n\\s*\\n")) {
            String first = block.strip().lines().findFirst().orElse("");
            if (first.isBlank()) continue;
            skeleton.append(first.startsWith("#") ? first : (first.length() > 160 ? first.substring(0, 160) + "…" : first)).append("\n\n");
        }
        return clip(skeleton.toString().strip(), tokens);
    }

    static String clip(String text, int tokens) {
        String clipped = text;
        while (TokenCounter.estimate(clipped) > tokens && clipped.length() > 16) {
            clipped = clipped.substring(0, (int) (clipped.length() * 0.9));
        }
        return clipped;
    }

    // ---------------------------------------------------------------- outline

    private record Outline(PromptBlock lines, int total, int shown) { }

    private Outline outline(UUID deck, MaterialsSpec spec, String request, List<String> sources, Set<UUID> exemplars) {
        GenerationSettings.Context budgets = settings.context();
        int total = context.headCount(deck);
        List<Head> shown = new ArrayList<>();
        if (total <= budgets.outlineLines()) {
            shown.addAll(context.latest(deck, budgets.outlineLines()));
        } else {
            Set<UUID> seen = new LinkedHashSet<>();
            for (Head head : context.starred(deck, budgets.outlineLines())) if (seen.add(head.memberKey())) shown.add(head);
            for (Head head : context.latest(deck, budgets.latestMaterials())) if (seen.add(head.memberKey())) shown.add(head);
            String query = (request + " " + String.join(" ", sources.stream().map(text -> clip(text, 200)).toList()));
            query = query.substring(0, Math.min(query.length(), 2_000));
            for (Head head : context.closest(deck, query, budgets.topK(), seen)) if (seen.add(head.memberKey())) shown.add(head);
        }
        Map<UUID, Integer> counts = context.exerciseCounts(deck, shown.stream().map(Head::memberKey).toList());
        List<PromptBlocks.OutlineEntry> entries = new ArrayList<>();
        int handle = 1;
        for (Head head : shown) {
            String title = head.title() != null ? head.title()
                    : previews.title(deck, head.memberKey(), head.revisionId(), head.scopeId(), head.contentRootId());
            // item_preview keeps the title only: the first line of a material is not read (no full documents in the outline)
            entries.add(new PromptBlocks.OutlineEntry("m" + handle++, title, "", counts.getOrDefault(head.memberKey(), 0)));
        }
        while (entries.size() > 1 && outlineTokens(entries) > budgets.outlineTokens()) entries.removeLast();
        return new Outline(PromptBlocks.outline(entries), total, entries.size());
    }

    private static int outlineTokens(List<PromptBlocks.OutlineEntry> entries) {
        return entries.stream().mapToInt(entry -> TokenCounter.estimate(entry.title()) + 14).sum();
    }

    // ------------------------------------------------------------- small parts

    private static String terms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = TERM.matcher(text);
        while (matcher.find() && terms.size() < MAX_TERMS) terms.add(matcher.group(1).strip());
        return terms.isEmpty() ? "нет" : String.join("; ", terms);
    }

    private static int words(String text) {
        return text.isBlank() ? 0 : text.strip().split("\\s+").length;
    }

    private static int count(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "не указано" : value;
    }

    private static String media(MaterialsSpec.Effective spec) {
        List<String> media = new ArrayList<>();
        if (spec.audio()) media.add("аудио (::audio)");
        if (spec.imageSearch()) media.add("картинка из поиска (::image mode=search)");
        return media.isEmpty() ? "нет, не добавляй медиа-директивы" : String.join(", ", media);
    }

    /** Target length in words by effort; the style card of the exemplars shows the form, the effort sets the size. */
    static int wordsFor(String effort) {
        return switch (effort) {
            case "SHORT" -> 150;
            case "DETAILED" -> 900;
            default -> 400;
        };
    }

    /** Output bound by effort (context-and-quality section 2.3: 0.6k, 1.8k and 4.5k tokens, with room for Cyrillic). */
    static int maxTokens(String effort) {
        return switch (effort) {
            case "SHORT" -> 900;
            case "DETAILED" -> 4_500;
            default -> 2_400;
        };
    }
}
