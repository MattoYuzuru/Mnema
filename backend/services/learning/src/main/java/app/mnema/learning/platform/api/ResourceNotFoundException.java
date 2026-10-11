package app.mnema.learning.platform.api;

/**
 * Missing and inaccessible private identities deliberately share this failure. {@link ContentUnavailableException} is the one subclass: content that
 * exists and was taken down after a complaint; every caller that treats a missing resource as absent treats it the same way.
 */
public class ResourceNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException() {
        super("Resource not found");
    }
}
