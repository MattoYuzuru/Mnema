package app.mnema.learning.platform.json;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Produces the stable payload bytes used by command idempotency.
 *
 * <p>The bytes are serialized here rather than by a JSON library: persisted command receipts store
 * their digest, so the exact byte layout is a storage contract that must not depend on library
 * defaults. Keys are sorted by UTF-16 code units, numbers are plain normalized decimals, and strings
 * escape the quote, the backslash, control characters and every UTF-16 surrogate (short escapes for
 * backspace, tab, line feed, form feed and carriage return, upper-case four-digit hexadecimal escapes
 * otherwise); all other characters are written as UTF-8.
 */
@Component
public final class CanonicalJsonHasher {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    public CanonicalPayload hash(JsonNode payload) {
        byte[] bytes = canonicalBytes(payload);
        try {
            return new CanonicalPayload(MessageDigest.getInstance(HASH_ALGORITHM).digest(bytes), bytes.length);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 digest is unavailable", exception);
        }
    }

    public byte[] canonicalBytes(JsonNode payload) {
        Objects.requireNonNull(payload, "payload");
        var output = new StringBuilder();
        writeCanonical(output, payload);
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void writeCanonical(StringBuilder output, JsonNode node) {
        if (node.isObject()) {
            output.append('{');
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>(node.properties());
            fields.sort(Map.Entry.comparingByKey(Comparator.naturalOrder()));
            boolean first = true;
            for (Map.Entry<String, JsonNode> field : fields) {
                if (!first) output.append(',');
                first = false;
                writeString(output, field.getKey());
                output.append(':');
                writeCanonical(output, field.getValue());
            }
            output.append('}');
            return;
        }
        if (node.isArray()) {
            output.append('[');
            boolean first = true;
            for (JsonNode element : node) {
                if (!first) output.append(',');
                first = false;
                writeCanonical(output, element);
            }
            output.append(']');
            return;
        }
        if (node.isString()) {
            writeString(output, node.stringValue());
            return;
        }
        if (node.isIntegralNumber()) {
            output.append(node.bigIntegerValue());
            return;
        }
        if (node.isFloatingPointNumber()) {
            if ((node.isDouble() || node.isFloat()) && !Double.isFinite(node.doubleValue())) {
                throw new IllegalArgumentException("Non-finite JSON numbers are not supported");
            }
            BigDecimal value = node.decimalValue().stripTrailingZeros();
            output.append(value.signum() == 0 ? "0" : value.toPlainString());
            return;
        }
        if (node.isBoolean()) {
            output.append(node.booleanValue());
            return;
        }
        if (node.isNull()) {
            output.append("null");
            return;
        }
        throw new IllegalArgumentException("Unsupported JSON node type: " + node.getNodeType());
    }

    private static void writeString(StringBuilder output, String value) {
        output.append('"');
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\t' -> output.append("\\t");
                case '\n' -> output.append("\\n");
                case '\f' -> output.append("\\f");
                case '\r' -> output.append("\\r");
                default -> {
                    if (current < 0x20 || Character.isSurrogate(current)) {
                        output.append("\\u").append(HEX[current >> 12]).append(HEX[(current >> 8) & 0xF])
                                .append(HEX[(current >> 4) & 0xF]).append(HEX[current & 0xF]);
                    } else {
                        output.append(current);
                    }
                }
            }
        }
        output.append('"');
    }

    public record CanonicalPayload(byte[] sha256, int byteLength) {

        public CanonicalPayload {
            sha256 = sha256.clone();
            if (sha256.length != 32) {
                throw new IllegalArgumentException("SHA-256 digest must contain 32 bytes");
            }
            if (byteLength < 0) {
                throw new IllegalArgumentException("byteLength must not be negative");
            }
        }

        @Override
        public byte[] sha256() {
            return sha256.clone();
        }
    }
}
