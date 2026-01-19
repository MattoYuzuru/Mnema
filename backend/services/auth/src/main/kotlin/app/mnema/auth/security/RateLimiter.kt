package app.mnema.auth.security

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

@Component
class RateLimiter {
    private data class WindowCounter(
        val windowStart: Instant,
        val count: Int
    )

    private val counters = ConcurrentHashMap<String, WindowCounter>()

    fun allow(key: String, limit: Int, window: Duration, now: Instant = Instant.now()): Boolean {
        val updated = counters.compute(key) { _, existing ->
            if (existing == null || Duration.between(existing.windowStart, now) > window) {
                WindowCounter(now, 1)
            } else {
                WindowCounter(existing.windowStart, existing.count + 1)
            }
        } ?: WindowCounter(now, 1)

        return updated.count <= limit
    }
}
