package app.mnema.learning.ai;

import java.time.Duration;

/** Backoff wait, replaceable in tests so retries never sleep for real. */
@FunctionalInterface
interface Sleeper {
    Sleeper SYSTEM = duration -> Thread.sleep(duration);

    void sleep(Duration duration) throws InterruptedException;
}
