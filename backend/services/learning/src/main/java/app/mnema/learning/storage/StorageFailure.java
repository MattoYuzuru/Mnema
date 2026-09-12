package app.mnema.learning.storage;

/** Internal failures contain no payload, object identifiers or database exception details. */
public final class StorageFailure extends RuntimeException {
    public enum Code { OBJECT_MISSING, OBJECT_MISMATCH, PREPARATION_EXPIRED, INVALID_PREPARATION, BUDGET_EXCEEDED }

    private final Code code;

    public StorageFailure(Code code) {
        super("Storage operation rejected: " + code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
