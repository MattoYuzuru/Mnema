package app.mnema.learning.generation.exercise;

import app.mnema.learning.catalog.exercise.ExerciseCommand;
import app.mnema.learning.generation.exercise.ExerciseFixtures.Fixture;
import app.mnema.learning.generation.exercise.ExerciseFixtures.GoldenIds;
import app.mnema.learning.study.attempt.ExerciseProbeEvaluator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executes every committed fixture of {@code contracts/generation/exercises}: a valid one compiles to exactly its
 * {@code expectedCommand} and {@code expectedIdMap} (with the golden allocator), parses through {@code ExerciseCommand.readCreate}
 * and passes the probes the server generates; a failing one produces exactly its {@code expectedLint} codes. Nothing here is
 * mocked: it is the pipeline the generation step runs.
 */
class ExerciseValidationFixtureTest {
    private static final ExerciseValidator VALIDATOR = new ExerciseValidator(ExerciseOutputSchema.load());

    @Test
    void everyValidFixtureCompilesToItsGoldenCommandAndPassesItsProbes() {
        int valid = 0;
        for (Fixture fixture : ExerciseFixtures.all()) {
            if (!fixture.valid()) continue;
            valid++;
            ExerciseContext context = fixture.context();
            ExerciseValidator.Verdict verdict = VALIDATOR.validate(0, fixture.exercise(), context, new GoldenIds());
            assertThat(verdict).as(fixture.name()).isInstanceOf(ExerciseValidator.Valid.class);
            ExerciseValidator.Accepted accepted = ((ExerciseValidator.Valid) verdict).exercise();
            assertThat(accepted.command()).as(fixture.name() + " command").isEqualTo(fixture.json().path("expectedCommand"));

            ExerciseCompiler.Compiled compiled = ExerciseCompiler.compile(fixture.exercise(), context, new GoldenIds());
            ObjectNode idMap = ExerciseFixtures.JSON.createObjectNode();
            compiled.idMap().forEach((local, id) -> idMap.put(local, id.toString()));
            assertThat(idMap).as(fixture.name() + " id map").isEqualTo(fixture.json().path("expectedIdMap"));

            ExerciseCommand command = ExerciseCommand.readCreate(new ByteArrayInputStream(
                    compiled.command().toString().getBytes(StandardCharsets.UTF_8)));
            List<ExerciseProbeEvaluator.Outcome> outcomes = ExerciseProbeEvaluator.run(command.exercise());
            assertThat(outcomes).as(fixture.name() + " probes").allMatch(ExerciseProbeEvaluator.Outcome::passed);
            Set<String> generated = new HashSet<>();
            outcomes.forEach(outcome -> generated.add(outcome.probe().name()));
            Set<String> recorded = new TreeSet<>();
            fixture.json().path("expectedSelfEvaluation").forEach(probe -> recorded.add(probe.path("probe").stringValue(null)));
            assertThat(generated).as(fixture.name() + " generates every recorded probe").containsAll(recorded);
            if (command.exercise().type().name().equals("SELF_CHECK")) assertThat(outcomes).isEmpty();
        }
        assertThat(valid).isGreaterThanOrEqualTo(8);
    }

    @Test
    void everyFailingFixtureProducesExactlyItsExpectedFindings() {
        int failing = 0;
        Set<String> covered = new TreeSet<>();
        for (Fixture fixture : ExerciseFixtures.all()) {
            if (fixture.valid()) continue;
            failing++;
            ExerciseValidator.Verdict verdict = VALIDATOR.validate(0, fixture.exercise(), fixture.context(), new GoldenIds());
            assertThat(verdict).as(fixture.name()).isInstanceOf(ExerciseValidator.Invalid.class);
            Set<String> codes = new TreeSet<>();
            ((ExerciseValidator.Invalid) verdict).findings().forEach(finding -> codes.add(finding.code().name()));
            List<String> expected = new ArrayList<>();
            fixture.json().path("expectedLint").forEach(code -> expected.add(code.stringValue(null)));
            assertThat(new ArrayList<>(codes)).as(fixture.name()).isEqualTo(expected);
            covered.addAll(codes);
        }
        assertThat(failing).isGreaterThanOrEqualTo(20);
        // every code of lint.json that a fixture can express is executed by one
        Set<String> exempt = Set.of("COMMAND_REJECTED", "SELF_EVALUATION_KEY_NOT_CORRECT", "SELF_EVALUATION_PROBE_ACCEPTED", "DUPLICATE_EXERCISE",
                "FREE_RESPONSE_ALTERNATIVES_NOT_DISTINCT");
        Set<String> all = new TreeSet<>();
        for (ExerciseCode code : ExerciseCode.values()) all.add(code.name());
        all.removeAll(exempt);
        assertThat(covered).containsAll(all);
    }

    @Test
    void theCodesOfTheEnumAreExactlyTheCodesOfLintJson() throws IOException {
        JsonNode lint = ExerciseFixtures.JSON.readTree(Files.readString(ExerciseFixtures.root()
                .resolve("contracts/generation/exercises/lint.json")));
        List<String> contract = new ArrayList<>();
        lint.path("codes").forEach(code -> {
            contract.add(code.path("code").stringValue(null));
        });
        List<String> implemented = new ArrayList<>();
        for (ExerciseCode code : ExerciseCode.values()) implemented.add(code.name());
        assertThat(implemented).containsExactlyElementsOf(contract);
        for (JsonNode code : lint.path("codes")) {
            assertThat(ExerciseCode.valueOf(code.path("code").stringValue(null)).phase().name()).isEqualTo(code.path("phase").stringValue(null));
        }
    }

    @Test
    void theClasspathSchemaIsTheContractFile() throws IOException {
        String contract = Files.readString(ExerciseFixtures.root().resolve("contracts/generation/exercises/output.schema.json"));
        String classpath = Files.readString(ExerciseFixtures.root()
                .resolve("backend/services/learning/src/main/resources/ai/exercises/output.schema.json"));
        assertThat(classpath).isEqualTo(contract);
        assertThat(ExerciseOutputSchema.load().text()).isEqualTo(contract);
    }
}
