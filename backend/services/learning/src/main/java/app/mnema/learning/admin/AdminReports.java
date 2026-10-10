package app.mnema.learning.admin;

import app.mnema.learning.usage.UsageClock;
import app.mnema.learning.usage.EntitlementSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Reporting never initializes an allowance or issues a provider call. All facts stay in their owning database. */
@Service
public class AdminReports {
    private final JdbcClient jdbc;
    private final UsageClock clock;
    private final Duration journalRetention;
    private final EntitlementSource entitlements;

    public AdminReports(JdbcClient jdbc, UsageClock clock, @Value("${learning.ai.call-retention:P90D}") Duration journalRetention, EntitlementSource entitlements) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.journalRetention = journalRetention;
        this.entitlements = entitlements;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public ObjectNode report(AdminReportRange range) {
        ObjectNode result = envelope(range);
        result.set("ai", ai(range));
        result.set("usage", usage(range, null));
        var learning = node();
        learning.put("studyUsers", scalar("SELECT count(DISTINCT account_id) FROM app_learning.study_attempt_tombstone WHERE submitted_at>=:from AND submitted_at<:to", range, null));
        learning.put("attempts", scalar("SELECT count(*) FROM app_learning.study_attempt_tombstone WHERE submitted_at>=:from AND submitted_at<:to", range, null));
        learning.put("completedSessions", scalar("SELECT count(*) FROM app_learning.study_session WHERE status='COMPLETE' AND completed_at>=:from AND completed_at<:to", range, null));
        learning.put("generationUsers", scalar("SELECT count(DISTINCT owner_id) FROM app_learning.generation_session WHERE created_at>=:from AND created_at<:to", range, null));
        learning.put("publishedArtifacts", scalar("SELECT count(DISTINCT artifact_id) FROM app_learning.generation_provenance WHERE created_at>=:from AND created_at<:to", range, null));
        learning.put("generationCoverage", "RETAINED_SESSIONS");
        result.set("learning", learning);
        var media = node().put("accuracy", "INVENTORY");
        media.put("blobBytes", scalar("SELECT COALESCE(sum(byte_length),0) FROM app_learning.media_blob", range, null));
        media.put("assets", scalar("SELECT count(*) FROM app_learning.media_asset WHERE state<>'DELETED'", range, null));
        var states = media.putArray("byState");
        jdbc.sql("SELECT state,count(*) AS count FROM app_learning.media_asset GROUP BY state ORDER BY state")
                .query((rs, n) -> node().put("state", rs.getString("state")).put("count", rs.getLong("count"))).list().forEach(states::add);
        result.set("media", media);
        var financial = result.putObject("financial");
        financial.set("revenue", revenue(range));
        financial.set("infrastructure", unavailable("INVOICE_SOURCE_NOT_CONNECTED"));
        financial.set("providerInvoices", unavailable("INVOICE_SOURCE_NOT_CONNECTED"));
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public ObjectNode user(UUID account, AdminReportRange range) {
        Instant now = clock.now();
        ObjectNode result = envelope(range, now).put("accountId", account.toString());
        var entitlement = entitlements.current(account, now);
        result.set("currentEntitlement", node().put("plan", entitlement.plan().name()).put("source", entitlement.source().name())
                .put("period", entitlement.period().name()).put("validUntil", entitlement.validUntil().toString()));
        ObjectNode usage = usage(range, account);
        usage.set("operations", usage.remove("features"));
        usage.set("operationsTruncated", usage.remove("featuresTruncated"));
        result.set("usage", usage);
        var learning = result.putObject("learning");
        learning.put("decks", scalar("SELECT count(*) FROM app_learning.deck WHERE owner_id=:owner AND deleted_at IS NULL", range, account));
        learning.put("items", scalar("SELECT count(*) FROM app_learning.deck_head_item i JOIN app_learning.deck d ON d.deck_id=i.deck_id WHERE d.owner_id=:owner AND d.deleted_at IS NULL", range, account));
        learning.put("studyAttempts", scalar("SELECT count(*) FROM app_learning.study_attempt_tombstone WHERE account_id=:owner AND submitted_at>=:from AND submitted_at<:to", range, account));
        learning.put("completedSessions", scalar("SELECT count(*) FROM app_learning.study_session WHERE account_id=:owner AND status='COMPLETE' AND completed_at>=:from AND completed_at<:to", range, account));
        learning.put("publishedArtifacts", scalar("SELECT count(DISTINCT artifact_id) FROM app_learning.generation_provenance WHERE owner_id=:owner AND created_at>=:from AND created_at<:to", range, account));
        var media = result.putObject("media");
        media.put("assets", scalar("SELECT count(*) FROM app_learning.media_asset WHERE owner_id=:owner AND state<>'DELETED'", range, account));
        media.put("sourceBytes", scalar("SELECT COALESCE(sum(b.byte_length),0) FROM app_learning.media_blob b WHERE EXISTS(SELECT 1 FROM app_learning.media_asset a WHERE a.owner_id=:owner AND a.state<>'DELETED' AND a.source_blob_id=b.blob_id)", range, account));
        var allowances = result.putArray("allowances");
        jdbc.sql("SELECT a.period_id,a.plan,a.source,a.credits_total,COALESCE(b.unlocked,0) AS unlocked,COALESCE(b.used,0) AS used,COALESCE(b.reserved,0) AS reserved,a.valid_until,a.updated_at "
                        + "FROM app_learning.usage_allowance a LEFT JOIN app_learning.usage_balance b USING(owner_id,period_id) WHERE a.owner_id=:owner AND a.period_end>:from AND a.period_start<:to ORDER BY a.period_id DESC LIMIT 4")
                .params(params(range, account)).query((rs, n) -> node().put("periodId", rs.getString("period_id")).put("plan", rs.getString("plan"))
                        .put("source", rs.getString("source")).put("total", rs.getInt("credits_total")).put("unlocked", rs.getInt("unlocked"))
                        .put("used", rs.getInt("used")).put("reserved", rs.getInt("reserved"))
                        .put("validUntil", rs.getObject("valid_until", OffsetDateTime.class).toInstant().toString())
                        .put("updatedAt", rs.getObject("updated_at", OffsetDateTime.class).toInstant().toString())).list().forEach(allowances::add);
        result.put("allowanceCoverage", "STORED_SNAPSHOTS");
        return result;
    }

    /**
     * Gross confirmed T-Bank orders by their {@code paid_at}: every order that was ever paid, which includes the ones refunded later
     * ({@code PaymentStateApplier} moves PAID to REFUNDED), so a refund never removes revenue from the period that earned it. The
     * refunded subset is shown beside, never netted. The refunded amount is not stored, so a partial refund is reported at the full
     * order amount: an upper bound. Bank fees are unknown.
     */
    private ObjectNode revenue(AdminReportRange range) {
        return jdbc.sql("SELECT count(*) AS paid,COALESCE(sum(amount_kopecks),0) AS paid_kopecks,"
                        + "count(*) FILTER(WHERE status='REFUNDED') AS refunded,COALESCE(sum(amount_kopecks) FILTER(WHERE status='REFUNDED'),0) AS refunded_kopecks "
                        + "FROM app_learning.billing_order WHERE status IN ('PAID','REFUNDED') AND paid_at>=:from AND paid_at<:to")
                .params(params(range, null)).query((rs, n) -> node().put("status", "AVAILABLE").put("source", "BILLING_ORDERS").put("currency", "RUB")
                        .put("accuracy", "GROSS_CONFIRMED_ORDERS").put("paidOrders", rs.getLong("paid")).put("paidKopecks", rs.getLong("paid_kopecks"))
                        .put("refundedOrders", rs.getLong("refunded")).put("refundedKopecks", rs.getLong("refunded_kopecks"))).single();
    }

    private ObjectNode ai(AdminReportRange range) {
        var rows = jdbc.sql("SELECT count(*) AS calls,count(*) FILTER(WHERE outcome='PENDING') AS pending,count(*) FILTER(WHERE outcome NOT IN ('PENDING','OK')) AS failed,"
                        + "COALESCE(sum(cost_micros),0) AS cost,count(latency_ms) AS latency_samples,percentile_cont(0.5) WITHIN GROUP(ORDER BY latency_ms) AS p50,percentile_cont(0.95) WITHIN GROUP(ORDER BY latency_ms) AS p95,percentile_cont(0.99) WITHIN GROUP(ORDER BY latency_ms) AS p99 "
                        + "FROM app_learning.ai_provider_call WHERE created_at>=:from AND created_at<:to")
                .params(params(range, null)).query((rs, n) -> {
                    var value = node().put("currency", "USD").put("accuracy", "ESTIMATE").put("calls", rs.getLong("calls"))
                            .put("pendingCalls", rs.getLong("pending")).put("failedCalls", rs.getLong("failed")).put("estimatedCostMicros", rs.getLong("cost"));
                    // The population is every call with a recorded latency (completed failures included, pending and unreported excluded).
                    value.set("latency", percentiles(rs, true).put("sampleCount", rs.getLong("latency_samples")).put("population", "RECORDED_LATENCY"));
                    return value;
                }).single();
        rows.put("journalRetentionDays", journalRetention.toDays());
        rows.put("retentionWindowStart", clock.now().minus(journalRetention).toString());
        rows.put("rangeIncludesExpiredData", range.start().isBefore(clock.now().minus(journalRetention)));
        rows.put("failedCallCostCoverage", "INCOMPLETE");
        var routes = jdbc.sql("SELECT capability,provider,model,count(*) AS calls,count(*) FILTER(WHERE outcome NOT IN ('PENDING','OK')) AS failed,COALESCE(sum(cost_micros),0) AS cost "
                        + "FROM app_learning.ai_provider_call WHERE created_at>=:from AND created_at<:to GROUP BY capability,provider,model ORDER BY cost DESC,capability,provider,model LIMIT 257")
                .params(params(range, null)).query((rs, n) -> node().put("capability", rs.getString("capability")).put("provider", rs.getString("provider"))
                        .put("model", rs.getString("model")).put("calls", rs.getLong("calls")).put("failedCalls", rs.getLong("failed")).put("estimatedCostMicros", rs.getLong("cost"))).list();
        rows.put("routeGroupsTruncated", routes.size() > 256);
        var routeArray = rows.putArray("byRoute");
        routes.stream().limit(256).forEach(routeArray::add);
        var days = rows.putArray("byDay");
        jdbc.sql("SELECT (created_at AT TIME ZONE 'UTC')::date AS day,count(*) AS calls,count(*) FILTER(WHERE outcome NOT IN ('OK','PENDING')) AS failed,COALESCE(sum(cost_micros),0) AS cost FROM app_learning.ai_provider_call WHERE created_at>=:from AND created_at<:to GROUP BY day ORDER BY day LIMIT 90")
                .params(params(range, null)).query((rs, n) -> node().put("date", rs.getObject("day", java.time.LocalDate.class).toString())
                        .put("calls", rs.getLong("calls")).put("failedCalls", rs.getLong("failed")).put("estimatedCostMicros", rs.getLong("cost"))).list().forEach(days::add);
        return rows;
    }

    private ObjectNode usage(AdminReportRange range, UUID owner) {
        String ownerClause = owner == null ? "" : " AND owner_id=:owner";
        String selected = " FROM app_learning.usage_ledger_entry WHERE kind='DEBIT' AND created_at>=:from AND created_at<:to" + ownerClause;
        ObjectNode value = jdbc.sql("SELECT count(DISTINCT owner_id) AS users,COALESCE(sum(-credits::bigint),0) AS credits" + selected)
                .params(params(range, owner)).query((rs, n) -> node().put("activeUsers", rs.getLong("users")).put("creditsDebited", rs.getLong("credits"))).single();
        var distribution = jdbc.sql("SELECT count(*) AS sample,percentile_cont(0.5) WITHIN GROUP(ORDER BY credits) AS p50,percentile_cont(0.95) WITHIN GROUP(ORDER BY credits) AS p95,percentile_cont(0.99) WITHIN GROUP(ORDER BY credits) AS p99 "
                        + "FROM (SELECT owner_id,sum(-credits::bigint) AS credits" + selected + " GROUP BY owner_id) u")
                .params(params(range, owner)).query((rs, n) -> percentiles(rs, false).put("sample", rs.getLong("sample"))).single();
        value.set("creditsPerUser", distribution);
        long denominator = value.path("activeUsers").longValue();
        var features = value.putArray("features");
        jdbc.sql("SELECT COALESCE(operation,bucket,'UNCLASSIFIED') AS operation,count(DISTINCT owner_id) AS users,count(*) AS events,COALESCE(sum(-credits::bigint),0) AS credits,COALESCE(sum(units),0) AS units" + selected
                        + " GROUP BY COALESCE(operation,bucket,'UNCLASSIFIED') ORDER BY credits DESC,operation LIMIT 65")
                .params(params(range, owner)).query((rs, n) -> {
                    var feature = node().put("operation", rs.getString("operation")).put("users", rs.getLong("users"))
                            .put("events", rs.getLong("events")).put("creditsDebited", rs.getLong("credits")).put("units", rs.getLong("units"));
                    if (denominator == 0) feature.putNull("share"); else feature.put("share", (double) rs.getLong("users") / denominator);
                    return feature;
                }).list().forEach(features::add);
        value.put("featuresTruncated", features.size() > 64);
        while (features.size() > 64) features.remove(64);
        if (owner == null) {
            var topUsers = value.putArray("topUsers");
            jdbc.sql("SELECT owner_id,count(*) AS events,sum(-credits::bigint) AS credits" + selected + " GROUP BY owner_id ORDER BY credits DESC,owner_id LIMIT 20")
                    .params(params(range, null)).query((rs, n) -> node().put("accountId", rs.getObject("owner_id", UUID.class).toString())
                            .put("events", rs.getLong("events")).put("creditsDebited", rs.getLong("credits"))).list().forEach(topUsers::add);
        }
        return value;
    }

    private long scalar(String sql, AdminReportRange range, UUID owner) { return jdbc.sql(sql).params(params(range, owner)).query(Long.class).single(); }

    private ObjectNode envelope(AdminReportRange range) { return envelope(range, clock.now()); }

    private ObjectNode envelope(AdminReportRange range, Instant now) { return node().put("from", range.from().toString()).put("to", range.to().toString()).put("generatedAt", now.toString()); }

    private static Map<String, Object> params(AdminReportRange range, UUID owner) {
        var values = new HashMap<String, Object>();
        values.put("from", OffsetDateTime.ofInstant(range.start(), ZoneOffset.UTC));
        values.put("to", OffsetDateTime.ofInstant(range.end(), ZoneOffset.UTC));
        if (owner != null) values.put("owner", owner);
        return values;
    }

    private static ObjectNode percentiles(ResultSet rs, boolean milliseconds) throws SQLException {
        var result = node();
        for (String key : new String[]{"p50", "p95", "p99"}) {
            Object value = rs.getObject(key);
            if (value == null) result.putNull(key + (milliseconds ? "Ms" : ""));
            else result.put(key + (milliseconds ? "Ms" : ""), ((Number) value).doubleValue());
        }
        return result;
    }

    private static ObjectNode unavailable(String reason) { return node().put("status", "UNAVAILABLE").put("reason", reason); }
    private static ObjectNode node() { return JsonNodeFactory.instance.objectNode(); }
}
