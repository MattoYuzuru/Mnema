package app.mnema.user.controller

import app.mnema.user.auth.AuthAccountClient
import app.mnema.user.entity.User
import app.mnema.user.media.client.MediaResolved
import app.mnema.user.media.service.MediaResolveCache
import app.mnema.user.repository.UserRepository
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.server.ResponseStatusException

class MeControllerTest {

    private val repository = mock(UserRepository::class.java)
    private val mediaResolveCache = mock(MediaResolveCache::class.java)
    private val authAccountClient = mock(AuthAccountClient::class.java)
    private val controller = MeController(repository, mediaResolveCache, authAccountClient)

    @Test
    fun `returns existing user with resolved avatar media url`() {
        val userId = UUID.randomUUID()
        val mediaId = UUID.randomUUID()
        val user = User(
            id = userId,
            email = "user@example.com",
            username = "mnema",
            avatarMediaId = mediaId,
            avatarUrl = null
        )
        val jwt = jwt(userId, "user@example.com")
        `when`(repository.findById(userId)).thenReturn(Optional.of(user))
        `when`(mediaResolveCache.resolveAvatar(mediaId, jwt.tokenValue)).thenReturn(
            MediaResolved(mediaId = mediaId, url = "https://cdn.example/avatar.png", expiresAt = Instant.now().plusSeconds(300))
        )

        val response = controller.getOrCreate(jwt)

        assertEquals("https://cdn.example/avatar.png", response.avatarUrl)
    }

    @Test
    fun `updating avatar url clears avatar media id`() {
        val userId = UUID.randomUUID()
        val user = User(
            id = userId,
            email = "user@example.com",
            username = "mnema",
            avatarMediaId = UUID.randomUUID(),
            avatarUrl = null
        )
        `when`(repository.findById(userId)).thenReturn(Optional.of(user))

        val response = controller.update(
            jwt(userId, "user@example.com"),
            MeController.MeUpdateRequest(avatarUrl = "https://img.example/new.png")
        )

        assertEquals("https://img.example/new.png", response.avatarUrl)
        assertNull(response.avatarMediaId)
    }

    @Test
    fun `delete maps auth deletion failure to bad gateway`() {
        val userId = UUID.randomUUID()
        val jwt = jwt(userId, "user@example.com")
        `when`(repository.existsById(userId)).thenReturn(true)
        doThrow(RuntimeException("upstream failed")).`when`(authAccountClient).deleteAccount(jwt.tokenValue)

        val exception = assertThrows<ResponseStatusException> {
            controller.delete(jwt)
        }

        assertEquals(HttpStatus.BAD_GATEWAY, exception.statusCode)
        verify(repository, never()).deleteById(userId)
    }

    private fun jwt(userId: UUID, email: String): Jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("user_id", userId.toString())
            .claim("email", email)
            .build()
}
