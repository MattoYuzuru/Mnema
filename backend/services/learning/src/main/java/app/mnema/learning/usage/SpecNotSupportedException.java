package app.mnema.learning.usage;

import app.mnema.learning.platform.api.ProblemExtension;

import java.util.Objects;

/** The spec is valid but its kind is not supported yet: {@code 422 SPEC_NOT_SUPPORTED} with the {@code kind} member. */
public final class SpecNotSupportedException extends RuntimeException implements ProblemExtension.ProblemExtensionSource {
    private static final long serialVersionUID = 1L;

    private final String kind;

    public SpecNotSupportedException(String kind) {
        super("Spec not supported", null, false, false);
        this.kind = Objects.requireNonNull(kind, "kind");
    }


    @Override
    public ProblemExtension extension() {
        return ProblemExtension.builder().put("kind", kind).build();
    }
}
