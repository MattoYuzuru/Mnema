package app.mnema.learning.generation.mbm;

import java.util.Objects;

/**
 * One compile finding. {@code line} is 1-based, or 0 for a finding about the options; {@code column} is the 1-based
 * Unicode code point of the offending block or directive and is {@code null} for inline findings and findings about
 * the whole document. Findings never echo content.
 */
public record MbmFinding(int line, Integer column, MbmCode code, String attribute) {

    /** An attribute name echoed in a finding is cut to this many characters: findings never echo arbitrary content. */
    public static final int MAX_ATTRIBUTE_ECHO = 32;

    public MbmFinding {
        if (attribute != null && attribute.length() > MAX_ATTRIBUTE_ECHO) {
            attribute = attribute.substring(0, MAX_ATTRIBUTE_ECHO);
        }
        Objects.requireNonNull(code, "code");
        if (line < 0) {
            throw new IllegalArgumentException("line must not be negative");
        }
        if (column != null && column < 1) {
            throw new IllegalArgumentException("column is 1-based");
        }
    }

    public MbmCode.Severity severity() {
        return code.severity();
    }
}
