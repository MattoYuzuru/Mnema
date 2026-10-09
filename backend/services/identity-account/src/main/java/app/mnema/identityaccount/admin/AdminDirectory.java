package app.mnema.identityaccount.admin;

import app.mnema.identityaccount.contract.AccountFailure;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AdminDirectory {
    public record Account(UUID accountId, String email, boolean emailVerified, String profileUsername, String displayName,
                          String status, boolean admin, String deletionState, Instant createdAt, Instant lastLoginAt,
                          Instant bannedAt, String banReason) { }
    public record Page(List<Account> accounts, String next) { public Page { accounts = List.copyOf(accounts); } }
    public record AuditEntry(UUID auditId, UUID actorAccountId, String action, UUID resourceId, UUID commandId, Instant occurredAt) { }
    public record AuditPage(List<AuditEntry> entries, String next) { public AuditPage { entries = List.copyOf(entries); } }
    private record Cursor(Instant createdAt, UUID accountId) {
        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString((createdAt + "|" + accountId).getBytes(StandardCharsets.US_ASCII));
        }
    }
    private static final String COLUMNS = "account_id,email,email_verified,profile_username,display_name,status,is_admin,deletion_state,created_at,last_login_at,banned_at,ban_reason";
    private final JdbcClient jdbc;

    public AdminDirectory(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true, timeout = 5)
    public Page page(String query, String status, String after) {
        if (query != null && (query.isBlank() || query.length() > 100 || query.codePoints().anyMatch(Character::isISOControl))) throw invalid();
        if (status != null && !List.of("ACTIVE", "BANNED").contains(status)) throw invalid();
        Cursor cursor = decode(after);
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM app_identity.account WHERE deletion_state <> 'PURGED'");
        var params = new HashMap<String, Object>();
        if (query != null) {
            sql.append(" AND (position(:q in normalized_email)>0 OR position(:q in COALESCE(normalized_profile_username,''))>0 "
                    + "OR position(:q in lower(COALESCE(display_name,'')))>0 OR account_id::text=:q)");
            params.put("q", query.strip().toLowerCase(Locale.ROOT));
        }
        if (status != null) { sql.append(" AND status=:status"); params.put("status", status); }
        if (cursor != null) {
            sql.append(" AND (created_at,account_id)<(:created,:id)");
            params.put("created", OffsetDateTime.ofInstant(cursor.createdAt(), java.time.ZoneOffset.UTC));
            params.put("id", cursor.accountId());
        }
        sql.append(" ORDER BY created_at DESC,account_id DESC LIMIT 51");
        var rows = jdbc.sql(sql.toString()).params(params).query((rs, n) -> account(rs)).list();
        String next = rows.size() > 50 ? new Cursor(rows.get(49).createdAt(), rows.get(49).accountId()).encode() : null;
        return new Page(rows.stream().limit(50).toList(), next);
    }

    @Transactional(readOnly = true, timeout = 5)
    public Account account(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_identity.account WHERE account_id=:id AND deletion_state<>'PURGED'")
                .param("id", id).query((rs, n) -> account(rs)).optional().orElseThrow(() -> new AccountFailure(404, "account_not_found"));
    }

    @Transactional(readOnly = true, timeout = 5)
    public AuditPage audit(String before) {
        UUID cursor = null;
        if (before != null) {
            try { cursor = UUID.fromString(before); if (!cursor.toString().equals(before)) throw invalid(); }
            catch (IllegalArgumentException failure) { throw invalid(); }
        }
        var rows = jdbc.sql("SELECT audit_id,actor_account_id,action,resource_id,occurred_at FROM app_identity.admin_audit "
                        + (cursor == null ? "" : "WHERE audit_id<:before ") + "ORDER BY audit_id DESC LIMIT 51")
                .params(cursor == null ? java.util.Map.of() : java.util.Map.of("before", cursor))
                .query((rs, n) -> new AuditEntry(rs.getObject("audit_id", UUID.class), rs.getObject("actor_account_id", UUID.class),
                        rs.getString("action"), rs.getObject("resource_id", UUID.class), null, instant(rs, "occurred_at"))).list();
        return new AuditPage(rows.stream().limit(50).toList(), rows.size() > 50 ? rows.get(49).auditId().toString() : null);
    }

    private static Account account(ResultSet rs) throws SQLException {
        return new Account(rs.getObject("account_id", UUID.class), rs.getString("email"), rs.getBoolean("email_verified"),
                rs.getString("profile_username"), rs.getString("display_name"), rs.getString("status"), rs.getBoolean("is_admin"),
                rs.getString("deletion_state"), instant(rs, "created_at"), instant(rs, "last_login_at"), instant(rs, "banned_at"), rs.getString("ban_reason"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Cursor decode(String value) {
        if (value == null) return null;
        if (value.isEmpty() || value.length() > 200) throw invalid();
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII);
            int separator = decoded.indexOf('|');
            if (separator < 1 || decoded.indexOf('|', separator + 1) >= 0) throw invalid();
            var cursor = new Cursor(Instant.parse(decoded.substring(0, separator)), UUID.fromString(decoded.substring(separator + 1)));
            int year = cursor.createdAt().atOffset(java.time.ZoneOffset.UTC).getYear();
            if (year < 1 || year > 9999 || !cursor.encode().equals(value)) throw invalid();
            return cursor;
        } catch (IllegalArgumentException | java.time.DateTimeException failure) { throw invalid(); }
    }

    private static AccountFailure invalid() { return new AccountFailure(400, "invalid_admin_query"); }
}
