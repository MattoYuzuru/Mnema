package app.mnema.learning.update.history;

import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import app.mnema.learning.fork.DeckPreparingException;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.update.CombinedPublication;
import app.mnema.learning.update.CombinedUnits;
import app.mnema.learning.update.CombinedUnits.ExerciseOp;
import app.mnema.learning.update.CombinedUnits.ItemOp;
import app.mnema.learning.update.CombinedUnits.ObjectiveOp;
import app.mnema.learning.update.apply.ForkDetachedException;
import app.mnema.learning.update.history.HistoryRepository.Copy;
import app.mnema.learning.update.history.HistoryRepository.ExerciseRow;
import app.mnema.learning.update.history.HistoryRepository.ItemRow;
import app.mnema.learning.update.history.RevertRepository.ExerciseHead;
import app.mnema.learning.update.history.RevertRepository.Head;
import app.mnema.learning.update.history.RevertRepository.Link;
import app.mnema.learning.update.plan.UnitKind;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Returns one unit of an apply to what it was before (Updates/6 part B, #445; contract {@code contracts/decks/updates.json}, {@code revert}): a material or an exercise that an update
 * changed, added or removed goes back to the revision the update replaced, or is removed again, or is brought back, through the combined publication (ONE new revision of the copy, made
 * of revisions that already exist in its lineage), together with the decision that keeps the next update from putting it straight back: {@code KEEP_MINE} for a change undone or a
 * removal undone, {@code DECLINE_NEW} for an addition removed, each remembering the author's state it covers (written by {@link RevertFinalWriter} in the same transaction).
 *
 * <p><b>Refused, not guessed.</b> The unit must still be exactly what the apply made it ({@code 409 UPDATE_REVERT_STALE} when the owner edited, deleted or returned it since, or the
 * copy moved while the command was built); a detached copy has no updates to take back. <b>Dependencies.</b> A material and the exercises that were taken with it (an exercise of the
 * same apply bound to a material of the same apply) are returned only together: returning the material alone would leave an exercise quoting a revision that is gone, returning the
 * exercise alone would leave it quoting nodes the older material may not have. The client names every unit of such a group ({@code with}); a request that leaves some out is
 * {@code 400 unit_needed} listing them, so the owner decides with the whole picture and the server never returns something he did not ask for. A learner's own exercise that is bound to
 * an added material blocks its removal ({@code material_still_used}); the objective heads follow the exercises on their own (the head the combined publication verifies is computed and
 * adopted, never asked of the client).
 *
 * <p><b>Idempotent by the command id</b>: the answer is stored with the revision and replayed byte for byte.
 */
@Service
public class RevertService {
    private static final Logger LOG = LoggerFactory.getLogger(RevertService.class);
    private static final int READ_PAGE = 100;
    /** The most materials whose places are looked up by a scan of the manifest. */
    private static final int SCAN_LIMIT = 20_000;
    private static final int CLOSURE_ROUNDS = 10;
    /** How many members before a removed one are looked at to find the one it comes back behind. */
    private static final int NEIGHBOURS = 50;

    /** The answer of a return command. */
    public record Result(ObjectNode answer, boolean replayed) { }

    private final HistoryRepository history;
    private final RevertRepository repository;
    private final ManifestPages pages;
    private final CombinedPublication publication;
    private final CommandReceiptService receipts;
    private final MeterRegistry meters;

    RevertService(HistoryRepository history, RevertRepository repository, ManifestPages pages, CombinedPublication publication, CommandReceiptService receipts, MeterRegistry meters) {
        this.history = history;
        this.repository = repository;
        this.pages = pages;
        this.publication = publication;
        this.receipts = receipts;
        this.meters = meters;
    }

    /** A named unit with what the journal says of it and where it stands. */
    private record Target(HistoryUnit unit, int ordinal) { }

    /**
     * @throws ResourceNotFoundException when the deck is not the caller's live copy, or the apply or the unit is not in its log
     * @throws DeckPreparingException when the copy is not materialized yet
     * @throws ForkDetachedException when the copy was detached
     * @throws UpdateRevertStaleException when a named unit is no longer exactly what the apply made it
     * @throws InvalidRequestException with {@code reason} {@code unit_needed} or {@code material_still_used} (naming units), {@code duplicate_unit}, {@code publication_refused}
     * @throws IdempotencyConflictException when the command id was used for another command
     */
    @Transactional(propagation = Propagation.NEVER)
    public Result revert(UUID owner, UUID deckId, UUID deckRevisionId, RevertCommand.Unit primary, RevertCommand command) {
        long started = System.nanoTime();
        Copy copy = history.copy(owner, deckId).orElseThrow(ResourceNotFoundException::new);
        if (copy.preparing()) throw new DeckPreparingException();
        ObjectNode request = command.envelope(deckId, deckRevisionId, primary);
        CommandIdentity identity = RevertFinalWriter.identity(owner, command.commandId());
        Optional<JsonNode> done = receipts.replay(identity, request);
        if (done.isPresent()) {
            meters.counter("mnema_deck_update_revert_total", "outcome", "replayed").increment();
            return new Result((ObjectNode) done.get().deepCopy(), true);
        }
        if (copy.detached()) throw new ForkDetachedException();
        history.entry(deckId, deckRevisionId).orElseThrow(ResourceNotFoundException::new);
        Set<RevertCommand.Unit> named = new LinkedHashSet<>();
        named.add(primary);
        for (RevertCommand.Unit unit : command.with()) {
            if (!named.add(unit)) throw InvalidRequestException.because("duplicate_unit");
        }

        // the head first: everything below is built against it, and the engine's compare-and-set on it is what makes the rest consistent
        Head head = repository.head(deckId).orElseThrow(ResourceNotFoundException::new);
        Map<RevertCommand.Unit, Target> targets = load(deckId, deckRevisionId, named);
        for (RevertCommand.Unit unit : named) {
            Target target = targets.get(unit);
            if (target == null) throw new ResourceNotFoundException();
            if (target.unit().state() != HistoryUnit.State.CURRENT) {
                meters.counter("mnema_deck_update_revert_total", "outcome", "stale").increment();
                throw new UpdateRevertStaleException(unit.unitId());
            }
        }
        requireGroups(deckId, copy, deckRevisionId, primary, targets);

        CombinedUnits units = build(owner, deckId, copy, head, deckRevisionId, command, request, targets);
        try {
            publication.publish(units);
        } catch (InvalidRequestException refused) {
            if (!refused.extension().members().isEmpty()) throw refused;
            meters.counter("mnema_deck_update_revert_total", "outcome", "refused").increment();
            throw InvalidRequestException.because("publication_refused");
        } catch (VersionConflictException moved) {
            // the copy moved while the command was built, or a place did not describe the manifest: what the owner saw is not what is there
            meters.counter("mnema_deck_update_revert_total", "outcome", "stale").increment();
            throw new UpdateRevertStaleException();
        }
        JsonNode answer = receipts.replay(identity, request).orElseThrow(IdempotencyConflictException::new);
        meters.counter("mnema_deck_update_revert_total", "outcome", "reverted").increment();
        LOG.info("deck_update_revert deck_id={} deck_revision_id={} command_id={} units={} micros={}", deckId, deckRevisionId, command.commandId(), targets.size(),
                (System.nanoTime() - started) / 1_000);
        return new Result((ObjectNode) answer.deepCopy(), false);
    }

    // ---- what the units are and where they stand ----

    /** The journal row of each unit that the apply has, with the head it stands on now. A unit the journal does not have is absent from the answer. */
    private Map<RevertCommand.Unit, Target> load(UUID deck, UUID revision, Set<RevertCommand.Unit> wanted) {
        Map<RevertCommand.Unit, Target> result = new LinkedHashMap<>();
        List<UUID> members = wanted.stream().filter(unit -> unit.kind() == UnitKind.MATERIAL).map(RevertCommand.Unit::key).toList();
        List<UUID> exercises = wanted.stream().filter(unit -> unit.kind() == UnitKind.EXERCISE).map(RevertCommand.Unit::key).toList();
        if (!members.isEmpty()) {
            Map<UUID, UUID> heads = history.itemHeads(deck, members);
            for (ItemRow row : history.items(deck, revision, members)) {
                result.put(new RevertCommand.Unit(UnitKind.MATERIAL, row.key()),
                        new Target(HistoryUnit.of(UnitKind.MATERIAL, row.key(), row.previous(), row.revision(), heads.get(row.key())), row.ordinal()));
            }
        }
        if (!exercises.isEmpty()) {
            Map<UUID, ExerciseHead> heads = repository.exerciseHeads(deck, exercises);
            for (ExerciseRow row : history.exercises(deck, revision, exercises)) {
                ExerciseHead head = heads.get(row.id());
                result.put(new RevertCommand.Unit(UnitKind.EXERCISE, row.id()),
                        new Target(HistoryUnit.of(UnitKind.EXERCISE, row.id(), row.previous(), row.revision(), head == null ? null : head.revisionId()), row.ordinal()));
            }
        }
        return result;
    }

    /**
     * The units of one apply that its bindings tie together are returned together. The group of the named units is found to a fixpoint (a few rounds); the units of it that can
     * still be returned and that the command does not name are listed in the refusal. A unit of the group that cannot be returned any more (the owner changed it) is not required.
     */
    private void requireGroups(UUID deck, Copy copy, UUID revision, RevertCommand.Unit primary, Map<RevertCommand.Unit, Target> named) {
        Set<RevertCommand.Unit> group = new LinkedHashSet<>(named.keySet());
        Set<RevertCommand.Unit> declined = new HashSet<>();
        for (int round = 0; round < CLOSURE_ROUNDS; round++) {
            Set<UUID> exercises = ids(group, UnitKind.EXERCISE);
            Set<UUID> members = ids(group, UnitKind.MATERIAL);
            Set<RevertCommand.Unit> wanted = new LinkedHashSet<>();
            for (Link link : repository.links(deck, copy.scopeId(), revision, exercises, members)) {
                if (exercises.contains(link.exerciseId()) && !members.contains(link.memberKey())) wanted.add(new RevertCommand.Unit(UnitKind.MATERIAL, link.memberKey()));
                else if (members.contains(link.memberKey()) && !exercises.contains(link.exerciseId())) wanted.add(new RevertCommand.Unit(UnitKind.EXERCISE, link.exerciseId()));
            }
            wanted.removeAll(declined);
            if (wanted.isEmpty()) break;
            Map<RevertCommand.Unit, Target> loaded = load(deck, revision, wanted);
            boolean grew = false;
            for (RevertCommand.Unit unit : wanted) {
                Target target = loaded.get(unit);
                if (target != null && target.unit().state() == HistoryUnit.State.CURRENT) {
                    group.add(unit);
                    grew = true;
                } else {
                    declined.add(unit);
                }
            }
            if (!grew) break;
        }
        List<String> missing = group.stream().filter(unit -> !named.containsKey(unit)).map(RevertCommand.Unit::unitId).sorted().limit(RevertCommand.MAX_WITH).toList();
        if (!missing.isEmpty()) {
            meters.counter("mnema_deck_update_revert_total", "outcome", "unit_needed").increment();
            throw new InvalidRequestException(ProblemExtension.builder().put("reason", "unit_needed").put("unitId", primary.unitId()).put("units", missing).build());
        }
        // a material the apply added is removed: no exercise of the copy may still be about it (the engine refuses, naming nobody), except those removed with it
        Set<UUID> added = new HashSet<>();
        Set<UUID> removedWith = new HashSet<>();
        for (Map.Entry<RevertCommand.Unit, Target> entry : named.entrySet()) {
            if (entry.getValue().unit().change() == HistoryUnit.Change.ADDED) {
                (entry.getKey().kind() == UnitKind.MATERIAL ? added : removedWith).add(entry.getKey().key());
            }
        }
        if (!added.isEmpty()) {
            for (UUID exercise : repository.headExercisesBoundTo(deck, copy.scopeId(), added)) {
                if (removedWith.contains(exercise)) continue;
                meters.counter("mnema_deck_update_revert_total", "outcome", "material_still_used").increment();
                throw new InvalidRequestException(ProblemExtension.builder().put("reason", "material_still_used").put("unitId", primary.unitId())
                        .put("dependency", UnitKind.EXERCISE.unitId(exercise)).build());
            }
        }
    }

    private static Set<UUID> ids(Set<RevertCommand.Unit> units, UnitKind kind) {
        Set<UUID> result = new HashSet<>();
        for (RevertCommand.Unit unit : units) if (unit.kind() == kind) result.add(unit.key());
        return result;
    }

    // ---- the command ----

    private CombinedUnits build(UUID owner, UUID deckId, Copy copy, Head head, UUID revision, RevertCommand command, ObjectNode request,
                                Map<RevertCommand.Unit, Target> targets) {
        List<ItemOp> items = items(deckId, copy, head, revision, targets);
        List<ExerciseOp> exercises = exercises(deckId, head, targets);
        List<ObjectiveOp> objectives = objectives(deckId, copy, targets);
        List<RevertWrites.Row> rows = new ArrayList<>();
        List<String> unitIds = new ArrayList<>();
        for (Map.Entry<RevertCommand.Unit, Target> entry : targets.entrySet()) {
            HistoryUnit unit = entry.getValue().unit();
            unitIds.add(entry.getKey().unitId());
            // what the plan must not offer again: the author's state the apply took (a revision, or "removed")
            rows.add(switch (unit.change()) {
                case ADDED -> new RevertWrites.Row(unit.kind(), unit.key(), "DECLINE_NEW", unit.revisionId().toString());
                case CHANGED -> new RevertWrites.Row(unit.kind(), unit.key(), "KEEP_MINE", unit.revisionId().toString());
                case REMOVED -> new RevertWrites.Row(unit.kind(), unit.key(), "KEEP_MINE", "-");
                case REORDERED -> throw new IllegalStateException("A move is not a unit that can be returned");
            });
        }
        RevertWrites writes = new RevertWrites(command.commandId(), request, unitIds, rows);
        return new CombinedUnits(command.commandId(), deckId, owner, head.revisionId(), null, items, exercises, objectives, writes.toFinalWrites());
    }

    /**
     * Replacements first (they move nothing), then removals from the end (the last first leaves the others' places untouched), then insertions from the front. Places of present
     * materials come from ONE scan of the head manifest (100 per read).
     *
     * <p><b>Where a removed material comes back.</b> The journal says the place it had in the manifest the apply was made from (H), which is not a place in the manifest now when the same
     * apply also removed or inserted members before it. So the place is found by its neighbour: the nearest member before it in H (read from H by position, one page of at most
     * {@value #NEIGHBOURS} entries) that the copy still has, and the material comes back right behind that member as the manifest is by then (after this command's own removals,
     * and after the insertions of this command that sit before it, in the order the members had in H). With no such neighbour in the window: the very front when the window
     * reached the start of H, else (more than {@value #NEIGHBOURS} members before it were all removed or are gone) the journal's place kept inside the manifest, an approximation. Exercises keep
     * that approximation always: the apply renumbers them, and their order is the order of their objectives' schedule, not a reading order.
     */
    private List<ItemOp> items(UUID deckId, Copy copy, Head head, UUID revision, Map<RevertCommand.Unit, Target> targets) {
        List<Target> materials = targets.entrySet().stream().filter(entry -> entry.getKey().kind() == UnitKind.MATERIAL).map(Map.Entry::getValue).toList();
        if (materials.isEmpty()) return List.of();
        List<Target> removed = materials.stream().filter(target -> target.unit().change() == HistoryUnit.Change.REMOVED).sorted(Comparator.comparingInt(Target::ordinal)).toList();
        // the neighbours of the members that come back, nearest first, from the manifest the apply was made from
        Map<UUID, List<UUID>> neighbours = new HashMap<>();
        Set<UUID> candidates = new HashSet<>();
        Head origin = removed.isEmpty() ? null : repository.parent(deckId, revision).orElseThrow(VersionConflictException::new);
        for (Target target : removed) {
            int from = Math.max(0, target.ordinal() - NEIGHBOURS);
            int count = Math.min(target.ordinal(), origin.memberCount()) - from;
            List<UUID> before = new ArrayList<>();
            if (count > 0) pages.members(copy.scopeId(), origin.membersRootId(), origin.memberCount(), from, count).forEach(entry -> before.add(entry.key()));
            java.util.Collections.reverse(before);
            neighbours.put(target.unit().key(), before);
            candidates.addAll(before);
        }
        Set<UUID> alive = candidates.isEmpty() ? Set.of() : history.itemHeads(deckId, candidates).keySet();
        Set<UUID> leaving = new HashSet<>();
        for (Target target : materials) if (target.unit().change() == HistoryUnit.Change.ADDED) leaving.add(target.unit().key());
        Map<UUID, UUID> anchor = new HashMap<>();
        for (Target target : removed) {
            for (UUID key : neighbours.get(target.unit().key())) {
                // a member this command removes too is no place to stand behind
                if (alive.contains(key) && !leaving.contains(key)) {
                    anchor.put(target.unit().key(), key);
                    break;
                }
            }
        }
        Set<UUID> wanted = new HashSet<>(anchor.values());
        for (Target target : materials) if (target.unit().change() != HistoryUnit.Change.REMOVED) wanted.add(target.unit().key());
        Map<UUID, Integer> places = places(copy.scopeId(), head, wanted);

        List<ItemOp> ops = new ArrayList<>();
        materials.stream().filter(target -> target.unit().change() == HistoryUnit.Change.CHANGED)
                .sorted(Comparator.comparingInt((Target target) -> place(places, target))).forEach(target ->
                        ops.add(new ItemOp.AdoptReplace(target.unit().key(), place(places, target), target.unit().previousRevisionId())));
        List<Target> removals = materials.stream().filter(target -> target.unit().change() == HistoryUnit.Change.ADDED)
                .sorted(Comparator.comparingInt((Target target) -> place(places, target)).reversed()).toList();
        removals.forEach(target -> ops.add(new ItemOp.Remove(target.unit().key(), place(places, target))));
        List<Integer> removedPlaces = removals.stream().map(target -> place(places, target)).sorted().toList();
        int size = head.memberCount() - removals.size();
        // where each comes back, in the manifest after the removals above and before any insertion of this command
        record Slot(Target target, int slot) { }
        List<Slot> slots = new ArrayList<>();
        for (Target target : removed) {
            UUID neighbour = anchor.get(target.unit().key());
            int slot;
            if (neighbour != null) {
                Integer at = places.get(neighbour);
                if (at == null) throw new VersionConflictException();
                int shifted = at - (int) removedPlaces.stream().filter(place -> place < at).count();
                slot = shifted + 1;
            } else if (neighbours.get(target.unit().key()).size() == target.ordinal()) {
                slot = 0;
            } else {
                slot = target.ordinal();
            }
            slots.add(new Slot(target, Math.min(slot, size)));
        }
        slots.sort(Comparator.comparingInt(Slot::slot).thenComparingInt(found -> found.target().ordinal()));
        // each insertion before it moves the later ones by one; the ones that share a place keep the order they had
        for (int index = 0; index < slots.size(); index++) {
            Slot found = slots.get(index);
            ops.add(new ItemOp.AdoptInsert(found.target().unit().key(), found.slot() + index, found.target().unit().previousRevisionId()));
        }
        return ops;
    }

    private static int place(Map<UUID, Integer> places, Target target) {
        Integer place = places.get(target.unit().key());
        // the head says the unit is as the apply made it, so the manifest must hold it: otherwise the copy moved under us
        if (place == null) throw new VersionConflictException();
        return place;
    }

    /** The places of these materials in the head manifest: a scan of at most {@value #SCAN_LIMIT} entries, 100 per read, stopping when all are found. */
    private Map<UUID, Integer> places(UUID scope, Head head, Set<UUID> wanted) {
        Map<UUID, Integer> found = new HashMap<>();
        if (wanted.isEmpty()) return found;
        if (head.memberCount() > SCAN_LIMIT) throw new ResourceLimitExceededException(ProblemExtension.limit(SCAN_LIMIT));
        for (int at = 0; at < head.memberCount() && found.size() < wanted.size(); at += READ_PAGE) {
            int index = at;
            for (Entry entry : pages.members(scope, head.membersRootId(), head.memberCount(), at, READ_PAGE)) {
                if (wanted.contains(entry.key())) found.put(entry.key(), index);
                index++;
            }
        }
        return found;
    }

    private List<ExerciseOp> exercises(UUID deckId, Head head, Map<RevertCommand.Unit, Target> targets) {
        List<Target> list = targets.entrySet().stream().filter(entry -> entry.getKey().kind() == UnitKind.EXERCISE).map(Map.Entry::getValue).toList();
        if (list.isEmpty()) return List.of();
        Map<UUID, ExerciseHead> heads = repository.exerciseHeads(deckId, list.stream().map(target -> target.unit().key()).toList());
        List<ExerciseOp> ops = new ArrayList<>();
        list.stream().filter(target -> target.unit().change() == HistoryUnit.Change.CHANGED).sorted(Comparator.comparingInt(target -> ordinal(heads, target)))
                .forEach(target -> ops.add(new ExerciseOp.AdoptReplace(target.unit().key(), ordinal(heads, target), target.unit().previousRevisionId())));
        List<Target> removals = list.stream().filter(target -> target.unit().change() == HistoryUnit.Change.ADDED)
                .sorted(Comparator.comparingInt((Target target) -> ordinal(heads, target)).reversed()).toList();
        removals.forEach(target -> ops.add(new ExerciseOp.Remove(target.unit().key(), ordinal(heads, target))));
        int size = head.exerciseCount() - removals.size();
        for (Target target : list.stream().filter(target -> target.unit().change() == HistoryUnit.Change.REMOVED).sorted(Comparator.comparingInt(Target::ordinal)).toList()) {
            ops.add(new ExerciseOp.AdoptInsert(target.unit().key(), Math.min(target.ordinal(), size), target.unit().previousRevisionId()));
            size++;
        }
        return ops;
    }

    private static int ordinal(Map<UUID, ExerciseHead> heads, Target target) {
        ExerciseHead head = heads.get(target.unit().key());
        if (head == null) throw new VersionConflictException();
        return head.ordinal();
    }

    /**
     * The objective heads of the exercises that come back: the combined publication demands that an objective an adopted exercise assesses has the head the materializer would write
     * for the resulting exercises (the newest revision their ASSESSED bindings pin). It is computed here for the exercises as they will be, and adopted where the head differs.
     */
    private List<ObjectiveOp> objectives(UUID deckId, Copy copy, Map<RevertCommand.Unit, Target> targets) {
        Map<UUID, UUID> adopted = new LinkedHashMap<>();
        Set<UUID> left = new LinkedHashSet<>();
        for (Map.Entry<RevertCommand.Unit, Target> entry : targets.entrySet()) {
            if (entry.getKey().kind() != UnitKind.EXERCISE) continue;
            HistoryUnit unit = entry.getValue().unit();
            left.add(unit.key());
            if (unit.change() != HistoryUnit.Change.ADDED) adopted.put(unit.key(), unit.previousRevisionId());
        }
        if (adopted.isEmpty()) return List.of();
        Set<UUID> touched = repository.assessedObjectives(copy.scopeId(), adopted);
        Map<UUID, UUID> expected = repository.expectedObjectiveHeads(deckId, copy.scopeId(), touched, left, adopted);
        Map<UUID, UUID> heads = history.objectiveHeads(deckId, touched);
        List<ObjectiveOp> ops = new ArrayList<>();
        for (UUID objective : touched) {
            UUID wanted = expected.get(objective);
            if (wanted != null && !wanted.equals(heads.get(objective))) ops.add(new ObjectiveOp.Adopt(objective, wanted));
        }
        return ops;
    }
}
