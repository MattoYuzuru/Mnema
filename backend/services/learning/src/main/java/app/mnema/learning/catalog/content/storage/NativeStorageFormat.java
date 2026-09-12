package app.mnema.learning.catalog.content.storage;

import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.platform.json.CanonicalJsonHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class NativeStorageFormat {
    static final int VERSION = 1;
    static final int BATCH_SIZE = 32;
    static final int FANOUT = 32;
    static final int TARGET_FRAGMENT_BYTES = 1024;
    static final int MIN_FRAGMENT_BYTES = 512;
    static final int MAX_FRAGMENT_BYTES = 2048;
    static final int MAX_RECORD_BYTES = NativeDocumentReader.MAX_BYTES + 32 * NativeDocumentReader.MAX_NODES;
    static final int MAX_OBJECTS = 16_000;
    static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    static final CanonicalJsonHasher JSON = new CanonicalJsonHasher();

    private NativeStorageFormat() { }

    static ObjectNode payload(String role) {
        return JsonNodeFactory.instance.objectNode().put("codec", VERSION).put("role", role);
    }

    static String canonical(JsonNode value) {
        return new String(JSON.canonicalBytes(value), StandardCharsets.UTF_8);
    }

    static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }

    static void require(boolean condition) {
        if (!condition) throw new NativeStorageFailure(NativeStorageFailure.Code.INVALID_GRAPH);
    }

    static void budget(boolean condition) {
        if (!condition) throw new NativeStorageFailure(NativeStorageFailure.Code.BUDGET_EXCEEDED);
    }

    static int integer(JsonNode value, int minimum, int maximum) {
        require(value.isIntegralNumber() && value.canConvertToInt());
        int result = value.intValue();
        require(result >= minimum && result <= maximum);
        return result;
    }

    static void fields(JsonNode value, String... fields) {
        Set<String> expected = Set.of(fields);
        require(value.isObject() && value.size() == expected.size()
                && value.properties().stream().allMatch(field -> expected.contains(field.getKey())));
    }

    /** Split only at Unicode scalar boundaries; canonical JSON has already rejected malformed Unicode. */
    static List<String> split(String value) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int size = 0;
        for (int at = 0; at < value.length();) {
            int codePoint = value.codePointAt(at);
            int width = codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
            if (size + width > TARGET_FRAGMENT_BYTES) {
                result.add(value.substring(start, at));
                start = at;
                size = 0;
            }
            size += width;
            at += Character.charCount(codePoint);
        }
        if (start < value.length()) result.add(value.substring(start));
        return repair(result);
    }

    /** Prevent repeated deletions from retaining an unbounded number of tiny fragments. */
    static List<String> repair(List<String> source) {
        var result = new ArrayList<>(source);
        for (int i = 0; i < result.size() && result.size() > 1;) {
            if (bytes(result.get(i)) >= MIN_FRAGMENT_BYTES) { i++; continue; }
            int left = i == result.size() - 1 ? i - 1 : i;
            String combined = result.get(left) + result.get(left + 1);
            result.remove(left + 1);
            if (bytes(combined) <= MAX_FRAGMENT_BYTES) {
                result.set(left, combined);
            } else {
                // At most 2559 bytes: two scalar-safe halves both comfortably exceed 512 bytes.
                // Balance by UTF-8 bytes, since character widths can differ across the halves.
                int target = bytes(combined) / 2;
                int middle = 0;
                int count = 0;
                while (count < target) {
                    int cp = combined.codePointAt(middle);
                    count += cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
                    middle += Character.charCount(cp);
                }
                result.set(left, combined.substring(0, middle));
                result.add(left + 1, combined.substring(middle));
            }
            i = Math.max(0, left - 1);
        }
        return List.copyOf(result);
    }
}
