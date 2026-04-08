package app.mnema.user.admin

import app.mnema.user.entity.User
import app.mnema.user.media.service.MediaResolveCache
import app.mnema.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID

class AdminManagementServiceTest {

    private val repository = mock(UserRepository::class.java)
    private val mediaResolveCache = mock(MediaResolveCache::class.java)
    private val service = AdminManagementService(repository, mediaResolveCache)

    @Test
    fun `grant admin stores delegator metadata`() {
        val actorId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val actor = User(id = actorId, email = "admin@example.com", username = "root", isAdmin = true)
        val target = User(id = targetId, email = "user@example.com", username = "user")
        `when`(repository.findById(actorId)).thenReturn(Optional.of(actor))
        `when`(repository.findById(targetId)).thenReturn(Optional.of(target))

        val response = service.grantAdmin(actorId, targetId, "token")

        assertEquals(true, response.admin)
        assertEquals(actorId, response.adminGrantedBy)
        assertNotNull(response.adminGrantedAt)
    }

    @Test
    fun `revoke admin rejects delegated subtree`() {
        val actorId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val actor = User(id = actorId, email = "admin@example.com", username = "root", isAdmin = true)
        val target = User(
            id = targetId,
            email = "child@example.com",
            username = "child",
            isAdmin = true,
            adminGrantedBy = actorId
        )
        `when`(repository.findById(actorId)).thenReturn(Optional.of(actor))
        `when`(repository.findById(targetId)).thenReturn(Optional.of(target))
        `when`(repository.existsByAdminGrantedByAndIsAdminTrue(targetId)).thenReturn(true)

        val ex = assertThrows<ResponseStatusException> {
            service.revokeAdmin(actorId, targetId, "token")
        }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
    }

    @Test
    fun `ban rejects admin target`() {
        val actorId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val actor = User(id = actorId, email = "admin@example.com", username = "root", isAdmin = true)
        val target = User(id = targetId, email = "other-admin@example.com", username = "other", isAdmin = true)
        `when`(repository.findById(actorId)).thenReturn(Optional.of(actor))
        `when`(repository.findById(targetId)).thenReturn(Optional.of(target))

        val ex = assertThrows<ResponseStatusException> {
            service.banUser(actorId, targetId, "reason", "token")
        }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
    }
}
