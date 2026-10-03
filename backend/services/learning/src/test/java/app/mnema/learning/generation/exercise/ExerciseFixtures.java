package app.mnema.learning.generation.exercise;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** The committed fixtures of {@code contracts/generation/exercises} and the golden allocator of their README. */
final class ExerciseFixtures {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Path DIRECTORY = root().resolve("contracts/generation/exercises/fixtures");

    private ExerciseFixtures() { }

    static Path root() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/generation/states.json"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot find repository root");
        return root;
    }

    record Fixture(String name, JsonNode json) {
        JsonNode exercise() { return json.path("modelOutput").path("exercises").get(0); }

        boolean valid() { return json.has("expectedCommand"); }

        ExerciseContext context() { return ExerciseFixtures.context(json.path("context")); }
    }

    static List<Fixture> all() {
        try (Stream<Path> files = Files.list(DIRECTORY)) {
            List<Fixture> fixtures = new ArrayList<>();
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                fixtures.add(new Fixture(file.getFileName().toString(), JSON.readTree(Files.readString(file, StandardCharsets.UTF_8))));
            }
            return fixtures;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** {@code materials}, {@code objectives} and the optional {@code allowedMechanics} of a fixture context. */
    static ExerciseContext context(JsonNode context) {
        Map<String, ExerciseContext.Material> materials = new LinkedHashMap<>();
        context.path("materials").properties().forEach(material -> {
            Map<String, ExerciseContext.Block> blocks = new LinkedHashMap<>();
            material.getValue().path("blocks").properties().forEach(block -> blocks.put(block.getKey(), new ExerciseContext.Block(
                    UUID.fromString(block.getValue().path("nodeId").stringValue(null)), block.getValue().path("text").stringValue(null))));
            materials.put(material.getKey(), new ExerciseContext.Material(UUID.fromString(material.getValue().path("memberKey").stringValue(null)),
                    UUID.fromString(material.getValue().path("itemRevisionId").stringValue(null)), blocks));
        });
        Map<String, ExerciseContext.Objective> objectives = new LinkedHashMap<>();
        context.path("objectives").properties().forEach(objective -> objectives.put(objective.getKey(), new ExerciseContext.Objective(
                UUID.fromString(objective.getValue().path("objectiveId").stringValue(null)),
                UUID.fromString(objective.getValue().path("objectiveRevisionId").stringValue(null)),
                objective.getValue().path("title").stringValue(null))));
        Set<String> allowed = ExerciseContext.ALL_MECHANICS;
        if (context.has("allowedMechanics")) {
            List<String> listed = new ArrayList<>();
            context.path("allowedMechanics").forEach(mechanic -> listed.add(mechanic.stringValue(null)));
            allowed = Set.copyOf(listed);
        }
        return new ExerciseContext(UUID.fromString(context.path("commandId").stringValue(null)),
                UUID.fromString(context.path("expectedDeckRevisionId").stringValue(null)), materials, objectives, allowed);
    }

    /** The golden allocator: {@code <prefix>-0000-4000-8000-<12 hex digits>}, counted from 1 per class. */
    static final class GoldenIds implements ExerciseIds {
        private static final Map<Kind, String> PREFIX = new EnumMap<>(Map.of(Kind.OPTION, "0b000000", Kind.BLANK, "b1a00000",
                Kind.LEFT, "1e000000", Kind.RIGHT, "7e000000", Kind.ITEM, "0d000000", Kind.CATEGORY, "ca000000"));
        private final Map<Kind, Integer> counters = new EnumMap<>(Kind.class);

        @Override
        public UUID next(Kind kind) {
            int number = counters.merge(kind, 1, Integer::sum);
            return UUID.fromString("%s-0000-4000-8000-%012x".formatted(PREFIX.get(kind), number));
        }
    }
}
