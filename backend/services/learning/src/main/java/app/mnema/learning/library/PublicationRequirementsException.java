package app.mnema.learning.library;

import app.mnema.learning.platform.api.ProblemExtension;

import java.util.List;

/** The «Публичная» checklist is not met: {@code 409 PUBLICATION_REQUIREMENTS} with the failing checklist keys as member {@code failed}. */
public final class PublicationRequirementsException extends RuntimeException implements ProblemExtension.ProblemExtensionSource {
    private static final long serialVersionUID = 1L;

    private final transient List<String> failed;

    public PublicationRequirementsException(List<String> failed) {
        super("The deck does not meet the requirements of a public deck", null, false, false);
        this.failed = List.copyOf(failed);
    }

    public List<String> failed() { return failed; }

    @Override public ProblemExtension extension() { return ProblemExtension.builder().put("failed", failed).build(); }
}
