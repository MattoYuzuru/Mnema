package app.mnema.learning.platform.api;

/** A decision needs a fact only Identity knows and Identity did not answer ({@code 503 IDENTITY_UNAVAILABLE}): the request is refused, never assumed. */
public final class IdentityUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public IdentityUnavailableException() {
        super("Identity unavailable", null, false, false);
    }
}
