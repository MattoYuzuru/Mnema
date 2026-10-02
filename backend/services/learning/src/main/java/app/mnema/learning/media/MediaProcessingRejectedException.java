package app.mnema.learning.media;

/** A stable, non-sensitive media processing failure that will not succeed on retry. */
final class MediaProcessingRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;

    MediaProcessingRejectedException(String code) {
        super(code);
        this.code = code;
    }

    String code() { return code; }
}
