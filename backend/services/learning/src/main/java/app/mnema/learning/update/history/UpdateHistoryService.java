package app.mnema.learning.update.history;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.update.history.HistoryRepository.Copy;
import app.mnema.learning.update.history.HistoryRepository.Entry;
import app.mnema.learning.update.history.HistoryUnit.Change;
import app.mnema.learning.update.plan.UnitKind;
import app.mnema.learning.update.plan.UnitTitles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * «Что нового» (Updates/6 part B, #445; contract {@code contracts/decks/updates.json}, {@code history}): the applies of a copy, newest first, each with the units it changed, read
 * from the journals the combined publication wrote with the revision, and the owner's mark that he has seen an entry. Owner only (anything that is not the caller's live copy is the
 * opaque 404). A read writes nothing; marking seen is an explicit, idempotent command of its own.
 *
 * <p>Bounded: at most {@value #MAX_ENTRIES} entries per page and {@value #MAX_UNITS} units per entry (an entry says {@code unitsTruncated} and how many units the apply took, in
 * {@code counts}); the units shown are chosen in the order METADATA, MATERIAL, EXERCISE, OBJECTIVE, ORDER, then the journal's. Every unit of an entry can be returned whether it is
 * listed or not.
 */
@Service
public class UpdateHistoryService {
    static final int MAX_ENTRIES = 20;
    static final int DEFAULT_ENTRIES = 5;
    static final int MAX_UNITS = 100;
    /** The most material titles one call derives from documents (the rest come from the cache, or are empty until a later call). */
    static final int TITLE_DECODES = 40;

    private final HistoryRepository repository;
    private final UnitTitles titles;

    UpdateHistoryService(HistoryRepository repository, UnitTitles titles) {
        this.repository = repository;
        this.titles = titles;
    }

    /** @param limit the raw query value (null: absent); 1-{@value #MAX_ENTRIES} */
    @Transactional(timeout = 10)
    public ObjectNode history(UUID owner, UUID deck, String limit) {
        int size = limit(limit);
        UuidPolicy.requireEntityId(owner, "owner");
        UuidPolicy.requireEntityId(deck, "deckId");
        Copy copy = repository.copy(owner, deck).orElseThrow(ResourceNotFoundException::new);
        ObjectNode json = JsonNodeFactory.instance.objectNode().put("deckId", deck.toString());
        ArrayNode entries = json.putArray("entries");
        if (copy.preparing()) return json;
        List<Entry> page = repository.entries(deck, size);
        if (page.isEmpty()) return json;

        // everything the entries need is read for ALL of them at once: a fixed number of statements whatever the number of entries (and no statement per entry or per unit)
        List<UUID> revisions = page.stream().map(Entry::deckRevisionId).toList();
        Map<UUID, String> retitled = repository.retitled(deck, revisions);
        Map<UUID, List<HistoryRepository.ItemRow>> items = repository.items(deck, revisions, MAX_UNITS + 1);
        Map<UUID, List<HistoryRepository.ExerciseRow>> exercises = repository.exercises(deck, revisions, MAX_UNITS + 1);
        Map<UUID, List<HistoryRepository.ObjectiveRow>> objectives = repository.objectives(deck, revisions, MAX_UNITS + 1);
        Set<UUID> reordered = repository.reordered(deck, revisions);
        Set<UUID> itemKeys = new HashSet<>();
        Set<UUID> exerciseKeys = new HashSet<>();
        Set<UUID> objectiveKeys = new HashSet<>();
        items.values().forEach(rows -> rows.stream().limit(MAX_UNITS).forEach(row -> itemKeys.add(row.key())));
        exercises.values().forEach(rows -> rows.stream().limit(MAX_UNITS).forEach(row -> exerciseKeys.add(row.id())));
        objectives.values().forEach(rows -> rows.stream().limit(MAX_UNITS).forEach(row -> objectiveKeys.add(row.id())));
        Map<UUID, UUID> itemHeads = repository.itemHeads(deck, itemKeys);
        Map<UUID, UUID> exerciseHeads = repository.exerciseHeads(deck, exerciseKeys);
        Map<UUID, UUID> objectiveHeads = repository.objectiveHeads(deck, objectiveKeys);

        Map<UUID, List<HistoryUnit>> units = new HashMap<>();
        Set<UUID> truncated = new HashSet<>();
        Set<UnitTitles.Ref> refs = new LinkedHashSet<>();
        for (Entry entry : page) {
            UUID revision = entry.deckRevisionId();
            List<HistoryUnit> list = new ArrayList<>();
            List<HistoryRepository.ItemRow> itemRows = items.getOrDefault(revision, List.of());
            List<HistoryRepository.ExerciseRow> exerciseRows = exercises.getOrDefault(revision, List.of());
            List<HistoryRepository.ObjectiveRow> objectiveRows = objectives.getOrDefault(revision, List.of());
            if (itemRows.size() > MAX_UNITS || exerciseRows.size() > MAX_UNITS || objectiveRows.size() > MAX_UNITS) truncated.add(revision);
            if (retitled.containsKey(revision)) list.add(new HistoryUnit(UnitKind.METADATA, deck, Change.CHANGED, null, null, null));
            for (HistoryRepository.ItemRow row : itemRows.subList(0, Math.min(itemRows.size(), MAX_UNITS))) {
                list.add(HistoryUnit.of(UnitKind.MATERIAL, row.key(), row.previous(), row.revision(), itemHeads.get(row.key())));
            }
            for (HistoryRepository.ExerciseRow row : exerciseRows.subList(0, Math.min(exerciseRows.size(), MAX_UNITS))) {
                list.add(HistoryUnit.of(UnitKind.EXERCISE, row.id(), row.previous(), row.revision(), exerciseHeads.get(row.id())));
            }
            for (HistoryRepository.ObjectiveRow row : objectiveRows.subList(0, Math.min(objectiveRows.size(), MAX_UNITS))) {
                list.add(HistoryUnit.of(UnitKind.OBJECTIVE, row.id(), row.previous(), row.revision(), objectiveHeads.get(row.id())));
            }
            if (reordered.contains(revision)) list.add(new HistoryUnit(UnitKind.ORDER, deck, Change.REORDERED, null, null, null));
            if (list.size() > MAX_UNITS) {
                truncated.add(revision);
                list = new ArrayList<>(list.subList(0, MAX_UNITS));
            }
            list.sort(Comparator.comparing(HistoryUnit::kind));
            units.put(revision, list);
            for (HistoryUnit unit : list) {
                if (unit.kind() == UnitKind.MATERIAL || unit.kind() == UnitKind.EXERCISE || unit.kind() == UnitKind.OBJECTIVE) {
                    refs.add(new UnitTitles.Ref(unit.kind(), unit.key(), unit.revisionId() != null ? unit.revisionId() : unit.previousRevisionId()));
                }
            }
        }
        Map<String, String> shown = refs.isEmpty() ? Map.of() : titles.of(copy.scopeId(), refs, TITLE_DECODES);
        for (Entry entry : page) entries.add(entry(entry, copy, units.get(entry.deckRevisionId()), truncated.contains(entry.deckRevisionId()), retitled.get(entry.deckRevisionId()), shown));
        return json;
    }

    /** Marks the entry seen (the first call sets the time, later ones change nothing); the opaque 404 for an entry that does not exist. */
    @Transactional(timeout = 5)
    public ObjectNode seen(UUID owner, UUID deck, UUID revision) {
        UuidPolicy.requireEntityId(owner, "owner");
        UuidPolicy.requireEntityId(deck, "deckId");
        UuidPolicy.requireEntityId(revision, "deckRevisionId");
        repository.copy(owner, deck).orElseThrow(ResourceNotFoundException::new);
        if (!repository.markSeen(deck, revision)) throw new ResourceNotFoundException();
        return JsonNodeFactory.instance.objectNode().put("deckId", deck.toString()).put("deckRevisionId", revision.toString()).put("seen", true);
    }

    // ---- one entry ----

    private static ObjectNode entry(Entry entry, Copy copy, List<HistoryUnit> units, boolean truncated, String retitled, Map<String, String> shown) {
        ObjectNode json = JsonNodeFactory.instance.objectNode().put("deckRevisionId", entry.deckRevisionId().toString()).put("upstreamRevisionId", entry.upstreamRevisionId().toString())
                .put("mode", entry.mode()).put("appliedAt", entry.appliedAt().toString()).put("seen", entry.seen());
        json.putObject("counts").put("applied", entry.applied()).put("pending", entry.pending());
        ArrayNode list = json.putArray("units");
        for (HistoryUnit unit : units) {
            ObjectNode node = list.addObject().put("unitId", unit.unitId()).put("kind", unit.kind().name()).put("key", unit.key().toString()).put("change", unit.change().name());
            if (unit.kind() == UnitKind.METADATA) node.put("title", retitled);
            else if (unit.kind() == UnitKind.ORDER) node.putNull("title");
            else node.put("title", shown.getOrDefault(unit.unitId(), ""));
            put(node, "previousRevisionId", unit.previousRevisionId());
            put(node, "revisionId", unit.revisionId());
            if (unit.state() == null) node.putNull("state"); else node.put("state", unit.state().name());
            node.put("revertible", unit.revertible(copy.detached()));
        }
        json.put("unitsTruncated", truncated);
        return json;
    }

    private static void put(ObjectNode node, String name, UUID value) {
        if (value == null) node.putNull(name); else node.put(name, value.toString());
    }

    static int limit(String value) {
        if (value == null) return DEFAULT_ENTRIES;
        if (!value.matches("[1-9][0-9]{0,1}")) throw new InvalidRequestException();
        int size = Integer.parseInt(value);
        if (size > MAX_ENTRIES) throw new InvalidRequestException();
        return size;
    }
}
