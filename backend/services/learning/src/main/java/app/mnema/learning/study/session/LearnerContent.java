package app.mnema.learning.study.session;

import app.mnema.learning.catalog.exercise.AnswerKey;
import app.mnema.learning.catalog.exercise.Block;
import app.mnema.learning.catalog.exercise.ExerciseContent;
import app.mnema.learning.catalog.exercise.ExerciseType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.UUID;

/**
 * Resolves authored content into what a learner may see, once, when a presentation is issued.
 *
 * <p>MATERIAL becomes TEXT from the pinned revision; author labels (media titles), answer keys and
 * accepted strings never enter the result. Transcripts stay inside the stored content and are filtered
 * by {@link #view} until the learner reveals them. MATCH sides are shuffled here with an unpredictable
 * source and the result is persisted, so reads and replays return the same order; the order is deliberately
 * not derivable from any identifier a client holds.
 */
public final class LearnerContent {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private LearnerContent() { }

    /** Resolves the plain text of a quoted native node from its pinned item revision. */
    @FunctionalInterface
    interface TextSource {
        String text(Block.Material material);
    }

    /** {@code content} is shown while answering; {@code reveal} only after the answer is final. */
    record Resolved(ObjectNode content, ObjectNode reveal) { }

    static Resolved issue(ExerciseType type, JsonNode stored, AnswerKey key, RandomGenerator random, TextSource text) {
        ExerciseContent model = ExerciseContent.parse(type, stored);
        ObjectNode content = JSON.objectNode();
        ObjectNode reveal = JSON.objectNode();
        switch (model) {
            case ExerciseContent.SelfCheck selfCheck -> {
                content.set("prompt", blocks(selfCheck.prompt(), text));
                content.set("reference", blocks(selfCheck.reference(), text));
            }
            case ExerciseContent.FreeResponse response -> {
                content.set("prompt", blocks(response.prompt(), text));
                content.put("responseInput", response.responseInput());
                // Feedback is stored in receipts, so it carries availability but never transcript text.
                reveal.set("reference", view(blocks(response.reference(), text), false));
            }
            case ExerciseContent.Cloze cloze -> {
                content.set("prompt", blocks(cloze.prompt(), text));
                content.set("passage", passage(cloze, (AnswerKey.Cloze) key));
            }
            case ExerciseContent.Choice choice -> {
                content.set("prompt", blocks(choice.prompt(), text));
                content.put("selectionMode", choice.multiple() ? "MULTIPLE" : "SINGLE");
                ArrayNode options = content.putArray("options");
                choice.options().forEach(option -> options.addObject().put("optionId", option.optionId().toString())
                        .set("blocks", blocks(option.blocks(), text)));
            }
            case ExerciseContent.Match match -> {
                content.set("prompt", blocks(match.prompt(), text));
                shuffledSides(match, random, content, text);
            }
        }
        return new Resolved(content, reveal);
    }

    /** The learner's view of stored content: transcripts are present only once revealed. */
    static <T extends JsonNode> T view(T stored, boolean revealed) {
        T copy = stored.deepCopy();
        if (!revealed) strip(copy);
        return copy;
    }

    /** True when any audio or video block carries a transcript that can be revealed. */
    static boolean hasTranscript(JsonNode stored) {
        if (stored.isObject() && isTimedMedia(stored) && stored.path("transcriptAvailable").asBoolean(false)) {
            return true;
        }
        for (JsonNode child : stored) if (hasTranscript(child)) return true;
        return false;
    }

    /**
     * First extended grapheme cluster of the NFC form after leading whitespace: one visible character, even for
     * emoji or combining marks, and never whitespace itself.
     */
    public static String firstLetter(String accepted) {
        String canonical = Normalizer.normalize(accepted, Normalizer.Form.NFC);
        int start = 0;
        while (start < canonical.length()) {
            int point = canonical.codePointAt(start);
            if (!Character.isWhitespace(point) && !Character.isSpaceChar(point)) break;
            start += Character.charCount(point);
        }
        BreakIterator graphemes = BreakIterator.getCharacterInstance(Locale.ROOT);
        graphemes.setText(canonical);
        int end = graphemes.following(start);
        return canonical.substring(start, end == BreakIterator.DONE ? canonical.length() : end);
    }

    /**
     * Blank id to first letter for the blanks whose author enabled a first-letter hint. Reads only the blank ids
     * and hint flags of {@code content}, so issued learner content and authored content give the same answer.
     */
    public static Map<UUID, String> firstLetterHints(JsonNode content, AnswerKey.Cloze key) {
        Map<UUID, String> keyed = new HashMap<>();
        key.blanks().forEach(blank -> keyed.put(blank.blankId(), firstLetter(blank.rule().accepted().getFirst())));
        Map<UUID, String> result = new HashMap<>();
        for (JsonNode segment : content.path("passage")) {
            if (segment.path("kind").textValue().equals("BLANK") && segment.path("firstLetterHint").booleanValue()) {
                UUID blankId = UUID.fromString(segment.path("blankId").textValue());
                result.put(blankId, keyed.get(blankId));
            }
        }
        return result;
    }

    private static void strip(JsonNode node) {
        if (node.isObject()) {
            if (isTimedMedia(node)) ((ObjectNode) node).remove("transcript");
            node.forEach(LearnerContent::strip);
        } else if (node.isArray()) node.forEach(LearnerContent::strip);
    }

    private static boolean isTimedMedia(JsonNode node) {
        String kind = node.path("kind").textValue();
        return "AUDIO".equals(kind) || "VIDEO".equals(kind);
    }

    private static ArrayNode blocks(List<Block> values, TextSource text) {
        ArrayNode result = JSON.arrayNode();
        values.forEach(block -> result.add(block(block, text)));
        return result;
    }

    private static ObjectNode block(Block block, TextSource text) {
        return switch (block) {
            case Block.Text value -> JSON.objectNode().put("kind", "TEXT").put("text", value.text());
            case Block.Material material -> JSON.objectNode().put("kind", "TEXT").put("text", text.text(material));
            case Block.Image image -> JSON.objectNode().put("kind", "IMAGE")
                    .put("assetId", image.assetId().toString()).put("alt", image.alt());
            case Block.Audio audio -> timed("AUDIO", audio.assetId(), audio.transcript());
            case Block.Video video -> timed("VIDEO", video.assetId(), video.transcript());
            case Block.Youtube youtube -> JSON.objectNode().put("kind", "YOUTUBE")
                    .put("videoId", youtube.videoId()).put("title", youtube.title());
        };
    }

    /** The author's title is a label for the editor and is deliberately not copied. */
    private static ObjectNode timed(String kind, UUID assetId, String transcript) {
        ObjectNode result = JSON.objectNode().put("kind", kind).put("assetId", assetId.toString())
                .put("transcriptAvailable", transcript != null);
        if (transcript != null) result.put("transcript", transcript);
        return result;
    }

    private static ArrayNode passage(ExerciseContent.Cloze cloze, AnswerKey.Cloze key) {
        Map<UUID, AnswerKey.BlankKey> keys = new HashMap<>();
        key.blanks().forEach(blank -> keys.put(blank.blankId(), blank));
        ArrayNode result = JSON.arrayNode();
        for (ExerciseContent.Segment segment : cloze.passage()) {
            switch (segment) {
                case ExerciseContent.Literal literal -> result.addObject().put("kind", "TEXT")
                        .put("text", literal.text());
                case ExerciseContent.Blank blank -> {
                    ObjectNode node = result.addObject().put("kind", "BLANK").put("blankId", blank.blankId().toString());
                    // ANSWER_LENGTH exposes only the width, computed from the private key.
                    int length = blank.fixed() ? blank.length()
                            : ExerciseContent.answerLength(keys.get(blank.blankId()).rule().accepted().getFirst());
                    node.putObject("size").put("mode", blank.fixed() ? "FIXED" : "ANSWER_LENGTH").put("length", length);
                    node.put("firstLetterHint", blank.firstLetterHint());
                }
            }
        }
        return result;
    }

    /**
     * Both columns are shuffled independently by the issue-time source and persisted. The answer key is
     * deliberately not consulted: correcting a permutation that happens to line rows up would itself leak
     * the key (with two pairs it would always cross them), so every arrangement stays possible.
     */
    private static void shuffledSides(ExerciseContent.Match match, RandomGenerator random, ObjectNode content,
                                      TextSource text) {
        List<ExerciseContent.Item> left = new ArrayList<>(match.left());
        List<ExerciseContent.Item> right = new ArrayList<>(match.right());
        Collections.shuffle(left, random);
        Collections.shuffle(right, random);
        content.set("left", items(left, text));
        content.set("right", items(right, text));
    }

    private static ArrayNode items(List<ExerciseContent.Item> values, TextSource text) {
        ArrayNode result = JSON.arrayNode();
        values.forEach(item -> result.addObject().put("itemId", item.itemId().toString())
                .set("blocks", blocks(item.blocks(), text)));
        return result;
    }
}
