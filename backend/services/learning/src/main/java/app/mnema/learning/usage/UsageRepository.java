package app.mnema.learning.usage;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL of the usage ledger. Time is always passed in (the usage {@code Clock}), never read from the database, so a test
 * can move it. No method opens a transaction: the callers do, and the ones that write need the caller's.
 *
 * <p>TODO(account-deletion task; owner: the epic that adds Learning's account purge): Learning has no account purge
 * path yet, so no usage row is removed when an account is deleted. The rows hold no personal data (an account id and
 * opaque references); the purge must delete by {@code owner_id} in this order: usage_ledger_entry, usage_reservation,
 * usage_balance, usage_allowance, usage_counter, entitlement_inbox. See "Retention" in the Learning guide.
 */
@Repository
class UsageRepository {
    private static final String RESERVATION_COLUMNS = "reservation_id,owner_id,period_id,scope,session_id,turn_id,state,"
            + "held_credits,debited_credits,rate_card_version,created_at,expires_at";
    private static final RowMapper<Reservation> RESERVATION = (row, number) -> new Reservation(
            row.getObject("reservation_id", UUID.class), row.getObject("owner_id", UUID.class),
            ReservationScope.valueOf(row.getString("scope")), row.getObject("session_id", UUID.class),
            row.getObject("turn_id", UUID.class), row.getString("period_id"), ReservationState.valueOf(row.getString("state")),
            row.getInt("held_credits"), row.getInt("debited_credits"), row.getString("rate_card_version"),
            instant(row, "created_at"), instant(row, "expires_at"));

    /** The materialized balance of one period. */
    record BalanceRow(int unlocked, int used, int reserved, long rowVersion, Instant updatedAt) {
        int available() {
            return unlocked - used - reserved;
        }
    }

    /** The allowance row of one period with the entitlement it was granted from. */
    record StoredAllowance(Allowance allowance, Entitlement.Source source, Instant validUntil) { }

    /** One ledger row to append. */
    record LedgerEntry(UUID entryId, UUID owner, String kind, int credits, Long costMicros, String operation,
                       String rateCardVersion, String periodId, String idempotencyKey, UUID reservationId,
                       String reference, Bucket bucket, Long units, Instant createdAt) { }

    /** The part of a ledger row a repeated write is checked against. */
    record LedgerRow(UUID ownerId, String kind, int credits, UUID reservationId, String periodId, String operation,
                     Long costMicros, String reference, String bucket, Long units) { }

    private final JdbcClient jdbc;

    UsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getTimestamp(column).toInstant();
    }

    private static Timestamp time(Instant instant) {
        return Timestamp.from(instant);
    }

    // ---------------------------------------------------------------- deck ACL

    boolean deckOwned(UUID owner, UUID deck) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_learning.deck WHERE owner_id=:owner AND deck_id=:deck AND deleted_at IS NULL)")
                .param("owner", owner).param("deck", deck).query(Boolean.class).single();
    }

    // --------------------------------------------------------------- allowance

    Optional<StoredAllowance> allowance(UUID owner, String periodId) {
        return jdbc.sql("SELECT plan,source,valid_until,credits_total,portions,burst_fraction,stt_month_seconds,"
                        + "stt_day_seconds,assessment_month,assessment_day,cap_podcasts,cap_quality_images,"
                        + "cap_high_factcheck,cap_smart_plan,smart_plan_window FROM app_learning.usage_allowance "
                        + "WHERE owner_id=:owner AND period_id=:period")
                .param("owner", owner).param("period", periodId).query((row, number) -> {
                    String portions = row.getString("portions");
                    BigDecimal burst = row.getBigDecimal("burst_fraction");
                    long sttMonth = row.getLong("stt_month_seconds");
                    Long sttMonthSeconds = row.wasNull() ? null : sttMonth;
                    var allowance = new Allowance(Plan.valueOf(row.getString("plan")), row.getInt("credits_total"),
                            portions == null ? null : Arrays.stream(portions.split(",")).map(Integer::valueOf).toList(),
                            burst == null ? null : burst.stripTrailingZeros(), sttMonthSeconds,
                            row.getLong("stt_day_seconds"), row.getLong("assessment_month"),
                            row.getLong("assessment_day"), row.getInt("cap_podcasts"), row.getInt("cap_quality_images"),
                            row.getInt("cap_high_factcheck"), row.getInt("cap_smart_plan"),
                            Window.valueOf(row.getString("smart_plan_window")));
                    return new StoredAllowance(allowance, Entitlement.Source.valueOf(row.getString("source")),
                            instant(row, "valid_until"));
                }).optional();
    }

    /** Writes the allowance of a period, replacing the plan's limits when the entitlement changed mid-period. */
    void upsertAllowance(UUID owner, UsageCalendar.Period period, Allowance allowance, Entitlement entitlement,
                         Instant now) {
        jdbc.sql("INSERT INTO app_learning.usage_allowance(owner_id,period_id,plan,source,period_start,period_end,"
                        + "valid_until,credits_total,portions,burst_fraction,stt_month_seconds,stt_day_seconds,"
                        + "assessment_month,assessment_day,cap_podcasts,cap_quality_images,cap_high_factcheck,"
                        + "cap_smart_plan,smart_plan_window,created_at,updated_at) VALUES (:owner,:period,:plan,:source,"
                        + ":start,:end,:validUntil,:credits,:portions,:burst,:sttMonth,:sttDay,:assessmentMonth,"
                        + ":assessmentDay,:podcasts,:images,:factcheck,:smart,:smartWindow,:now,:now) "
                        + "ON CONFLICT (owner_id,period_id) DO UPDATE SET plan=EXCLUDED.plan,source=EXCLUDED.source,"
                        + "valid_until=EXCLUDED.valid_until,credits_total=EXCLUDED.credits_total,"
                        + "portions=EXCLUDED.portions,burst_fraction=EXCLUDED.burst_fraction,"
                        + "stt_month_seconds=EXCLUDED.stt_month_seconds,stt_day_seconds=EXCLUDED.stt_day_seconds,"
                        + "assessment_month=EXCLUDED.assessment_month,assessment_day=EXCLUDED.assessment_day,"
                        + "cap_podcasts=EXCLUDED.cap_podcasts,cap_quality_images=EXCLUDED.cap_quality_images,"
                        + "cap_high_factcheck=EXCLUDED.cap_high_factcheck,cap_smart_plan=EXCLUDED.cap_smart_plan,"
                        + "smart_plan_window=EXCLUDED.smart_plan_window,updated_at=EXCLUDED.updated_at")
                .param("owner", owner).param("period", period.id()).param("plan", allowance.plan().name())
                .param("source", entitlement.source().name()).param("start", time(period.start()))
                .param("end", time(period.end())).param("validUntil", time(entitlement.validUntil()))
                .param("credits", allowance.credits())
                .param("portions", allowance.portions() == null ? null
                        : String.join(",", allowance.portions().stream().map(String::valueOf).toList()))
                .param("burst", allowance.burstFraction()).param("sttMonth", allowance.sttMonth())
                .param("sttDay", allowance.sttDay()).param("assessmentMonth", allowance.assessmentMonth())
                .param("assessmentDay", allowance.assessmentDay()).param("podcasts", allowance.podcasts())
                .param("images", allowance.qualityImages()).param("factcheck", allowance.highFactcheck())
                .param("smart", allowance.smartPlanLimit()).param("smartWindow", allowance.smartPlanWindow().name())
                .param("now", time(now)).update();
    }

    // ----------------------------------------------------------------- balance

    void ensureBalance(UUID owner, String periodId, Instant now) {
        jdbc.sql("INSERT INTO app_learning.usage_balance(owner_id,period_id,updated_at) VALUES (:owner,:period,:now) "
                        + "ON CONFLICT (owner_id,period_id) DO NOTHING")
                .param("owner", owner).param("period", periodId).param("now", time(now)).update();
    }

    Optional<BalanceRow> balance(UUID owner, String periodId) {
        return jdbc.sql("SELECT unlocked,used,reserved,row_version,updated_at FROM app_learning.usage_balance "
                        + "WHERE owner_id=:owner AND period_id=:period")
                .param("owner", owner).param("period", periodId).query(UsageRepository::balanceRow).optional();
    }

    /** Locks the row without blocking the foreign-key checks of concurrent reservation inserts (NO KEY UPDATE). */
    BalanceRow lockBalance(UUID owner, String periodId) {
        return jdbc.sql("SELECT unlocked,used,reserved,row_version,updated_at FROM app_learning.usage_balance "
                        + "WHERE owner_id=:owner AND period_id=:period FOR NO KEY UPDATE")
                .param("owner", owner).param("period", periodId).query(UsageRepository::balanceRow).single();
    }

    private static BalanceRow balanceRow(ResultSet row, int number) throws SQLException {
        return new BalanceRow(row.getInt("unlocked"), row.getInt("used"), row.getInt("reserved"),
                row.getLong("row_version"), instant(row, "updated_at"));
    }

    /** Raises the unlocked amount; the balance row must be locked by the caller. */
    void addUnlocked(UUID owner, String periodId, int delta, Instant now) {
        jdbc.sql("UPDATE app_learning.usage_balance SET unlocked=unlocked+:delta,row_version=row_version+1,"
                        + "updated_at=:now WHERE owner_id=:owner AND period_id=:period")
                .param("delta", delta).param("now", time(now)).param("owner", owner).param("period", periodId).update();
    }

    /**
     * Admission: the one conditional update that keeps the balance from going negative. Zero rows means the amount
     * does not fit any more or another admission won the row version; the caller re-reads and decides.
     */
    boolean tryReserve(UUID owner, String periodId, int hold, long expectedVersion, Instant now) {
        return jdbc.sql("UPDATE app_learning.usage_balance SET reserved=reserved+:hold,row_version=row_version+1,"
                        + "updated_at=:now WHERE owner_id=:owner AND period_id=:period "
                        + "AND unlocked-used-reserved>=:hold AND row_version=:version")
                .param("hold", hold).param("now", time(now)).param("owner", owner).param("period", periodId)
                .param("version", expectedVersion).update() == 1;
    }

    /** Turns {@code credits} of a hold into spend. */
    void applyDebit(UUID owner, String periodId, int credits, Instant now) {
        jdbc.sql("UPDATE app_learning.usage_balance SET used=used+:credits,reserved=reserved-:credits,"
                        + "row_version=row_version+1,updated_at=:now WHERE owner_id=:owner AND period_id=:period")
                .param("credits", credits).param("now", time(now)).param("owner", owner).param("period", periodId).update();
    }

    /** Returns the unspent part of a hold to the balance. */
    void returnHold(UUID owner, String periodId, int credits, Instant now) {
        if (credits == 0) return;
        jdbc.sql("UPDATE app_learning.usage_balance SET reserved=reserved-:credits,row_version=row_version+1,"
                        + "updated_at=:now WHERE owner_id=:owner AND period_id=:period")
                .param("credits", credits).param("now", time(now)).param("owner", owner).param("period", periodId).update();
    }

    // ------------------------------------------------------------ reservations

    void insertReservation(Reservation reservation) {
        jdbc.sql("INSERT INTO app_learning.usage_reservation(reservation_id,owner_id,period_id,scope,session_id,turn_id,"
                        + "state,held_credits,debited_credits,rate_card_version,created_at,expires_at) VALUES (:id,:owner,"
                        + ":period,:scope,:session,:turn,'ACTIVE',:held,0,:version,:created,:expires)")
                .param("id", reservation.reservationId()).param("owner", reservation.ownerId())
                .param("period", reservation.periodId()).param("scope", reservation.scope().name())
                .param("session", reservation.sessionId()).param("turn", reservation.turnId())
                .param("held", reservation.heldCredits()).param("version", reservation.rateCardVersion())
                .param("created", time(reservation.createdAt())).param("expires", time(reservation.expiresAt())).update();
    }

    Optional<Reservation> lockReservation(UUID reservationId) {
        return jdbc.sql("SELECT " + RESERVATION_COLUMNS + " FROM app_learning.usage_reservation "
                        + "WHERE reservation_id=:id FOR UPDATE").param("id", reservationId).query(RESERVATION).optional();
    }

    Optional<Reservation> reservation(UUID reservationId) {
        return jdbc.sql("SELECT " + RESERVATION_COLUMNS + " FROM app_learning.usage_reservation WHERE reservation_id=:id")
                .param("id", reservationId).query(RESERVATION).optional();
    }

    void addDebited(UUID reservationId, int credits) {
        jdbc.sql("UPDATE app_learning.usage_reservation SET debited_credits=debited_credits+:credits "
                        + "WHERE reservation_id=:id AND state='ACTIVE'")
                .param("credits", credits).param("id", reservationId).update();
    }

    /** Moves the expiry of a live hold later; never earlier. */
    void extend(UUID reservationId, Instant expiresAt) {
        jdbc.sql("UPDATE app_learning.usage_reservation SET expires_at=:expires WHERE reservation_id=:id "
                        + "AND state='ACTIVE' AND expires_at<:expires")
                .param("expires", time(expiresAt)).param("id", reservationId).update();
    }

    void end(UUID reservationId, ReservationState state, Instant now) {
        jdbc.sql("UPDATE app_learning.usage_reservation SET state=:state,ended_at=:now "
                        + "WHERE reservation_id=:id AND state='ACTIVE'")
                .param("state", state.name()).param("now", time(now)).param("id", reservationId).update();
    }

    /** Live holds that are past their expiry; rows another worker holds are skipped, not waited for. */
    List<Reservation> lockDue(Instant now, int limit) {
        return jdbc.sql("SELECT " + RESERVATION_COLUMNS + " FROM app_learning.usage_reservation WHERE state='ACTIVE' "
                        + "AND expires_at<=:now ORDER BY expires_at,reservation_id LIMIT :limit FOR UPDATE SKIP LOCKED")
                .param("now", time(now)).param("limit", limit).query(RESERVATION).list();
    }

    // ------------------------------------------------------------------ ledger

    /** @return whether the row was appended; false when the idempotency key already exists */
    boolean insertLedger(LedgerEntry entry) {
        return jdbc.sql("INSERT INTO app_learning.usage_ledger_entry(entry_id,owner_id,kind,credits,cost_micros,operation,"
                        + "rate_card_version,period_id,idempotency_key,reservation_id,reference,bucket,units,created_at) "
                        + "VALUES (:id,:owner,:kind,:credits,:cost,:operation,:version,:period,:key,:reservation,:reference,"
                        + ":bucket,:units,:now) ON CONFLICT (idempotency_key) DO NOTHING")
                .param("id", entry.entryId()).param("owner", entry.owner()).param("kind", entry.kind())
                .param("credits", entry.credits()).param("cost", entry.costMicros()).param("operation", entry.operation())
                .param("version", entry.rateCardVersion()).param("period", entry.periodId())
                .param("key", entry.idempotencyKey()).param("reservation", entry.reservationId())
                .param("reference", entry.reference()).param("bucket", entry.bucket() == null ? null : entry.bucket().name())
                .param("units", entry.units()).param("now", time(entry.createdAt())).update() == 1;
    }

    Optional<LedgerRow> ledgerByKey(String idempotencyKey) {
        return jdbc.sql("SELECT owner_id,kind,credits,reservation_id,period_id,operation,cost_micros,reference,bucket,units "
                        + "FROM app_learning.usage_ledger_entry WHERE idempotency_key=:key").param("key", idempotencyKey)
                .query((row, number) -> new LedgerRow(row.getObject("owner_id", UUID.class), row.getString("kind"),
                        row.getInt("credits"), row.getObject("reservation_id", UUID.class), row.getString("period_id"),
                        row.getString("operation"), row.getObject("cost_micros", Long.class), row.getString("reference"),
                        row.getString("bucket"), row.getObject("units", Long.class))).optional();
    }

    /** Credits debited by {@code owner} in {@code [from, to)}: the input of the daily burst. */
    long debitedBetween(UUID owner, Instant from, Instant to) {
        return jdbc.sql("SELECT COALESCE(SUM(-credits),0) FROM app_learning.usage_ledger_entry WHERE owner_id=:owner "
                        + "AND kind='DEBIT' AND created_at>=:from AND created_at<:to")
                .param("owner", owner).param("from", time(from)).param("to", time(to)).query(Long.class).single();
    }

    // ---------------------------------------------------------------- counters

    void ensureCounter(UUID owner, Bucket bucket, Window window, Instant start) {
        jdbc.sql("INSERT INTO app_learning.usage_counter(owner_id,bucket,window_kind,window_start) "
                        + "VALUES (:owner,:bucket,:window,:start) ON CONFLICT DO NOTHING")
                .param("owner", owner).param("bucket", bucket.name()).param("window", window.name())
                .param("start", time(start)).update();
    }

    long lockCounter(UUID owner, Bucket bucket, Window window, Instant start) {
        return jdbc.sql("SELECT used FROM app_learning.usage_counter WHERE owner_id=:owner AND bucket=:bucket "
                        + "AND window_kind=:window AND window_start=:start FOR UPDATE")
                .param("owner", owner).param("bucket", bucket.name()).param("window", window.name())
                .param("start", time(start)).query(Long.class).single();
    }

    long counter(UUID owner, Bucket bucket, Window window, Instant start) {
        return jdbc.sql("SELECT COALESCE(SUM(used),0) FROM app_learning.usage_counter WHERE owner_id=:owner "
                        + "AND bucket=:bucket AND window_kind=:window AND window_start=:start")
                .param("owner", owner).param("bucket", bucket.name()).param("window", window.name())
                .param("start", time(start)).query(Long.class).single();
    }

    void addCounter(UUID owner, Bucket bucket, Window window, Instant start, long amount) {
        jdbc.sql("UPDATE app_learning.usage_counter SET used=used+:amount WHERE owner_id=:owner AND bucket=:bucket "
                        + "AND window_kind=:window AND window_start=:start")
                .param("amount", amount).param("owner", owner).param("bucket", bucket.name())
                .param("window", window.name()).param("start", time(start)).update();
    }

    /** Drops counters of windows that ended before {@code before}; at most {@code limit} rows per call. */
    int purgeCounters(Instant before, int limit) {
        return jdbc.sql("DELETE FROM app_learning.usage_counter WHERE ctid IN (SELECT ctid FROM app_learning.usage_counter "
                        + "WHERE window_start<:before LIMIT :limit)")
                .param("before", time(before)).param("limit", limit).update();
    }

    // ----------------------------------------------------------- entitlements

    /** @return whether a new snapshot was stored; false for an identical repeat */
    boolean insertSnapshot(String snapshotId, UUID owner, Plan plan, String source, Instant start, Instant end,
                           String allowancesJson, Instant validUntil, Instant now) {
        return jdbc.sql("INSERT INTO app_learning.entitlement_inbox(snapshot_id,owner_id,plan,source,period_start,"
                        + "period_end,allowances,valid_until,received_at) VALUES (:id,:owner,:plan,:source,:start,:end,"
                        + "CAST(:allowances AS jsonb),:validUntil,:now) ON CONFLICT (snapshot_id) DO NOTHING")
                .param("id", snapshotId).param("owner", owner).param("plan", plan.name()).param("source", source)
                .param("start", time(start)).param("end", time(end)).param("allowances", allowancesJson)
                .param("validUntil", time(validUntil)).param("now", time(now)).update() == 1;
    }

    boolean snapshotMatches(String snapshotId, UUID owner, Plan plan, String source, Instant start, Instant end,
                            String allowancesJson, Instant validUntil) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_learning.entitlement_inbox WHERE snapshot_id=:id AND owner_id=:owner "
                        + "AND plan=:plan AND source=:source AND period_start=:start AND period_end=:end "
                        + "AND allowances=CAST(:allowances AS jsonb) AND valid_until=:validUntil)")
                .param("id", snapshotId).param("owner", owner).param("plan", plan.name()).param("source", source)
                .param("start", time(start)).param("end", time(end)).param("allowances", allowancesJson)
                .param("validUntil", time(validUntil)).query(Boolean.class).single();
    }

    /** One snapshot as the entitlement source reads it. */
    record SnapshotRow(Plan plan, String source, Instant periodStart, Instant periodEnd, Instant validUntil) { }

    /**
     * The newest snapshot of {@code owner} that is in force at {@code now}: it has started and has not expired. Newest is
     * the latest received; an equal instant falls to the later period start, then the id, so the answer is stable.
     */
    Optional<SnapshotRow> newestValidSnapshot(UUID owner, Instant now) {
        return jdbc.sql("SELECT plan,source,period_start,period_end,valid_until FROM app_learning.entitlement_inbox "
                        + "WHERE owner_id=:owner AND period_start<=:now AND valid_until>:now "
                        + "ORDER BY received_at DESC, period_start DESC, snapshot_id DESC LIMIT 1")
                .param("owner", owner).param("now", time(now))
                .query((row, number) -> new SnapshotRow(Plan.valueOf(row.getString("plan")), row.getString("source"),
                        instant(row, "period_start"), instant(row, "period_end"), instant(row, "valid_until")))
                .optional();
    }
}
