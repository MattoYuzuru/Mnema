package app.mnema.learning.ai;

/**
 * Text generation port. The implementation routes, retries, falls back, journals and meters; it never opens a database
 * transaction around the provider call and refuses to run inside one. It reports usage and cost but never debits the
 * user's quota: that is the caller's step code.
 */
public interface TextGeneration {
    AiResult<TextResponse> generate(TextRequest request);
}
