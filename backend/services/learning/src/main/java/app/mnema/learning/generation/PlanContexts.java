package app.mnema.learning.generation;

import app.mnema.learning.ai.prompt.AssembledPrompt;
import app.mnema.learning.ai.prompt.PromptAssembler;
import app.mnema.learning.ai.prompt.PromptBlock;
import app.mnema.learning.ai.prompt.PromptBlocks;
import app.mnema.learning.ai.prompt.PromptTask;
import app.mnema.learning.ai.prompt.PromptValues;
import app.mnema.learning.catalog.content.ItemPreviews;
import app.mnema.learning.generation.ContextBuilder.SourceGoneException;
import app.mnema.learning.generation.ContextRepository.Brief;
import app.mnema.learning.generation.ContextRepository.Head;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.Rows.Source;
import app.mnema.learning.usage.AdmissionPricing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the prompt of a {@code PLAN} step from the database ({@code prompts/v1/plan.md}): the deck brief, the chosen materials with what the deck
 * already holds for each (the number of exercises and the mechanics behind it), or the chosen notes with the titles the deck already has, the
 * spec's request, the limits, and the budget the plan must fit. The prompt is small (it carries titles, a short outline and clipped notes, never a
 * whole material), so it stays far under the 32k ceiling of a Flash call. Everything that came from a user goes through {@link PromptBlocks}.
 *
 * <p>The same call produces the {@code targets} or {@code sources} of the stored plan (the titles the client shows beside each row), so what the
 * model was told and what the client reads are one thing.
 */
@Component
class PlanContexts {
    private static final int TITLE_CHARACTERS = 100;
    private static final int OUTLINE_TITLES = 40;
    private static final int NOTE_LABEL = 60;
    /** All notes together: a plan needs the gist of each, not the text (the section has a 12k ceiling and the model thinks on top of it). */
    private static final int NOTES_TOKENS = 8_000;
    private static final double TEMPERATURE = 0.3;

    /** What one run needs: the prompt, the session as the plan validates against it and the titles of the plan's rows. */
    record Request(AssembledPrompt prompt, Plans.Basis basis, ArrayNode targets, ArrayNode sources, double temperature) { }

    private final ContextRepository context;
    private final GenerationRepository repository;
    private final ItemPreviews previews;
    private final PromptAssembler assembler;
    private final AdmissionPricing pricing;
    private final GenerationSettings settings;
    private final Plans plans;
    private final int maxPerTarget;
    private final int maxPerSession;
    private final int maxArtifacts;

    PlanContexts(ContextRepository context, GenerationRepository repository, ItemPreviews previews, PromptAssembler assembler,
                 AdmissionPricing pricing, GenerationSettings settings, Plans plans,
                 @Value("${learning.generation.max-exercises-per-target:10}") int maxPerTarget,
                 @Value("${learning.generation.max-exercises-per-session:60}") int maxPerSession,
                 @Value("${learning.generation.max-artifacts-per-session:20}") int maxArtifacts) {
        this.context = context;
        this.repository = repository;
        this.previews = previews;
        this.assembler = assembler;
        this.pricing = pricing;
        this.settings = settings;
        this.plans = plans;
        this.maxPerTarget = maxPerTarget;
        this.maxPerSession = maxPerSession;
        this.maxArtifacts = maxArtifacts;
    }

    /**
     * @param holdCredits the batch hold: the budget the plan must fit (the planner's input, architecture section 10)
     * @throws SourceGoneException the deck or a pinned note is gone
     * @throws app.mnema.learning.ai.prompt.PromptException the prompt exceeds its budget
     */
    Request build(Session session, int holdCredits) {
        Plans.Basis basis = plans.basis(session);
        UUID owner = session.ownerId();
        UUID deck = session.deckId();
        Brief brief = context.brief(owner, deck).orElseThrow(SourceGoneException::new);
        PromptValues values = PromptValues.create().text("deck.title", orDash(brief.title())).text("deck.description", orDash(brief.description()))
                .number("counts.items", brief.items()).number("counts.exercises", brief.exercises())
                .text("lang.output", session.spec().path("outputLanguage").stringValue(MaterialsSpec.DEFAULT_LANGUAGE))
                .text("task.kind", basis.kind());
        ArrayNode targets = Json.array();
        ArrayNode sources = Json.array();
        if (basis.kind().equals(Plans.EXERCISES)) {
            values.block("target_lines", targetLines(deck, basis, targets)).block("note_blocks", PromptBlocks.empty())
                    .block("outline.lines", PromptBlocks.empty())
                    .text("task.hint", exerciseHint(session, basis))
                    .block("limit_lines", PromptBlocks.lines(exerciseLimits(basis)));
        } else {
            values.block("target_lines", PromptBlocks.empty()).block("note_blocks", noteBlocks(session, basis, sources))
                    .block("outline.lines", outline(deck)).text("request", basis.materials().prompt())
                    .text("task.hint", materialHint(basis)).block("limit_lines", PromptBlocks.lines(materialLimits(basis)));
        }
        values.text("task.budget", budget(basis, holdCredits));
        AssembledPrompt prompt = assembler.assemble(PromptTask.PLAN, values);
        return new Request(prompt, basis, targets, sources, TEMPERATURE);
    }

    // ------------------------------------------------------------------------ exercises

    /** {@code m1 · title · exercises: 3 (CLOZE 2, CHOICE 1)} per target, in the spec's order; fills the stored titles. */
    private PromptBlock targetLines(UUID deck, Plans.Basis basis, ArrayNode stored) {
        List<UUID> members = basis.targets().stream().map(Source::memberKey).toList();
        Map<UUID, Head> heads = new HashMap<>();
        context.heads(deck, members).forEach(head -> heads.put(head.memberKey(), head));
        Map<UUID, Integer> counts = context.exerciseCounts(deck, members);
        Map<UUID, Map<String, Integer>> mechanics = context.mechanicCounts(deck, members);
        List<String> lines = new ArrayList<>();
        int handle = 1;
        for (Source target : basis.targets()) {
            Head head = heads.get(target.memberKey());
            String title = head == null ? "" : head.title() != null ? head.title()
                    : previews.title(head.scopeId(), head.memberKey(), head.revisionId());
            title = clip(title == null ? "" : title);
            int count = counts.getOrDefault(target.memberKey(), 0);
            StringBuilder line = new StringBuilder("m").append(handle++).append(" · ").append(title.isBlank() ? "без названия" : title)
                    .append(" · exercises: ").append(count);
            Map<String, Integer> byMechanic = mechanics.getOrDefault(target.memberKey(), Map.of());
            if (!byMechanic.isEmpty()) {
                line.append(" (");
                byMechanic.forEach((mechanic, number) -> line.append(mechanic).append(' ').append(number).append(", "));
                line.setLength(line.length() - 2);
                line.append(')');
            }
            lines.add(line.toString());
            stored.addObject().put("memberKey", target.memberKey().toString()).put("title", title).put("exercises", count);
        }
        return PromptBlocks.lines(lines);
    }

    /** The task line the planner reads: the allowed mechanics (the Stub reads them) and the requested number per material. */
    private static String exerciseHint(Session session, Plans.Basis basis) {
        // the quantity the spec states: the per-target count of EXACT; AUTO and a budget leave the number to the planner
        var quantity = session.spec().path("settings").path("quantity");
        String perTarget = quantity.path("mode").stringValue("").equals("EXACT") ? Integer.toString(quantity.path("perTarget").asInt(0)) : "не указано";
        return "Механики: " + String.join(", ", basis.allowedMechanics()) + ". На материал: " + perTarget + ".";
    }

    private List<String> exerciseLimits(Plans.Basis basis) {
        return List.of("разрешённые механики: " + String.join(", ", basis.allowedMechanics()),
                "упражнений на материал: от 1 до " + maxPerTarget, "упражнений в плане всего: не больше " + maxPerSession,
                "материалов в плане: не больше " + basis.targets().size() + ", каждый не больше одного раза");
    }

    // ------------------------------------------------------------------------ materials

    /** {@code <note id="n1">text</note>} per note, clipped to its share of the notes budget; fills the stored labels. */
    private PromptBlock noteBlocks(Session session, Plans.Basis basis, ArrayNode stored) {
        List<PromptBlock> blocks = new ArrayList<>();
        int share = Math.max(100, Math.min(NOTES_TOKENS, settings.context().notesTokens()) / Math.max(1, basis.notes().size()));
        int number = 1;
        for (Source note : basis.notes()) {
            String text = repository.pinnedNoteText(session.sessionId(), note.noteId(), note.noteRowVersion()).orElseThrow(SourceGoneException::new);
            blocks.add(PromptBlocks.note("n" + number++, ContextBuilder.clip(text, share)));
            stored.addObject().put("noteId", note.noteId().toString()).put("label", label(text));
        }
        return PromptBlocks.join(blocks);
    }

    /** The titles the deck already has (the latest ones), so the plan does not repeat a material. */
    private PromptBlock outline(UUID deck) {
        List<String> titles = new ArrayList<>();
        for (Head head : context.latest(deck, OUTLINE_TITLES)) {
            String title = head.title() != null ? head.title()
                    : previews.title(head.scopeId(), head.memberKey(), head.revisionId());
            if (title != null && !title.isBlank()) titles.add(clip(title));
        }
        return PromptBlocks.lines(titles);
    }

    private static String materialHint(Plans.Basis basis) {
        if (basis.perNote()) return "В каждом пункте source — handle заметки.";
        return basis.notes().isEmpty() ? "Заметок нет: source — null, темы бери из просьбы." : "Заметки объединены: source — null.";
    }

    private List<String> materialLimits(Plans.Basis basis) {
        List<String> lines = new ArrayList<>();
        lines.add("материалов в плане: не больше " + maxArtifacts);
        String fixed = basis.fixedEffort();
        lines.add(fixed == null ? "подробность выбирай сам: SHORT, MEDIUM или DETAILED" : "подробность задана: " + fixed);
        return lines;
    }

    // ------------------------------------------------------------------------ budget

    /** The credits the plan may cost and what one unit costs, so the model can trade a few big items for many small ones. */
    private String budget(Plans.Basis basis, int holdCredits) {
        if (basis.kind().equals(Plans.EXERCISES)) {
            return "не больше " + holdCredits + " кредитов; пять упражнений стоят " + pricing.exerciseCredits(5) + " кредитов";
        }
        return "не больше " + holdCredits + " кредитов; материал: SHORT " + pricing.credits("MATERIAL_SHORT") + ", MEDIUM "
                + pricing.credits("MATERIAL_MEDIUM") + ", DETAILED " + pricing.credits("MATERIAL_DETAILED");
    }

    // ------------------------------------------------------------------------ small parts

    private static String label(String note) {
        String line = note.strip().lines().findFirst().orElse("").strip();
        if (line.isEmpty()) return "";
        return line.length() <= NOTE_LABEL ? line : line.substring(0, NOTE_LABEL).strip() + "…";
    }

    private static String clip(String text) {
        if (text.length() <= TITLE_CHARACTERS) return text;
        int end = Character.isHighSurrogate(text.charAt(TITLE_CHARACTERS - 1)) ? TITLE_CHARACTERS - 1 : TITLE_CHARACTERS;
        return text.substring(0, end) + "…";
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "не указано" : value;
    }
}
