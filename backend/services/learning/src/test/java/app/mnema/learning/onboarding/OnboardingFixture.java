package app.mnema.learning.onboarding;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads {@code contracts/onboarding/journey.json}, the file the frontend contract spec executes too. */
final class OnboardingFixture {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final JsonNode CONTRACT = read();

    private OnboardingFixture() { }

    private static JsonNode read() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/onboarding/journey.json"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot find repository root");
        try {
            return JSON.readTree(Files.readString(root.resolve("contracts/onboarding/journey.json")));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** A mutable copy of a fixture object. */
    static ObjectNode copy(JsonNode node) {
        return (ObjectNode) node.deepCopy();
    }

    static InputStream bytes(JsonNode value) {
        return new ByteArrayInputStream(value.toString().getBytes(StandardCharsets.UTF_8));
    }
}
