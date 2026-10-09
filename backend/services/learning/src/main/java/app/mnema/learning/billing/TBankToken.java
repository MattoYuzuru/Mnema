package app.mnema.learning.billing;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * The T-Bank request and notification signature (<a href="https://developer.tbank.ru/eacq/intro/developer/token">Token</a>,
 * {@code contracts/billing/README.md}): the values of the root scalar members plus {@code Password}, sorted by member name and concatenated, then
 * SHA-256 over UTF-8 as lowercase hex. {@code Token} itself, {@code null} members, objects and arrays ({@code DATA}, {@code Receipt}, {@code Params}) are left
 * out; booleans are {@code true} or {@code false} and a number is its JSON text (parse with exact decimals). Pure and stateless.
 */
final class TBankToken {
    private static final String TOKEN = "Token";
    private static final String PASSWORD = "Password";
    private static final int HEX_LENGTH = 64;

    private TBankToken() { }

    /** The signature of {@code root} under {@code password}; an existing {@code Token} member does not take part. */
    static String compute(ObjectNode root, String password) {
        Map<String, String> values = new TreeMap<>();
        for (var member : root.properties()) {
            String name = member.getKey();
            JsonNode value = member.getValue();
            if (name.equals(TOKEN) || value == null || value.isNull() || value.isObject() || value.isArray()) continue;
            values.put(name, text(value));
        }
        values.put(PASSWORD, password);
        StringBuilder joined = new StringBuilder();
        values.values().forEach(joined::append);
        return HexFormat.of().formatHex(sha256(joined.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** Adds the {@code Token} member: the request is ready to send. */
    static ObjectNode sign(ObjectNode root, String password) {
        root.put(TOKEN, compute(root, password));
        return root;
    }

    /** Whether {@code root} carries the right {@code Token}; the comparison does not stop at the first differing byte. */
    static boolean verify(ObjectNode root, String password) {
        JsonNode token = root.path(TOKEN);
        if (!token.isString()) return false;
        String received = token.stringValue(null);
        if (received == null || received.length() != HEX_LENGTH) return false;
        return MessageDigest.isEqual(compute(root, password).getBytes(StandardCharsets.UTF_8),
                received.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonNode value) {
        if (value.isString()) return value.stringValue(null);
        if (value.isBoolean()) return value.booleanValue() ? "true" : "false";
        if (value.isIntegralNumber()) return value.bigIntegerValue().toString();
        if (value.isNumber()) return value.decimalValue().toPlainString();
        return value.asString("");
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
