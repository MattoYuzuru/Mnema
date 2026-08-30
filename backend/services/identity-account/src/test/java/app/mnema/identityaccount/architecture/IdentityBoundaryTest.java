package app.mnema.identityaccount.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityBoundaryTest {

    private final Path projectDirectory = projectDirectory();

    @Test
    void productionSourcesAndBuildHaveNoLegacyOrLearningDependency() throws Exception {
        assertThat(LegacyDependencyGuard.sourceViolations(projectDirectory.resolve("src/main/java"))).isEmpty();
        assertThat(LegacyDependencyGuard.declaresProjectDependency(
                Files.readString(projectDirectory.resolve("build.gradle.kts"))
        )).isFalse();
    }

    @Test
    void guardRejectsLegacyImportFixture() {
        assertThat(LegacyDependencyGuard.rejectsSource("""
                package fixture;
                import app.mnema.auth.user.AuthUser;
                final class InvalidDependency {}
                """)).isTrue();
        assertThat(LegacyDependencyGuard.declaresProjectDependency(
                "implementation(project(\":services:auth\"))"
        )).isTrue();
    }

    private static Path projectDirectory() {
        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        if (Files.isDirectory(workingDirectory.resolve("src/main"))) {
            return workingDirectory;
        }
        return workingDirectory.resolve("services/identity-account");
    }
}
