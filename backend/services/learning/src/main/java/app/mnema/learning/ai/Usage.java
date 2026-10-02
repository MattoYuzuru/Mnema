package app.mnema.learning.ai;

/**
 * Provider-reported token usage. {@code promptTokens = cacheHitTokens + cacheMissTokens}; providers without a cache
 * report everything as a miss.
 */
public record Usage(int promptTokens, int cacheHitTokens, int cacheMissTokens, int completionTokens) {
    public static final Usage ZERO = new Usage(0, 0, 0, 0);

    public Usage {
        if (promptTokens < 0 || cacheHitTokens < 0 || cacheMissTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("Token counts must not be negative");
        }
    }
}
