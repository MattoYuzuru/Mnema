package app.mnema.learning.platform.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Typed extension members of an RFC 9457 problem ({@code contracts/generation/errors.json}, {@code extensionMembers}).
 * {@link ApiErrorCode} keeps its fixed status, title and detail; a problem with members names them here and nowhere
 * else, so a member can never replace {@code type}, {@code title}, {@code status}, {@code detail}, {@code instance} or
 * {@code code}.
 *
 * <p>Values are restricted to what JSON carries without interpretation: strings, booleans, integers, instants (written
 * as RFC 3339), enums (written by name), {@code null} and lists or maps of those. Callers pass identifiers, counts and
 * tokens, never exception text or user content.
 */
public record ProblemExtension(Map<String, Object> members) {
    private static final Pattern NAME = Pattern.compile("[a-z][A-Za-z0-9]{0,31}");
    private static final Set<String> RESERVED = Set.of("type", "title", "status", "detail", "instance", "code");
    private static final int MAX_MEMBERS = 16;
    private static final int MAX_STRING_LENGTH = 200;
    private static final int MAX_DEPTH = 3;
    private static final ProblemExtension NONE = new ProblemExtension(Map.of());

    /**
     * Validates and copies the members, keeping their order.
     *
     * @throws IllegalArgumentException a reserved or malformed name, too many members or an unsupported value
     */
    public ProblemExtension {
        Objects.requireNonNull(members, "members");
        if (members.size() > MAX_MEMBERS) throw new IllegalArgumentException("Too many problem members");
        Map<String, Object> copy = new LinkedHashMap<>();
        members.forEach((name, value) -> {
            if (name == null || !NAME.matcher(name).matches() || RESERVED.contains(name)) {
                throw new IllegalArgumentException("Invalid problem member name");
            }
            copy.put(name, normalize(value, 0));
        });
        members = Collections.unmodifiableMap(copy);
    }

    /** No members: the plain problem. */
    public static ProblemExtension none() {
        return NONE;
    }

    /** The numeric limit that was hit (deck hub limit codes). */
    public static ProblemExtension limit(long limit) {
        return builder().put("limit", limit).build();
    }

    /** Implemented by exceptions whose Problem Detail carries extension members; the handler reads it for every code. */
    public interface ProblemExtensionSource {
        ProblemExtension extension();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Collects members in the order the contract lists them. */
    public static final class Builder {
        private final Map<String, Object> members = new LinkedHashMap<>();

        private Builder() { }

        /** @throws IllegalArgumentException for a duplicate, a reserved or malformed name, or an unsupported value */
        public Builder put(String name, Object value) {
            if (members.containsKey(name)) throw new IllegalArgumentException("Duplicate problem member");
            members.put(name, value);
            // Validate eagerly so the producer bug surfaces at the call that made it.
            new ProblemExtension(members);
            return this;
        }

        public ProblemExtension build() {
            return members.isEmpty() ? NONE : new ProblemExtension(members);
        }
    }

    private static Object normalize(Object value, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Problem member nested too deeply");
        return switch (value) {
            case null -> null;
            case String text -> {
                if (text.length() > MAX_STRING_LENGTH) throw new IllegalArgumentException("Problem member too long");
                yield text;
            }
            case Boolean flag -> flag;
            case Integer number -> number;
            case Long number -> number;
            case Instant instant -> instant.toString();
            case Enum<?> constant -> constant.name();
            case List<?> list -> {
                List<Object> copy = new ArrayList<>(list.size());
                for (Object element : list) copy.add(normalize(element, depth + 1));
                yield Collections.unmodifiableList(copy);
            }
            case Map<?, ?> map -> {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key) || !NAME.matcher(key).matches()) {
                        throw new IllegalArgumentException("Invalid problem member key");
                    }
                    copy.put(key, normalize(entry.getValue(), depth + 1));
                }
                yield Collections.unmodifiableMap(copy);
            }
            default -> throw new IllegalArgumentException("Unsupported problem member type");
        };
    }
}
