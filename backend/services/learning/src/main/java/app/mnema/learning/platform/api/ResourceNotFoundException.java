package app.mnema.learning.platform.api;

/** Missing and inaccessible private identities deliberately share this failure. */
public final class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException() {
        super("Resource not found");
    }
}
