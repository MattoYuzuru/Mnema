package app.mnema.learning.generation.mbm;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Optional post-compile hook, for example the copy lint (an n-gram of at most 8 words shared with a reference text).
 * The lint sees the normalized source and the finished native-v1 document; its findings never fail a compilation
 * and travel in {@link MbmResult.Success#lintFindings()}. No lint is implemented in this package.
 */
@FunctionalInterface
public interface MbmLint {

    List<LintFinding> check(String normalizedSource, JsonNode document);

    /** A lint finding: a 1-based source line (0 when none) and the lint's own stable code. */
    record LintFinding(int line, String code) {
    }
}
