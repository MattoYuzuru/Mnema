package app.mnema.learning.onboarding;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads {@code contracts/onboarding/milestones.json} (the registry the frontend executes too) and the V74 migration that mirrors its keys. */
final class MilestoneFixture {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final JsonNode REGISTRY = read("contracts/onboarding/milestones.json");
    static final String MIGRATION = text("backend/services/learning/src/main/resources/db/learning/migration/V74__account_milestone.sql");

    private MilestoneFixture() { }

    /** The repository root, found from the working directory of the test. */
    static Path root() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/onboarding/milestones.json"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot find repository root");
        return root;
    }

    static String text(String relative) {
        try {
            return Files.readString(root().resolve(relative));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static JsonNode read(String relative) {
        return JSON.readTree(text(relative));
    }
}
