package app.mnema.learning.onboarding;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern ROUTE = Pattern.compile("^(GET|PUT|POST|PATCH|DELETE) (/api/\\S+)");

    /**
     * The routes a milestone's {@code writer} text names, as the handler mapping spells them ({@code "PUT /onboarding/preferences"}, without the {@code /api} prefix): a part of
     * the text that starts with an HTTP method and an {@code /api} path. The other parts are {@code Class.method} writers.
     */
    static List<String> routes(JsonNode milestone) {
        List<String> routes = new ArrayList<>();
        String text = milestone.path("writer").stringValue(null);
        if (text == null) return routes;
        for (String part : text.split(",")) {
            Matcher matcher = ROUTE.matcher(part.strip());
            if (matcher.find()) routes.add(matcher.group(1) + " " + matcher.group(2).substring("/api".length()));
        }
        return routes;
    }

    private static JsonNode read(String relative) {
        return JSON.readTree(text(relative));
    }
}
