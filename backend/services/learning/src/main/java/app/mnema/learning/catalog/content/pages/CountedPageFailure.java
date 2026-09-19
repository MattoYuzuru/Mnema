package app.mnema.learning.catalog.content.pages;

/** Internal page errors deliberately exclude content and identifiers. */
public final class CountedPageFailure extends RuntimeException {
    public enum Code { INVALID_PAGE, INVALID_RANGE, KEY_MISMATCH, BUDGET_EXCEEDED }
    private final Code code;

    public CountedPageFailure(Code code) {
        super("Counted page operation rejected: " + code.name());
        this.code = code;
    }

    public Code code() { return code; }
}
