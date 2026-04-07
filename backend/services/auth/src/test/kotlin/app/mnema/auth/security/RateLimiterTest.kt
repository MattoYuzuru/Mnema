package app.mnema.auth.security

import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RateLimiterTest {

    @Test
    fun `allows requests until limit is reached`() {
        val limiter = RateLimiter()
        val now = Instant.parse("2026-04-07T10:00:00Z")

        assertTrue(limiter.allow("login:1", 2, Duration.ofMinutes(1), now))
        assertTrue(limiter.allow("login:1", 2, Duration.ofMinutes(1), now.plusSeconds(10)))
        assertFalse(limiter.allow("login:1", 2, Duration.ofMinutes(1), now.plusSeconds(20)))
    }

    @Test
    fun `resets counter after window elapses`() {
        val limiter = RateLimiter()
        val now = Instant.parse("2026-04-07T10:00:00Z")

        assertTrue(limiter.allow("register:1", 1, Duration.ofMinutes(1), now))
        assertFalse(limiter.allow("register:1", 1, Duration.ofMinutes(1), now.plusSeconds(30)))
        assertTrue(limiter.allow("register:1", 1, Duration.ofMinutes(1), now.plusSeconds(61)))
    }
}
