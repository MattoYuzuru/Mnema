package app.mnema.learning.catalog.content.storage;

/** Internal codec failures contain neither content nor physical/semantic identifiers. */
public final class NativeStorageFailure extends RuntimeException {
    public enum Code { INVALID_GRAPH, BUDGET_EXCEEDED, STRUCTURE_CHANGED, PREPARATION_EXPIRED, INCOMPLETE }

    private final Code code;

    public NativeStorageFailure(Code code) {
        super("Native storage operation rejected: " + code.name());
        this.code = code;
    }

    public Code code() { return code; }
}
