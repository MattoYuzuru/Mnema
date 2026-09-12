package app.mnema.learning.catalog.deck;

import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class DeckServiceIntegrationTest extends PostgresIntegrationTest {
    @Autowired private DeckService service;
    @Autowired private DeckRepository repository;
    @Autowired private JdbcClient jdbc;
    @Autowired private ImmutableStorage storage;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void createsPrivateDeckAndReusesExactRootsAcrossImmutableMetadataHistory() {
        UUID actor = UUID.randomUUID();
        DeckCommand create = command("Первая");
        var result = service.create(actor, create);
        JsonNode first = result.acknowledgement().path("deck");
        UUID deck = UUID.fromString(first.path("deckId").textValue());
        DeckRecord before = repository.find(actor, deck).orElseThrow();
        assertThat(result.replayed()).isFalse();
        assertThat(service.read(actor, deck)).isEqualTo(first);
        assertThat(first.path("visibility").textValue()).isEqualTo("private");
        assertThat(first.path("rowVersion").textValue()).isEqualTo("0");
        assertThat(first.toString()).doesNotContain("reuseScope", "rootId", "pinId");
        assertThat(count("storage_object", "reuse_scope_id", before.scopeId())).isEqualTo(2);
        assertThat(service.create(actor, create).acknowledgement()).isEqualTo(result.acknowledgement());
        var updated = service.save(actor, deck, 0, command("  Новая\nколода  "));
        DeckRecord after = repository.find(actor, deck).orElseThrow();
        assertThat(after.rowVersion()).isEqualTo(1);
        assertThat(after.title()).isEqualTo("  Новая\nколода  ");
        assertThat(after.membersRootId()).isEqualTo(before.membersRootId());
        assertThat(after.exercisesRootId()).isEqualTo(before.exercisesRootId());
        assertThat(after.createdAt()).isEqualTo(before.createdAt());
        assertThat(after.revisionId()).isNotEqualTo(before.revisionId());
        assertThat(updated.acknowledgement().path("deck")).isEqualTo(after.toJson());
        assertThat(count("deck_revision", "deck_id", deck)).isEqualTo(2);
        assertThat(count("storage_object", "reuse_scope_id", before.scopeId())).isEqualTo(2);
        assertThat(count("storage_pin", "reuse_scope_id", before.scopeId())).isEqualTo(4);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.storage_pin WHERE reuse_scope_id = :scope AND pin_kind = 'staging'")
                .param("scope", before.scopeId()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT title FROM app_learning.deck_revision WHERE deck_id = :deck AND sequence = 0")
                .param("deck", deck).query(String.class).single()).isEqualTo("Первая");
        jdbc.sql("UPDATE app_learning.storage_gc_candidate SET not_before = statement_timestamp() - interval '1 hour' WHERE reuse_scope_id = :scope")
                .param("scope", before.scopeId()).update();
        assertThat(storage.collectBatch(before.scopeId(), Instant.now().plusSeconds(10), 8).deleted()).isZero();
    }

    @Test
    void replaysOriginalAcknowledgementAfterNewerPublicationButNeverBypassesCurrentAcl() {
        UUID actor = UUID.randomUUID();
        UUID deck = id(service.create(actor, command("create")));
        DeckCommand edit = command("one");
        var first = service.save(actor, deck, 0, edit);
        service.save(actor, deck, 1, command("two"));
        var replay = service.save(actor, deck, 0, edit);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.acknowledgement()).isEqualTo(first.acknowledgement());
        assertThat(service.read(actor, deck).path("rowVersion").textValue()).isEqualTo("2");
        assertThatThrownBy(() -> service.save(actor, deck, 1, edit)).isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> service.save(actor, deck, 0, new DeckCommand(edit.commandId(), "different", "")))
                .isInstanceOf(IdempotencyConflictException.class);
        UUID secondDeck = id(service.create(actor, command("second")));
        assertThatThrownBy(() -> service.save(actor, secondDeck, 0, edit)).isInstanceOf(IdempotencyConflictException.class);
        UUID foreign = UUID.randomUUID();
        assertThatThrownBy(() -> service.save(foreign, deck, 0, edit)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.read(foreign, deck)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.read(actor, UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
        assertThat(count("deck_revision", "deck_id", deck)).isEqualTo(3);
    }

    @Test
    void retriesCreateWithoutDuplicateAndRejectsChangedActorOrPayload() {
        UUID actor = UUID.randomUUID();
        DeckCommand command = command("one");
        var created = service.create(actor, command);
        var retry = service.create(actor, command);
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.acknowledgement()).isEqualTo(created.acknowledgement());
        assertThat(count("deck", "owner_id", actor)).isEqualTo(1);
        assertThatThrownBy(() -> service.create(actor, new DeckCommand(command.commandId(), "other", "")))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> service.create(UUID.randomUUID(), command)).isInstanceOf(IdempotencyConflictException.class);
        JsonNode copy = retry.acknowledgement();
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy).put("privateMutation", true);
        assertThat(retry.acknowledgement().has("privateMutation")).isFalse();
    }

    @Test
    void competingSameBaseWritesPublishExactlyOneAndFailedCommandLeavesNoReceiptOrPins() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID deck = id(service.create(actor, command("original")));
        DeckRecord original = repository.find(actor, deck).orElseThrow();
        DeckCommand left = command("left"), right = command("right");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> race(start, actor, deck, left));
            var b = executor.submit(() -> race(start, actor, deck, right));
            start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("applied", "conflict");
        }
        assertThat(count("deck_revision", "deck_id", deck)).isEqualTo(2);
        assertThat(count("storage_pin", "reuse_scope_id", original.scopeId())).isEqualTo(4);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.command_receipt WHERE command_id IN (:left, :right)")
                .param("left", left.commandId()).param("right", right.commandId()).query(Long.class).single()).isEqualTo(1);
        DeckCommand future = command("future");
        assertThatThrownBy(() -> service.save(actor, deck, 10, future)).isInstanceOf(VersionConflictException.class);
        assertThat(count("command_receipt", "command_id", future.commandId())).isZero();
    }

    @Test
    void transactionRollbackRevertsHeadHistoryPinsAndAcknowledgementTogether() {
        UUID actor = UUID.randomUUID();
        DeckCommand create = command("rollback");
        var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            service.create(actor, create);
            status.setRollbackOnly();
        });
        assertThat(count("deck", "owner_id", actor)).isZero();
        assertThat(count("storage_pin", "actor_id", actor)).isZero();
        assertThat(count("command_receipt", "command_id", create.commandId())).isZero();
        UUID deck = id(service.create(actor, create));
        DeckRecord before = repository.find(actor, deck).orElseThrow();
        DeckCommand save = command("rollback-save");
        transaction.executeWithoutResult(status -> {
            service.save(actor, deck, 0, save);
            status.setRollbackOnly();
        });
        assertThat(repository.find(actor, deck).orElseThrow()).isEqualTo(before);
        assertThat(count("deck_revision", "deck_id", deck)).isEqualTo(1);
        assertThat(count("storage_pin", "reuse_scope_id", before.scopeId())).isEqualTo(2);
        assertThat(count("command_receipt", "command_id", save.commandId())).isZero();
    }

    @Test
    void pagesOnlyOwnDecksAndMetadataDoesNotReorderTheKeyset() {
        UUID actor = UUID.randomUUID();
        for (int i = 0; i < 5; i++) service.create(actor, command("deck " + i));
        UUID foreign = UUID.randomUUID();
        UUID foreignDeck = id(service.create(foreign, command("foreign")));
        JsonNode first = service.list(actor, "2", null);
        assertThat(first.path("items").size()).isEqualTo(2);
        String cursor = first.path("nextCursor").textValue();
        UUID selected = UUID.fromString(first.path("items").get(1).path("deckId").textValue());
        service.save(actor, selected, 0, command("changed"));
        JsonNode second = service.list(actor, "2", cursor);
        JsonNode third = service.list(actor, "2", second.path("nextCursor").textValue());
        assertThat(third.path("items").size()).isEqualTo(1);
        assertThat(third.path("nextCursor").isNull()).isTrue();
        var ids = new java.util.HashSet<String>();
        for (JsonNode page : List.of(first, second, third)) page.path("items").forEach(row -> assertThat(ids.add(row.path("deckId").textValue())).isTrue());
        assertThat(ids).hasSize(5).doesNotContain(foreignDeck.toString());
        assertThat(service.list(UUID.randomUUID(), null, cursor).path("items").isEmpty()).isTrue();
        assertThat(service.list(actor, "100", new DeckCursor(Instant.parse("9999-01-01T00:00:00Z"), foreignDeck).encode()).path("items").size()).isEqualTo(5);
    }

    @Test
    void databaseRejectsImmutableHistoryCrossDeckHeadAndPublishedPinRelease() {
        UUID actor = UUID.randomUUID();
        UUID deck = id(service.create(actor, command("one")));
        UUID foreign = id(service.create(UUID.randomUUID(), command("two")));
        DeckRecord before = repository.find(actor, deck).orElseThrow();
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck_revision SET title = 'changed' WHERE deck_id = :deck")
                .param("deck", deck).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM app_learning.storage_pin WHERE reuse_scope_id = :scope")
                .param("scope", before.scopeId()).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                jdbc.sql("""
                        UPDATE app_learning.deck SET head_revision_id = (SELECT head_revision_id FROM app_learning.deck WHERE deck_id = :foreign),
                            row_version = row_version + 1 WHERE deck_id = :deck
                        """).param("foreign", foreign).param("deck", deck).update()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.deck SET owner_id = :owner, row_version = row_version + 1 WHERE deck_id = :deck")
                .param("owner", UUID.randomUUID()).param("deck", deck).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.find(actor, deck).orElseThrow()).isEqualTo(before);
    }

    private String race(CountDownLatch start, UUID actor, UUID deck, DeckCommand command) throws InterruptedException {
        start.await();
        try { service.save(actor, deck, 0, command); return "applied"; }
        catch (VersionConflictException expected) { return "conflict"; }
    }
    private static DeckCommand command(String title) { return new DeckCommand(UUID.randomUUID(), title, "описание"); }
    private static UUID id(DeckService.WriteResult result) { return UUID.fromString(result.acknowledgement().path("deck").path("deckId").textValue()); }
    private long count(String table, String column, UUID value) {
        // Test-owned fixed identifiers only; application queries never interpolate request input.
        return jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE " + column + " = :value")
                .param("value", value).query(Long.class).single();
    }
}
