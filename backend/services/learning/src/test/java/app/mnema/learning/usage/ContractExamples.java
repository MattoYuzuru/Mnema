package app.mnema.learning.usage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads the shared wire fixtures of {@code contracts/generation}, the same files the frontend consumes. */
final class ContractExamples {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ContractExamples() { }

    static JsonNode generation(String example) {
        return http().path("examples").path(example);
    }

    static JsonNode http() {
        return read("contracts/generation/http.json");
    }

    static JsonNode errors() {
        return read("contracts/generation/errors.json");
    }

    static JsonNode read(String relative) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve(relative))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot find repository root");
        try {
            return JSON.readTree(Files.readString(root.resolve(relative)));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
