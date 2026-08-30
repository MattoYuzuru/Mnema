package app.mnema.learning.platform.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Produces the stable payload bytes used by command idempotency. */
@Component
public final class CanonicalJsonHasher {

    private static final String HASH_ALGORITHM = "SHA-256";

    private final JsonFactory jsonFactory;

    public CanonicalJsonHasher(ObjectMapper objectMapper) {
        this.jsonFactory = objectMapper.getFactory();
    }

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
        try (var output = new ByteArrayOutputStream();
             JsonGenerator generator = jsonFactory.createGenerator(output)) {
            writeCanonical(generator, payload);
            generator.flush();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Payload cannot be canonicalized", exception);
        }
    }

    private void writeCanonical(JsonGenerator generator, JsonNode node) throws IOException {
        if (node.isObject()) {
            generator.writeStartObject();
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            fields.addAll(node.properties());
            fields.sort(Map.Entry.comparingByKey(Comparator.naturalOrder()));
            for (Map.Entry<String, JsonNode> field : fields) {
                generator.writeFieldName(field.getKey());
                writeCanonical(generator, field.getValue());
            }
            generator.writeEndObject();
            return;
        }
        if (node.isArray()) {
            generator.writeStartArray();
            for (JsonNode element : node) {
                writeCanonical(generator, element);
            }
            generator.writeEndArray();
            return;
        }
        if (node.isTextual()) {
            generator.writeString(node.textValue());
            return;
        }
        if (node.isIntegralNumber()) {
            generator.writeNumber(node.bigIntegerValue());
            return;
        }
        if (node.isFloatingPointNumber()) {
            if ((node.isDouble() || node.isFloat()) && !Double.isFinite(node.doubleValue())) {
                throw new IllegalArgumentException("Non-finite JSON numbers are not supported");
            }
            BigDecimal value = node.decimalValue().stripTrailingZeros();
            generator.writeNumber(value.signum() == 0 ? "0" : value.toPlainString());
            return;
        }
        if (node.isBoolean()) {
            generator.writeBoolean(node.booleanValue());
            return;
        }
        if (node.isNull()) {
            generator.writeNull();
            return;
        }
        throw new IllegalArgumentException("Unsupported JSON node type: " + node.getNodeType());
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
