package app.mnema.learning.platform.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Additive extension members of one Problem Detail ({@code limit}, ...). The status, title and detail of an
 * {@link ApiErrorCode} stay fixed; an extension only adds numeric or boolean machine-readable members. Values never
 * echo input, titles or content. Exceptions that carry one implement {@link ProblemExtensionSource}.
 */
public record ProblemExtension(Map<String, Object> members) {
    private static final ProblemExtension NONE = new ProblemExtension(Map.of());

    public ProblemExtension {
        Objects.requireNonNull(members, "members");
        Map<String, Object> copy = new LinkedHashMap<>();
        members.forEach((name, value) -> {
            if (!name.matches("[a-z][A-Za-z0-9]{0,31}") || name.equals("code") || name.equals("type")
                    || name.equals("title") || name.equals("status") || name.equals("detail")
                    || name.equals("instance")) {
                throw new IllegalArgumentException("Invalid problem extension member");
            }
            if (!(value instanceof Long || value instanceof Integer || value instanceof Boolean)) {
                throw new IllegalArgumentException("Problem extension members are numbers or booleans");
            }
            copy.put(name, value);
        });
        members = Map.copyOf(copy);
    }

    public static ProblemExtension none() { return NONE; }

    /** The one extension both hub limit codes use: the numeric limit that was hit. */
    public static ProblemExtension limit(long limit) { return new ProblemExtension(Map.of("limit", limit)); }

    /** Implemented by exceptions whose Problem Detail carries extension members. */
    public interface ProblemExtensionSource {
        ProblemExtension extension();
    }
}
