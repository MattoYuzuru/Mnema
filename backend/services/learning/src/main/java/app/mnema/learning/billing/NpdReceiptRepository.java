package app.mnema.learning.billing;

import app.mnema.learning.billing.NpdReceipt.State;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL of {@code billing_receipt}. Time is always passed in (the usage clock). No method opens a transaction: {@link NpdReceipts} does, and a row that is
 * changed is read under {@code FOR UPDATE} first. Nothing here selects a credential; the receipt carries no personal data.
 */
@Repository
class NpdReceiptRepository {
    /** A row taken by the worker, and the state it was in before the claim ({@code PENDING}: never sent; {@code SENDING}: look it up first). */
    record Claim(NpdReceipt receipt, State previous) { }

    private static final String COLUMNS = "order_id,state,service_name,amount_kopecks,operation_time,deadline_at,receipt_uuid,cancel_requested,attempts,"
            + "next_attempt_at,last_error_code,failed_alerted_at,overdue_alerted_at,created_at,updated_at,row_version";

    private final JdbcClient jdbc;

    NpdReceiptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Timestamp time(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static NpdReceipt receipt(ResultSet row, int number) throws SQLException {
        return new NpdReceipt(row.getObject("order_id", UUID.class), State.valueOf(row.getString("state")), row.getString("service_name"),
                row.getLong("amount_kopecks"), instant(row, "operation_time"), instant(row, "deadline_at"), row.getString("receipt_uuid"),
                row.getBoolean("cancel_requested"), row.getInt("attempts"), instant(row, "next_attempt_at"), row.getString("last_error_code"),
                instant(row, "failed_alerted_at"), instant(row, "overdue_alerted_at"), instant(row, "created_at"), instant(row, "updated_at"),
                row.getLong("row_version"));
    }

    /** @return whether the receipt was new; an order has at most one */
    boolean insert(NpdReceipt receipt) {
        return jdbc.sql("INSERT INTO app_learning.billing_receipt(order_id,state,service_name,amount_kopecks,operation_time,deadline_at,next_attempt_at,"
                        + "created_at,updated_at) VALUES (:id,:state,:name,:amount,:operation,:deadline,:next,:created,:created) ON CONFLICT DO NOTHING")
                .param("id", receipt.orderId()).param("state", receipt.state().name()).param("name", receipt.serviceName())
                .param("amount", receipt.amountKopecks()).param("operation", time(receipt.operationTime())).param("deadline", time(receipt.deadlineAt()))
                .param("next", time(receipt.nextAttemptAt())).param("created", time(receipt.createdAt())).update() == 1;
    }

    Optional<NpdReceipt> find(UUID orderId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.billing_receipt WHERE order_id=:id").param("id", orderId)
                .query(NpdReceiptRepository::receipt).optional();
    }

    Optional<NpdReceipt> lock(UUID orderId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.billing_receipt WHERE order_id=:id FOR UPDATE").param("id", orderId)
                .query(NpdReceiptRepository::receipt).optional();
    }

    /** Writes every mutable column of {@code receipt} (the caller holds the row lock) and bumps the version. */
    void save(NpdReceipt receipt, Instant now) {
        jdbc.sql("UPDATE app_learning.billing_receipt SET state=:state,receipt_uuid=:uuid,cancel_requested=:cancel,attempts=:attempts,"
                        + "next_attempt_at=:next,last_error_code=:code,failed_alerted_at=:failed,overdue_alerted_at=:overdue,updated_at=:now,"
                        + "row_version=row_version+1 WHERE order_id=:id")
                .param("state", receipt.state().name()).param("uuid", receipt.receiptUuid(), java.sql.Types.VARCHAR)
                .param("cancel", receipt.cancelRequested()).param("attempts", receipt.attempts()).param("next", time(receipt.nextAttemptAt()))
                .param("code", receipt.lastErrorCode(), java.sql.Types.VARCHAR).param("failed", time(receipt.failedAlertedAt()), java.sql.Types.TIMESTAMP)
                .param("overdue", time(receipt.overdueAlertedAt()), java.sql.Types.TIMESTAMP).param("now", time(now)).param("id", receipt.orderId()).update();
    }

    /**
     * Takes up to {@code limit} receipts that are due. In one statement the rows are locked ({@code FOR UPDATE SKIP LOCKED}, so several workers take disjoint
     * sets), their attempt is counted, their lease runs until {@code leaseUntil} and a {@code PENDING} row becomes {@code SENDING} — the mark that a request may
     * be on the wire, persisted before the request is made so that a crash cannot lead to a blind second send.
     */
    List<Claim> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("WITH due AS (SELECT order_id, state AS previous FROM app_learning.billing_receipt WHERE state IN ('PENDING','SENDING','CANCEL_PENDING') "
                        + "AND next_attempt_at<=:now ORDER BY next_attempt_at LIMIT :limit FOR UPDATE SKIP LOCKED) "
                        + "UPDATE app_learning.billing_receipt r SET attempts=r.attempts+1, next_attempt_at=:lease, "
                        + "state=CASE WHEN r.state='PENDING' THEN 'SENDING' ELSE r.state END, updated_at=:now, row_version=r.row_version+1 "
                        + "FROM due WHERE r.order_id=due.order_id RETURNING r.order_id,r.state,r.service_name,r.amount_kopecks,r.operation_time,r.deadline_at,"
                        + "r.receipt_uuid,r.cancel_requested,r.attempts,r.next_attempt_at,r.last_error_code,r.failed_alerted_at,r.overdue_alerted_at,"
                        + "r.created_at,r.updated_at,r.row_version,due.previous")
                .param("now", time(now)).param("lease", time(leaseUntil)).param("limit", limit)
                .query((row, number) -> new Claim(receipt(row, number), State.valueOf(row.getString("previous")))).list();
    }

    /** Receipts still waiting to be registered whose legal deadline has passed and that nobody was told about yet, under their row locks. */
    List<NpdReceipt> lockOverdue(Instant now, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.billing_receipt WHERE state IN ('PENDING','SENDING') AND overdue_alerted_at IS NULL "
                        + "AND deadline_at<=:now ORDER BY deadline_at LIMIT :limit FOR UPDATE SKIP LOCKED")
                .param("now", time(now)).param("limit", limit).query(NpdReceiptRepository::receipt).list();
    }

    /** Receipt numbers of {@code candidates} that are the stored receipt of an order other than {@code orderId}. */
    List<String> receiptsOfOtherOrders(UUID orderId, java.util.Collection<String> candidates) {
        return jdbc.sql("SELECT receipt_uuid FROM app_learning.billing_receipt WHERE receipt_uuid IN (:uuids) AND order_id<>:id")
                .param("uuids", candidates).param("id", orderId).query(String.class).list();
    }

    /**
     * PAID orders paid in the window {@code (since, paidBefore)}, newest first, without a registered (or annulled) receipt: orders the daily check reports.
     * Older orders are out of the window on purpose; an operator closes out the ones paid before V47 (guide).
     */
    List<UUID> paidWithoutReceipt(Instant since, Instant paidBefore, int limit) {
        return jdbc.sql("SELECT o.order_id FROM app_learning.billing_order o LEFT JOIN app_learning.billing_receipt r ON r.order_id=o.order_id "
                        + "WHERE o.status='PAID' AND o.paid_at>:since AND o.paid_at<:before "
                        + "AND (r.order_id IS NULL OR r.state NOT IN ('REGISTERED','CANCEL_PENDING','CANCELLED')) ORDER BY o.paid_at DESC LIMIT :limit")
                .param("since", time(since)).param("before", time(paidBefore)).param("limit", limit).query(UUID.class).list();
    }

    /** REFUNDED orders changed since {@code since}, newest first, whose receipt is not annulled (or does not exist). */
    List<UUID> refundedWithoutCancellation(Instant since, int limit) {
        return jdbc.sql("SELECT o.order_id FROM app_learning.billing_order o LEFT JOIN app_learning.billing_receipt r ON r.order_id=o.order_id "
                        + "WHERE o.status='REFUNDED' AND o.updated_at>:since AND (r.order_id IS NULL OR r.state<>'CANCELLED') ORDER BY o.updated_at DESC LIMIT :limit")
                .param("since", time(since)).param("limit", limit).query(UUID.class).list();
    }
}
