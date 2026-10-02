package app.mnema.learning.notification;

import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Publishes inside the caller's transaction ({@link Propagation#MANDATORY}). The owner's cursor row is locked first,
 * the dedupe key is checked under that lock and only then is a {@code seq} allocated, so a repeat neither creates a row
 * nor leaves a gap, and concurrent publishers of one owner get contiguous values in commit order.
 */
@Component
class JdbcNotificationPublisher implements NotificationPublisher {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:+@/-]{0,199}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final NotificationRepository repository;
    private final NotificationSettings settings;

    JdbcNotificationPublisher(NotificationRepository repository, NotificationSettings settings) {
        this.repository = repository;
        this.settings = settings;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean publish(UUID owner, NotificationKind kind, String dedupeKey, Map<String, ?> params,
                           NotificationRoute route) {
        UuidPolicy.requireEntityId(owner, "owner");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(route, "route");
        if (dedupeKey == null || !KEY.matcher(dedupeKey).matches()) {
            throw new IllegalArgumentException("Invalid notification: dedupe key");
        }
        kind.validate(params);
        if (route != kind.route(params)) throw new IllegalArgumentException("Invalid notification: route");
        String json = json(kind, params);
        if (json.getBytes(StandardCharsets.UTF_8).length > settings.paramsMaxBytes) {
            throw new IllegalArgumentException("Invalid notification: params too large");
        }
        repository.lockCursor(owner);
        if (repository.dedupeKeyTaken(owner, dedupeKey)) return false;
        long seq = repository.allocateSeq(owner);
        repository.insert(owner, seq, kind, dedupeKey, json, route, settings.retention.toSeconds());
        repository.evictBeyond(owner, seq, settings.maxPerAccount);
        return true;
    }

    /** Fields in the contract's order, so stored documents are uniform. */
    private static String json(NotificationKind kind, Map<String, ?> params) {
        ObjectNode node = JSON.createObjectNode();
        for (NotificationKind.Param param : kind.params()) {
            Object value = params.get(param.name());
            switch (value) {
                case null -> node.putNull(param.name());
                case UUID id -> node.put(param.name(), id.toString());
                case Instant instant -> node.put(param.name(), instant.toString());
                case Number number -> node.put(param.name(), number.longValue());
                default -> node.put(param.name(), value.toString());
            }
        }
        return node.toString();
    }
}
