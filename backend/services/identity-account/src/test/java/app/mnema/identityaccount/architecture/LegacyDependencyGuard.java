package app.mnema.identityaccount.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class LegacyDependencyGuard {

    private static final Pattern LEGACY_JAVA_REFERENCE = Pattern.compile(
            "\\bapp\\.mnema\\.(?:auth|user|core|media|importer|ai|learning)(?:\\.|;)"
    );
    private static final Pattern PROJECT_DEPENDENCY = Pattern.compile("\\bproject\\s*\\(");

    private LegacyDependencyGuard() {
    }

    static List<String> sourceViolations(Path sourceRoot) throws IOException {
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (LEGACY_JAVA_REFERENCE.matcher(Files.readString(file)).find()) {
                    violations.add(sourceRoot.relativize(file).toString());
                }
            }
        }
        return violations;
    }

    static boolean rejectsSource(String source) {
        return LEGACY_JAVA_REFERENCE.matcher(source).find();
    }

    static boolean declaresProjectDependency(String buildScript) {
        return PROJECT_DEPENDENCY.matcher(buildScript).find();
    }
}
