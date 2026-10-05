package app.mnema.learning.ai;

import java.util.Locale;

/**
 * Provider capability classes. Each has its own per-instance concurrency limit, daily budget and circuit breakers;
 * {@code ASSESS} (semantic grading) is separate from {@code TEXT} so interactive grading never queues behind drafts;
 * {@code IMAGE_SEARCH} (licensed stock photos) is separate from {@code SEARCH} (paid web search) because their cost and
 * limits differ; {@code STT} (speech to text, #298) is a separate class of the speech inputs of the learners.
 */
public enum AiCapability {
    TEXT, ASSESS, TTS, STT, IMAGE, IMAGE_SEARCH, VIDEO, SEARCH;

    /** Stable lower-case label used in logs, metrics and the {@code ai_provider_call.capability} column (upper case). */
    public String label() { return name().toLowerCase(Locale.ROOT); }
}
