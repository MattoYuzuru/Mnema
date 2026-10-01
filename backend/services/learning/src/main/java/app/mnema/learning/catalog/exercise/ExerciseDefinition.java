package app.mnema.learning.catalog.exercise;

import app.mnema.learning.media.MediaCatalog;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static app.mnema.learning.catalog.exercise.StrictJson.invalid;

/**
 * The mechanic-defining parts of an exercise (type, content, answer key and evaluator) after the one
 * validation shared by publication and the stateless author preview. It performs only structural checks:
 * MATERIAL blocks are not resolved, media assets are opaque and nothing is looked up.
 */
public record ExerciseDefinition(ExerciseType type, ExerciseContent model, AnswerKey key, EvaluatorPolicy policy) {
    public static final int MAX_MEDIA_BLOCKS = MediaCatalog.MAX_EXERCISE_ASSETS;

    /** Strict exact-field parse of the four parts; any failure is an {@code INVALID_REQUEST}. */
    public static ExerciseDefinition read(JsonNode type, JsonNode content, JsonNode answerKey,
                                          JsonNode evaluatorPolicy) {
        ExerciseType mechanic = ExerciseType.fromWire(type.textValue()).orElseThrow(StrictJson::invalid);
        ExerciseContent model = ExerciseContent.parse(mechanic, content);
        AnswerKey key = AnswerKey.parse(mechanic, answerKey);
        AnswerKey.requireConsistent(model, key);
        EvaluatorPolicy policy = EvaluatorPolicy.parse(mechanic, evaluatorPolicy);
        assets(model); // rejects one asset pinned as two different media kinds
        if (model.blocks().stream().filter(Block::isMedia).count() > MAX_MEDIA_BLOCKS) throw invalid();
        return new ExerciseDefinition(mechanic, model, key, policy);
    }

    /** Distinct assets pinned by IMAGE, AUDIO and VIDEO blocks of every slot. */
    static List<MediaCatalog.ExerciseAsset> assets(ExerciseContent model) {
        Map<UUID, MediaCatalog.Kind> kinds = new LinkedHashMap<>();
        for (Block block : model.blocks()) {
            MediaCatalog.ExerciseAsset asset = block.asset().orElse(null);
            if (asset == null) continue;
            MediaCatalog.Kind previous = kinds.putIfAbsent(asset.assetId(), asset.kind());
            if (previous != null && previous != asset.kind()) throw invalid();
        }
        List<MediaCatalog.ExerciseAsset> result = new ArrayList<>();
        kinds.forEach((assetId, kind) -> result.add(new MediaCatalog.ExerciseAsset(assetId, kind)));
        return List.copyOf(result);
    }
}
