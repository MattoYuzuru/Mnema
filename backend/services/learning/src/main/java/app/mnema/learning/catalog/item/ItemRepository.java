package app.mnema.learning.catalog.item;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class ItemRepository {
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
                 WHERE d.owner_id=:actor AND d.deck_id=:deck
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
                 WHERE d.owner_id=:actor AND p.deck_id=:deck AND p.member_key=:member
                """).param("actor", actor).param("deck", deck).param("member", member).query(ITEM).optional();
    }

    boolean itemExists(UUID actor, UUID deck, UUID member) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM app_learning.learning_item i JOIN app_learning.deck d ON d.deck_id=i.deck_id
                    WHERE d.owner_id=:actor AND i.deck_id=:deck AND i.member_key=:member)
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
                 WHERE d.owner_id=:actor AND r.deck_id=:deck AND r.member_key=:member AND r.revision_id=:revision
                """).param("actor", actor).param("deck", deck).param("member", member).param("revision", revision)
                .query(ITEM).optional();
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
                 WHERE d.owner_id=:actor AND p.deck_id=:deck AND p.member_key IN (:members)
                """).param("actor", actor).param("deck", deck).param("members", members)
                .query(ITEM).list();
    }

    Instant now() { return jdbc.sql("SELECT statement_timestamp()").query(Timestamp.class).single().toInstant(); }

    int advance(UUID actor, UUID deck, UUID revision, long expected) {
        return jdbc.sql("""
                UPDATE app_learning.deck SET head_revision_id=:revision,row_version=row_version+1
                 WHERE owner_id=:actor AND deck_id=:deck AND row_version=:expected
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
