package app.mnema.learning.update;

import app.mnema.learning.platform.api.InvalidRequestException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * What the caller of a {@link CombinedUnits} command wants written in the SAME transaction as the new deck revision (Updates/4: the copy's
 * {@code deck_fork.base_revision_id} and its per-unit decisions). Generic on purpose: a type tag and an opaque JSON object of at most
 * {@value #MAX_BYTES} bytes (4 MiB since Updates/4, #443, migration V63: the per-unit decisions of an update of thousands of units; it was 64 KiB), so this module needs no
 * schema of the decisions and a job can persist the writes together with the command.
 * The engine passes them to the one {@link FinalWriter} bean, after the heads and the journal and before the receipt.
 *
 * @param type a short lowercase tag the writer dispatches on, {@code ^[a-z][a-z0-9_.-]{0,63}$}
 * @param body the opaque payload, an object whose compact JSON is at most {@value #MAX_BYTES} bytes
 */
public record FinalWrites(String type, ObjectNode body) {
    /** The most bytes of the compact JSON of the body. */
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public FinalWrites {
        if (type == null || !TYPE.matcher(type).matches() || body == null) throw new InvalidRequestException();
        body = body.deepCopy();
        try {
            if (JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new InvalidRequestException();
        } catch (JacksonException unwritable) {
            throw new InvalidRequestException();
        }
    }

    @Override
    public ObjectNode body() { return body.deepCopy(); }

    /** The canonical form: part of the command's identity and of its stored copy. */
    ObjectNode toJson() {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", type);
        node.set("body", body.deepCopy());
        return node;
    }

    static FinalWrites fromJson(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (!node.path("type").isString() || !(node.path("body") instanceof ObjectNode body)) throw new InvalidRequestException();
        return new FinalWrites(node.path("type").stringValue(null), body);
    }
}
