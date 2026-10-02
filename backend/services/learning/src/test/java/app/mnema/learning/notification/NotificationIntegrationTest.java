package app.mnema.learning.notification;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The publisher, the owner-scoped reads and commands and the retention worker against real PostgreSQL. */
@SpringBootTest
class NotificationIntegrationTest extends PostgresIntegrationTest {
    @Autowired private NotificationPublisher publisher;
    @Autowired private NotificationService service;
    @Autowired private NotificationRetentionService retention;
    @Autowired private NotificationSettings settings;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcClient jdbc;

    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }

    private boolean publishReady(UUID owner, String key, int plannedCount) {
        return tx().execute(status -> publisher.publish(owner, NotificationKind.GENERATION_PLAN_READY, key,
                planReady(plannedCount), NotificationRoute.WORKSHOP));
    }

    private static Map<String, Object> planReady(int plannedCount) {
        Map<String, Object> params = new HashMap<>();
        params.put("deckId", UUID.randomUUID());
        params.put("sessionId", UUID.randomUUID());
        params.put("sessionKind", "MATERIALS");
        params.put("plannedCount", plannedCount);
        return params;
    }

    private NotificationService.Listing list(UUID owner, int limit, Long after, String cursor, String etag) {
        return service.list(owner, limit, after, NotificationCursor.decode(cursor), etag);
    }

    private static List<String> seqs(NotificationService.Listing listing) {
        return listing.page().items().stream().map(NotificationService.View::seq).toList();
    }

    @Test
    void publishingNeedsTheCallersTransaction() {
        UUID owner = UUID.randomUUID();
        assertThatThrownBy(() -> publisher.publish(owner, NotificationKind.GENERATION_PLAN_READY, "k", planReady(1),
                NotificationRoute.WORKSHOP)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count(owner)).isZero();
    }

    @Test
    void aRollbackOfTheDomainTransactionLeavesNoNotificationAndNoCursor() {
        UUID owner = UUID.randomUUID();
        tx().executeWithoutResult(status -> {
            publisher.publish(owner, NotificationKind.GENERATION_PLAN_READY, "k", planReady(1), NotificationRoute.WORKSHOP);
            status.setRollbackOnly();
        });
        assertThat(count(owner)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.notification_cursor WHERE owner_id=:o")
                .param("o", owner).query(Long.class).single()).isZero();
    }

    @Test
    void aRepeatedDedupeKeyIsANoOpThatKeepsTheFirstAndConsumesNoSeq() {
        UUID owner = UUID.randomUUID();
        Map<String, Object> first = planReady(3);
        Boolean created = tx().execute(s -> publisher.publish(owner, NotificationKind.GENERATION_PLAN_READY,
                "generation:a:plan-ready", first, NotificationRoute.WORKSHOP));
        assertThat(created).isTrue();
        assertThat(publishReady(owner, "generation:a:plan-ready", 9)).isFalse();
        assertThat(publishReady(owner, "generation:b:plan-ready", 4)).isTrue();

        var page = list(owner, 20, null, null, null).page();
        assertThat(page.items()).extracting(NotificationService.View::seq).containsExactly("2", "1");
        assertThat(page.items().get(1).params().path("plannedCount").intValue()).isEqualTo(3);
        assertThat(page.items().get(1).params().path("deckId").stringValue(null)).isEqualTo(first.get("deckId").toString());
    }

    @Test
    void concurrentPublishesOfOneOwnerGetUniqueContiguousSequenceValues() throws Exception {
        UUID owner = UUID.randomUUID();
        int publishers = 24;
        var ready = new CountDownLatch(publishers);
        var go = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> results = new java.util.ArrayList<>();
            for (int i = 0; i < publishers; i++) {
                String key = "concurrent:" + i;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return publishReady(owner, key, 1);
                }));
            }
            ready.await();
            go.countDown();
            for (var result : results) assertThat(result.get()).isTrue();
        }
        List<Long> seqs = jdbc.sql("SELECT seq FROM app_learning.notification WHERE owner_id=:o ORDER BY seq")
                .param("o", owner).query(Long.class).list();
        assertThat(seqs).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, publishers).boxed().toList());
        assertThat(jdbc.sql("SELECT last_seq FROM app_learning.notification_cursor WHERE owner_id=:o")
                .param("o", owner).query(Long.class).single()).isEqualTo(publishers);
    }

    @Test
    void concurrentPublishesOfOneDedupeKeyCreateExactlyOneNotification() throws Exception {
        UUID owner = UUID.randomUUID();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new java.util.ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) results.add(executor.submit(() -> publishReady(owner, "same", 1)));
            long created = 0;
            for (var result : results) if (result.get()) created++;
            assertThat(created).isEqualTo(1);
        }
        assertThat(count(owner)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT last_seq FROM app_learning.notification_cursor WHERE owner_id=:o")
                .param("o", owner).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void newestFirstListPagesTowardsOlderAndCatchUpPagesTowardsNewerInAscendingOrder() {
        UUID owner = UUID.randomUUID();
        for (int i = 1; i <= 5; i++) publishReady(owner, "k" + i, i);

        var first = list(owner, 2, null, null, null).page();
        assertThat(first.items()).extracting(NotificationService.View::seq).containsExactly("5", "4");
        assertThat(first.nextCursor()).isNotNull();
        var second = list(owner, 2, null, first.nextCursor(), null).page();
        assertThat(second.items()).extracting(NotificationService.View::seq).containsExactly("3", "2");
        var last = list(owner, 2, null, second.nextCursor(), null).page();
        assertThat(last.items()).extracting(NotificationService.View::seq).containsExactly("1");
        assertThat(last.nextCursor()).isNull();

        var catchUp = list(owner, 2, 1L, null, null).page();
        assertThat(catchUp.items()).extracting(NotificationService.View::seq).containsExactly("2", "3");
        var more = list(owner, 2, null, catchUp.nextCursor(), null).page();
        assertThat(more.items()).extracting(NotificationService.View::seq).containsExactly("4", "5");
        assertThat(more.nextCursor()).isNull();
        assertThat(list(owner, 20, 5L, null, null).page().items()).isEmpty();

        assertThatThrownBy(() -> service.list(owner, 20, 1L, NotificationCursor.decode(first.nextCursor()), null))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(first.unreadCount()).isEqualTo(5);
        assertThat(first.readUpto()).isEqualTo("0");
        assertThat(first.activeWork()).isZero();
    }

    @Test
    void theValidatorChangesWithEveryRepresentationChangeAndAMatchIsNotModified() {
        UUID owner = UUID.randomUUID();
        String empty = list(owner, 20, null, null, null).etag();
        publishReady(owner, "a", 1);
        publishReady(owner, "b", 1);
        var listing = list(owner, 20, null, null, null);
        assertThat(listing.etag()).isNotEqualTo(empty).startsWith("\"").endsWith("\"");
        assertThat(list(owner, 20, null, null, listing.etag()).page()).isNull();
        assertThat(list(owner, 20, null, null, "\"x\", W/" + listing.etag()).page()).isNull();
        assertThat(list(owner, 20, null, null, "*").page()).isNull();
        assertThat(list(owner, 20, null, null, "\"other\"").page()).isNotNull();
        // the same state under another query is another representation
        assertThat(list(owner, 1, null, null, listing.etag()).page()).isNotNull();
        assertThat(list(owner, 20, 0L, null, listing.etag()).page()).isNotNull();

        String before = listing.etag();
        var read = service.advanceReadCursor(owner, 1);
        assertThat(read.unreadCount()).isEqualTo(1);
        String afterRead = list(owner, 20, null, null, null).etag();
        assertThat(afterRead).isNotEqualTo(before);

        UUID newest = UUID.fromString(list(owner, 20, null, null, null).page().items().getFirst().notificationId().toString());
        service.dismiss(owner, newest);
        String afterDismiss = list(owner, 20, null, null, null).etag();
        assertThat(afterDismiss).isNotEqualTo(afterRead);

        publishReady(owner, "c", 1);
        String afterPublish = list(owner, 20, null, null, null).etag();
        assertThat(afterPublish).isNotEqualTo(afterDismiss);

        // an expiry changes the representation although nobody wrote through the API
        jdbc.sql("UPDATE app_learning.notification SET created_at=CURRENT_TIMESTAMP-interval '31 days',"
                        + "expires_at=CURRENT_TIMESTAMP-interval '1 second' WHERE owner_id=:o AND seq=1")
                .param("o", owner).update();
        var expired = list(owner, 20, null, null, afterPublish);
        assertThat(expired.page()).isNotNull();
        assertThat(expired.etag()).isNotEqualTo(afterPublish);
        assertThat(seqs(expired)).containsExactly("3");
    }

    @Test
    void theEarliestExpiryBoundaryIsPartOfTheValidator() {
        UUID owner = UUID.randomUUID();
        publishReady(owner, "a", 1);
        publishReady(owner, "b", 1);
        String before = list(owner, 20, null, null, null).etag();
        jdbc.sql("UPDATE app_learning.notification SET expires_at=expires_at-interval '1 day' WHERE owner_id=:o AND seq=1")
                .param("o", owner).update();
        assertThat(list(owner, 20, null, null, null).etag()).isNotEqualTo(before);
    }

    @Test
    void theReadCursorIsAMonotonicMaximumAndNeverPassesTheLatestSeq() {
        UUID owner = UUID.randomUUID();
        assertThat(service.advanceReadCursor(owner, 0)).isEqualTo(new NotificationService.ReadCursor("0", 0));
        assertThatThrownBy(() -> service.advanceReadCursor(owner, 1)).isInstanceOf(InvalidRequestException.class);
        for (int i = 1; i <= 4; i++) publishReady(owner, "k" + i, 1);

        assertThat(service.advanceReadCursor(owner, 3)).isEqualTo(new NotificationService.ReadCursor("3", 1));
        assertThat(service.advanceReadCursor(owner, 1)).isEqualTo(new NotificationService.ReadCursor("3", 1));
        assertThat(service.advanceReadCursor(owner, 3)).isEqualTo(new NotificationService.ReadCursor("3", 1));
        assertThatThrownBy(() -> service.advanceReadCursor(owner, 5)).isInstanceOf(InvalidRequestException.class);
        assertThat(service.advanceReadCursor(owner, 4)).isEqualTo(new NotificationService.ReadCursor("4", 0));
        assertThat(list(owner, 20, null, null, null).page().readUpto()).isEqualTo("4");
    }

    @Test
    void dismissIsIdempotentHidesTheNotificationAndTreatsForeignAndAbsentAsNotFound() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        publishReady(owner, "a", 1);
        publishReady(owner, "b", 1);
        UUID id = list(owner, 20, null, null, null).page().items().getFirst().notificationId();

        service.dismiss(owner, id);
        service.dismiss(owner, id);
        var page = list(owner, 20, null, null, null).page();
        assertThat(page.items()).extracting(NotificationService.View::seq).containsExactly("1");
        assertThat(page.unreadCount()).isEqualTo(1);
        assertThatThrownBy(() -> service.dismiss(stranger, id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.dismiss(owner, UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
        // a dismissed row still holds its dedupe key: the first notification stands
        assertThat(publishReady(owner, "b", 1)).isFalse();
        assertThat(list(owner, 20, null, null, null).page().items()).hasSize(1);
    }

    @Test
    void anExpiredNotificationCannotBeDismissedAndReleasesItsDedupeKey() {
        UUID owner = UUID.randomUUID();
        publishReady(owner, "k", 1);
        UUID id = list(owner, 20, null, null, null).page().items().getFirst().notificationId();
        expire(owner, 1);
        assertThatThrownBy(() -> service.dismiss(owner, id)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(publishReady(owner, "k", 2)).isTrue();
        assertThat(list(owner, 20, null, null, null).page().items()).extracting(NotificationService.View::seq)
                .containsExactly("2");
    }

    @Test
    void expiryIsExactlyThirtyDaysAfterCreationAndTheWorkerDeletesOnlyExpiredRows() {
        UUID owner = UUID.randomUUID();
        publishReady(owner, "old", 1);
        publishReady(owner, "new", 1);
        assertThat(jdbc.sql("SELECT bool_and(expires_at=created_at+interval '720 hours') FROM app_learning.notification "
                + "WHERE owner_id=:o").param("o", owner).query(Boolean.class).single()).isTrue();
        assertThat(settings.retention.toDays()).isEqualTo(30);
        expire(owner, 1);

        int deleted = 0;
        for (int batch = 0; batch < 50 && deleted == 0; batch++) deleted = retention.purgeBatch();
        assertThat(deleted).isPositive();
        assertThat(count(owner)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT seq FROM app_learning.notification WHERE owner_id=:o").param("o", owner)
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void theTwoHundredFirstNotificationEvictsTheOldestAndKeepsSequenceValuesIncreasing() {
        UUID owner = UUID.randomUUID();
        assertThat(settings.maxPerAccount).isEqualTo(200);
        for (int i = 1; i <= 201; i++) publishReady(owner, "k" + i, 1);
        assertThat(count(owner)).isEqualTo(200);
        assertThat(jdbc.sql("SELECT min(seq) FROM app_learning.notification WHERE owner_id=:o").param("o", owner)
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT max(seq) FROM app_learning.notification WHERE owner_id=:o").param("o", owner)
                .query(Long.class).single()).isEqualTo(201);
        // the evicted notification released its key; the new one takes the next seq
        assertThat(publishReady(owner, "k1", 1)).isTrue();
        assertThat(list(owner, 1, null, null, null).page().items().getFirst().seq()).isEqualTo("202");
        assertThat(count(owner)).isEqualTo(200);
    }

    @Test
    void producersCannotStoreProseOrAnythingBeyondTheKindsFields() {
        UUID owner = UUID.randomUUID();
        Map<String, Object> prose = planReady(1);
        prose.put("sessionKind", "Моя колода про кошек");
        Map<String, Object> extra = planReady(1);
        extra.put("title", "x");
        Map<String, Object> negative = planReady(-1);
        Map<String, Object> missing = planReady(1);
        missing.remove("deckId");
        Map<String, Object> nullDeck = planReady(1);
        nullDeck.put("deckId", null);
        Map<String, Object> text = planReady(1);
        text.put("deckId", "11111111-1111-4111-8111-111111111111");
        for (Map<String, Object> params : List.of(prose, extra, negative, missing, nullDeck, text)) {
            assertThatThrownBy(() -> tx().executeWithoutResult(s -> publisher.publish(owner,
                    NotificationKind.GENERATION_PLAN_READY, "k", params, NotificationRoute.WORKSHOP)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> publisher.publish(owner,
                NotificationKind.GENERATION_PLAN_READY, "k", planReady(1), NotificationRoute.DECK)))
                .isInstanceOf(IllegalArgumentException.class);
        for (String key : new String[] {"", "has space", "x".repeat(201), null}) {
            assertThatThrownBy(() -> tx().executeWithoutResult(s -> publisher.publish(owner,
                    NotificationKind.GENERATION_PLAN_READY, key, planReady(1), NotificationRoute.WORKSHOP)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(count(owner)).isZero();
    }

    @Test
    void theDatabaseItselfCapsParamsAndConstrainsShape() {
        UUID owner = UUID.randomUUID();
        tx().executeWithoutResult(s -> jdbc.sql("INSERT INTO app_learning.notification_cursor(owner_id) VALUES (:o)")
                .param("o", owner).update());
        String big = "{\"x\":\"" + "a".repeat(4100) + "\"}";
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.notification(notification_id,owner_id,seq,kind,severity,"
                        + "params,route,dedupe_key,created_at,expires_at) VALUES (:id,:o,1,'USAGE_LOW','INFO',"
                        + "CAST(:p AS jsonb),'PLANS','k',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+interval '1 day')")
                .param("id", UUID.randomUUID()).param("o", owner).param("p", big).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private long count(UUID owner) {
        return jdbc.sql("SELECT count(*) FROM app_learning.notification WHERE owner_id=:o").param("o", owner)
                .query(Long.class).single();
    }

    private void expire(UUID owner, long seq) {
        jdbc.sql("UPDATE app_learning.notification SET created_at=CURRENT_TIMESTAMP-interval '31 days',"
                        + "expires_at=CURRENT_TIMESTAMP-interval '1 second' WHERE owner_id=:o AND seq=:seq")
                .param("o", owner).param("seq", seq).update();
    }
}
