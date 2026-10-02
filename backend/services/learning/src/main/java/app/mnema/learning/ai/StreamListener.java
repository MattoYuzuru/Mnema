package app.mnema.learning.ai;

/** Receives text deltas of a streamed call, on the calling thread. */
public interface StreamListener {
    void onDelta(String text);

    /** The previous attempt is abandoned after deltas were delivered; the next deltas restart the text. */
    default void onRestart() { }
}
