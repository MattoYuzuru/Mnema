package app.mnema.learning.platform.api;

/** A caller with a valid token who is not allowed to do this ({@code 403 ACCESS_DENIED}), for example a non-admin on an admin endpoint. */
public final class AccessForbiddenException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AccessForbiddenException() {
        super("Access denied", null, false, false);
    }
}
