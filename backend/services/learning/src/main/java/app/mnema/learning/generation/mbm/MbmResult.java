package app.mnema.learning.generation.mbm;

import tools.jackson.databind.JsonNode;

import java.util.List;

/** Outcome of {@link MbmCompiler#compile}: a document with slots and warnings, or errors and no document. */
public sealed interface MbmResult {

    /**
     * A compiled document, already accepted by {@code NativeDocumentReader}.
     *
     * @param document native-v1 envelope; for an edit the range wrapped in a {@code doc} with the reserved root ID
     * @param nodeCount number of native nodes
     * @param slots one record per media directive, in document order
     * @param warnings ordered by (line, column)
     * @param lintFindings findings of the optional {@link MbmLint} hooks
     */
    record Success(JsonNode document, int nodeCount, List<MbmSlot> slots, List<MbmFinding> warnings,
                   List<MbmLint.LintFinding> lintFindings) implements MbmResult {

        public Success {
            document = document.deepCopy();
            slots = List.copyOf(slots);
            warnings = List.copyOf(warnings);
            lintFindings = List.copyOf(lintFindings);
        }

        /** A copy: the result owns its tree, callers cannot mutate it through the returned node. */
        @Override
        public JsonNode document() {
            return document.deepCopy();
        }
    }

    /** Compilation failed: at most 20 errors ordered by (line, column), and no document. */
    record Failure(List<MbmFinding> errors) implements MbmResult {

        public Failure {
            errors = List.copyOf(errors);
        }
    }
}
