package app.mnema.learning.platform.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Reads bounded, unambiguous JSON without interpreting or discarding native node capabilities.
 *
 * <p>Use this boundary before content validation and command hashing. In particular, normal
 * tree deserialization can otherwise discard duplicate keys or round unknown numeric values.
 * JSONB cannot represent NUL or unpaired UTF-16 surrogates. Numeric bounds also keep the
 * canonical hasher's plain-decimal output bounded. Numbers must survive browser JSON
 * parsing without a semantic change; unsafe integers or rounded decimals are rejected.
 * Errors deliberately contain no input.
 */
public final class ContentJsonReader {

    private static final int MAX_NUMBER_LENGTH = 256;
    private static final int MAX_DECIMAL_SCALE = 128;
    private static final BigDecimal MAX_SAFE_INTEGER = new BigDecimal("9007199254740991");
    private final int maxBytes;
    private final ObjectReader reader;

    public ContentJsonReader(int maxBytes, int maxDepth, int maxTokens) {
        if (maxBytes < 1 || maxDepth < 1 || maxTokens < 1) {
            throw new IllegalArgumentException("JSON limits must be positive");
        }
        this.maxBytes = maxBytes;
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(maxDepth)
                        .maxTokenCount(maxTokens)
                        .maxStringLength(maxBytes)
                        .maxNumberLength(MAX_NUMBER_LENGTH)
                        .build())
                .build();
        reader = JsonMapper.builder(factory)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build().readerFor(JsonNode.class);
    }

    /** Returns an independent semantic tree; whitespace and object-key ordering are not retained. */
    public JsonNode read(byte[] utf8) {
        Objects.requireNonNull(utf8, "utf8");
        if (utf8.length == 0 || utf8.length > maxBytes) {
            throw invalid();
        }
        try {
            JsonNode root = reader.readValue(decodeUtf8(utf8));
            if (root == null || !root.isObject()) {
                throw invalid();
            }
            validateScalars(root);
            return root;
        } catch (IOException | ArithmeticException exception) {
            // Jackson exceptions can include private field names and values even with source
            // locations disabled. Do not retain them as causes in an exposed request failure.
            throw invalid();
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void validateScalars(JsonNode root) {
        var pending = new ArrayDeque<JsonNode>();
        pending.add(root);
        while (!pending.isEmpty()) {
            JsonNode node = pending.removeLast();
            if (node.isObject()) {
                for (var property : node.properties()) {
                    validateString(property.getKey());
                    pending.add(property.getValue());
                }
            } else if (node.isArray()) {
                node.forEach(pending::add);
            } else if (node.isTextual()) {
                validateString(node.textValue());
            } else if (node.isNumber()) {
                var decimal = node.decimalValue();
                if (decimal.precision() > MAX_NUMBER_LENGTH
                        || decimal.scale() < -MAX_DECIMAL_SCALE
                        || decimal.scale() > MAX_DECIMAL_SCALE
                        || (decimal.stripTrailingZeros().scale() <= 0
                            && decimal.abs().compareTo(MAX_SAFE_INTEGER) > 0)
                        || BigDecimal.valueOf(decimal.doubleValue()).compareTo(decimal) != 0) {
                    throw invalid();
                }
            }
        }
    }

    private static void validateString(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == 0 || Character.isLowSurrogate(current)) {
                throw invalid();
            }
            if (Character.isHighSurrogate(current)
                    && (++i == value.length() || !Character.isLowSurrogate(value.charAt(i)))) {
                throw invalid();
            }
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid content JSON");
    }
}
