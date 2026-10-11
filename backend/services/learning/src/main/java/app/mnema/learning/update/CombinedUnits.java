package app.mnema.learning.update;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The command of a combined publication (Updates/2, #441): many heads of a deck move, in ONE new revision of the deck, to revisions that already exist in its lineage
 * (the author's), together with removals, member order and the title and description. It creates no lineage revision and no content (community decks architecture §7).
 *
 * <p><b>The operations are sequential.</b> {@code items} is applied to the members manifest in list order, and every ordinal is valid in the manifest as the PREVIOUS operations
 * left it (like the changes of an ordinary item publication); the same holds for {@code exercises} and the exercises manifest, whose ordinals are the
 * {@code deck_head_exercise} ordinals {@code 0..n-1}. The engine never searches a key: each operation names the key AND the ordinal at which it expects it, and an ordinal that holds
 * another key means the caller's plan is stale ({@code VersionConflictException}). Items are applied first, then exercises, then objectives (an adopted exercise may bind a material
 * adopted in the same command).
 *
 * <p><b>Shape.</b> Per kind a key appears in at most one {@code Adopt*}/{@code Remove} operation and in at most one {@link ItemOp.Move}; a {@code Move} of a key that is also adopted
 * comes AFTER the adoption, and a removed key is not moved. (One journal row per member and revision, so a revision's own published position is exact.)
 *
 * @param commandId the idempotency key (UUIDv4 or v7): the same id with the same command is a replay, with another command a conflict
 * @param deckId the deck (a copy) the revision is made for
 * @param actorId the owner of the deck; anyone else sees the opaque 404
 * @param expectedHeadRevisionId the head revision the command was built against; any other head is {@code VersionConflictException}
 * @param metadata the new title and description; {@code null} leaves them unchanged
 * @param finalWrites what the {@link FinalWriter} writes in the final transaction; {@code null} for nothing
 */
public record CombinedUnits(UUID commandId, UUID deckId, UUID actorId, UUID expectedHeadRevisionId, Metadata metadata,
                            List<ItemOp> items, List<ExerciseOp> exercises, List<ObjectiveOp> objectives, FinalWrites finalWrites) {
    /** The largest ordinal any operation may name (the manifests hold at most 100 000 entries). */
    public static final int MAX_ORDINAL = 99_999;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public CombinedUnits(UUID commandId, UUID deckId, UUID actorId, UUID expectedHeadRevisionId, Metadata metadata,
                         List<ItemOp> items, List<ExerciseOp> exercises, List<ObjectiveOp> objectives) {
        this(commandId, deckId, actorId, expectedHeadRevisionId, metadata, items, exercises, objectives, null);
    }

    public CombinedUnits {
        try {
            UuidPolicy.requireCommandId(commandId);
            UuidPolicy.requireEntityId(deckId, "deckId");
            UuidPolicy.requireEntityId(actorId, "actorId");
            UuidPolicy.requireEntityId(expectedHeadRevisionId, "expectedHeadRevisionId");
            items = List.copyOf(items);
            exercises = List.copyOf(exercises);
            objectives = List.copyOf(objectives);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new InvalidRequestException();
        }
        validateItems(items);
        validateExercises(exercises);
        validateObjectives(objectives);
    }

    /** The new title and description, validated like the metadata command of a deck. */
    public record Metadata(String title, String description) {
        public Metadata {
            new DeckCommand(UUID.randomUUID(), title, description);
        }
    }

    /** One operation on the members manifest and the heads of the materials. */
    public sealed interface ItemOp {
        UUID memberKey();

        /** The member is in the head: its head and manifest entry move to {@code revisionId}, an existing revision of that member in the lineage. */
        record AdoptReplace(UUID memberKey, int ordinal, UUID revisionId) implements ItemOp { }

        /** The member is NOT in the head: it is inserted at {@code ordinal} with {@code revisionId} as its head. */
        record AdoptInsert(UUID memberKey, int ordinal, UUID revisionId) implements ItemOp { }

        /** The member leaves the deck (its manifest entry, head and «Эталон» mark); its revisions stay in the lineage. */
        record Remove(UUID memberKey, int ordinal) implements ItemOp { }

        /** The member moves from {@code fromOrdinal} to {@code toOrdinal}, its position in the manifest after the move. */
        record Move(UUID memberKey, int fromOrdinal, int toOrdinal) implements ItemOp { }
    }

    /** One operation on the exercises manifest and the heads of the exercises. */
    public sealed interface ExerciseOp {
        UUID exerciseId();

        record AdoptReplace(UUID exerciseId, int ordinal, UUID revisionId) implements ExerciseOp { }

        record AdoptInsert(UUID exerciseId, int ordinal, UUID revisionId) implements ExerciseOp { }

        record Remove(UUID exerciseId, int ordinal) implements ExerciseOp { }
    }

    /** The objective head moves to {@code revisionId} (inserted when the deck has no head for it yet). */
    public sealed interface ObjectiveOp {
        UUID objectiveId();

        record Adopt(UUID objectiveId, UUID revisionId) implements ObjectiveOp { }
    }

    /** Items + exercises + objectives + the metadata: the figure the synchronous limit and the maximum are measured by (a move is an item operation). */
    public int unitCount() {
        return items.size() + exercises.size() + objectives.size() + (metadata == null ? 0 : 1);
    }

    // ---- identity and storage ----

    /** The canonical command identity (receipt digest): everything but the id and the actor, which the receipt carries itself. */
    ObjectNode envelope() {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("deckId", deckId.toString()).put("expectedHeadRevisionId", expectedHeadRevisionId.toString());
        if (metadata == null) envelope.putNull("metadata");
        else envelope.putObject("metadata").put("title", metadata.title()).put("description", metadata.description());
        ArrayNode itemNodes = envelope.putArray("items");
        for (ItemOp op : items) itemNodes.add(unit(op));
        ArrayNode exerciseNodes = envelope.putArray("exercises");
        for (ExerciseOp op : exercises) exerciseNodes.add(unit(op));
        ArrayNode objectiveNodes = envelope.putArray("objectives");
        for (ObjectiveOp op : objectives) objectiveNodes.add(unit(op));
        if (finalWrites == null) envelope.putNull("finalWrites"); else envelope.set("finalWrites", finalWrites.toJson());
        return envelope;
    }

    private static ObjectNode unit(ItemOp op) {
        ObjectNode node = JSON.createObjectNode();
        switch (op) {
            case ItemOp.AdoptReplace replace -> node.put("op", "adoptReplace").put("key", replace.memberKey().toString())
                    .put("ordinal", replace.ordinal()).put("revisionId", replace.revisionId().toString());
            case ItemOp.AdoptInsert insert -> node.put("op", "adoptInsert").put("key", insert.memberKey().toString())
                    .put("ordinal", insert.ordinal()).put("revisionId", insert.revisionId().toString());
            case ItemOp.Remove remove -> node.put("op", "remove").put("key", remove.memberKey().toString()).put("ordinal", remove.ordinal());
            case ItemOp.Move move -> node.put("op", "move").put("key", move.memberKey().toString())
                    .put("from", move.fromOrdinal()).put("to", move.toOrdinal());
        }
        return node;
    }

    private static ObjectNode unit(ExerciseOp op) {
        ObjectNode node = JSON.createObjectNode();
        switch (op) {
            case ExerciseOp.AdoptReplace replace -> node.put("op", "adoptReplace").put("key", replace.exerciseId().toString())
                    .put("ordinal", replace.ordinal()).put("revisionId", replace.revisionId().toString());
            case ExerciseOp.AdoptInsert insert -> node.put("op", "adoptInsert").put("key", insert.exerciseId().toString())
                    .put("ordinal", insert.ordinal()).put("revisionId", insert.revisionId().toString());
            case ExerciseOp.Remove remove -> node.put("op", "remove").put("key", remove.exerciseId().toString()).put("ordinal", remove.ordinal());
        }
        return node;
    }

    private static ObjectNode unit(ObjectiveOp op) {
        return switch (op) {
            case ObjectiveOp.Adopt adopt -> JSON.createObjectNode().put("op", "adopt").put("key", adopt.objectiveId().toString())
                    .put("revisionId", adopt.revisionId().toString());
        };
    }

    /** The storage form of one operation: the row of {@code combined_publication_unit}. */
    record Row(int unit, String kind, UUID key, UUID revisionId, Integer ordinal, Integer toOrdinal) { }

    /** The operations as rows, numbered in the order items, exercises, objectives (the order the job applies them in). */
    List<Row> rows() {
        List<Row> rows = new ArrayList<>(items.size() + exercises.size() + objectives.size());
        for (ItemOp op : items) {
            int unit = rows.size();
            rows.add(switch (op) {
                case ItemOp.AdoptReplace replace -> new Row(unit, "ITEM_REPLACE", replace.memberKey(), replace.revisionId(), replace.ordinal(), null);
                case ItemOp.AdoptInsert insert -> new Row(unit, "ITEM_INSERT", insert.memberKey(), insert.revisionId(), insert.ordinal(), null);
                case ItemOp.Remove remove -> new Row(unit, "ITEM_REMOVE", remove.memberKey(), null, remove.ordinal(), null);
                case ItemOp.Move move -> new Row(unit, "ITEM_MOVE", move.memberKey(), null, move.fromOrdinal(), move.toOrdinal());
            });
        }
        for (ExerciseOp op : exercises) {
            int unit = rows.size();
            rows.add(switch (op) {
                case ExerciseOp.AdoptReplace replace -> new Row(unit, "EXERCISE_REPLACE", replace.exerciseId(), replace.revisionId(), replace.ordinal(), null);
                case ExerciseOp.AdoptInsert insert -> new Row(unit, "EXERCISE_INSERT", insert.exerciseId(), insert.revisionId(), insert.ordinal(), null);
                case ExerciseOp.Remove remove -> new Row(unit, "EXERCISE_REMOVE", remove.exerciseId(), null, remove.ordinal(), null);
            });
        }
        for (ObjectiveOp op : objectives) {
            int unit = rows.size();
            rows.add(switch (op) {
                case ObjectiveOp.Adopt adopt -> new Row(unit, "OBJECTIVE_ADOPT", adopt.objectiveId(), adopt.revisionId(), null, null);
            });
        }
        return rows;
    }

    /** The item operation a stored row stands for (the row's kind must be an {@code ITEM_*} kind). */
    static ItemOp itemOp(Row row) {
        return switch (row.kind()) {
            case "ITEM_REPLACE" -> new ItemOp.AdoptReplace(row.key(), row.ordinal(), row.revisionId());
            case "ITEM_INSERT" -> new ItemOp.AdoptInsert(row.key(), row.ordinal(), row.revisionId());
            case "ITEM_REMOVE" -> new ItemOp.Remove(row.key(), row.ordinal());
            case "ITEM_MOVE" -> new ItemOp.Move(row.key(), row.ordinal(), row.toOrdinal());
            default -> throw new IllegalStateException("Not an item unit");
        };
    }

    static ExerciseOp exerciseOp(Row row) {
        return switch (row.kind()) {
            case "EXERCISE_REPLACE" -> new ExerciseOp.AdoptReplace(row.key(), row.ordinal(), row.revisionId());
            case "EXERCISE_INSERT" -> new ExerciseOp.AdoptInsert(row.key(), row.ordinal(), row.revisionId());
            case "EXERCISE_REMOVE" -> new ExerciseOp.Remove(row.key(), row.ordinal());
            default -> throw new IllegalStateException("Not an exercise unit");
        };
    }

    static ObjectiveOp objectiveOp(Row row) {
        if (!"OBJECTIVE_ADOPT".equals(row.kind())) throw new IllegalStateException("Not an objective unit");
        return new ObjectiveOp.Adopt(row.key(), row.revisionId());
    }

    /** Rebuilds the command a job stored: the header columns and the rows in unit order. */
    static CombinedUnits restore(UUID commandId, UUID deckId, UUID actorId, UUID expectedHeadRevisionId, JsonNode metadata, JsonNode finalWrites, List<Row> rows) {
        List<ItemOp> items = new ArrayList<>();
        List<ExerciseOp> exercises = new ArrayList<>();
        List<ObjectiveOp> objectives = new ArrayList<>();
        for (Row row : rows) {
            if (row.kind().startsWith("ITEM_")) items.add(itemOp(row));
            else if (row.kind().startsWith("EXERCISE_")) exercises.add(exerciseOp(row));
            else objectives.add(objectiveOp(row));
        }
        Metadata meta = metadata == null || metadata.isNull() ? null
                : new Metadata(metadata.path("title").stringValue(null), metadata.path("description").stringValue(null));
        return new CombinedUnits(commandId, deckId, actorId, expectedHeadRevisionId, meta, items, exercises, objectives, FinalWrites.fromJson(finalWrites));
    }

    ObjectNode metadataJson() {
        return metadata == null ? null : JSON.createObjectNode().put("title", metadata.title()).put("description", metadata.description());
    }

    // ---- validation ----

    private static void validateItems(List<ItemOp> items) {
        Map<UUID, ItemOp> primary = new HashMap<>();
        Set<UUID> moved = new HashSet<>();
        for (ItemOp op : items) {
            Ids.requireKey(op.memberKey());
            switch (op) {
                case ItemOp.AdoptReplace replace -> {
                    ordinal(replace.ordinal());
                    Ids.requireKey(replace.revisionId());
                    primary(primary, moved, op);
                }
                case ItemOp.AdoptInsert insert -> {
                    ordinal(insert.ordinal());
                    Ids.requireKey(insert.revisionId());
                    primary(primary, moved, op);
                }
                case ItemOp.Remove remove -> {
                    ordinal(remove.ordinal());
                    primary(primary, moved, op);
                }
                case ItemOp.Move move -> {
                    ordinal(move.fromOrdinal());
                    ordinal(move.toOrdinal());
                    if (!moved.add(move.memberKey()) || primary.get(move.memberKey()) instanceof ItemOp.Remove) throw new InvalidRequestException();
                }
            }
        }
    }

    private static void primary(Map<UUID, ItemOp> primary, Set<UUID> moved, ItemOp op) {
        // one adoption or removal per key, and a move comes after it, never before
        if (primary.putIfAbsent(op.memberKey(), op) != null || moved.contains(op.memberKey())) throw new InvalidRequestException();
    }

    private static void validateExercises(List<ExerciseOp> exercises) {
        Set<UUID> seen = new HashSet<>();
        for (ExerciseOp op : exercises) {
            Ids.requireKey(op.exerciseId());
            if (!seen.add(op.exerciseId())) throw new InvalidRequestException();
            switch (op) {
                case ExerciseOp.AdoptReplace replace -> { ordinal(replace.ordinal()); Ids.requireKey(replace.revisionId()); }
                case ExerciseOp.AdoptInsert insert -> { ordinal(insert.ordinal()); Ids.requireKey(insert.revisionId()); }
                case ExerciseOp.Remove remove -> ordinal(remove.ordinal());
            }
        }
    }

    private static void validateObjectives(List<ObjectiveOp> objectives) {
        Set<UUID> seen = new HashSet<>();
        for (ObjectiveOp op : objectives) {
            Ids.requireKey(op.objectiveId());
            if (!seen.add(op.objectiveId())) throw new InvalidRequestException();
            switch (op) {
                case ObjectiveOp.Adopt adopt -> Ids.requireKey(adopt.revisionId());
            }
        }
    }

    private static void ordinal(int value) {
        if (value < 0 || value > MAX_ORDINAL) throw new InvalidRequestException();
    }

    /** A non-nil RFC 9562 identifier, or the opaque invalid request. */
    private static final class Ids {
        static void requireKey(UUID value) {
            try { UuidPolicy.requireEntityId(value, "id"); }
            catch (IllegalArgumentException | NullPointerException invalid) { throw new InvalidRequestException(); }
        }
    }
}
