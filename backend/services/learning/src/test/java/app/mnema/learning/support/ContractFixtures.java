package app.mnema.learning.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads the shared wire fixtures of {@code contracts/study}, the same files the frontend consumes. */
public final class ContractFixtures {
    public static final JsonMapper JSON = JsonMapper.builder().build();

    private ContractFixtures() { }

    public static JsonNode fixture(String name) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/study/" + name))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot find repository root");
        try {
            return JSON.readTree(Files.readString(root.resolve("contracts/study/" + name)));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** A deep copy of one entry of {@code mechanics.json}, safe to mutate. */
    public static ObjectNode mechanic(String name) {
        return (ObjectNode) fixture("mechanics.json").path(name).deepCopy();
    }

    public static InputStream bytes(JsonNode value) {
        return new ByteArrayInputStream(value.toString().getBytes(StandardCharsets.UTF_8));
    }
}
