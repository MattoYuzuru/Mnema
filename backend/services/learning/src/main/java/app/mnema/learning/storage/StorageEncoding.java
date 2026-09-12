package app.mnema.learning.storage;

import app.mnema.learning.platform.json.CanonicalJsonHasher;
import app.mnema.learning.platform.json.ContentJsonReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.List;

import static app.mnema.learning.storage.StorageTypes.NewObject;

/** A versioned internal fingerprint envelope; it is not a public content hash. */
@Component
final class StorageEncoding {
    static final int MAX_PAYLOAD_BYTES = 16_384;
    static final int MAX_BATCH_BYTES = 1_048_576;
    private final CanonicalJsonHasher canonical;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ContentJsonReader reader = new ContentJsonReader(MAX_PAYLOAD_BYTES, 128, 32_768);

    StorageEncoding(CanonicalJsonHasher canonical) {
        this.canonical = canonical;
    }

    String validatePayload(JsonNode payload) {
        requireJsonNodes(payload);
        try {
            var output = new BoundedOutput();
            mapper.writeValue(output, payload);
            byte[] bytes = output.bytes();
            reader.read(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid physical storage JSON");
        }
    }

    private static void requireJsonNodes(JsonNode root) {
        var pending = new ArrayDeque<AtDepth>();
        pending.add(new AtDepth(root, 1));
        int nodes = 1;
        while (!pending.isEmpty()) {
            AtDepth current = pending.removeLast();
            JsonNode node = current.node();
            if (current.depth() > 128) throw new IllegalArgumentException("Invalid physical storage JSON");
            if (node.isContainerNode()) {
                if (node.size() > 32_768 - nodes) throw new IllegalArgumentException("Invalid physical storage JSON");
                nodes += node.size();
                node.forEach(child -> pending.add(new AtDepth(child, current.depth() + 1)));
            } else if (!(node.isTextual() || node.isNumber() || node.isBoolean() || node.isNull())
                    || ((node.isDouble() || node.isFloat()) && !Double.isFinite(node.doubleValue()))) {
                // POJO, binary and missing nodes are not JSON scalars. Jackson can silently coerce them.
                throw new IllegalArgumentException("Invalid physical storage JSON");
            }
        }
    }

    private record AtDepth(JsonNode node, int depth) { }

    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        @Override public void write(int value) throws IOException {
            requireRoom(1);
            delegate.write(value);
        }
        @Override public void write(byte[] values, int offset, int length) throws IOException {
            requireRoom(length);
            delegate.write(values, offset, length);
        }
        private void requireRoom(int length) throws IOException {
            if (length > MAX_PAYLOAD_BYTES - delegate.size()) throw new IOException("Physical storage byte limit");
        }
        byte[] bytes() { return delegate.toByteArray(); }
    }

    JsonNode readPayload(String payload) {
        return reader.read(payload.getBytes(StandardCharsets.UTF_8));
    }

    byte[] fingerprint(NewObject object) {
        return canonical.hash(envelope(object)).sha256();
    }

    void requireBatchBudget(List<NewObject> objects) {
        ObjectNode batch = JsonNodeFactory.instance.objectNode();
        var values = batch.putArray("objects");
        objects.forEach(object -> values.add(envelope(object)));
        if (canonical.canonicalBytes(batch).length > MAX_BATCH_BYTES) {
            throw new StorageFailure(StorageFailure.Code.BUDGET_EXCEEDED);
        }
    }

    private ObjectNode envelope(NewObject object) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("fingerprintVersion", 1);
        value.put("objectId", object.objectId().toString());
        value.put("kind", object.kind().name());
        value.put("encodingVersion", object.encodingVersion());
        value.put("dagRank", object.dagRank());
        value.set("payload", object.payload());
        var edges = value.putArray("edges");
        object.edges().forEach(edge -> {
            var entry = edges.addObject();
            entry.put("ordinal", edge.ordinal());
            if (edge.logicalKey() == null) entry.putNull("logicalKey");
            else entry.put("logicalKey", edge.logicalKey().toString());
            entry.put("reuseScopeId", edge.child().reuseScopeId().toString());
            entry.put("childId", edge.child().objectId().toString());
        });
        return value;
    }
}
