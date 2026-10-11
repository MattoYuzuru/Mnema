package app.mnema.learning.update.plan;

import java.util.Locale;
import java.util.Optional;

/**
 * The kinds of unit an update is decided by. The declaration order is the order of kinds inside one class of the plan: the deck's text first, then materials, their exercises,
 * the objectives the exercises evidence, and the order of materials last.
 */
public enum UnitKind {
    METADATA, MATERIAL, EXERCISE, OBJECTIVE, ORDER;

    /** METADATA and ORDER are single units keyed by the copy's own deck id. */
    public boolean deckWide() { return this == METADATA || this == ORDER; }

    /** The wire name ({@code MATERIAL}), strict: lowercase or padded names are not kinds. */
    public static Optional<UnitKind> fromWire(String value) {
        if (value == null) return Optional.empty();
        for (UnitKind kind : values()) {
            if (kind.name().equals(value)) return Optional.of(kind);
        }
        return Optional.empty();
    }

    public String unitId(java.util.UUID key) { return name() + ":" + key.toString().toLowerCase(Locale.ROOT); }
}
