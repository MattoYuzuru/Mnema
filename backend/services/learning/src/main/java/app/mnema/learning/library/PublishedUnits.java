package app.mnema.learning.library;

import app.mnema.learning.catalog.content.ItemPreviews;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import app.mnema.learning.moderation.ContentTakedowns;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The units of the PUBLISHED revision of a deck, for the owner console's takedown picker (Share/15, #437): the same immutable manifests the public readers
 * walk, so the operator names exactly the revisions readers get. It does not decide access (the console owner is the caller); it shows titles and prompts
 * because it is the operator's tool. Storage reads run in the transactions of the storage layer, never inside one held across the whole page.
 */
@Service
public class PublishedUnits {
    public static final String ITEM = "ITEM";
    public static final String EXERCISE = "EXERCISE";

    /** One unit: {@code label} is the material title or the exercise prompt summary, {@code type} the exercise mechanic (null for a material). */
    public record Unit(String kind, UUID key, UUID revisionId, int ordinal, String label, String type, boolean takenDown) { }

    public record Page(String kind, int total, List<Unit> units, String nextCursor) { }

    private record Header(UUID scope, UUID revisionId, UUID membersRoot, UUID exercisesRoot, int memberCount, int exerciseCount) { }

    private final JdbcClient jdbc;
    private final ManifestPages manifests;
    private final PublishedContentRepository content;
    private final ItemPreviews previews;
    private final ContentTakedowns takedowns;

    PublishedUnits(JdbcClient jdbc, ManifestPages manifests, PublishedContentRepository content, ItemPreviews previews, ContentTakedowns takedowns) {
        this.jdbc = jdbc;
        this.manifests = manifests;
        this.content = content;
        this.previews = previews;
        this.takedowns = takedowns;
    }

    /** Whether the deck has a published revision at all (the picker of an unpublished deck is empty). */
    private Optional<Header> header(UUID deck) {
        return jdbc.sql("""
                SELECT d.reuse_scope_id, r.revision_id, r.members_root_id, r.exercises_root_id, r.member_count, r.exercise_count
                  FROM app_learning.deck d JOIN app_learning.deck_publication p ON p.deck_id = d.deck_id
                  JOIN app_learning.deck_revision r ON r.deck_id = p.deck_id AND r.revision_id = p.published_revision_id
                 WHERE d.deck_id = :deck
                """).param("deck", deck).query((row, ignored) -> new Header(row.getObject("reuse_scope_id", UUID.class), row.getObject("revision_id", UUID.class),
                row.getObject("members_root_id", UUID.class), row.getObject("exercises_root_id", UUID.class), row.getInt("member_count"),
                row.getInt("exercise_count"))).optional();
    }

    /** @param limit 1..100 text (default 50); @param cursor an opaque cursor of this list or null */
    public Page page(UUID deck, String kind, String limit, String cursor) {
        boolean items = ITEM.equals(kind);
        if (!items && !EXERCISE.equals(kind)) throw new InvalidRequestException();
        int size = limit == null ? 50 : PublicCursor.pageSize(limit);
        char letter = items ? 'i' : 'e';
        Optional<Header> found = header(deck);
        if (found.isEmpty()) {
            if (cursor != null) PublicCursor.decode(cursor, letter);
            return new Page(kind, 0, List.of(), null);
        }
        Header header = found.orElseThrow();
        int total = items ? header.memberCount() : header.exerciseCount();
        PublicCursor position = PublicCursor.decode(cursor, letter);
        if (position != null && !position.revisionId().equals(header.revisionId())) throw new VersionConflictException();
        int start = position == null ? 0 : position.next();
        if (start > total) throw new InvalidRequestException();
        List<Unit> units = items ? itemUnits(header, start, size) : exerciseUnits(header, start, size);
        int next = start + units.size();
        return new Page(kind, total, units, next < total ? new PublicCursor(letter, header.revisionId(), next).encode() : null);
    }

    private List<Unit> itemUnits(Header header, int start, int size) {
        List<Entry> entries = manifests.members(header.scope(), header.membersRoot(), header.memberCount(), start, size);
        Map<String, PublishedContentRepository.ItemRevision> revisions = new HashMap<>();
        content.itemRevisions(header.scope(), entries.stream().map(Entry::key).toList(), entries.stream().map(entry -> entry.target().objectId()).toList())
                .forEach(revision -> revisions.put(revision.memberKey() + "/" + revision.descriptorRootId(), revision));
        Set<UUID> blocked = takedowns.items(header.scope(), revisions.values().stream().map(PublishedContentRepository.ItemRevision::revisionId).toList());
        List<Unit> units = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            PublishedContentRepository.ItemRevision revision = revisions.get(entry.key() + "/" + entry.target().objectId());
            if (revision == null) throw new IllegalStateException("Published member projection is inconsistent");
            units.add(new Unit(ITEM, entry.key(), revision.revisionId(), start + index, previews.derive(header.scope(), revision.contentRootId()), null,
                    blocked.contains(revision.revisionId())));
        }
        return units;
    }

    private List<Unit> exerciseUnits(Header header, int start, int size) {
        List<Entry> entries = manifests.exercises(header.scope(), header.exercisesRoot(), header.exerciseCount(), start, size);
        Map<String, PublishedContentRepository.ExerciseRow> rows = new HashMap<>();
        content.exercises(header.scope(), entries.stream().map(Entry::key).toList(), entries.stream().map(entry -> entry.target().objectId()).toList())
                .forEach(row -> rows.put(row.exerciseId() + "/" + row.descriptorRootId(), row));
        Set<UUID> blocked = takedowns.exercises(header.scope(), rows.values().stream().map(PublishedContentRepository.ExerciseRow::revisionId).toList());
        List<Unit> units = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            PublishedContentRepository.ExerciseRow row = rows.get(entry.key() + "/" + entry.target().objectId());
            if (row == null) throw new IllegalStateException("Published exercise projection is inconsistent");
            String prompt = ExercisePrompts.summary(row.type(), row.question());
            units.add(new Unit(EXERCISE, entry.key(), row.revisionId(), start + index, prompt == null ? "" : prompt, row.type(), blocked.contains(row.revisionId())));
        }
        return units;
    }

    /** A unit the operator names by kind and key. */
    public record Key(String kind, UUID key) { }

    /**
     * The revision the deck's PUBLISHED manifests hold for each of the units, resolved in ONE pass per manifest (one traversal of the pages for all the keys of a
     * kind, then one lookup of their revisions): a unit that is not in the published manifest, or a deck that is unpublished, is absent from the result.
     * The caller holds the deck's publication row, so the published revision cannot move meanwhile.
     */
    public Map<Key, UUID> publishedRevisions(UUID deck, Collection<Key> units) {
        Map<Key, UUID> result = new HashMap<>();
        if (units.isEmpty()) return result;
        Optional<Header> found = header(deck);
        if (found.isEmpty()) return result;
        Header header = found.orElseThrow();
        List<UUID> members = units.stream().filter(unit -> ITEM.equals(unit.kind())).map(Key::key).distinct().toList();
        List<UUID> wantedExercises = units.stream().filter(unit -> EXERCISE.equals(unit.kind())).map(Key::key).distinct().toList();
        if (units.stream().anyMatch(unit -> !ITEM.equals(unit.kind()) && !EXERCISE.equals(unit.kind()))) throw new InvalidRequestException();
        Map<UUID, PublishedContentRepository.Located> itemSpots = content.locateAll(header.scope(), header.membersRoot(), header.memberCount(),
                ManifestPages.MEMBERS, "members", members);
        if (!itemSpots.isEmpty()) {
            content.itemRevisions(header.scope(), itemSpots.keySet(), itemSpots.values().stream().map(PublishedContentRepository.Located::descriptorRootId).toList())
                    .forEach(revision -> {
                        PublishedContentRepository.Located spot = itemSpots.get(revision.memberKey());
                        if (spot != null && spot.descriptorRootId().equals(revision.descriptorRootId())) result.put(new Key(ITEM, revision.memberKey()), revision.revisionId());
                    });
        }
        Map<UUID, PublishedContentRepository.Located> exerciseSpots = content.locateAll(header.scope(), header.exercisesRoot(), header.exerciseCount(),
                ManifestPages.EXERCISES, "exercises", wantedExercises);
        if (!exerciseSpots.isEmpty()) {
            content.exercises(header.scope(), exerciseSpots.keySet(), exerciseSpots.values().stream().map(PublishedContentRepository.Located::descriptorRootId).toList())
                    .forEach(row -> {
                        PublishedContentRepository.Located spot = exerciseSpots.get(row.exerciseId());
                        if (spot != null && spot.descriptorRootId().equals(row.descriptorRootId())) result.put(new Key(EXERCISE, row.exerciseId()), row.revisionId());
                    });
        }
        return result;
    }

    /** The lineage scope of a deck (deleted or not), the key the takedown rows use. */
    public UUID scope(UUID deck) {
        return jdbc.sql("SELECT reuse_scope_id FROM app_learning.deck WHERE deck_id = :deck").param("deck", deck).query(UUID.class).optional()
                .orElseThrow(ResourceNotFoundException::new);
    }
}
