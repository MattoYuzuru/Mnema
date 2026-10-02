package app.mnema.learning.generation.mbm;

/**
 * Internal signal that a safety bound of the parser (work budget, nesting depth, node count) was exceeded. It is
 * always caught by the compiler and reported as {@link MbmCode#MBM_DOCUMENT_TOO_LARGE}; it never leaves the package.
 */
final class LimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    LimitExceededException() {
        super("MBM safety bound exceeded", null, false, false);
    }
}
