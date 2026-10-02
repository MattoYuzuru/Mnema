package app.mnema.learning.study.attempt;

public final class PresentationExpiredException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PresentationExpiredException() { super("Study presentation expired"); }
}
