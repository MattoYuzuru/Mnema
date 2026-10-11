package app.mnema.learning.update.plan;

import app.mnema.learning.library.DeckAccess;
import app.mnema.learning.library.DeckRef;
import app.mnema.learning.library.ManifestDiff;
import app.mnema.learning.library.Resolution;
import app.mnema.learning.library.Viewer;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.update.plan.PlanRepository.Copy;
import app.mnema.learning.update.plan.PlanRepository.SourceRevision;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The update plan of a copy (community decks architecture sections 5 and 7, contract {@code contracts/decks/updates.json}): what the author published since the copy's base,
 * unit by unit, against what the copy holds now. It is a pure read: it creates no revision, no row and no pin, whatever the size of the deck (the only locks are the row key
 * shares of immutable storage objects every reader takes). Owner only: a deck that is not the caller's live copy is the opaque 404.
 *
 * <p>The sources are the pins of the plan: the copy's head revision H, the source's published revision P (read through {@link DeckAccess} with the code the copier used, so a
 * source that is deleted, private, hidden or whose code was rotated away is unavailable, and nothing else is said of it), the base B and the copy's decisions. One repeatable-read
 * transaction reads all of them, and {@code planVersion} is their digest.
 */
@Service
public class UpdatePlanService {
    public static final int DEFAULT_PAGE = 50;
    public static final int MAX_PAGE = 100;

    private final PlanRepository repository;
    private final DeckAccess access;
    private final ManifestDiff diffs;
    private final PlanContent contents;
    private final PlanSides sides;
    private final UpdatePlanSettings settings;

    UpdatePlanService(PlanRepository repository, DeckAccess access, ManifestDiff diffs, PlanContent contents, PlanSides sides, UpdatePlanSettings settings) {
        this.repository = repository;
        this.access = access;
        this.diffs = diffs;
        this.contents = contents;
        this.sides = sides;
        this.settings = settings;
    }

    /** The plan with the rows it was read from, for the unit endpoint and the page. */
    record Computed(UpdatePlan plan, Copy copy, SourceRevision published) { }

    /** The whole plan of the caller's copy; its {@link UpdatePlan#state()} says when there is nothing to plan. */
    @Transactional(timeout = 10, isolation = Isolation.REPEATABLE_READ)
    public UpdatePlan plan(UUID owner, UUID deckId) {
        return compute(owner, deckId).plan();
    }

    /** One page of the plan as the wire shape. {@code cursor} and {@code limit} are the raw query values (null: absent). */
    @Transactional(timeout = 10, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode page(UUID owner, UUID deckId, String cursor, String limit) {
        int size = pageSize(limit);
        PlanVersion.Cursor position = PlanVersion.Cursor.decode(cursor, deckId);
        Computed computed = compute(owner, deckId);
        UpdatePlan plan = computed.plan();
        if (position != null && (!plan.hasPins() || !position.planVersion().equals(plan.planVersion()))) throw new UpdatePlanStaleException();
        int from = position == null ? 0 : position.offset();
        if (from > plan.units().size()) throw new InvalidRequestException();
        int to = Math.min(plan.units().size(), from + size);
        List<PlanUnit> page = plan.units().subList(from, to);
        Map<String, String> titles = page.isEmpty() ? Map.of() : sides.titles(computed.copy(), computed.published(), page);
        ObjectNode json = JsonNodeFactory.instance.objectNode().put("state", plan.state().name()).put("forkDeckId", deckId.toString());
        if (!plan.hasPins()) {
            json.putArray("units");
            return json.putNull("nextCursor");
        }
        json.put("forkHeadRevisionId", plan.forkHeadRevisionId().toString()).put("upstreamRevisionId", plan.upstreamRevisionId().toString())
                .put("baseRevisionId", plan.baseRevisionId().toString()).put("planVersion", plan.planVersion());
        if (plan.releaseNote() == null) json.putNull("releaseNote"); else json.put("releaseNote", plan.releaseNote());
        UpdatePlan.Summary summary = plan.summary();
        json.putObject("summary").put("newMaterials", summary.newMaterials()).put("newExercises", summary.newExercises()).put("fixes", summary.fixes())
                .put("conflicts", summary.conflicts()).put("deletedByAuthor", summary.deletedByAuthor()).put("askAgain", summary.askAgain());
        ArrayNode units = json.putArray("units");
        for (PlanUnit unit : page) units.add(unitJson(unit, titles));
        if (to < plan.units().size()) json.put("nextCursor", new PlanVersion.Cursor(deckId, plan.planVersion(), to).encode()); else json.putNull("nextCursor");
        return json;
    }

    /**
     * The two sides of one unit of the CURRENT plan: the plan is recomputed and must still be the one the caller read ({@code planVersion}, else 409), and a unit it does not
     * list is the opaque 404, so this is never a way to read a revision of the lineage by its id.
     */
    @Transactional(timeout = 10, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode unit(UUID owner, UUID deckId, UnitKind kind, UUID key, String planVersion) {
        if (!PlanVersion.valid(planVersion)) throw new InvalidRequestException();
        Computed computed = compute(owner, deckId);
        UpdatePlan plan = computed.plan();
        if (!plan.hasPins()) throw new ResourceNotFoundException();
        if (!plan.planVersion().equals(planVersion)) throw new UpdatePlanStaleException();
        PlanUnit unit = plan.unit(kind, key).orElseThrow(ResourceNotFoundException::new);
        return sides.read(computed, unit);
    }

    static ObjectNode unitJson(PlanUnit unit, Map<String, String> titles) {
        ObjectNode json = JsonNodeFactory.instance.objectNode().put("unitId", unit.unitId()).put("kind", unit.kind().name()).put("key", unit.key().toString())
                .put("class", unit.unitClass().name());
        if (unit.reason() == null) json.putNull("reason"); else json.put("reason", unit.reason().name());
        String title = titles.get(unit.unitId());
        if (title == null) json.putNull("title"); else json.put("title", title);
        put(json, "authorRevisionId", unit.authorRevisionId());
        put(json, "mineRevisionId", unit.mineRevisionId());
        put(json, "baseRevisionId", unit.baseRevisionId());
        ArrayNode dependencies = json.putArray("dependsOn");
        unit.dependsOn().forEach(dependencies::add);
        return json.put("defaultChoice", unit.defaultChoice().name());
    }

    private static void put(ObjectNode json, String name, UUID value) {
        if (value == null) json.putNull(name); else json.put(name, value.toString());
    }

    private Computed compute(UUID owner, UUID deckId) {
        // one budget for the whole computation: its clock starts with the request's work
        PlanBudget budget = settings.budget();
        UuidPolicy.requireEntityId(owner, "owner");
        UuidPolicy.requireEntityId(deckId, "deckId");
        Copy copy = repository.copy(owner, deckId).orElseThrow(ResourceNotFoundException::new);
        if (!copy.materialized()) return closed(UpdatePlan.State.PREPARING, copy);
        if (copy.detached()) return closed(UpdatePlan.State.DETACHED, copy);
        // the source is read exactly like the copier's own hub does: by the code he copied by, so a rotated code, a deleted, private or hidden source is "unavailable"
        Resolution resolution = access.resolve(Viewer.account(owner, null), copy.upstreamCode());
        boolean available = resolution.granted() && resolution.deck().deckId().equals(copy.upstreamDeckId()) && resolution.deck().published() != null;
        if (!available) return closed(UpdatePlan.State.SOURCE_UNAVAILABLE, copy);
        DeckRef.Published pinned = resolution.deck().published();
        SourceRevision published = new SourceRevision(pinned.revisionId(), pinned.title(), pinned.description(), pinned.membersRootId(), pinned.exercisesRootId(),
                pinned.memberCount(), pinned.exerciseCount());
        SourceRevision base = repository.sourceRevision(copy.upstreamDeckId(), copy.baseRevisionId())
                .orElseThrow(() -> new IllegalStateException("The base revision of a copy is missing"));
        List<Decision> decisions = repository.decisions(copy.deckId());
        List<PlanUnit> units = new PlanComputation(repository, diffs, contents, copy, base, published, decisions, budget).units();
        String version = PlanVersion.of(copy.headRevisionId(), published.revisionId(), copy.baseRevisionId(), decisions);
        String note = units.isEmpty() ? null : repository.releaseNote(copy.upstreamDeckId(), published.revisionId()).orElse(null);
        UpdatePlan plan = new UpdatePlan(units.isEmpty() ? UpdatePlan.State.UP_TO_DATE : UpdatePlan.State.READY, copy.deckId(), copy.headRevisionId(), published.revisionId(),
                copy.baseRevisionId(), version, note, UpdatePlan.Summary.of(units), units);
        return new Computed(plan, copy, published);
    }

    private static Computed closed(UpdatePlan.State state, Copy copy) {
        return new Computed(UpdatePlan.closed(state, copy.deckId()), copy, null);
    }

    static int pageSize(String value) {
        if (value == null) return DEFAULT_PAGE;
        if (!value.matches("[1-9][0-9]{0,2}")) throw new InvalidRequestException();
        int size = Integer.parseInt(value);
        if (size > MAX_PAGE) throw new InvalidRequestException();
        return size;
    }
}
