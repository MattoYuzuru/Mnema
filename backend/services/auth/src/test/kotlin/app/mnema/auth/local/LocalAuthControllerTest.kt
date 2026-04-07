package app.mnema.auth.local

import app.mnema.auth.security.LocalTokenResponse
import app.mnema.auth.security.TurnstileService
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.security.oauth2.jwt.Jwt

class LocalAuthControllerTest {

    private val authService = mock(LocalAuthService::class.java)
    private val turnstileService = mock(TurnstileService::class.java)
    private val controller = LocalAuthController(authService, turnstileService)

    @Test
    fun `register maps service response and uses forwarded ip`() {
        val request = LocalAuthController.RegisterRequest("user@example.com", "mnema", "secret-password", "captcha")
        val servletRequest = mock(HttpServletRequest::class.java)
        `when`(servletRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.10, 127.0.0.1")
        `when`(authService.register(request, "203.0.113.10"))
            .thenReturn(LocalTokenResponse("token", 3600, "Bearer", "openid profile"))

        val response = controller.register(request, servletRequest)

        assertEquals("token", response.access_token)
        assertEquals(3600, response.expires_in)
        assertEquals("Bearer", response.token_type)
        assertEquals("openid profile", response.scope)
    }

    @Test
    fun `login falls back to remote address`() {
        val request = LocalAuthController.LoginRequest("mnema", "secret-password", "captcha")
        val servletRequest = mock(HttpServletRequest::class.java)
        `when`(servletRequest.getHeader("X-Forwarded-For")).thenReturn(null)
        `when`(servletRequest.remoteAddr).thenReturn("127.0.0.1")
        `when`(authService.login(request, "127.0.0.1"))
            .thenReturn(LocalTokenResponse("token", 1800, "Bearer", "openid"))

        val response = controller.login(request, servletRequest)

        assertEquals("token", response.access_token)
        assertEquals(1800, response.expires_in)
    }

    @Test
    fun `turnstile config converts blank site key to null`() {
        `when`(turnstileService.enabled()).thenReturn(true)
        `when`(turnstileService.siteKey()).thenReturn("")

        val response = controller.turnstileConfig()

        assertEquals(true, response.enabled)
        assertNull(response.siteKey)
    }

    @Test
    fun `password endpoints delegate to service`() {
        val jwt = Jwt.withTokenValue("token").header("alg", "none").claim("user_id", "123e4567-e89b-12d3-a456-426614174000").build()
        `when`(authService.passwordStatus(jwt)).thenReturn(true)

        val status = controller.passwordStatus(jwt)
        val changed = controller.changePassword(jwt, LocalAuthController.PasswordChangeRequest(currentPassword = "old", newPassword = "new-secret"))
        controller.deleteAccount(jwt)

        assertEquals(true, status.hasPassword)
        assertEquals(true, changed.hasPassword)
        verify(authService).changePassword(jwt, LocalAuthController.PasswordChangeRequest(currentPassword = "old", newPassword = "new-secret"))
        verify(authService).deleteAccount(jwt)
    }
}
