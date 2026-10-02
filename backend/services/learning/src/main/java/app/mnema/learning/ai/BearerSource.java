package app.mnema.learning.ai;

import java.time.Duration;

/** Supplies the bearer credential of a provider; the value is never logged or placed in an exception. */
interface BearerSource {
    /** Whether a credential is available at all (key present), without calling the provider. */
    boolean configured();

    AiResult<String> bearer(Duration budget);

    /** The provider rejected the credential (401): drop any cached token so the next call exchanges again. */
    default void invalidate() { }

    /** A static API key. */
    static BearerSource staticKey(String key) {
        return new BearerSource() {
            @Override public boolean configured() { return !key.isEmpty(); }

            @Override public AiResult<String> bearer(Duration budget) {
                return key.isEmpty() ? AiResult.failed(new AiFailure.NotConfigured("no_key")) : AiResult.ok(key);
            }
        };
    }
}
