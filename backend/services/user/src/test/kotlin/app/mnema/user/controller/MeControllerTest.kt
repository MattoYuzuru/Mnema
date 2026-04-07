package app.mnema.user.controller

import app.mnema.user.auth.AuthAccountClient
import app.mnema.user.entity.User
import app.mnema.user.media.client.MediaResolved
import app.mnema.user.media.service.MediaResolveCache
import app.mnema.user.repository.UserRepository
import org.springframework.dao.DataIntegrityViolationException
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
    fun `returns existing user with unresolved avatar media and keeps stored fallback url`() {
        val userId = UUID.randomUUID()
        val mediaId = UUID.randomUUID()
        val user = User(
            id = userId,
            email = "user@example.com",
            username = "mnema",
            avatarMediaId = mediaId,
            avatarUrl = "https://img.example/fallback.png"
        )
        val jwt = jwt(userId, "user@example.com")
        `when`(repository.findById(userId)).thenReturn(Optional.of(user))
        `when`(mediaResolveCache.resolveAvatar(mediaId, jwt.tokenValue)).thenReturn(
            MediaResolved(mediaId = mediaId, url = "", expiresAt = Instant.now().plusSeconds(300))
        )

        val response = controller.getOrCreate(jwt)

        assertEquals("https://img.example/fallback.png", response.avatarUrl)
    }

    @Test
    fun `migrates existing email profile to jwt user id`() {
        val oldId = UUID.randomUUID()
        val newId = UUID.randomUUID()
        val existing = User(id = oldId, email = "user@example.com", username = "mnema", avatarUrl = null)
        val migrated = User(id = newId, email = "user@example.com", username = "mnema", avatarUrl = null)
        val jwt = jwt(newId, "user@example.com", picture = "https://img.example/profile.png")

        `when`(repository.findById(newId)).thenReturn(Optional.empty(), Optional.of(migrated))
        `when`(repository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(existing))

        val response = controller.getOrCreate(jwt)

        verify(repository).migrateId(oldId, newId)
        assertEquals("https://img.example/profile.png", response.avatarUrl)
    }

    @Test
    fun `creates profile on first login and appends numeric suffix for duplicate username`() {
        val userId = UUID.randomUUID()
        val jwt = jwt(
            userId = userId,
            email = "user@example.com",
            preferredUsername = " Fancy User ",
            picture = "https://img.example/profile.png"
        )

        `when`(repository.findById(userId)).thenReturn(Optional.empty())
        `when`(repository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.empty())
        `when`(repository.existsByUsernameIgnoreCase("FancyUser")).thenReturn(true)
        `when`(repository.existsByUsernameIgnoreCase("FancyUser1")).thenReturn(false)
        `when`(repository.saveAndFlush(org.mockito.ArgumentMatchers.any(User::class.java))).thenAnswer { it.arguments[0] }

        val response = controller.getOrCreate(jwt)

        assertEquals("FancyUser1", response.username)
        assertEquals("https://img.example/profile.png", response.avatarUrl)
    }

    @Test
    fun `returns existing profile after concurrent insert conflict`() {
        val userId = UUID.randomUUID()
        val existing = User(id = userId, email = "user@example.com", username = "mnema")
        val jwt = jwt(userId, "user@example.com")

        `when`(repository.findById(userId)).thenReturn(Optional.empty(), Optional.of(existing))
        `when`(repository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.empty())
        `when`(repository.existsByUsernameIgnoreCase("user")).thenReturn(false)
        `when`(repository.saveAndFlush(org.mockito.ArgumentMatchers.any(User::class.java)))
            .thenThrow(DataIntegrityViolationException("duplicate"))

        val response = controller.getOrCreate(jwt)

        assertEquals(existing.id, response.id)
        assertEquals("mnema", response.username)
    }

    @Test
    fun `get or create rejects missing jwt claims and failed migration`() {
        val missingUserId = Jwt.withTokenValue("token").header("alg", "none").claim("email", "user@example.com").build()
        val missingUserIdEx = assertThrows<ResponseStatusException> { controller.getOrCreate(missingUserId) }
        assertEquals(HttpStatus.UNAUTHORIZED, missingUserIdEx.statusCode)

        val newId = UUID.randomUUID()
        val oldId = UUID.randomUUID()
        val existing = User(id = oldId, email = "user@example.com", username = "mnema")
        `when`(repository.findById(newId)).thenReturn(Optional.empty(), Optional.empty())
        `when`(repository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(existing))

        val migrationEx = assertThrows<ResponseStatusException> {
            controller.getOrCreate(jwt(newId, "user@example.com"))
        }
        assertEquals(HttpStatus.CONFLICT, migrationEx.statusCode)
        assertEquals("Unable to migrate user profile", migrationEx.reason)
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
    fun `updating avatar media id clears avatar url`() {
        val userId = UUID.randomUUID()
        val mediaId = UUID.randomUUID()
        val user = User(
            id = userId,
            email = "user@example.com",
            username = "mnema",
            avatarMediaId = null,
            avatarUrl = "https://img.example/old.png"
        )
        `when`(repository.findById(userId)).thenReturn(Optional.of(user))
        `when`(mediaResolveCache.resolveAvatar(mediaId, "token")).thenReturn(
            MediaResolved(mediaId = mediaId, url = "https://cdn.example/avatar.png", expiresAt = Instant.now().plusSeconds(300))
        )

        val response = controller.update(
            jwt(userId, "user@example.com"),
            MeController.MeUpdateRequest(avatarMediaId = mediaId)
        )

        assertEquals(mediaId, response.avatarMediaId)
        assertEquals("https://cdn.example/avatar.png", response.avatarUrl)
    }

    @Test
    fun `update validates username bio and missing user`() {
        val userId = UUID.randomUUID()
        val user = User(id = userId, email = "user@example.com", username = "mnema")
        `when`(repository.findById(userId)).thenReturn(Optional.of(user), Optional.of(user), Optional.empty())
        `when`(repository.existsByUsernameIgnoreCaseAndIdNot("taken", userId)).thenReturn(true)

        val usernameEx = assertThrows<ResponseStatusException> {
            controller.update(jwt(userId, "user@example.com"), MeController.MeUpdateRequest(username = "a".repeat(51)))
        }
        assertEquals(HttpStatus.BAD_REQUEST, usernameEx.statusCode)

        val conflictEx = assertThrows<ResponseStatusException> {
            controller.update(jwt(userId, "user@example.com"), MeController.MeUpdateRequest(username = "taken"))
        }
        assertEquals(HttpStatus.CONFLICT, conflictEx.statusCode)

        val missingEx = assertThrows<ResponseStatusException> {
            controller.update(jwt(userId, "user@example.com"), MeController.MeUpdateRequest(bio = "bio"))
        }
        assertEquals(HttpStatus.NOT_FOUND, missingEx.statusCode)
    }

    @Test
    fun `update rejects bio too long and missing user id claim`() {
        val userId = UUID.randomUUID()
        val user = User(id = userId, email = "user@example.com", username = "mnema")
        `when`(repository.findById(userId)).thenReturn(Optional.of(user))

        val bioEx = assertThrows<ResponseStatusException> {
            controller.update(jwt(userId, "user@example.com"), MeController.MeUpdateRequest(bio = "a".repeat(201)))
        }
        assertEquals(HttpStatus.BAD_REQUEST, bioEx.statusCode)

        val missingClaimEx = assertThrows<ResponseStatusException> {
            controller.update(
                Jwt.withTokenValue("token").header("alg", "none").claim("email", "user@example.com").build(),
                MeController.MeUpdateRequest(username = "mnema")
            )
        }
        assertEquals(HttpStatus.UNAUTHORIZED, missingClaimEx.statusCode)
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

    @Test
    fun `delete removes existing user after auth deletion`() {
        val userId = UUID.randomUUID()
        val jwt = jwt(userId, "user@example.com")
        `when`(repository.existsById(userId)).thenReturn(true)

        controller.delete(jwt)

        verify(authAccountClient).deleteAccount(jwt.tokenValue)
        verify(repository).deleteById(userId)
    }

    @Test
    fun `delete rejects invalid claim and missing user`() {
        val missingClaimEx = assertThrows<ResponseStatusException> {
            controller.delete(Jwt.withTokenValue("token").header("alg", "none").claim("user_id", "not-a-uuid").build())
        }
        assertEquals(HttpStatus.UNAUTHORIZED, missingClaimEx.statusCode)

        val userId = UUID.randomUUID()
        `when`(repository.existsById(userId)).thenReturn(false)
        val missingUserEx = assertThrows<ResponseStatusException> {
            controller.delete(jwt(userId, "user@example.com"))
        }
        assertEquals(HttpStatus.NOT_FOUND, missingUserEx.statusCode)
    }

    private fun jwt(
        userId: UUID,
        email: String,
        preferredUsername: String? = null,
        picture: String? = null
    ): Jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("user_id", userId.toString())
            .claim("email", email)
            .apply {
                preferredUsername?.let { claim("preferred_username", it) }
                picture?.let { claim("picture", it) }
            }
            .build()
}
