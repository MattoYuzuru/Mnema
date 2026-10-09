package app.mnema.learning.billing;

import app.mnema.learning.usage.Plan;
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
 * SQL of {@code billing_order} and {@code billing_event}. Time is always passed in (the usage clock), never read from the database. No method opens a
 * transaction: the services do, and the rows that decide money ({@link #lock}) are read under {@code FOR UPDATE}. Nothing here selects or logs a payment
 * link, an amount or a credential into a message.
 */
@Repository
class BillingRepository {
    /** One row of the audit; {@code orderId} and {@code paymentId} may be null. */
    record Event(UUID orderId, String paymentId, String source, String bankStatus, Boolean success, String errorCode, Long amountKopecks,
                 String outcome) { }

    private static final String COLUMNS = "order_id,owner_id,plan,status,amount_kopecks,list_price_kopecks,discount_percent,discount_code_id,"
            + "payment_id,payment_url,provider_status,failure_reason,snapshot_id,period_start,period_end,paid_at,expires_at,created_at,updated_at,"
            + "last_checked_at,row_version";

    private final JdbcClient jdbc;

    BillingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Timestamp time(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static BillingOrder order(ResultSet row, int number) throws SQLException {
        return new BillingOrder(row.getObject("order_id", UUID.class), row.getObject("owner_id", UUID.class), Plan.valueOf(row.getString("plan")),
                OrderStatus.valueOf(row.getString("status")), row.getLong("amount_kopecks"), row.getLong("list_price_kopecks"),
                (Integer) row.getObject("discount_percent"), row.getObject("discount_code_id", UUID.class), row.getString("payment_id"),
                row.getString("payment_url"), row.getString("provider_status"), row.getString("failure_reason"), row.getString("snapshot_id"),
                instant(row, "period_start"), instant(row, "period_end"), instant(row, "paid_at"), instant(row, "expires_at"),
                instant(row, "created_at"), instant(row, "updated_at"), instant(row, "last_checked_at"), row.getLong("row_version"));
    }

    /**
     * Serializes one kind of decision about one account until the transaction ends: {@code checkout} (the rate limit and the reuse of an open order are
     * decided one request at a time) and {@code grant} (two paid orders of one plan must not start the same month).
     */
    void lockAccount(String scope, UUID owner) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) lock").param("key", "billing." + scope + ":" + owner)
                .query(Integer.class).single();
    }

    void insert(BillingOrder order) {
        jdbc.sql("INSERT INTO app_learning.billing_order(order_id,owner_id,plan,period,status,amount_kopecks,list_price_kopecks,discount_percent,"
                        + "discount_code_id,expires_at,created_at,updated_at) VALUES (:id,:owner,:plan,'MONTH',:status,:amount,:list,:percent,:code,"
                        + ":expires,:created,:created)")
                .param("id", order.orderId()).param("owner", order.owner()).param("plan", order.plan().name()).param("status", order.status().name())
                .param("amount", order.amountKopecks()).param("list", order.listPriceKopecks())
                .param("percent", order.discountPercent(), java.sql.Types.INTEGER).param("code", order.discountCodeId(), java.sql.Types.OTHER)
                .param("expires", time(order.expiresAt())).param("created", time(order.createdAt())).update();
    }

    Optional<BillingOrder> find(UUID orderId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.billing_order WHERE order_id=:id").param("id", orderId)
                .query(BillingRepository::order).optional();
    }

    /** The order under a row lock: concurrent notifications, return-page reads and the reconciler of one order take turns here. */
    Optional<BillingOrder> lock(UUID orderId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.billing_order WHERE order_id=:id FOR UPDATE").param("id", orderId)
                .query(BillingRepository::order).optional();
    }

    /** An unfinished order of the same purchase that still has {@code until} left on its link: the next checkout reuses it. */
    Optional<BillingOrder> openOrder(UUID owner, Plan plan, long amountKopecks, Instant until) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.billing_order WHERE owner_id=:owner AND plan=:plan AND period='MONTH' "
                        + "AND amount_kopecks=:amount AND status IN ('CREATED','PENDING') AND expires_at>:until ORDER BY created_at DESC LIMIT 1")
                .param("owner", owner).param("plan", plan.name()).param("amount", amountKopecks).param("until", time(until))
                .query(BillingRepository::order).optional();
    }

    /** An unfinished order of {@code owner} that still holds the discount of {@code codeId} and whose link has not expired. */
    Optional<BillingOrder> openDiscountHolder(UUID owner, UUID codeId, Instant now) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.billing_order WHERE owner_id=:owner AND discount_code_id=:code "
                        + "AND status IN ('CREATED','PENDING') AND expires_at>:now").param("owner", owner).param("code", codeId).param("now", time(now))
                .query(BillingRepository::order).optional();
    }

    /**
     * Frees a discount held by unfinished orders whose link time is over: they are closed {@code FAILED/EXPIRED}. A payment that still reaches such an order
     * is granted all the same ({@code PaymentStateApplier}), so closing it loses no money, and the discount can price the next purchase.
     */
    int expireDiscountHolders(UUID owner, UUID codeId, Instant now) {
        return jdbc.sql("UPDATE app_learning.billing_order SET status='FAILED',failure_reason='EXPIRED',updated_at=:now,row_version=row_version+1 "
                        + "WHERE owner_id=:owner AND discount_code_id=:code AND status IN ('CREATED','PENDING') AND expires_at<=:now")
                .param("owner", owner).param("code", codeId).param("now", time(now)).update();
    }

    /**
     * Takes the right to call {@code Init} for a {@code CREATED} order: one caller per {@code staleBefore} window, so two retries cannot open two bank payments.
     *
     * @return whether this caller won
     */
    boolean claimInit(UUID orderId, Instant now, Instant staleBefore) {
        return jdbc.sql("UPDATE app_learning.billing_order SET init_claimed_at=:now WHERE order_id=:id AND status='CREATED' "
                        + "AND (init_claimed_at IS NULL OR init_claimed_at<:stale)").param("now", time(now)).param("id", orderId)
                .param("stale", time(staleBefore)).update() == 1;
    }

    /** Gives the claim back after a bank refusal: nothing was opened, so a retry may ask at once. */
    void releaseInit(UUID orderId) {
        jdbc.sql("UPDATE app_learning.billing_order SET init_claimed_at=NULL WHERE order_id=:id").param("id", orderId).update();
    }

    long createdSince(UUID owner, Instant since) {
        return jdbc.sql("SELECT count(*) FROM app_learning.billing_order WHERE owner_id=:owner AND created_at>:since")
                .param("owner", owner).param("since", time(since)).query(Long.class).single();
    }

    Optional<Instant> oldestCreatedSince(UUID owner, Instant since) {
        return jdbc.sql("SELECT min(created_at) FROM app_learning.billing_order WHERE owner_id=:owner AND created_at>:since")
                .param("owner", owner).param("since", time(since)).query(Timestamp.class).optional().map(Timestamp::toInstant);
    }

    /** The latest end of the account's paid months of {@code plan} that is still in the future: a second payment starts there. */
    Optional<Instant> latestPaidPeriodEnd(UUID owner, Plan plan, Instant now) {
        return jdbc.sql("SELECT max(period_end) FROM app_learning.billing_order WHERE owner_id=:owner AND plan=:plan AND status='PAID' "
                        + "AND period_end>:now").param("owner", owner).param("plan", plan.name()).param("now", time(now))
                .query(Timestamp.class).optional().map(Timestamp::toInstant);
    }

    /** Writes every mutable column of {@code order} (the caller holds the row lock) and bumps the version. */
    void save(BillingOrder order, Instant now) {
        jdbc.sql("UPDATE app_learning.billing_order SET status=:status,payment_id=:payment,payment_url=:url,provider_status=:provider,"
                        + "failure_reason=:failure,snapshot_id=:snapshot,period_start=:start,period_end=:end,paid_at=:paid,updated_at=:now,"
                        + "row_version=row_version+1 WHERE order_id=:id")
                .param("status", order.status().name()).param("payment", order.paymentId(), java.sql.Types.VARCHAR)
                .param("url", order.paymentUrl(), java.sql.Types.VARCHAR).param("provider", order.providerStatus(), java.sql.Types.VARCHAR)
                .param("failure", order.failureReason(), java.sql.Types.VARCHAR).param("snapshot", order.snapshotId(), java.sql.Types.VARCHAR)
                .param("start", time(order.periodStart()), java.sql.Types.TIMESTAMP).param("end", time(order.periodEnd()), java.sql.Types.TIMESTAMP)
                .param("paid", time(order.paidAt()), java.sql.Types.TIMESTAMP).param("now", time(now)).param("id", order.orderId()).update();
    }

    /**
     * Takes the right to ask the bank about a pending order: only one caller wins per interval, however many browsers poll.
     *
     * @return whether this caller won
     */
    boolean claimRefresh(UUID orderId, Instant now, Instant staleBefore) {
        return jdbc.sql("UPDATE app_learning.billing_order SET last_checked_at=:now WHERE order_id=:id AND status='PENDING' AND payment_id IS NOT NULL "
                        + "AND (last_checked_at IS NULL OR last_checked_at<:stale)")
                .param("now", time(now)).param("id", orderId).param("stale", time(staleBefore)).update() == 1;
    }

    /**
     * Claims up to {@code limit} unfinished orders for the reconciler: created before {@code createdBefore}, not looked at since {@code checkedBefore}.
     * {@code FOR UPDATE SKIP LOCKED} lets several workers claim disjoint sets; the claim stamps {@code last_checked_at}.
     */
    List<BillingOrder> claimOpen(Instant now, Instant createdBefore, Instant checkedBefore, int limit) {
        List<BillingOrder> claimed = jdbc.sql("SELECT " + COLUMNS + " FROM app_learning.billing_order WHERE status IN ('CREATED','PENDING') "
                        + "AND created_at<:created AND (last_checked_at IS NULL OR last_checked_at<:checked) ORDER BY created_at LIMIT :limit "
                        + "FOR UPDATE SKIP LOCKED")
                .param("created", time(createdBefore)).param("checked", time(checkedBefore)).param("limit", limit)
                .query(BillingRepository::order).list();
        for (BillingOrder order : claimed) {
            jdbc.sql("UPDATE app_learning.billing_order SET last_checked_at=:now WHERE order_id=:id").param("now", time(now))
                    .param("id", order.orderId()).update();
        }
        return claimed;
    }

    /** @return whether the event was new; a repeated notification of the same payment and status is not stored twice */
    boolean insertEvent(Event event, Instant now) {
        return jdbc.sql("INSERT INTO app_learning.billing_event(order_id,payment_id,source,bank_status,success,error_code,amount_kopecks,outcome,"
                        + "received_at) VALUES (:order,:payment,:source,:status,:success,:code,:amount,:outcome,:now) ON CONFLICT DO NOTHING")
                .param("order", event.orderId(), java.sql.Types.OTHER).param("payment", event.paymentId(), java.sql.Types.VARCHAR)
                .param("source", event.source()).param("status", event.bankStatus(), java.sql.Types.VARCHAR)
                .param("success", event.success(), java.sql.Types.BOOLEAN).param("code", event.errorCode(), java.sql.Types.VARCHAR)
                .param("amount", event.amountKopecks(), java.sql.Types.BIGINT).param("outcome", event.outcome(), java.sql.Types.VARCHAR)
                .param("now", time(now)).update() == 1;
    }
}
