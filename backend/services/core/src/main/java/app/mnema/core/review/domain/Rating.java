package app.mnema.core.review.domain;

public enum Rating {
    AGAIN(0), HARD(1), GOOD(2), EASY(3);

    private final int code;
    Rating(int code) { this.code = code; }
    public int code() { return code; }

    public static Rating fromString(String v) {
        return Rating.valueOf(v.trim().toUpperCase());
    }
}
