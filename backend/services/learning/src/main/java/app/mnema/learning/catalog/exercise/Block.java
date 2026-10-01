package app.mnema.learning.catalog.exercise;

import app.mnema.learning.media.MediaCatalog;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static app.mnema.learning.catalog.exercise.StrictJson.fields;
import static app.mnema.learning.catalog.exercise.StrictJson.id;
import static app.mnema.learning.catalog.exercise.StrictJson.invalid;
import static app.mnema.learning.catalog.exercise.StrictJson.nonBlank;

/**
 * A typed authoring block. The same six kinds fill every content slot; {@link Slot} decides which kinds
 * and how many a slot accepts. Titles and transcripts are author-side data and never reach a learner
 * before an explicit reveal.
 */
public sealed interface Block {
    int MAX_LABEL = 1_024;
    int MAX_TRANSCRIPT = 16_384;
    Pattern YOUTUBE_VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    /** Verbatim text: newlines and indentation are preserved. */
    record Text(String text) implements Block { }

    /** A pinned native node resolved to plain text for the learner. */
    record Material(UUID memberKey, UUID itemRevisionId, UUID nodeId, int maxText) implements Block { }

    record Image(UUID assetId, String alt) implements Block { }

    record Audio(UUID assetId, String title, String transcript) implements Block { }

    record Video(UUID assetId, String title, String transcript) implements Block { }

    record Youtube(String videoId, String title) implements Block { }

    /** Asset a block pins in {@code exercise_media_ref}; text, material and YouTube pin nothing. */
    default Optional<MediaCatalog.ExerciseAsset> asset() {
        return switch (this) {
            case Image image -> Optional.of(new MediaCatalog.ExerciseAsset(image.assetId(), MediaCatalog.Kind.IMAGE));
            case Audio audio -> Optional.of(new MediaCatalog.ExerciseAsset(audio.assetId(), MediaCatalog.Kind.AUDIO));
            case Video video -> Optional.of(new MediaCatalog.ExerciseAsset(video.assetId(), MediaCatalog.Kind.VIDEO));
            default -> Optional.empty();
        };
    }

    /** Image, audio, video and YouTube count towards the per-exercise media bound. */
    default boolean isMedia() { return !(this instanceof Text) && !(this instanceof Material); }

    static Block parse(JsonNode node, Slot slot) {
        if (!node.isObject() || !node.path("kind").isTextual()) throw invalid();
        Block block = switch (node.path("kind").textValue()) {
            case "TEXT" -> {
                fields(node, "kind", "text");
                String text = nonBlank(node.path("text"), slot.maxText());
                yield new Text(text);
            }
            case "MATERIAL" -> {
                fields(node, "kind", "memberKey", "itemRevisionId", "nodeId");
                yield new Material(id(node, "memberKey"), id(node, "itemRevisionId"), id(node, "nodeId"),
                        slot.maxText());
            }
            case "IMAGE" -> {
                fields(node, "kind", "assetId", "alt");
                yield new Image(id(node, "assetId"), nonBlank(node.path("alt"), MAX_LABEL));
            }
            case "AUDIO" -> {
                fields(node, Set.of("kind", "assetId", "title"), Set.of("transcript"));
                yield new Audio(id(node, "assetId"), nonBlank(node.path("title"), MAX_LABEL), transcript(node));
            }
            case "VIDEO" -> {
                fields(node, Set.of("kind", "assetId", "title"), Set.of("transcript"));
                yield new Video(id(node, "assetId"), nonBlank(node.path("title"), MAX_LABEL), transcript(node));
            }
            case "YOUTUBE" -> {
                fields(node, "kind", "videoId", "title");
                if (!node.path("videoId").isTextual()
                        || !YOUTUBE_VIDEO_ID.matcher(node.path("videoId").textValue()).matches()) throw invalid();
                yield new Youtube(node.path("videoId").textValue(), nonBlank(node.path("title"), MAX_LABEL));
            }
            default -> throw invalid();
        };
        if (!slot.accepts(block)) throw invalid();
        return block;
    }

    private static String transcript(JsonNode node) {
        return node.has("transcript") ? nonBlank(node.path("transcript"), MAX_TRANSCRIPT) : null;
    }
}
