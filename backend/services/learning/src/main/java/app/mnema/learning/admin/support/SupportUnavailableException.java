package app.mnema.learning.admin.support;

/** No trusted acknowledgement exists; mutations retain their exact command for retry. */
public final class SupportUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;
}
