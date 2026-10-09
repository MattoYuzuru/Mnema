package app.mnema.learning.admin;

import app.mnema.learning.platform.api.InvalidRequestException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AdminAudit {
    public record Entry(UUID auditId, UUID actorAccountId, String action, UUID resourceId, UUID commandId, Instant occurredAt) { }
    public record Page(List<Entry> entries, String next) { public Page { entries = List.copyOf(entries); } }
    private final JdbcClient jdbc;

    public AdminAudit(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID actor, String action, UUID resource, UUID command) {
        jdbc.sql("INSERT INTO app_learning.admin_audit(actor_account_id,action,resource_id,command_id) VALUES (:actor,:action,:resource,:command)")
                .param("actor", actor).param("action", action).param("resource", resource).param("command", command).update();
    }

    public Page page(String before) {
        UUID cursor = cursor(before);
        var rows = jdbc.sql("SELECT audit_id,actor_account_id,action,resource_id,command_id,occurred_at FROM app_learning.admin_audit "
                        + (cursor == null ? "" : "WHERE audit_id < :before ") + "ORDER BY audit_id DESC LIMIT 51")
                .params(cursor == null ? java.util.Map.of() : java.util.Map.of("before", cursor))
                .query((rs, n) -> new Entry(rs.getObject("audit_id", UUID.class), rs.getObject("actor_account_id", UUID.class),
                        rs.getString("action"), rs.getObject("resource_id", UUID.class), rs.getObject("command_id", UUID.class),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant())).list();
        return new Page(rows.stream().limit(50).toList(), rows.size() > 50 ? rows.get(49).auditId().toString() : null);
    }

    public static UUID cursor(String value) {
        if (value == null) return null;
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException failure) { throw new InvalidRequestException(); }
    }
}
