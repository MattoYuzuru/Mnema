package app.mnema.learning.catalog.deck;

import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.*;
import app.mnema.learning.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Real V3 constraints: no trigger disabling, timestamp rewriting or fake storage pins. */
@SpringBootTest
class DeckConstraintIntegrationTest extends PostgresIntegrationTest {
    @Autowired DeckRepository repository;
    @Autowired DeckService service;
    @Autowired ImmutableStorage storage;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    private static final Instant TIME = Instant.parse("2026-09-12T12:00:00.123456Z");

    @Test
    void equalMicrosecondCreationKeysPageExactlyOnceAcrossMetadataSaveAndForeignCursor() {
        UUID actor = UUID.randomUUID();
        Fixture lower = create(actor, id(1), TIME.minusNanos(1000));
        Fixture low = create(actor, id(2), TIME);
        Fixture middle = create(actor, id(3), TIME);
        Fixture high = create(actor, id(4), TIME);
        Fixture upper = create(actor, id(5), TIME.plusNanos(1000));
        Fixture foreign = create(UUID.randomUUID(), id(6), TIME);

        JsonNode first = service.list(actor, "2", null);
        assertThat(ids(first)).containsExactly(upper.row().deckId(), high.row().deckId());
        DeckCursor cursor = DeckCursor.decode(first.path("nextCursor").textValue());
        assertThat(cursor.createdAt()).isEqualTo(TIME);
        assertThat(cursor.createdAt().getNano()).isEqualTo(123_456_000);
        assertThat(cursor.deckId()).isEqualTo(high.row().deckId());
        service.save(actor, high.row().deckId(), 0, command("already returned"));
        service.save(actor, middle.row().deckId(), 0, command("not yet returned"));

        JsonNode second = service.list(actor, "2", first.path("nextCursor").textValue());
        JsonNode third = service.list(actor, "2", second.path("nextCursor").textValue());
        assertThat(ids(second)).containsExactly(middle.row().deckId(), low.row().deckId());
        assertThat(second.path("items").get(0).path("metadata").path("title").textValue()).isEqualTo("not yet returned");
        assertThat(ids(third)).containsExactly(lower.row().deckId());
        assertThat(third.path("nextCursor").isNull()).isTrue();
        assertThat(repository.find(actor, middle.row().deckId()).orElseThrow().createdAt()).isEqualTo(TIME);
        var all = new ArrayList<UUID>();
        for (JsonNode page : List.of(first, second, third)) all.addAll(ids(page));
        assertThat(all).doesNotHaveDuplicates().hasSize(5).doesNotContain(foreign.row().deckId());
        String foreignLocation = new DeckCursor(TIME, foreign.row().deckId()).encode();
        assertThat(ids(service.list(actor, "100", foreignLocation)))
                .containsExactly(high.row().deckId(), middle.row().deckId(), low.row().deckId(), lower.row().deckId());
        assertThat(ids(service.list(UUID.randomUUID(), "100", foreignLocation))).isEmpty();
    }

    @Test
    void ownerPaginationIndexAndExactDeferredHeadConstraintMatchQueries() {
        String index = jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname='app_learning' AND indexname='deck_owner_page'")
                .query(String.class).single();
        assertThat(index).contains("(owner_id, created_at DESC, deck_id DESC)");
        String head = jdbc.sql("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid='app_learning.deck'::regclass AND conname='deck_exact_head'
                """).query(String.class).single();
        assertThat(head).contains("FOREIGN KEY (deck_id, head_revision_id, row_version)",
                "REFERENCES app_learning.deck_revision(deck_id, revision_id, sequence)", "DEFERRABLE INITIALLY DEFERRED");
        List<String> protection = jdbc.sql("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid='app_learning.deck_revision'::regclass AND contype='f'
                """).query(String.class).list();
        assertThat(protection).anyMatch(value -> value.contains("(reuse_scope_id, members_pin_id, members_root_id)")
                && value.contains("storage_pin(reuse_scope_id, pin_id, root_id)"));
        assertThat(protection).anyMatch(value -> value.contains("(reuse_scope_id, exercises_pin_id, exercises_root_id)")
                && value.contains("storage_pin(reuse_scope_id, pin_id, root_id)"));
    }

    enum DeckBinding { FOREIGN_SCOPE, FOREIGN_OWNER, FOREIGN_PARENT, WRONG_PARENT_SEQUENCE, MISSING_PARENT }

    @Test
    void distinctLogicalDecksCanRetainAuthorizedLineageRootsWithoutSharingHeadsOrAccess() {
        Fixture source = create(UUID.randomUUID(), UUID.randomUUID(), TIME);
        UUID actor = UUID.randomUUID();
        // Synthetic authorized lineage setup only, not a public fork command or grant.
        DeckRecord branch = new DeckRecord(UUID.randomUUID(), UUID.randomUUID(), source.row().scopeId(), 0,
                "branch", "", TIME, TIME, source.row().membersRootId(), source.row().exercisesRootId(), 0, 0);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            repository.insertDeck(branch.deckId(), actor, branch.scopeId(), branch.revisionId(), TIME);
            Protection pins = protect(branch, actor, "deck.revision", branch.revisionId(), false);
            repository.insertRevision(branch, actor, null, command("branch"), pins.members(), pins.exercises());
        });
        service.save(actor, branch.deckId(), 0, command("independent edit"));
        assertThat(repository.find(source.actor(), source.row().deckId()).orElseThrow()).isEqualTo(source.row());
        assertThat(repository.find(actor, branch.deckId()).orElseThrow().rowVersion()).isOne();
        assertThat(repository.find(actor, source.row().deckId())).isEmpty();
        assertThat(repository.find(source.actor(), branch.deckId())).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.storage_object WHERE reuse_scope_id=:scope")
                .param("scope", branch.scopeId()).query(Long.class).single()).isEqualTo(2);
        assertThat(pinCount(branch.scopeId())).isEqualTo(6);
    }

    @ParameterizedTest
    @EnumSource(DeckBinding.class)
    void revisionCannotCrossDeckScopeOwnerOrParentSequence(DeckBinding violation) {
        Fixture base = create(UUID.randomUUID(), UUID.randomUUID(), TIME);
        Fixture foreign = create(UUID.randomUUID(), UUID.randomUUID(), TIME);
        long pins = pinCount(base.row().scopeId()) + pinCount(foreign.row().scopeId());
        String state = violation == DeckBinding.MISSING_PARENT ? "23514" : "23503";
        rejected(state, () -> {
            Fixture roots = violation == DeckBinding.FOREIGN_SCOPE ? foreign : base;
            UUID actor = violation == DeckBinding.FOREIGN_OWNER ? foreign.actor() : base.actor();
            long sequence = violation == DeckBinding.WRONG_PARENT_SEQUENCE ? 2 : 1;
            UUID parent = violation == DeckBinding.FOREIGN_PARENT ? foreign.row().revisionId()
                    : violation == DeckBinding.MISSING_PARENT ? null : base.row().revisionId();
            DeckRecord candidate = candidate(base, roots, sequence);
            Protection protection = protect(candidate, actor, "deck.revision", candidate.revisionId(), false);
            repository.insertRevision(candidate, actor, parent, command("invalid binding"), protection.members(), protection.exercises());
        });
        assertThat(repository.find(base.actor(), base.row().deckId()).orElseThrow()).isEqualTo(base.row());
        assertThat(pinCount(base.row().scopeId()) + pinCount(foreign.row().scopeId())).isEqualTo(pins);
        assertThat(revisionCount(base.row().deckId())).isOne();
    }

    enum PinBinding { STAGING, WRONG_KIND, WRONG_REVISION, WRONG_ACTOR, SWAPPED_ROOTS, SAME_ROOT, MISSING_PIN }

    @ParameterizedTest
    @EnumSource(PinBinding.class)
    void revisionRequiresItsOwnExactDurableRootPins(PinBinding violation) {
        Fixture base = create(UUID.randomUUID(), UUID.randomUUID(), TIME);
        rejected("23514", () -> {
            DeckRecord candidate = candidate(base, base, 1);
            UUID pinActor = violation == PinBinding.WRONG_ACTOR ? UUID.randomUUID() : base.actor();
            String kind = violation == PinBinding.WRONG_KIND ? "deck.other" : "deck.revision";
            UUID pinOwner = violation == PinBinding.WRONG_REVISION ? UUID.randomUUID() : candidate.revisionId();
            Protection protection = protect(candidate, pinActor, kind, pinOwner, violation == PinBinding.STAGING);
            UUID members = violation == PinBinding.SWAPPED_ROOTS ? protection.exercises() : protection.members();
            UUID exercises = violation == PinBinding.SWAPPED_ROOTS ? protection.members() : protection.exercises();
            if (violation == PinBinding.MISSING_PIN) members = UUID.randomUUID();
            if (violation == PinBinding.SAME_ROOT) candidate = new DeckRecord(candidate.deckId(), candidate.revisionId(), candidate.scopeId(),
                    candidate.rowVersion(), candidate.title(), candidate.description(), candidate.createdAt(), candidate.updatedAt(),
                    candidate.membersRootId(), candidate.membersRootId(), 0, 0);
            repository.insertRevision(candidate, base.actor(), base.row().revisionId(), command("invalid pins"), members, exercises);
        });
        assertThat(pinCount(base.row().scopeId())).isEqualTo(2);
        assertThat(revisionCount(base.row().deckId())).isOne();
    }

    @Test
    void deferredHeadRequiresSameDeckAndMatchingRevisionSequenceAtCommit() {
        Fixture first = create(UUID.randomUUID(), UUID.randomUUID(), TIME);
        Fixture other = create(UUID.randomUUID(), UUID.randomUUID(), TIME);
        service.save(other.actor(), other.row().deckId(), 0, command("other sequence one"));
        UUID otherHead = repository.find(other.actor(), other.row().deckId()).orElseThrow().revisionId();
        // Each update satisfies the transition trigger; the exact deferred FK rejects commit.
        for (UUID target : List.of(first.row().revisionId(), otherHead, UUID.randomUUID())) {
            rejected("23503", () -> assertThat(repository.advance(first.actor(), first.row().deckId(), target, 0)).isOne());
        }
        assertThat(repository.find(first.actor(), first.row().deckId()).orElseThrow()).isEqualTo(first.row());
    }

    @Test
    void missingInitialRevisionAndChangedDeckIdentityOrCreationKeyCannotCommit() {
        UUID actor = UUID.randomUUID(); UUID missing = UUID.randomUUID();
        rejected("23503", () -> repository.insertDeck(missing, actor, UUID.randomUUID(), UUID.randomUUID(), TIME));
        assertThat(repository.find(actor, missing)).isEmpty();
        Fixture base = create(actor, UUID.randomUUID(), TIME);
        for (String assignment : List.of("owner_id = :value", "reuse_scope_id = :value", "deck_id = :value")) {
            rejected("23514", () -> jdbc.sql("UPDATE app_learning.deck SET " + assignment + ", row_version = row_version + 1 WHERE deck_id=:deck")
                    .param("value", UUID.randomUUID()).param("deck", base.row().deckId()).update());
        }
        rejected("23514", () -> jdbc.sql("UPDATE app_learning.deck SET created_at=created_at+interval '1 microsecond', row_version=row_version+1 WHERE deck_id=:deck")
                .param("deck", base.row().deckId()).update());
        rejected("23514", () -> jdbc.sql("UPDATE app_learning.deck SET row_version=row_version+2 WHERE deck_id=:deck")
                .param("deck", base.row().deckId()).update());
        assertThat(repository.find(actor, base.row().deckId()).orElseThrow()).isEqualTo(base.row());
    }

    @Test
    void publishedPinsRootsAndHistoryRemainProtectedAfterMetadataSave() {
        Fixture base = create(UUID.randomUUID(), UUID.randomUUID(), TIME);
        service.save(base.actor(), base.row().deckId(), 0, command("new history"));
        rejected("23503", () -> storage.release(base.row().scopeId(), base.membersPin()));
        rejected("23503", () -> storage.release(base.row().scopeId(), base.exercisesPin()));
        rejected("23503", () -> jdbc.sql("DELETE FROM app_learning.storage_object WHERE reuse_scope_id=:scope AND object_id=:root")
                .param("scope", base.row().scopeId()).param("root", base.row().membersRootId()).update());
        rejected("23514", () -> jdbc.sql("UPDATE app_learning.deck_revision SET title='rewritten' WHERE deck_id=:deck AND revision_id=:revision")
                .param("deck", base.row().deckId()).param("revision", base.row().revisionId()).update());
        rejected("23503", () -> jdbc.sql("DELETE FROM app_learning.deck_revision WHERE deck_id=:deck AND revision_id=:revision")
                .param("deck", base.row().deckId()).param("revision", base.row().revisionId()).update());
        assertThat(revisionCount(base.row().deckId())).isEqualTo(2);
        assertThat(pinCount(base.row().scopeId())).isEqualTo(4);
    }

    private Fixture create(UUID actor, UUID deck, Instant time) {
        return new TransactionTemplate(transactions).execute(status -> {
            UUID scope = UUID.randomUUID(), revision = UUID.randomUUID();
            NewObject members = empty("members"), exercises = empty("exercises");
            DeckRecord row = new DeckRecord(deck, revision, scope, 0, "fixture", "exact time", time, time,
                    members.objectId(), exercises.objectId(), 0, 0);
            repository.insertDeck(deck, actor, scope, revision, time);
            List<StagedRoot> staged = storage.stageBatch(new StageBatch(scope, actor, List.of(members, exercises),
                    List.of(members.objectId(), exercises.objectId())), Duration.ofMinutes(1));
            PinOwner owner = new PinOwner("deck.revision", revision, actor);
            UUID membersPin = storage.retain(staged.getFirst(), owner);
            UUID exercisesPin = storage.retain(staged.getLast(), owner);
            repository.insertRevision(row, actor, null, command("fixture"), membersPin, exercisesPin);
            staged.forEach(root -> storage.release(scope, root.stagingPinId()));
            return new Fixture(row, actor, membersPin, exercisesPin);
        });
    }

    private Protection protect(DeckRecord row, UUID actor, String ownerKind, UUID ownerId, boolean stagingOnly) {
        List<StagedRoot> staged = storage.stageBatch(new StageBatch(row.scopeId(), actor, List.of(),
                List.of(row.membersRootId(), row.exercisesRootId())), Duration.ofMinutes(1));
        if (stagingOnly) return new Protection(staged.getFirst().stagingPinId(), staged.getLast().stagingPinId());
        PinOwner owner = new PinOwner(ownerKind, ownerId, actor);
        UUID members = storage.retain(staged.getFirst(), owner), exercises = storage.retain(staged.getLast(), owner);
        staged.forEach(root -> storage.release(row.scopeId(), root.stagingPinId()));
        return new Protection(members, exercises);
    }

    private static DeckRecord candidate(Fixture base, Fixture roots, long sequence) {
        return new DeckRecord(base.row().deckId(), UUID.randomUUID(), roots.row().scopeId(), sequence, "candidate", "",
                base.row().createdAt(), TIME.plusSeconds(1), roots.row().membersRootId(), roots.row().exercisesRootId(), 0, 0);
    }

    private void rejected(String sqlState, Runnable action) {
        Throwable failure = catchThrowable(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> action.run()));
        assertThat(failure).as("transaction must fail with SQLSTATE %s", sqlState).isNotNull();
        Throwable cause = failure;
        while (!(cause instanceof SQLException) && cause.getCause() != null) cause = cause.getCause();
        assertThat(cause).isInstanceOfSatisfying(SQLException.class, exception -> assertThat(exception.getSQLState()).isEqualTo(sqlState));
    }

    private long pinCount(UUID scope) {
        return jdbc.sql("SELECT count(*) FROM app_learning.storage_pin WHERE reuse_scope_id=:scope")
                .param("scope", scope).query(Long.class).single();
    }
    private long revisionCount(UUID deck) {
        return jdbc.sql("SELECT count(*) FROM app_learning.deck_revision WHERE deck_id=:deck")
                .param("deck", deck).query(Long.class).single();
    }
    private static NewObject empty(String role) {
        var payload = JsonNodeFactory.instance.objectNode().put("codec", 1).put("role", role).put("treeHeight", 0);
        payload.putArray("counts");
        return new NewObject(UUID.randomUUID(), ObjectKind.PAGE, (short) 1, (short) 10, payload, List.of());
    }
    private static List<UUID> ids(JsonNode page) {
        var result = new ArrayList<UUID>();
        page.path("items").forEach(row -> result.add(UUID.fromString(row.path("deckId").textValue())));
        return result;
    }
    private static UUID id(int value) { return new UUID(0xabcdefab00004000L, 0x8000000000000000L + value); }
    private static DeckCommand command(String title) { return new DeckCommand(UUID.randomUUID(), title, ""); }
    private record Protection(UUID members, UUID exercises) { }
    private record Fixture(DeckRecord row, UUID actor, UUID membersPin, UUID exercisesPin) { }
}
