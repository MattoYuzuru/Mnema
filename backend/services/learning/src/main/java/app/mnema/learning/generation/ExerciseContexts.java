package app.mnema.learning.generation;

import app.mnema.learning.ai.TokenCounter;
import app.mnema.learning.ai.prompt.AssembledPrompt;
import app.mnema.learning.ai.prompt.PromptAssembler;
import app.mnema.learning.ai.prompt.PromptBlock;
import app.mnema.learning.ai.prompt.PromptBlocks;
import app.mnema.learning.ai.prompt.PromptTask;
import app.mnema.learning.ai.prompt.PromptValues;
import app.mnema.learning.catalog.content.ItemPreviews;
import app.mnema.learning.generation.ContextBuilder.SourceGoneException;
import app.mnema.learning.generation.ContextRepository.ExerciseLine;
import app.mnema.learning.generation.ContextRepository.Head;
import app.mnema.learning.generation.ContextRepository.ObjectiveLine;
import app.mnema.learning.generation.PinnedMaterials.Pinned;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.exercise.ExerciseContext;
import app.mnema.learning.generation.exercise.ExerciseOutputSchema;
import app.mnema.learning.generation.exercise.ExerciseValidator;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the prompt of one {@code EXERCISES} step from the database, in the cache-friendly order of the exercise prompt of
 * {@code prompts/v1/exercises.md} (the assembler puts the data policy of the core in front of it): the schema, the pinned
 * material as {@code <material id="m1">} with the handles {@code b1..bn} of its top-level blocks, the objectives of the material
 * ({@code t1..}, with the mechanics of the exercises that already evidence each), the first line of every current exercise
 * of the material (at most {@value #MAX_EXISTING}, so the model does not repeat them), the titles of neighbouring materials,
 * and the task: how many exercises, which mechanics, which language. The same call returns the {@link ExerciseContext} that
 * validates the answer, so what the model was shown and what the lint resolves are one thing.
 *
 * <p>Everything that came from a user (material text, titles, objective titles, exercise prompts) goes through
 * {@link PromptBlocks}, which redacts personal-data patterns and escapes markup.
 */
@Component
class ExerciseContexts {
    private static final int MAX_EXISTING = 30;
    private static final int MAX_OBJECTIVES = 30;
    private static final int MAX_NEIGHBORS = 10;
    /** Leaves room for the schema (about 2.5k tokens), the skill (1.5k) and the lists below within the 14k ceiling of the section. */
    private static final int MATERIAL_TOKENS = 5_500;
    private static final int TITLE_CHARACTERS = 100;
    private static final int FIRST_LINE = 120;
    private static final double TEMPERATURE = 0.4;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** The placeholders of the compiled command, replaced at approval (a fixed version-4 command id and a deck revision). */
    static final UUID PLACEHOLDER_COMMAND = UUID.fromString("018f1d98-5c10-4abc-8abc-0123456789c1");
    static final UUID PLACEHOLDER_DECK_REVISION = UUID.fromString("22222222-2222-4222-8222-222222222222");

    /** What one run needs: the assembled prompt, the context that validates the answer and the output bounds. */
    record Request(AssembledPrompt prompt, ExerciseContext context, int maxTokens, double temperature, Set<String> existingKeys) {
        /** The blocks the model was shown, as {@code m1:b3 -> node id}: what a later re-pin compares with the head of the material. */
        Map<String, String> shown() {
            Map<String, String> shown = new LinkedHashMap<>();
            context.materials().get("m1").blocks().forEach((handle, block) -> shown.put("m1:" + handle, block.nodeId().toString()));
            return shown;
        }
    }

    private final ContextRepository context;
    private final PinnedMaterials materials;
    private final ItemPreviews previews;
    private final PromptAssembler assembler;
    private final GenerationSettings settings;
    private final String compactSchema;

    ExerciseContexts(ContextRepository context, PinnedMaterials materials, ItemPreviews previews, PromptAssembler assembler,
                     ExerciseOutputSchema schema, GenerationSettings settings) {
        this.context = context;
        this.materials = materials;
        this.previews = previews;
        this.assembler = assembler;
        this.settings = settings;
        this.compactSchema = compact(schema.text());
    }

    /**
     * @param member the target material and the revision this step is written from (the pin of the artifact)
     * @throws SourceGoneException the pinned revision is not readable any more
     * @throws app.mnema.learning.ai.prompt.PromptException the prompt exceeds its budget
     */
    Request build(Session session, UUID member, UUID revision, int count, ExercisesSpec spec) {
        UUID owner = session.ownerId();
        UUID deck = session.deckId();
        Pinned pinned = materials.read(owner, deck, member, revision).orElseThrow(SourceGoneException::new);
        List<PinnedMaterials.Block> shown = withinBudget(pinned.blocks());
        Map<String, ExerciseContext.Block> blocks = new LinkedHashMap<>();
        for (PinnedMaterials.Block block : shown) blocks.put(block.handle(), new ExerciseContext.Block(block.nodeId(), block.text()));
        List<PromptBlocks.HandleLine> lines = shown.stream().map(block -> new PromptBlocks.HandleLine(block.handle(), block.text())).toList();

        Map<String, ExerciseContext.Objective> objectives = new LinkedHashMap<>();
        List<String> objectiveLines = new ArrayList<>();
        int number = 1;
        for (ObjectiveLine objective : context.objectives(deck, member, MAX_OBJECTIVES)) {
            String handle = "t" + number++;
            objectives.put(handle, new ExerciseContext.Objective(objective.objectiveId(), objective.revisionId(), objective.title()));
            objectiveLines.add(handle + " · " + clip(objective.title(), TITLE_CHARACTERS)
                    + (objective.types().isEmpty() ? "" : " · " + String.join(", ", objective.types())));
        }
        List<String> existing = new ArrayList<>();
        Set<String> existingKeys = new LinkedHashSet<>();
        for (ExerciseLine exercise : context.exercises(deck, member, MAX_EXISTING)) {
            String line = firstLine(exercise.content());
            if (!line.isBlank()) existing.add(exercise.type() + " · " + line);
            String known = key(exercise.content());
            if (!known.isBlank()) existingKeys.add(known);
        }

        List<String> mechanics = spec.allowedMechanics();
        PromptValues values = PromptValues.create().block("schema", PromptBlocks.schema(compactSchema))
                .block("material_blocks", PromptBlocks.material("m1", lines))
                .block("objective_lines", PromptBlocks.lines(objectiveLines))
                .block("existing_exercise_lines", PromptBlocks.lines(existing))
                .block("neighbor_lines", PromptBlocks.lines(neighbors(deck, member)))
                .number("task.count", count).text("task.mechanics", mechanicsText(mechanics, count))
                .text("lang.output", spec.outputLanguage());
        AssembledPrompt prompt = assembler.assemble(PromptTask.EXERCISES, values);
        ExerciseContext validation = new ExerciseContext(PLACEHOLDER_COMMAND, PLACEHOLDER_DECK_REVISION,
                Map.of("m1", new ExerciseContext.Material(member, revision, blocks)), objectives, spec.allowedSet());
        return new Request(prompt, validation, Math.min(16_000, 800 + 800 * count), TEMPERATURE, existingKeys);
    }

    /** The blocks that fit the token budget of the material (the rest are not offered, so no handle names them). */
    private List<PinnedMaterials.Block> withinBudget(List<PinnedMaterials.Block> blocks) {
        int budget = Math.min(MATERIAL_TOKENS, settings.context().notesTokens());
        List<PinnedMaterials.Block> shown = new ArrayList<>();
        int tokens = 0;
        for (PinnedMaterials.Block block : blocks) {
            tokens += TokenCounter.estimate(block.text()) + 4;
            if (tokens > budget && !shown.isEmpty()) break;
            shown.add(block);
        }
        return shown;
    }

    /**
     * The mechanics line of the task. The machine-readable part is {@code Механики: A, B, C} (registry names, comma separated, in
     * registry order: the Stub reads it); the rest asks the model for variety, which a single mechanic repeated does not give.
     */
    private static String mechanicsText(List<String> mechanics, int count) {
        String text = String.join(", ", mechanics);
        if (count >= 3 && mechanics.size() >= 3) return text + ". Разных механик — не меньше трёх";
        if (count >= 2 && mechanics.size() >= 2) return text + ". Чередуй механики";
        return text;
    }

    /** The titles of other materials of the deck, newest first: the neighbouring concepts a distractor may be taken from. */
    private List<String> neighbors(UUID deck, UUID member) {
        List<String> titles = new ArrayList<>();
        for (Head head : context.latest(deck, MAX_NEIGHBORS + 2)) {
            if (head.memberKey().equals(member) || titles.size() == MAX_NEIGHBORS) continue;
            String title = head.title() != null ? head.title()
                    : previews.title(deck, head.memberKey(), head.revisionId(), head.scopeId(), head.contentRootId());
            if (title != null && !title.isBlank()) titles.add(clip(title, TITLE_CHARACTERS));
        }
        return titles;
    }

    /** The first line of the prompt of an exercise: its first TEXT block, else the passage of a cloze; cut to a short line. */
    static String firstLine(String content) {
        try {
            JsonNode node = JSON.readTree(content);
            String text = "";
            for (JsonNode block : node.path("prompt")) {
                if (block.path("kind").stringValue("").equals("TEXT") && !block.path("text").stringValue("").isBlank()) {
                    text = block.path("text").stringValue("");
                    break;
                }
            }
            if (text.isBlank()) {
                StringBuilder passage = new StringBuilder();
                for (JsonNode segment : node.path("passage")) {
                    passage.append(segment.path("kind").stringValue("").equals("TEXT") ? segment.path("text").stringValue("") : "…");
                }
                text = passage.toString();
            }
            String line = text.strip().lines().findFirst().orElse("");
            return clip(line, FIRST_LINE);
        } catch (JacksonException unreadable) {
            return "";
        }
    }

    private static String key(String content) {
        try {
            return ExerciseValidator.promptKey(JSON.readTree(content));
        } catch (JacksonException unreadable) {
            return "";
        }
    }

    private static String clip(String text, int characters) {
        if (text.length() <= characters) return text;
        int end = Character.isHighSurrogate(text.charAt(characters - 1)) ? characters - 1 : characters;
        return text.substring(0, end) + "…";
    }

    private static String compact(String text) {
        try {
            return JSON.writeValueAsString(JSON.readTree(text));
        } catch (JacksonException unreadable) {
            throw new IllegalStateException("The exercise output schema is not valid JSON", unreadable);
        }
    }
}
