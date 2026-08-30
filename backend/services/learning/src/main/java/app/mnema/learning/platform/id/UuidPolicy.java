package app.mnema.learning.platform.id;

import java.util.Objects;
import java.util.UUID;

/**
 * UUID policy shared by replacement domains.
 *
 * <p>Persisted identifiers use PostgreSQL's native {@code uuid} type. Existing entity identifiers
 * may use any non-nil RFC 9562/IETF UUID version, while newly submitted command identifiers must
 * use portable UUIDv4 or time-ordered UUIDv7.
 */
public final class UuidPolicy {

    private static final int IETF_VARIANT = 2;

    private UuidPolicy() {
    }

    public static UUID newPortableId() {
        return UUID.randomUUID();
    }

    public static UUID requireEntityId(UUID value, String field) {
        Objects.requireNonNull(field, "field");
        UUID id = Objects.requireNonNull(value, field + " must not be null");
        if (id.getMostSignificantBits() == 0 && id.getLeastSignificantBits() == 0) {
            throw new IllegalArgumentException(field + " must not be the nil UUID");
        }
        if (id.variant() != IETF_VARIANT) {
            throw new IllegalArgumentException(field + " must use the RFC 9562/IETF UUID variant");
        }
        return id;
    }

    public static UUID requireCommandId(UUID value) {
        UUID id = requireEntityId(value, "commandId");
        if (id.version() != 4 && id.version() != 7) {
            throw new IllegalArgumentException("commandId must be UUIDv4 or UUIDv7");
        }
        return id;
    }
}
