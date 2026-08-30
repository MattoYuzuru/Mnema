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
