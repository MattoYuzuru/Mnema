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
    private static final int SCAN = 500;

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

    /**
     * The revision the deck's published manifest holds for a unit, or empty when the deck is unpublished or the unit is not in the published manifest.
     * A material is found by one traversal of the members manifest; an exercise by a scan of the exercises manifest in pages of {@value #SCAN}
     * (the exercises manifest is not key-addressed; this is an operator's rare action).
     */
    public Optional<UUID> publishedRevision(UUID deck, String kind, UUID key) {
        Optional<Header> found = header(deck);
        if (found.isEmpty()) return Optional.empty();
        Header header = found.orElseThrow();
        if (ITEM.equals(kind)) {
            if (header.memberCount() == 0) return Optional.empty();
            return content.locate(header.scope(), header.membersRoot(), header.memberCount(), key)
                    .flatMap(located -> content.itemRevisions(header.scope(), List.of(key), List.of(located.descriptorRootId())).stream().findFirst())
                    .map(PublishedContentRepository.ItemRevision::revisionId);
        }
        if (!EXERCISE.equals(kind)) throw new InvalidRequestException();
        for (int start = 0; start < header.exerciseCount(); start += SCAN) {
            for (Entry entry : manifests.exercises(header.scope(), header.exercisesRoot(), header.exerciseCount(), start, SCAN)) {
                if (!key.equals(entry.key())) continue;
                return content.exercises(header.scope(), List.of(key), List.of(entry.target().objectId())).stream().findFirst()
                        .map(PublishedContentRepository.ExerciseRow::revisionId);
            }
        }
        return Optional.empty();
    }

    /** The lineage scope of a deck (deleted or not), the key the takedown rows use. */
    public UUID scope(UUID deck) {
        return jdbc.sql("SELECT reuse_scope_id FROM app_learning.deck WHERE deck_id = :deck").param("deck", deck).query(UUID.class).optional()
                .orElseThrow(ResourceNotFoundException::new);
    }
}
