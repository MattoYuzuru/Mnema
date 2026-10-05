package app.mnema.learning.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LearningBoundaryTest {

    private final Path projectDirectory = projectDirectory();

    @Test
    void productionSourcesAndBuildHaveNoLegacyModuleDependency() throws Exception {
        assertThat(LegacyDependencyGuard.sourceViolations(projectDirectory.resolve("src/main/java"))).isEmpty();
        assertThat(LegacyDependencyGuard.declaresProjectDependency(
                Files.readString(projectDirectory.resolve("build.gradle.kts"))
        )).isFalse();
    }

    /**
     * The learning goal tunes client copy and the recommended tier only. No AI, generation, study or media code may read the
     * profile, so the goal can never be assembled into a prompt or sent to a provider.
     */
    @Test
    void theLearningGoalNeverReachesAProviderPath() throws Exception {
        for (String module : new String[] {"ai", "generation", "study", "media"}) {
            try (var files = Files.walk(projectDirectory.resolve("src/main/java/app/mnema/learning/" + module))) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    assertThat(Files.readString(file)).as(file.toString())
                            .doesNotContain("app.mnema.learning.profile").doesNotContain("LearningGoal");
                }
            }
        }
        try (var prompts = Files.walk(projectDirectory.resolve("src/main/resources/ai/prompts"))) {
            for (Path file : prompts.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(file).toLowerCase(java.util.Locale.ROOT)).as(file.toString())
                        .doesNotContain("learning-profile").doesNotContain("learning_goal");
            }
        }
    }

    @Test
    void guardRejectsLegacyImportFixture() {
        assertThat(LegacyDependencyGuard.rejectsSource("""
                package fixture;
                import app.mnema.core.deck.service.DeckService;
                final class InvalidDependency {}
                """)).isTrue();
        assertThat(LegacyDependencyGuard.declaresProjectDependency("implementation(project(\":services:core\"))"))
                .isTrue();
    }

    private static Path projectDirectory() {
        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        if (Files.isDirectory(workingDirectory.resolve("src/main"))) {
            return workingDirectory;
        }
        return workingDirectory.resolve("services/learning");
    }
}
