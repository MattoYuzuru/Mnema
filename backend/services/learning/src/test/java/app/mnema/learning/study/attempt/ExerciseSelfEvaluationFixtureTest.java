package app.mnema.learning.study.attempt;

import app.mnema.learning.catalog.exercise.ExerciseCommand;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every {@code expectedSelfEvaluation} probe of {@code contracts/generation/exercises/fixtures} through
 * {@link ExerciseProbeEvaluator}, the public door to {@link AttemptEvaluation}, the evaluator behind {@code /exercise-previews}
 * (the generation step uses the same facade); like the preview service it passes the authored content as the learner content.
 * The lint and compile of those fixtures are executed by {@code ExerciseValidationFixtureTest}.
 */
class ExerciseSelfEvaluationFixtureTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void everyProbeOfEveryValidFixtureEvaluatesToTheExpectedResult() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/generation/states.json"))) root = root.getParent();
        assertThat(root).as("repository root").isNotNull();
        Set<String> mechanics = new TreeSet<>();
        int probes = 0;
        try (Stream<Path> files = Files.list(root.resolve("contracts/generation/exercises/fixtures"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                JsonNode fixture = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
                if (!fixture.has("expectedCommand")) continue;
                ExerciseCommand command = ExerciseCommand.readCreate(new ByteArrayInputStream(
                        fixture.path("expectedCommand").toString().getBytes(StandardCharsets.UTF_8)));
                mechanics.add(command.exercise().type().name());
                for (JsonNode probe : fixture.path("expectedSelfEvaluation")) {
                    String result = ExerciseProbeEvaluator.evaluate(command.exercise(), response(probe.path("response"), fixture.path("expectedIdMap")));
                    assertThat(result).as(file.getFileName() + " " + probe.path("probe").stringValue(null))
                            .isEqualTo(probe.path("expectedResult").stringValue(null));
                    probes++;
                }
            }
        }
        assertThat(mechanics).hasSize(7);
        assertThat(probes).isGreaterThanOrEqualTo(19);
    }

    private static UUID id(String local, JsonNode map) {
        assertThat(map.has(local)).as("local id " + local).isTrue();
        return UUID.fromString(map.path(local).stringValue(null));
    }

    private static AttemptCommand.Response response(JsonNode response, JsonNode map) {
        return switch (response.path("kind").stringValue(null)) {
            case "TEXT" -> new AttemptCommand.TextResponse(response.path("text").stringValue(null));
            case "CLOZE" -> {
                List<AttemptCommand.BlankText> blanks = new ArrayList<>();
                response.path("blanks").forEach(blank -> blanks.add(new AttemptCommand.BlankText(
                        id(blank.path("blankId").stringValue(null), map), blank.path("text").stringValue(null))));
                yield new AttemptCommand.ClozeResponse(blanks);
            }
            case "CHOICE" -> {
                List<UUID> ids = new ArrayList<>();
                response.path("optionIds").forEach(option -> ids.add(id(option.stringValue(null), map)));
                yield new AttemptCommand.ChoiceResponse(ids);
            }
            case "MATCH" -> {
                List<AttemptCommand.MatchPair> pairs = new ArrayList<>();
                response.path("pairs").forEach(pair -> pairs.add(new AttemptCommand.MatchPair(
                        id(pair.path("leftId").stringValue(null), map), id(pair.path("rightId").stringValue(null), map))));
                yield new AttemptCommand.MatchResponse(pairs);
            }
            case "ORDER" -> {
                List<UUID> ids = new ArrayList<>();
                response.path("sequence").forEach(item -> ids.add(id(item.stringValue(null), map)));
                yield new AttemptCommand.OrderResponse(ids);
            }
            case "CATEGORIZE" -> {
                List<AttemptCommand.CategoryAssignment> assignments = new ArrayList<>();
                response.path("assignments").forEach(assignment -> assignments.add(new AttemptCommand.CategoryAssignment(
                        id(assignment.path("itemId").stringValue(null), map), id(assignment.path("categoryId").stringValue(null), map))));
                yield new AttemptCommand.CategorizeResponse(assignments);
            }
            default -> throw new IllegalArgumentException("Unknown probe response kind");
        };
    }
}
