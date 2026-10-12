package app.mnema.learning.update.plan;

import app.mnema.learning.update.plan.PlanRepository.ObjectiveRev;
import app.mnema.learning.update.plan.PlanRepository.Pair;
import app.mnema.learning.update.plan.PlanRepository.Rev;
import app.mnema.learning.catalog.content.ItemPreviews;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The one-line titles of lineage revisions for the «Что нового» screen (Updates/6 part B, #445): the same lines the update plan shows ({@link PlanSides}: the first readable block of a
 * material, the question of an exercise, the title of an objective), for revisions the CALLER names. The caller must have found the revisions in the journal of a deck it has already
 * authorized (a revision of its own copy), never from a request's id. A taken-down revision shows an empty title. It takes the key-share locks of immutable storage like the plan does, so it runs in an
 * ordinary (not read-only) transaction; the only write is the lineage's title cache, which {@link ItemPreviews} fills.
 */
@Component
public final class UnitTitles {
    /** A revision whose title is wanted: a MATERIAL, EXERCISE or OBJECTIVE unit key and one of its revisions. */
    public record Ref(UnitKind kind, UUID key, UUID revisionId) { }

    private final PlanRepository repository;
    private final ItemPreviews previews;

    UnitTitles(PlanRepository repository, ItemPreviews previews) {
        this.repository = repository;
        this.previews = previews;
    }

    /**
     * The title of each ref by unit id ({@code KIND:key}); empty text when the revision is taken down or unknown. A material's title is the cached one of {@code item_preview}
     * (one statement for all of them); a revision without one is derived and cached by {@link ItemPreviews} (the lineage's own, idempotent projection that every list of
     * materials fills), for at most {@code decodeBudget} revisions per call, in the order of {@code refs}: the rest answer empty until a later call, so the cost of a call is bounded
     * (the statements of exercises and objectives are a handful for all).
     */
    public Map<String, String> of(UUID scope, Collection<Ref> refs, int decodeBudget) {
        Map<String, String> result = new HashMap<>();
        Set<Pair> materials = new LinkedHashSet<>();
        Set<Pair> exercises = new LinkedHashSet<>();
        Set<Pair> objectives = new LinkedHashSet<>();
        for (Ref ref : refs) {
            switch (ref.kind()) {
                case MATERIAL -> materials.add(new Pair(ref.key(), ref.revisionId()));
                case EXERCISE -> exercises.add(new Pair(ref.key(), ref.revisionId()));
                case OBJECTIVE -> objectives.add(new Pair(ref.key(), ref.revisionId()));
                case METADATA, ORDER -> { }
            }
        }
        if (!materials.isEmpty()) {
            Map<Pair, Rev> revisions = repository.itemsById(scope, materials);
            Map<Pair, String> cached = repository.cachedTitles(scope, materials);
            int budget = decodeBudget;
            for (Pair pair : materials) {
                Rev revision = revisions.get(pair);
                String title;
                if (revision == null || revision.blocked()) title = "";
                else if (cached.get(pair) != null) title = PlanSides.clip(cached.get(pair));
                else if (budget-- > 0) title = PlanSides.clip(previews.title(scope, pair.key(), pair.second()));
                else title = "";
                result.put(UnitKind.MATERIAL.unitId(pair.key()), title);
            }
        }
        if (!exercises.isEmpty()) {
            Map<Pair, Rev> revisions = repository.exercisesById(scope, exercises);
            Map<Pair, JsonNode> bodies = repository.exerciseContents(scope, exercises);
            for (Pair pair : exercises) {
                Rev revision = revisions.get(pair);
                JsonNode content = bodies.get(pair);
                result.put(UnitKind.EXERCISE.unitId(pair.key()), revision == null || revision.blocked() || content == null ? "" : PlanSides.clip(PlanSides.prompt(content)));
            }
        }
        if (!objectives.isEmpty()) {
            Map<Pair, ObjectiveRev> revisions = repository.objectiveRevisions(scope, objectives);
            for (Pair pair : objectives) {
                ObjectiveRev revision = revisions.get(pair);
                result.put(UnitKind.OBJECTIVE.unitId(pair.key()), revision == null ? "" : PlanSides.clip(revision.title()));
            }
        }
        return result;
    }
}
