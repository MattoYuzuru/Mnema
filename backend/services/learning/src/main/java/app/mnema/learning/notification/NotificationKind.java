package app.mnema.learning.notification;

import app.mnema.learning.platform.id.UuidPolicy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static app.mnema.learning.notification.NotificationKind.ParamType.COUNT;
import static app.mnema.learning.notification.NotificationKind.ParamType.TIMESTAMP;
import static app.mnema.learning.notification.NotificationKind.ParamType.TOKEN;
import static app.mnema.learning.notification.NotificationKind.ParamType.UUID;

/**
 * The notification vocabulary of {@code contracts/notifications}: severity, route rule and the exact {@code params}
 * fields of each kind. Params carry only identifiers, counts, enum tokens and timestamps, so no prose or personal data
 * can be stored: the client builds the sentence from {@code kind + params}.
 */
public enum NotificationKind {
    GENERATION_PLAN_READY(NotificationSeverity.INFO, NotificationRoute.WORKSHOP,
            param("deckId", UUID), param("sessionId", UUID), param("sessionKind", TOKEN), param("plannedCount", COUNT)),
    GENERATION_READY(NotificationSeverity.INFO, NotificationRoute.WORKSHOP,
            param("deckId", UUID), param("sessionId", UUID), param("sessionKind", TOKEN),
            param("artifactCount", COUNT), param("approvableCount", COUNT)),
    GENERATION_PARTIAL(NotificationSeverity.WARNING, NotificationRoute.WORKSHOP,
            param("deckId", UUID), param("sessionId", UUID), param("sessionKind", TOKEN),
            param("approvableCount", COUNT), param("failedCount", COUNT)),
    GENERATION_FAILED(NotificationSeverity.ERROR, NotificationRoute.WORKSHOP,
            param("deckId", UUID), param("sessionId", UUID), param("sessionKind", TOKEN), param("errorCode", TOKEN)),
    USAGE_LOW(NotificationSeverity.WARNING, NotificationRoute.PLANS,
            param("bucket", TOKEN), param("percent", COUNT), param("unit", TOKEN), param("remaining", COUNT),
            nullable("renewsAt", TIMESTAMP), param("plan", TOKEN)),
    USAGE_EXHAUSTED(NotificationSeverity.WARNING, NotificationRoute.PLANS,
            param("bucket", TOKEN), param("window", TOKEN), nullable("renewsAt", TIMESTAMP), param("plan", TOKEN)),
    GENERATION_SESSION_EXPIRING(NotificationSeverity.WARNING, NotificationRoute.WORKSHOP,
            param("deckId", UUID), param("sessionId", UUID), param("expiresAt", TIMESTAMP), param("pendingCount", COUNT)),
    /** The route is {@link #route(Map)}: WORKSHOP with a session, DECK with only a deck, else NONE. */
    MEDIA_PROCESSING_FAILED(NotificationSeverity.ERROR, null,
            param("assetId", UUID), param("mediaKind", TOKEN), nullable("deckId", UUID), nullable("sessionId", UUID),
            nullable("artifactId", UUID), nullable("slotKey", TOKEN), param("reason", TOKEN));

    /** Value type of one params field. */
    enum ParamType { UUID, TOKEN, COUNT, TIMESTAMP }

    record Param(String name, ParamType type, boolean nullable) { }

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}");
    private static final long MAX_COUNT = 1_000_000_000L;

    private final NotificationSeverity severity;
    private final NotificationRoute fixedRoute;
    private final List<Param> params;

    NotificationKind(NotificationSeverity severity, NotificationRoute fixedRoute, Param... params) {
        this.severity = severity;
        this.fixedRoute = fixedRoute;
        this.params = List.of(params);
    }

    private static Param param(String name, ParamType type) { return new Param(name, type, false); }

    private static Param nullable(String name, ParamType type) { return new Param(name, type, true); }

    public NotificationSeverity severity() { return severity; }

    List<Param> params() { return params; }

    /** The only route a producer may use for this kind given its params. */
    public NotificationRoute route(Map<String, ?> values) {
        if (fixedRoute != null) return fixedRoute;
        if (values.get("sessionId") != null) return NotificationRoute.WORKSHOP;
        return values.get("deckId") != null ? NotificationRoute.DECK : NotificationRoute.NONE;
    }

    /**
     * Rejects anything that is not exactly this kind's field set of non-prose values. A violation is a producer bug,
     * so it fails loudly instead of storing a malformed notification.
     */
    void validate(Map<String, ?> values) {
        Objects.requireNonNull(values, "params");
        if (values.size() != params.size()) throw invalid("params must be exactly the fields of the kind");
        for (Param param : params) {
            if (!values.containsKey(param.name())) throw invalid("missing param " + param.name());
            Object value = values.get(param.name());
            if (value == null) {
                if (!param.nullable()) throw invalid("param " + param.name() + " must not be null");
                continue;
            }
            boolean valid = switch (param.type()) {
                case UUID -> value instanceof java.util.UUID id && isEntityId(id);
                case TOKEN -> value instanceof String text && TOKEN_PATTERN.matcher(text).matches();
                case COUNT -> (value instanceof Integer || value instanceof Long)
                        && ((Number) value).longValue() >= 0 && ((Number) value).longValue() <= MAX_COUNT;
                case TIMESTAMP -> value instanceof Instant;
            };
            if (!valid) throw invalid("param " + param.name() + " has an invalid value");
        }
    }

    private static boolean isEntityId(java.util.UUID id) {
        try {
            UuidPolicy.requireEntityId(id, "id");
            return true;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid notification: " + message);
    }
}
