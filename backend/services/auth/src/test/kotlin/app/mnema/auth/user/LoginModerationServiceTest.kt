package app.mnema.auth.user

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class LoginModerationServiceTest {

    private val userModerationClient = mock(UserModerationClient::class.java)
    private val service = LoginModerationService(userModerationClient)

    @Test
    fun `assert login allowed skips null user and missing moderation state`() {
        val userId = UUID.randomUUID()
        `when`(userModerationClient.getModerationState(userId)).thenReturn(null)

        assertDoesNotThrow { service.assertLoginAllowed(null) }
        assertDoesNotThrow { service.assertLoginAllowed(userId) }
        verify(userModerationClient).getModerationState(userId)
    }

    @Test
    fun `assert login allowed accepts active user`() {
        val userId = UUID.randomUUID()
        `when`(userModerationClient.getModerationState(userId)).thenReturn(
            UserModerationState(userId, admin = false, banned = false)
        )

        assertDoesNotThrow { service.assertLoginAllowed(userId) }
    }

    @Test
    fun `assert login allowed rejects banned user`() {
        val userId = UUID.randomUUID()
        `when`(userModerationClient.getModerationState(userId)).thenReturn(
            UserModerationState(userId, admin = false, banned = true)
        )

        val ex = assertThrows<ResponseStatusException> {
            service.assertLoginAllowed(userId)
        }

        assertEquals(HttpStatus.LOCKED, ex.statusCode)
        assertEquals("banned", ex.reason)
    }
}
