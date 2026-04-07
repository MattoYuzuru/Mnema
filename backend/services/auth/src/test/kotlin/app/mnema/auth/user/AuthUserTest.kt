package app.mnema.auth.user

import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthUserTest {

    @Test
    fun `locks account after max failed attempts`() {
        val now = Instant.parse("2026-04-07T10:00:00Z")
        val user = AuthUser(email = "user@example.com")

        user.registerFailedLogin(now, 2, Duration.ofMinutes(15))
        assertFalse(user.isLocked(now))
        user.registerFailedLogin(now, 2, Duration.ofMinutes(15))

        assertTrue(user.isLocked(now.plusSeconds(1)))
        assertEquals(0, user.failedLoginAttempts)
    }

    @Test
    fun `reset clears lock state`() {
        val user = AuthUser(email = "user@example.com")
        user.registerFailedLogin(Instant.now(), 1, Duration.ofMinutes(5))

        user.resetFailedLogins()

        assertEquals(0, user.failedLoginAttempts)
        assertNull(user.lockedUntil)
    }

    @Test
    fun `update profile keeps existing values when new ones are blank`() {
        val user = AuthUser(
            email = "user@example.com",
            name = "Existing Name",
            pictureUrl = "https://img.example/current.png"
        )

        user.updateProfile(" ", "", verified = true)

        assertTrue(user.emailVerified)
        assertEquals("Existing Name", user.name)
        assertEquals("https://img.example/current.png", user.pictureUrl)
    }
}
