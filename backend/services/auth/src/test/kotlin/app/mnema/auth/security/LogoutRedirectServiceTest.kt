package app.mnema.auth.security

import app.mnema.auth.config.CorsProps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LogoutRedirectServiceTest {

    @Test
    fun `returns default redirect when parameter is missing`() {
        val service = LogoutRedirectService(
            corsProps = CorsProps(listOf("https://mnema.app")),
            defaultRedirect = "https://mnema.app/"
        )

        assertEquals("https://mnema.app/", service.resolve(null))
    }

    @Test
    fun `allows safe relative redirect`() {
        val service = LogoutRedirectService(
            corsProps = CorsProps(listOf("https://mnema.app")),
            defaultRedirect = "https://mnema.app/"
        )

        assertEquals("/profile", service.resolve("/profile"))
    }

    @Test
    fun `rejects redirect to unknown origin`() {
        val service = LogoutRedirectService(
            corsProps = CorsProps(listOf("https://mnema.app", "https://app.mnema.dev")),
            defaultRedirect = "https://mnema.app/"
        )

        assertEquals("https://mnema.app/", service.resolve("https://evil.example/logout"))
    }

    @Test
    fun `allows configured origin with explicit port`() {
        val service = LogoutRedirectService(
            corsProps = CorsProps(listOf("https://localhost:3005")),
            defaultRedirect = "https://mnema.app/"
        )

        assertEquals("https://localhost:3005/after-logout", service.resolve("https://localhost:3005/after-logout"))
    }
}
