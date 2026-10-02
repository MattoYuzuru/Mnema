package app.mnema.learning.catalog.item;

import app.mnema.learning.catalog.content.pages.CountedPageTypes.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class ItemRepository {
    private static final Profile MEMBERS = Profile.members(ItemService.MAX_MEMBERS);
    private final JdbcClient jdbc;

    ItemRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    record DeckHead(UUID deckId, UUID scopeId, UUID revisionId, long version, String title, String description,
                    Instant createdAt, UUID membersRootId, UUID exercisesRootId, int memberCount, int exerciseCount) { }

    private static final RowMapper<DeckHead> DECK = (row, ignored) -> new DeckHead(
            row.getObject("deck_id", UUID.class), row.getObject("reuse_scope_id", UUID.class),
            row.getObject("head_revision_id", UUID.class), row.getLong("row_version"), row.getString("title"),
            row.getString("description"), row.getTimestamp("deck_created_at").toInstant(),
            row.getObject("members_root_id", UUID.class), row.getObject("exercises_root_id", UUID.class),
            row.getInt("member_count"), row.getInt("exercise_count"));

    private static final RowMapper<ItemRecord> ITEM = (row, ignored) -> new ItemRecord(
            row.getObject("deck_id", UUID.class), row.getObject("member_key", UUID.class),
            row.getObject("revision_id", UUID.class), row.getLong("item_sequence"),
            row.getObject("deck_revision_id", UUID.class), row.getLong("deck_sequence"),
            row.getObject("ordinal", Integer.class),
            row.getObject("reuse_scope_id", UUID.class), row.getObject("content_root_id", UUID.class),
            row.getObject("descriptor_root_id", UUID.class), row.getTimestamp("item_created_at").toInstant(),
            row.getTimestamp("updated_at").toInstant());

    Optional<DeckHead> deck(UUID actor, UUID deck) {
        return jdbc.sql("""
                SELECT d.deck_id,d.reuse_scope_id,d.head_revision_id,d.row_version,d.created_at AS deck_created_at,
                       r.title,r.description,r.members_root_id,r.exercises_root_id,r.member_count,r.exercise_count
                  FROM app_learning.deck d JOIN app_learning.deck_revision r
                    ON r.deck_id=d.deck_id AND r.revision_id=d.head_revision_id
                 WHERE d.owner_id=:actor AND d.deck_id=:deck AND d.deleted_at IS NULL
                """).param("actor", actor).param("deck", deck).query(DECK).optional();
    }

    Optional<ItemRecord> headItem(UUID actor, UUID deck, UUID member) {
        return jdbc.sql("""
                SELECT p.deck_id,p.member_key,p.revision_id,p.item_sequence,NULL::INTEGER AS ordinal,
                       r.deck_revision_id,r.deck_sequence,
                       r.reuse_scope_id,
                       r.content_root_id,r.descriptor_root_id,i.created_at AS item_created_at,p.updated_at
                  FROM app_learning.deck d JOIN app_learning.deck_head_item p ON p.deck_id=d.deck_id
                  JOIN app_learning.item_revision r ON r.deck_id=p.deck_id AND r.member_key=p.member_key
                    AND r.revision_id=p.revision_id
                  JOIN app_learning.learning_item i ON i.deck_id=p.deck_id AND i.member_key=p.member_key
                 WHERE d.owner_id=:actor AND d.deleted_at IS NULL AND p.deck_id=:deck AND p.member_key=:member
                """).param("actor", actor).param("deck", deck).param("member", member).query(ITEM).optional();
    }

    boolean itemExists(UUID actor, UUID deck, UUID member) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM app_learning.learning_item i JOIN app_learning.deck d ON d.deck_id=i.deck_id
                    WHERE d.owner_id=:actor AND d.deleted_at IS NULL AND i.deck_id=:deck AND i.member_key=:member)
                """).param("actor", actor).param("deck", deck).param("member", member).query(Boolean.class).single();
    }

    Optional<ItemRecord> revision(UUID actor, UUID deck, UUID member, UUID revision) {
        return jdbc.sql("""
                SELECT r.deck_id,r.member_key,r.revision_id,r.item_sequence,r.deck_revision_id,r.deck_sequence,
                       c.to_ordinal AS ordinal,r.reuse_scope_id,r.content_root_id,r.descriptor_root_id,
                       i.created_at AS item_created_at,r.created_at AS updated_at
                  FROM app_learning.deck d JOIN app_learning.item_revision r ON r.deck_id=d.deck_id
                  JOIN app_learning.learning_item i ON i.deck_id=r.deck_id AND i.member_key=r.member_key
                  JOIN app_learning.deck_item_change c ON c.deck_id=r.deck_id AND c.member_key=r.member_key
                    AND c.revision_id=r.revision_id
                 WHERE d.owner_id=:actor AND d.deleted_at IS NULL AND r.deck_id=:deck AND r.member_key=:member AND r.revision_id=:revision
                """).param("actor", actor).param("deck", deck).param("member", member).param("revision", revision)
                .query(ITEM).optional();
    }

    /**
     * Recursive traversal of the membership pages of one root: the CTE {@code pages} holds the leaf pages
     * ({@code tree_height=0}) with their base ordinal. Never follows descriptors/content or historical roots.
     * Callers append further CTEs or the final SELECT and bind root, scope, count, leafRank and maxHeight.
     */
    private static final String MEMBER_PAGES = """
            WITH RECURSIVE pages(object_id,tree_height,base,expected_count) AS (
                SELECT CAST(:root AS uuid),(root.payload->>'treeHeight')::integer,0::bigint,CAST(:count AS bigint)
                  FROM app_learning.storage_object root
                 WHERE root.reuse_scope_id=:scope AND root.object_id=:root AND root.sealed
                   AND root.kind='page' AND root.encoding_version=1
                   AND root.payload->>'role'='members' AND root.payload->>'codec'='1'
                   AND root.dag_rank=:leafRank+(root.payload->>'treeHeight')::integer
                UNION ALL
                SELECT child.object_id,parent.tree_height-1,parent.base+link.prior_count,link.child_count
                  FROM pages parent
                  JOIN app_learning.storage_object page ON page.reuse_scope_id=:scope AND page.object_id=parent.object_id
                  JOIN LATERAL (
                      SELECT edge.child_id,edge.logical_key,
                             (page.payload->'counts'->edge.ordinal)::bigint AS child_count,
                             COALESCE(sum((page.payload->'counts'->edge.ordinal)::bigint) OVER
                               (ORDER BY edge.ordinal ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING),0)::bigint AS prior_count,
                             sum((page.payload->'counts'->edge.ordinal)::bigint) OVER () AS total_count
                        FROM app_learning.storage_edge edge
                       WHERE edge.reuse_scope_id=:scope AND edge.parent_id=parent.object_id
                  ) link ON link.logical_key IS NULL AND link.child_count>0 AND link.total_count=parent.expected_count
                  JOIN app_learning.storage_object child ON child.reuse_scope_id=:scope AND child.object_id=link.child_id
                   AND child.sealed AND child.kind='page' AND child.encoding_version=1
                   AND child.payload->>'role'='members' AND child.payload->>'codec'='1'
                   AND (child.payload->>'treeHeight')::integer=parent.tree_height-1
                   AND child.dag_rank=:leafRank+parent.tree_height-1
                 WHERE parent.tree_height>0 AND parent.tree_height<=:maxHeight
                   AND jsonb_array_length(page.payload->'counts')=page.edge_count
            )""";

    /** One rooted membership-page traversal; never follows descriptors/content or historical roots. */
    Optional<Integer> currentOrdinal(DeckHead deck, ItemRecord item) {
        return jdbc.sql(MEMBER_PAGES + """
                SELECT (page.base+edge.ordinal)::integer FROM pages page
                  JOIN app_learning.storage_edge edge ON edge.reuse_scope_id=:scope AND edge.parent_id=page.object_id
                 WHERE page.tree_height=0 AND edge.logical_key=:member AND edge.child_id=:descriptor
                   AND page.base+edge.ordinal<:count
                """).param("root", deck.membersRootId()).param("scope", deck.scopeId())
                .param("count", deck.memberCount()).param("member", item.memberKey()).param("descriptor", item.descriptorRootId())
                .param("leafRank", MEMBERS.leafRank()).param("maxHeight", MEMBERS.maximumHeight())
                .query(Integer.class).optional();
    }

    /** One member of the {@code exerciseCount}-sorted page: authoring ordinal, descriptor and enabled exercise count. */
    record SortedMember(UUID memberKey, int ordinal, UUID descriptorRootId, int exerciseCount) { }

    /**
     * One page of the members of {@code deck} ordered by (enabled assessed exercises ascending, ordinal ascending),
     * strictly after {@code (afterCount, afterOrdinal)} when given. One statement: the member-page traversal supplies
     * every ordinal, so the cost is O(members) and independent of the page number. Members and their exercise rows are
     * combined by UNION ALL + GROUP BY, never by a join of the two sets: on tables without statistics (just bulk-loaded)
     * the planner estimates one row for each side and a nested-loop join would rescan one set per row of the other,
     * quadratic in the Deck size. The aggregation has no plan with that shape.
     */
    List<SortedMember> sortedMembers(DeckHead deck, Integer afterCount, Integer afterOrdinal, int limit) {
        boolean after = afterCount != null;
        var query = jdbc.sql(MEMBER_PAGES + """
                , members AS MATERIALIZED (
                    SELECT edge.logical_key AS member_key,edge.child_id AS descriptor_id,
                           (page.base+edge.ordinal)::integer AS ordinal
                      FROM pages page
                      JOIN app_learning.storage_edge edge ON edge.reuse_scope_id=:scope AND edge.parent_id=page.object_id
                     WHERE page.tree_height=0 AND page.base+edge.ordinal<:count
                ), merged AS (
                    SELECT member_key,ordinal,descriptor_id,0 AS exercises FROM members
                    UNION ALL
                    SELECT binding.member_key,NULL::integer,NULL::uuid,1
                      FROM app_learning.deck_head_exercise head
                      JOIN app_learning.exercise_revision revision ON revision.deck_id=head.deck_id
                       AND revision.exercise_id=head.exercise_id AND revision.revision_id=head.revision_id AND revision.enabled
                      JOIN app_learning.exercise_content_binding binding ON binding.deck_id=head.deck_id
                       AND binding.exercise_id=head.exercise_id AND binding.exercise_revision_id=head.revision_id
                       AND binding.role='ASSESSED'
                     WHERE head.deck_id=:deck
                ), grouped AS (
                    SELECT member_key,max(ordinal) AS ordinal,(array_agg(descriptor_id) FILTER (WHERE descriptor_id IS NOT NULL))[1] AS descriptor_id,
                           sum(exercises)::integer AS exercise_count
                      FROM merged GROUP BY member_key HAVING max(ordinal) IS NOT NULL
                )
                SELECT member_key,ordinal,descriptor_id,exercise_count FROM grouped
                """ + (after ? " WHERE (exercise_count,ordinal)>(:afterCount,:afterOrdinal) " : " ")
                + " ORDER BY exercise_count,ordinal LIMIT :limit")
                .param("root", deck.membersRootId()).param("scope", deck.scopeId()).param("count", deck.memberCount())
                .param("leafRank", MEMBERS.leafRank()).param("maxHeight", MEMBERS.maximumHeight())
                .param("deck", deck.deckId()).param("limit", limit);
        if (after) query.param("afterCount", afterCount).param("afterOrdinal", afterOrdinal);
        return query.query((row, ignored) -> new SortedMember(row.getObject("member_key", UUID.class),
                row.getInt("ordinal"), row.getObject("descriptor_id", UUID.class), row.getInt("exercise_count"))).list();
    }

    /** A member's position at one Deck revision: authoring ordinal and the descriptor root published there. */
    record Position(UUID memberKey, int ordinal, UUID descriptorRootId) { }

    /** Positions of the given members in the immutable member root of {@code at} (absent members are not returned). */
    List<Position> memberPositions(DeckHead at, Collection<UUID> members) {
        if (members.isEmpty() || at.memberCount() == 0) return List.of();
        return jdbc.sql(MEMBER_PAGES + """
                SELECT edge.logical_key AS member_key,(page.base+edge.ordinal)::integer AS ordinal,edge.child_id AS descriptor_id
                  FROM pages page
                  JOIN app_learning.storage_edge edge ON edge.reuse_scope_id=:scope AND edge.parent_id=page.object_id
                 WHERE page.tree_height=0 AND page.base+edge.ordinal<:count AND edge.logical_key IN (:members)
                """).param("root", at.membersRootId()).param("scope", at.scopeId()).param("count", at.memberCount())
                .param("leafRank", MEMBERS.leafRank()).param("maxHeight", MEMBERS.maximumHeight())
                .param("members", members)
                .query((row, ignored) -> new Position(row.getObject("member_key", UUID.class), row.getInt("ordinal"),
                        row.getObject("descriptor_id", UUID.class))).list();
    }

    /** Enabled current exercises assessing each given material; a material without any is absent from the map. */
    Map<UUID, Integer> exerciseCounts(UUID deck, Collection<UUID> members) {
        Map<UUID, Integer> result = new HashMap<>();
        if (members.isEmpty()) return result;
        jdbc.sql("""
                SELECT binding.member_key,count(*)::integer AS exercise_count
                  FROM app_learning.exercise_content_binding binding
                  JOIN app_learning.deck_head_exercise head ON head.deck_id=binding.deck_id
                   AND head.exercise_id=binding.exercise_id AND head.revision_id=binding.exercise_revision_id
                  JOIN app_learning.exercise_revision revision ON revision.deck_id=head.deck_id
                   AND revision.exercise_id=head.exercise_id AND revision.revision_id=head.revision_id AND revision.enabled
                 WHERE binding.deck_id=:deck AND binding.role='ASSESSED' AND binding.member_key IN (:members)
                 GROUP BY binding.member_key
                """).param("deck", deck).param("members", members)
                .query((row, ignored) -> result.put(row.getObject("member_key", UUID.class), row.getInt("exercise_count")))
                .list();
        return result;
    }

    /** ALL current exercises (enabled and disabled) assessing the given materials: what a deletion affects. */
    int affectedExercises(UUID deck, Collection<UUID> members) {
        if (members.isEmpty()) return 0;
        return jdbc.sql("""
                SELECT count(*)::integer
                  FROM app_learning.exercise_content_binding binding
                  JOIN app_learning.deck_head_exercise head ON head.deck_id=binding.deck_id
                   AND head.exercise_id=binding.exercise_id AND head.revision_id=binding.exercise_revision_id
                 WHERE binding.deck_id=:deck AND binding.role='ASSESSED' AND binding.member_key IN (:members)
                """).param("deck", deck).param("members", members).query(Integer.class).single();
    }

    /** The deck as it was at an exact (possibly historical) revision, for resolving a bulk selection. */
    Optional<DeckHead> deckAtRevision(UUID actor, UUID deck, UUID revision) {
        return jdbc.sql("""
                SELECT d.deck_id,d.reuse_scope_id,r.revision_id AS head_revision_id,r.sequence AS row_version,
                       d.created_at AS deck_created_at,r.title,r.description,r.members_root_id,r.exercises_root_id,
                       r.member_count,r.exercise_count
                  FROM app_learning.deck d JOIN app_learning.deck_revision r ON r.deck_id=d.deck_id
                 WHERE d.owner_id=:actor AND d.deck_id=:deck AND d.deleted_at IS NULL AND r.revision_id=:revision
                """).param("actor", actor).param("deck", deck).param("revision", revision).query(DECK).optional();
    }

    /** Item revision published with each given descriptor root, keyed by descriptor. */
    Map<UUID, UUID> revisionsByDescriptor(UUID deck, Collection<UUID> descriptors) {
        Map<UUID, UUID> result = new HashMap<>();
        if (descriptors.isEmpty()) return result;
        jdbc.sql("""
                SELECT descriptor_root_id,revision_id FROM app_learning.item_revision
                 WHERE deck_id=:deck AND descriptor_root_id IN (:descriptors)
                """).param("deck", deck).param("descriptors", descriptors)
                .query((row, ignored) -> result.put(row.getObject("descriptor_root_id", UUID.class),
                        row.getObject("revision_id", UUID.class))).list();
        return result;
    }

    /** Exemplar members that are still current materials of the deck (a row for a deleted member is invisible). */
    List<UUID> exemplars(UUID deck) {
        return jdbc.sql("""
                SELECT exemplar.member_key FROM app_learning.deck_item_exemplar exemplar
                  JOIN app_learning.deck_head_item item ON item.deck_id=exemplar.deck_id AND item.member_key=exemplar.member_key
                 WHERE exemplar.deck_id=:deck ORDER BY exemplar.marked_at,exemplar.member_key
                """).param("deck", deck).query(UUID.class).list();
    }

    /** Serializes exemplar changes of one deck so the per-deck limit cannot be exceeded concurrently. */
    void lockExemplars(UUID deck) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended('deck.exemplar:' || :deck, 0))) lock")
                .param("deck", deck.toString()).query(Integer.class).single();
    }

    /** Marks a current material; a no-op when it is already marked or no longer a head item. */
    int insertExemplar(UUID deck, UUID member, Instant time) {
        return jdbc.sql("""
                INSERT INTO app_learning.deck_item_exemplar(deck_id,member_key,marked_at)
                SELECT item.deck_id,item.member_key,:time FROM app_learning.deck_head_item item
                 WHERE item.deck_id=:deck AND item.member_key=:member
                ON CONFLICT (deck_id,member_key) DO NOTHING
                """).param("deck", deck).param("member", member).param("time", Timestamp.from(time)).update();
    }

    int deleteExemplar(UUID deck, UUID member) {
        return jdbc.sql("DELETE FROM app_learning.deck_item_exemplar WHERE deck_id=:deck AND member_key=:member")
                .param("deck", deck).param("member", member).update();
    }

    List<ItemRecord> heads(UUID actor, UUID deck, List<UUID> members) {
        if (members.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT p.deck_id,p.member_key,p.revision_id,p.item_sequence,NULL::INTEGER AS ordinal,
                       r.deck_revision_id,r.deck_sequence,
                       r.reuse_scope_id,
                       r.content_root_id,r.descriptor_root_id,i.created_at AS item_created_at,p.updated_at
                  FROM app_learning.deck d JOIN app_learning.deck_head_item p ON p.deck_id=d.deck_id
                  JOIN app_learning.item_revision r ON r.deck_id=p.deck_id AND r.member_key=p.member_key
                    AND r.revision_id=p.revision_id
                  JOIN app_learning.learning_item i ON i.deck_id=p.deck_id AND i.member_key=p.member_key
                 WHERE d.owner_id=:actor AND d.deleted_at IS NULL AND p.deck_id=:deck AND p.member_key IN (:members)
                """).param("actor", actor).param("deck", deck).param("members", members)
                .query(ITEM).list();
    }

    Instant now() { return jdbc.sql("SELECT statement_timestamp()").query(Timestamp.class).single().toInstant(); }

    int advance(UUID actor, UUID deck, UUID revision, long expected) {
        return jdbc.sql("""
                UPDATE app_learning.deck SET head_revision_id=:revision,row_version=row_version+1
                 WHERE owner_id=:actor AND deck_id=:deck AND row_version=:expected AND deleted_at IS NULL
                """).param("revision", revision).param("actor", actor).param("deck", deck).param("expected", expected).update();
    }

    void insertDeckRevision(DeckHead previous, UUID revision, UUID actor, UUID command, Instant time,
                            UUID membersRoot, UUID exercisesRoot, UUID membersPin, UUID exercisesPin, int memberCount) {
        jdbc.sql("""
                INSERT INTO app_learning.deck_revision(deck_id,revision_id,reuse_scope_id,owner_id,sequence,
                    parent_revision_id,parent_sequence,command_id,title,description,created_at,members_root_id,
                    exercises_root_id,members_pin_id,exercises_pin_id,member_count,exercise_count)
                VALUES (:deck,:revision,:scope,:actor,:sequence,:parent,:parentSequence,:command,:title,:description,
                    :time,:members,:exercises,:membersPin,:exercisesPin,:memberCount,:exerciseCount)
                """).param("deck", previous.deckId()).param("revision", revision).param("scope", previous.scopeId())
                .param("actor", actor).param("sequence", previous.version() + 1).param("parent", previous.revisionId())
                .param("parentSequence", previous.version()).param("command", command).param("title", previous.title())
                .param("description", previous.description()).param("time", Timestamp.from(time)).param("members", membersRoot)
                .param("exercises", exercisesRoot).param("membersPin", membersPin).param("exercisesPin", exercisesPin)
                .param("memberCount", memberCount).param("exerciseCount", previous.exerciseCount()).update();
    }

    void insertItem(UUID deck, UUID member, UUID actor, UUID scope, Instant time) {
        jdbc.sql("""
                INSERT INTO app_learning.learning_item(deck_id,member_key,owner_id,reuse_scope_id,created_at)
                VALUES (:deck,:member,:actor,:scope,:time)
                """).param("deck", deck).param("member", member).param("actor", actor).param("scope", scope)
                .param("time", Timestamp.from(time)).update();
    }

    void insertItemRevision(UUID deck, UUID member, UUID revision, UUID scope, UUID actor, long sequence,
                            UUID parent, UUID deckRevision, long deckSequence, UUID command, UUID contentRoot,
                            UUID descriptorRoot, Instant time) {
        jdbc.sql("""
                INSERT INTO app_learning.item_revision(deck_id,member_key,revision_id,reuse_scope_id,owner_id,
                    item_sequence,parent_revision_id,parent_item_sequence,deck_revision_id,deck_sequence,command_id,
                    format_version,content_root_id,descriptor_root_id,created_at)
                VALUES (:deck,:member,:revision,:scope,:actor,:sequence,:parent,:parentSequence,:deckRevision,
                    :deckSequence,:command,1,:content,:descriptor,:time)
                """).param("deck", deck).param("member", member).param("revision", revision).param("scope", scope)
                .param("actor", actor).param("sequence", sequence).param("parent", parent, java.sql.Types.OTHER)
                .param("parentSequence", parent == null ? null : sequence - 1, java.sql.Types.BIGINT)
                .param("deckRevision", deckRevision).param("deckSequence", deckSequence).param("command", command)
                .param("content", contentRoot).param("descriptor", descriptorRoot).param("time", Timestamp.from(time)).update();
    }

    void insertHead(UUID deck, UUID member, UUID revision, long sequence, Instant time) {
        jdbc.sql("""
                INSERT INTO app_learning.deck_head_item(deck_id,member_key,revision_id,item_sequence,updated_at)
                VALUES (:deck,:member,:revision,:sequence,:time)
                """).param("deck", deck).param("member", member).param("revision", revision).param("sequence", sequence)
                .param("time", Timestamp.from(time)).update();
    }

    void updateHead(UUID deck, UUID member, UUID revision, long sequence, Instant time) {
        jdbc.sql("""
                UPDATE app_learning.deck_head_item SET revision_id=:revision,item_sequence=:sequence,updated_at=:time
                 WHERE deck_id=:deck AND member_key=:member
                """).param("revision", revision).param("sequence", sequence).param("time", Timestamp.from(time))
                .param("deck", deck).param("member", member).update();
    }

    void deleteHead(UUID deck, UUID member) {
        jdbc.sql("DELETE FROM app_learning.deck_head_item WHERE deck_id=:deck AND member_key=:member")
                .param("deck", deck).param("member", member).update();
        deleteExemplar(deck, member);
    }

    void change(UUID deck, UUID deckRevision, long deckSequence, int index, UUID member, String kind,
                UUID previousRevision, UUID revision, Integer from, Integer to) {
        jdbc.sql("""
                INSERT INTO app_learning.deck_item_change(deck_id,deck_revision_id,deck_sequence,change_ordinal,
                    member_key,change_kind,previous_revision_id,revision_id,from_ordinal,to_ordinal)
                VALUES (:deck,:deckRevision,:deckSequence,:index,:member,:kind,:previous,:revision,:from,:to)
                """).param("deck", deck).param("deckRevision", deckRevision).param("deckSequence", deckSequence)
                .param("index", index).param("member", member).param("kind", kind)
                .param("previous", previousRevision, java.sql.Types.OTHER).param("revision", revision, java.sql.Types.OTHER)
                .param("from", from, java.sql.Types.INTEGER).param("to", to, java.sql.Types.INTEGER).update();
    }

}
