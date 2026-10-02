package app.mnema.learning.ai;

import java.util.regex.Pattern;

/**
 * The opaque, non-personal key sent to providers as the user id. It can only be produced by {@link UserKeys} (an HMAC of
 * the account id), so a raw account id, e-mail or name cannot be passed to a provider by mistake.
 */
public final class OpaqueUserKey {
    private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private final String value;

    OpaqueUserKey(String value) {
        if (value == null || !SHAPE.matcher(value).matches()) throw new IllegalArgumentException("Invalid user key");
        this.value = value;
    }

    String value() { return value; }

    @Override
    public boolean equals(Object other) { return other instanceof OpaqueUserKey key && key.value.equals(value); }

    @Override
    public int hashCode() { return value.hashCode(); }

    /** Never prints the key. */
    @Override
    public String toString() { return "OpaqueUserKey[redacted]"; }
}
