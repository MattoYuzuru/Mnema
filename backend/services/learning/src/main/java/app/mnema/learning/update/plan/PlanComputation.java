package app.mnema.learning.update.plan;

import app.mnema.learning.catalog.content.NativeNodeIndex;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Change;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Result;
import app.mnema.learning.library.ManifestDiff;
import app.mnema.learning.update.plan.PlanRepository.AncestorQuery;
import app.mnema.learning.update.plan.PlanRepository.Binding;
import app.mnema.learning.update.plan.PlanRepository.Copy;
import app.mnema.learning.update.plan.PlanRepository.ExerciseBody;
import app.mnema.learning.update.plan.PlanRepository.ObjectiveRev;
import app.mnema.learning.update.plan.PlanRepository.Pair;
import app.mnema.learning.update.plan.PlanRepository.Rev;
import app.mnema.learning.update.plan.PlanRepository.SourceRevision;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static app.mnema.learning.update.plan.UnitClass.CONFLICT;
import static app.mnema.learning.update.plan.UnitClass.NEW;
import static app.mnema.learning.update.plan.UnitClass.SAFE;

/**
 * The three-way comparison of one copy, for ONE request (community decks architecture sections 5 and 7). It reads and writes nothing else than it is told to: the base B (what the
 * copy and the source started from, or the unit's own older base), the author's published revision P and the copy's head H are compared unit by unit.
 *
 * <p><b>Where the sides come from.</b> The author's changes are the frontier diff of the two published manifests of B and P ({@link ManifestDiff}, O(changes), nothing else is
 * read); a unit the diff does not name was not changed by the author and is not on offer. The copy's side is its own head rows, probed by key for exactly the units named. The
 * revision behind a manifest entry is the lineage row with that descriptor (the probe the materializer uses). A unit with a decision row has the base the row says, see
 * {@link Decision}.
 *
 * <p><b>Classification</b> (a = the author's state in P, b = the unit's base, c = the copy's head; each a revision or absent):
 * a = c: nothing. a = b: the author did not change it. Otherwise a removed unit the copy still has is DELETED; an added unit the copy has not got is NEW; a changed unit is SAFE
 * when c = b or when c has the content of a (the fingerprint), CONFLICT when the learner changed it differently or deleted it. A recorded decision hides the unit while the
 * author's state is still the one it covers and turns every non-trivial result into ASK_AGAIN once the author changed it.
 */
final class PlanComputation {
    private final PlanRepository repository;
    private final ManifestDiff diffs;
    private final PlanContent contents;
    private final Copy copy;
    private final SourceRevision base;
    private final SourceRevision published;
    private final List<Decision> decisions;
    private final Map<Slot, Decision> rows = new HashMap<>();
    private final UUID scope;
    private final UUID deck;
    private final UUID source;
    private final Map<UUID, NativeNodeIndex> nodeIndexes = new HashMap<>();
    private final PlanBudget budget;

    PlanComputation(PlanRepository repository, ManifestDiff diffs, PlanContent contents, Copy copy, SourceRevision base, SourceRevision published, List<Decision> decisions,
                    PlanBudget budget) {
        this.repository = repository;
        this.diffs = diffs;
        this.contents = contents;
        this.copy = copy;
        this.base = base;
        this.published = published;
        this.decisions = decisions;
        this.scope = copy.scopeId();
        this.deck = copy.deckId();
        this.source = copy.upstreamDeckId();
        this.budget = budget;
        decisions.forEach(decision -> rows.put(new Slot(decision.kind(), decision.key()), decision));
    }

    private record Slot(UnitKind kind, UUID key) { }

    /** A unit with the place it takes in the plan: removed units go after the others, in the order of the base. */
    private record Entry(PlanUnit unit, boolean removed, int ordinal) { }

    /** A lane unit with the author's revision (for the dependency check). */
    private record Laned(Entry entry, UUID author) { }

    private enum Raw { NOTHING, NEW, SAFE, IDENTICAL, CONFLICT, LEARNER_DELETED, DELETED }

    /** The units of the plan in their final order, and the implicit ones (not listed, brought by the exercises that need them). */
    record PlanUnits(List<PlanUnit> units, List<PlanUnit> implicit) { }

    /** All units of the plan in their final order. */
    PlanUnits units() {
        Result memberDiff = diffs.members(scope, base.membersRootId(), base.memberCount(), published.membersRootId(), published.memberCount());
        Result exerciseDiff = diffs.exercises(scope, base.exercisesRootId(), base.exerciseCount(), published.exercisesRootId(), published.exerciseCount());
        List<Entry> entries = new ArrayList<>();
        metadata().ifPresent(entries::add);
        List<Laned> materials = lane(new ItemLane(), memberDiff);
        List<Laned> exercises = lane(new ExerciseLane(), exerciseDiff);
        order(memberDiff).ifPresent(entries::add);
        Map<UUID, PlanUnit> materialUnits = new HashMap<>();
        materials.forEach(laned -> materialUnits.put(laned.entry().unit().key(), laned.entry().unit()));
        materials.forEach(laned -> entries.add(laned.entry()));
        Map<Pair, List<Binding>> bindings = repository.bindings(scope, exercises.stream().filter(laned -> laned.author() != null)
                .map(laned -> new Pair(laned.entry().unit().key(), laned.author())).toList());
        Objectives objectives = objectives(exercises, bindings);
        entries.addAll(objectives.entries());
        entries.addAll(finishExercises(exercises, bindings, materialUnits, objectives));
        entries.sort(Comparator.comparingInt((Entry entry) -> entry.unit().unitClass().ordinal()).thenComparingInt(entry -> entry.unit().kind().ordinal())
                .thenComparing(Entry::removed).thenComparingInt(Entry::ordinal).thenComparing(entry -> entry.unit().key()));
        return new PlanUnits(entries.stream().map(Entry::unit).toList(), objectives.hidden());
    }

    // ---- materials and exercises ----

    /** What a lane needs of the tables of its kind. */
    private interface Lane {
        UnitKind kind();

        Map<Pair, Rev> byDescriptor(Collection<Pair> pairs);

        Map<Pair, Rev> byId(Collection<Pair> pairs);

        Map<UUID, Rev> heads(Collection<UUID> keys);

        /** The fingerprints of the content of these revisions, by revision id; a taken-down revision has none, and none is read once the budget is spent. */
        Map<UUID, String> fingerprints(Collection<Rev> revisions);
    }

    private final class ItemLane implements Lane {
        @Override public UnitKind kind() { return UnitKind.MATERIAL; }

        @Override public Map<Pair, Rev> byDescriptor(Collection<Pair> pairs) { return repository.itemsByDescriptor(scope, pairs); }

        @Override public Map<Pair, Rev> byId(Collection<Pair> pairs) { return repository.itemsById(scope, pairs); }

        @Override public Map<UUID, Rev> heads(Collection<UUID> keys) { return repository.itemHeads(deck, keys); }

        @Override
        public Map<UUID, String> fingerprints(Collection<Rev> revisions) {
            Map<UUID, String> result = new HashMap<>();
            for (Rev revision : revisions) {
                if (budget.spent()) break;
                if (!revision.blocked()) result.put(revision.revisionId(), contents.fingerprint(contents.document(scope, revision.contentRootId())));
            }
            return result;
        }
    }

    private final class ExerciseLane implements Lane {
        @Override public UnitKind kind() { return UnitKind.EXERCISE; }

        @Override public Map<Pair, Rev> byDescriptor(Collection<Pair> pairs) { return repository.exercisesByDescriptor(scope, pairs); }

        @Override public Map<Pair, Rev> byId(Collection<Pair> pairs) { return repository.exercisesById(scope, pairs); }

        @Override public Map<UUID, Rev> heads(Collection<UUID> keys) { return repository.exerciseHeads(deck, keys); }

        @Override
        public Map<UUID, String> fingerprints(Collection<Rev> revisions) {
            Map<UUID, String> result = new HashMap<>();
            if (budget.spent()) return result;
            Map<Pair, ExerciseBody> bodies = repository.exerciseBodies(scope, revisions.stream().filter(revision -> !revision.blocked())
                    .map(revision -> new Pair(revision.key(), revision.revisionId())).toList());
            bodies.values().forEach(body -> result.put(body.revisionId(), contents.fingerprint(learnerRelevant(body))));
            return result;
        }
    }

    /** The part of an exercise revision the learner sees and answers: no ids, no sequence, no time. */
    static JsonNode learnerRelevant(ExerciseBody body) {
        ObjectNode value = JsonNodeFactory.instance.objectNode().put("type", body.type()).put("enabled", body.enabled());
        value.set("content", body.content());
        value.set("answerKey", body.answerKey());
        value.set("evaluatorPolicy", body.evaluatorPolicy());
        return value;
    }

    private record Seed(UUID key, Change change, Decision row, UUID author, Rev authorRev, UUID baseOld) { }

    private List<Laned> lane(Lane lane, Result author) {
        UnitKind kind = lane.kind();
        Map<UUID, Change> changes = new LinkedHashMap<>();
        author.changes().forEach(change -> changes.put(change.key(), change));
        Set<UUID> keys = new LinkedHashSet<>(changes.keySet());
        for (Decision row : decisions) {
            if (row.kind() == kind && row.pending()) keys.add(row.key());
        }
        if (keys.isEmpty()) return List.of();
        Set<Pair> descriptors = new HashSet<>();
        for (Change change : changes.values()) {
            if (change.before() != null) descriptors.add(new Pair(change.key(), change.before().objectId()));
            if (change.after() != null) descriptors.add(new Pair(change.key(), change.after().objectId()));
        }
        Map<Pair, Rev> byDescriptor = lane.byDescriptor(descriptors);
        Map<UUID, Rev> heads = lane.heads(keys);

        // pass 1: the author's state and the old base of every key; a pending unit the author has not touched since carries the state that was offered
        List<Seed> seeds = new ArrayList<>();
        Set<Pair> offered = new HashSet<>();
        for (UUID key : keys) {
            Change change = changes.get(key);
            Decision row = rows.get(new Slot(kind, key));
            if (change != null) {
                Rev after = change.after() == null ? null : revision(byDescriptor, key, change.after().objectId());
                Rev before = change.before() == null ? null : revision(byDescriptor, key, change.before().objectId());
                seeds.add(new Seed(key, change, row, after == null ? null : after.revisionId(), after, before == null ? null : before.revisionId()));
            } else {
                UUID state = row.resolved();
                if (state != null) offered.add(new Pair(key, state));
                seeds.add(new Seed(key, null, row, state, null, row.pendingBase()));
            }
        }
        Map<Pair, Rev> offeredRevisions = offered.isEmpty() ? Map.of() : lane.byId(offered);

        // pass 2: the bases, what is hidden by a decision, what is taken down
        record Probe(Seed seed, UUID base, Rev author, Rev mine) { }
        List<Probe> probes = new ArrayList<>();
        Set<Rev> needFingerprint = new HashSet<>();
        for (Seed seed : seeds) {
            Decision row = seed.row();
            if (row != null && !row.pending() && Objects.equals(seed.author(), row.resolved())) continue;
            UUID effectiveBase = row == null ? seed.baseOld() : row.pending() ? row.pendingBase() : row.resolved();
            Rev authorRev = seed.authorRev() != null ? seed.authorRev() : seed.author() == null ? null : offeredRevisions.get(new Pair(seed.key(), seed.author()));
            if (seed.author() != null && authorRev == null) continue;
            // a taken-down revision of the author is never offered (the adoption would be refused as well)
            if (authorRev != null && authorRev.blocked()) continue;
            Rev mine = heads.get(seed.key());
            Probe probe = new Probe(seed, effectiveBase, authorRev, mine);
            probes.add(probe);
            UUID a = seed.author();
            UUID c = mine == null ? null : mine.revisionId();
            // past the bound of pairs of the whole plan, or once the time is up, the contents are assumed to differ (a conflict, the conservative answer)
            if (a != null && c != null && !a.equals(c) && !a.equals(effectiveBase) && !c.equals(effectiveBase) && budget.takePair()) {
                needFingerprint.add(authorRev);
                needFingerprint.add(mine);
            }
        }
        Map<UUID, String> fingerprints = needFingerprint.isEmpty() ? Map.of() : lane.fingerprints(needFingerprint);

        List<Laned> result = new ArrayList<>();
        for (Probe probe : probes) {
            UUID a = probe.seed().author();
            UUID c = probe.mine() == null ? null : probe.mine().revisionId();
            String fa = a == null ? null : fingerprints.get(a);
            boolean identical = fa != null && c != null && fa.equals(fingerprints.get(c));
            Raw raw = raw(probe.base(), a, c, identical);
            Decision row = probe.seed().row();
            PlanUnit unit = unit(kind, probe.seed().key(), raw, row != null && !row.pending(), a, c, probe.base());
            if (unit == null) continue;
            Change change = probe.seed().change();
            boolean removed = change != null && change.after() == null;
            int ordinal = change == null ? Integer.MAX_VALUE : removed ? change.beforeOrdinal() : change.afterOrdinal();
            result.add(new Laned(new Entry(unit, removed, ordinal), a));
        }
        return result;
    }

    private static Rev revision(Map<Pair, Rev> byDescriptor, UUID key, UUID descriptor) {
        Rev found = byDescriptor.get(new Pair(key, descriptor));
        if (found == null) throw new IllegalStateException("A manifest entry names no revision of the lineage");
        return found;
    }

    private static Raw raw(UUID base, UUID author, UUID mine, boolean identical) {
        if (Objects.equals(author, mine) || Objects.equals(author, base)) return Raw.NOTHING;
        if (author == null) return Raw.DELETED;
        if (mine == null) return base == null ? Raw.NEW : Raw.LEARNER_DELETED;
        if (mine.equals(base)) return Raw.SAFE;
        return identical ? Raw.IDENTICAL : Raw.CONFLICT;
    }

    /** The unit for a raw result; {@code resolvedRow}: a recorded decision turns every real difference into a new question. Null: nothing to show. */
    private static PlanUnit unit(UnitKind kind, UUID key, Raw raw, boolean resolvedRow, UUID author, UUID mine, UUID base) {
        if (raw == Raw.NOTHING) return null;
        UnitClass unitClass;
        Reason reason = null;
        if (raw == Raw.IDENTICAL) {
            unitClass = SAFE;
        } else if (resolvedRow) {
            unitClass = UnitClass.ASK_AGAIN;
            reason = Reason.CHANGED_AFTER_DECISION;
        } else {
            switch (raw) {
                case NEW -> unitClass = NEW;
                case SAFE -> unitClass = SAFE;
                case CONFLICT -> {
                    unitClass = CONFLICT;
                    reason = Reason.BOTH_CHANGED;
                }
                case LEARNER_DELETED -> {
                    unitClass = CONFLICT;
                    reason = Reason.DELETED_BY_YOU;
                }
                default -> unitClass = UnitClass.DELETED;
            }
        }
        return new PlanUnit(kind, key, unitClass, reason, author, mine, base, List.of(), PlanUnit.defaultFor(unitClass));
    }

    // ---- exercises: dependencies and the node check ----

    private List<Entry> finishExercises(List<Laned> exercises, Map<Pair, List<Binding>> bindings, Map<UUID, PlanUnit> materialUnits, Objectives objectives) {
        if (exercises.isEmpty()) return List.of();
        Set<UUID> bound = new HashSet<>();
        bindings.values().forEach(list -> list.forEach(binding -> bound.add(binding.memberKey())));
        Map<UUID, Rev> heads = bound.isEmpty() ? Map.of() : repository.itemHeads(deck, bound);
        List<Entry> result = new ArrayList<>();
        for (Laned laned : exercises) {
            PlanUnit unit = laned.entry().unit();
            if (laned.author() == null) {
                result.add(laned.entry());
                continue;
            }
            TreeSet<String> dependencies = new TreeSet<>();
            TreeSet<String> implied = new TreeSet<>();
            Reason issue = null;
            for (Binding binding : bindings.getOrDefault(new Pair(unit.key(), laned.author()), List.of())) {
                Rev head = heads.get(binding.memberKey());
                PlanUnit material = materialUnits.get(binding.memberKey());
                if (head == null) {
                    // a material the copy has not got: the author's new one comes with the exercise, anything else is one the learner deleted (or declined)
                    if (material != null && material.unitClass() == NEW) dependencies.add(material.unitId());
                    else issue = worse(issue, Reason.MATERIAL_DELETED);
                } else if (!head.revisionId().equals(binding.itemRevisionId()) && !hasNodes(head, binding.nodeIds())) {
                    // the learner keeps his own revision and the quoted nodes are not in it; the author's fix of the material, if safe, brings them
                    if (material != null && material.unitClass() == SAFE && binding.itemRevisionId().equals(material.authorRevisionId())) dependencies.add(material.unitId());
                    else issue = worse(issue, Reason.MATERIAL_CHANGED);
                }
            }
            for (Binding binding : bindings.getOrDefault(new Pair(unit.key(), laned.author()), List.of())) {
                if (!"ASSESSED".equals(binding.role()) || binding.objectiveId() == null) continue;
                PlanUnit objective = objectives.units().get(binding.objectiveId());
                if (objective != null) {
                    (objectives.implicit().contains(binding.objectiveId()) ? implied : dependencies).add(objective.unitId());
                    if (objective.unitClass() != SAFE) issue = worse(issue, Reason.OBJECTIVE_RENAMED);
                } else if (objectives.declined().contains(binding.objectiveId())) {
                    issue = worse(issue, Reason.OBJECTIVE_RENAMED);
                }
            }
            boolean offered = unit.unitClass() == NEW || unit.unitClass() == SAFE;
            PlanUnit finished = offered && issue != null ? unit.with(CONFLICT, issue, List.copyOf(dependencies), List.copyOf(implied))
                    : unit.with(unit.unitClass(), unit.reason(), List.copyOf(dependencies), List.copyOf(implied));
            result.add(new Entry(finished, laned.entry().removed(), laned.entry().ordinal()));
        }
        return result;
    }

    /** The worse of two reasons an exercise cannot be adopted: a deleted material, then a changed one, then a renamed objective. */
    private static Reason worse(Reason current, Reason found) {
        if (found == null) return current;
        if (current == null) return found;
        return severity(current) >= severity(found) ? current : found;
    }

    private static int severity(Reason reason) {
        return switch (reason) {
            case MATERIAL_DELETED -> 3;
            case MATERIAL_CHANGED -> 2;
            case OBJECTIVE_RENAMED -> 1;
            default -> 0;
        };
    }

    private boolean hasNodes(Rev head, List<UUID> nodes) {
        if (nodes.isEmpty()) return true;
        // with the time up an index that is not loaded yet is not loaded: the nodes are judged missing (the cautious answer)
        if (!nodeIndexes.containsKey(head.revisionId()) && budget.spent()) return false;
        NativeNodeIndex index = nodeIndexes.computeIfAbsent(head.revisionId(), ignored -> contents.nodes(scope, head.contentRootId()));
        return nodes.stream().allMatch(node -> index.text(node).isPresent());
    }

    // ---- objectives ----

    /**
     * The objective units (listed and implicit alike, for the dependencies of the exercises), the objectives a recorded decision keeps as they are, the listed units as entries,
     * the ids of the implicit ones and those units.
     */
    private record Objectives(Map<UUID, PlanUnit> units, Set<UUID> declined, List<Entry> entries, Set<UUID> implicit, List<PlanUnit> hidden) { }

    private Objectives objectives(List<Laned> exercises, Map<Pair, List<Binding>> bindings) {
        // objective -> the revisions the author's exercises pin, and the place of its first exercise
        Map<UUID, Set<UUID>> pinned = new LinkedHashMap<>();
        Map<UUID, Boolean> onlyNew = new HashMap<>();
        Map<UUID, Integer> place = new HashMap<>();
        for (Laned laned : exercises) {
            if (laned.author() == null) continue;
            for (Binding binding : bindings.getOrDefault(new Pair(laned.entry().unit().key(), laned.author()), List.of())) {
                if (!"ASSESSED".equals(binding.role()) || binding.objectiveId() == null) continue;
                pinned.computeIfAbsent(binding.objectiveId(), ignored -> new LinkedHashSet<>()).add(binding.objectiveRevisionId());
                place.merge(binding.objectiveId(), laned.entry().ordinal(), Math::min);
                onlyNew.merge(binding.objectiveId(), laned.entry().unit().unitClass() == NEW, Boolean::logicalAnd);
            }
        }
        if (pinned.isEmpty()) return new Objectives(Map.of(), Set.of(), List.of(), Set.of(), List.of());
        Map<UUID, UUID> heads = repository.objectiveHeads(deck, pinned.keySet());
        Set<Pair> wanted = new HashSet<>();
        pinned.forEach((objective, revisions) -> revisions.forEach(revision -> wanted.add(new Pair(objective, revision))));
        heads.forEach((objective, revision) -> wanted.add(new Pair(objective, revision)));
        Map<Pair, ObjectiveRev> revisions = repository.objectiveRevisions(scope, wanted);
        List<AncestorQuery> questions = new ArrayList<>();
        Map<UUID, UUID> target = new LinkedHashMap<>();
        for (Map.Entry<UUID, Set<UUID>> entry : pinned.entrySet()) {
            // the newest revision any of the author's exercises pins: the one the materializer would give the head
            UUID newest = entry.getValue().stream().max(Comparator.<UUID>comparingLong(revision -> revisions.get(new Pair(entry.getKey(), revision)).sequence())
                    .thenComparing(UUID::toString)).orElseThrow();
            target.put(entry.getKey(), newest);
            UUID head = heads.get(entry.getKey());
            if (head != null && !head.equals(newest)) questions.add(new AncestorQuery(entry.getKey(), newest, head));
        }
        Map<Pair, Boolean> ancestors = questions.isEmpty() ? Map.of() : repository.ancestors(scope, questions);
        Map<UUID, PlanUnit> units = new LinkedHashMap<>();
        Set<UUID> declined = new HashSet<>();
        List<Entry> entries = new ArrayList<>();
        Set<UUID> implicit = new HashSet<>();
        List<PlanUnit> hidden = new ArrayList<>();
        for (Map.Entry<UUID, UUID> entry : target.entrySet()) {
            UUID objective = entry.getKey();
            UUID author = entry.getValue();
            UUID head = heads.get(objective);
            if (author.equals(head)) continue;
            Decision row = rows.get(new Slot(UnitKind.OBJECTIVE, objective));
            boolean decided = row != null && !row.pending();
            if (decided && author.equals(row.resolved())) {
                declined.add(objective);
                continue;
            }
            boolean sameTitle = head != null && Objects.equals(revisions.get(new Pair(objective, author)).title(), revisions.get(new Pair(objective, head)).title());
            boolean headIsAncestor = head == null || Boolean.TRUE.equals(ancestors.get(new Pair(objective, author)));
            UnitClass unitClass;
            Reason reason = null;
            if (sameTitle) {
                unitClass = SAFE;
            } else if (decided) {
                unitClass = UnitClass.ASK_AGAIN;
                reason = Reason.CHANGED_AFTER_DECISION;
            } else if (headIsAncestor) {
                unitClass = SAFE;
            } else {
                unitClass = CONFLICT;
                reason = Reason.BOTH_CHANGED;
            }
            PlanUnit unit = new PlanUnit(UnitKind.OBJECTIVE, objective, unitClass, reason, author, head, null, List.of(), PlanUnit.defaultFor(unitClass));
            units.put(objective, unit);
            if (head == null && onlyNew.getOrDefault(objective, false)) {
                // the copy has no head for it and only the author's NEW exercises pin it: it comes with them, it is nothing to review
                implicit.add(objective);
                hidden.add(unit);
            } else {
                entries.add(new Entry(unit, false, place.getOrDefault(objective, Integer.MAX_VALUE)));
            }
        }
        return new Objectives(units, declined, entries, implicit, hidden);
    }

    // ---- the deck's own text and the order of materials ----

    private java.util.Optional<Entry> metadata() {
        Decision row = rows.get(new Slot(UnitKind.METADATA, deck));
        SourceRevision baseline = baseline(row);
        if (baseline == null) return java.util.Optional.empty();
        Raw raw = metadataRaw(baseline.title(), baseline.description());
        boolean resolved = row != null && !row.pending();
        if (resolved && same(published.title(), published.description(), baseline.title(), baseline.description())) return java.util.Optional.empty();
        PlanUnit unit = unit(UnitKind.METADATA, deck, raw, resolved, published.revisionId(), copy.headRevisionId(), baseline.revisionId());
        return java.util.Optional.ofNullable(unit).map(found -> new Entry(found, false, 0));
    }

    private Raw metadataRaw(String baseTitle, String baseDescription) {
        if (same(published.title(), published.description(), copy.title(), copy.description())
                || same(published.title(), published.description(), baseTitle, baseDescription)) return Raw.NOTHING;
        return same(copy.title(), copy.description(), baseTitle, baseDescription) ? Raw.SAFE : Raw.CONFLICT;
    }

    private static boolean same(String titleA, String descriptionA, String titleB, String descriptionB) {
        return Objects.equals(titleA, titleB) && Objects.equals(descriptionA, descriptionB);
    }

    /**
     * The source revision a deck-wide unit is compared with: the base, or the revision a decision names. A decision whose revision can no longer be read is not trusted: the
     * unit is judged against the base (a recorded decision then still asks again, never hides).
     */
    private SourceRevision baseline(Decision row) {
        if (row == null) return base;
        UUID revision = row.pending() ? row.pendingBaseRevisionId() : row.resolvedRevisionId();
        if (revision == null || revision.equals(base.revisionId())) return base;
        return repository.sourceRevision(source, revision).orElse(base);
    }

    private java.util.Optional<Entry> order(Result memberDiff) {
        Decision row = rows.get(new Slot(UnitKind.ORDER, deck));
        SourceRevision baseline = baseline(row);
        Result author = baseline == base ? memberDiff
                : diffs.members(scope, baseline.membersRootId(), baseline.memberCount(), published.membersRootId(), published.memberCount());
        if (!author.order().changed()) return java.util.Optional.empty();
        boolean resolved = row != null && !row.pending();
        UnitClass unitClass;
        Reason reason = null;
        if (resolved) {
            unitClass = UnitClass.ASK_AGAIN;
            reason = Reason.CHANGED_AFTER_DECISION;
        } else {
            Result mine = diffs.members(scope, baseline.membersRootId(), baseline.memberCount(), copy.membersRootId(), copy.memberCount());
            unitClass = mine.order().changed() ? CONFLICT : SAFE;
            if (unitClass == CONFLICT) reason = Reason.BOTH_CHANGED;
        }
        PlanUnit unit = new PlanUnit(UnitKind.ORDER, deck, unitClass, reason, published.revisionId(), copy.headRevisionId(), baseline.revisionId(), List.of(),
                PlanUnit.defaultFor(unitClass));
        return java.util.Optional.of(new Entry(unit, false, 0));
    }
}
