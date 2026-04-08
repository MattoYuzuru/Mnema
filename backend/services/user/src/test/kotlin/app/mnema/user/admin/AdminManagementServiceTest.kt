package app.mnema.user.admin

import app.mnema.user.entity.User
import app.mnema.user.media.client.MediaResolved
import app.mnema.user.media.service.MediaResolveCache
import app.mnema.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
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
        val actor = admin(actorId)
        val target = user(targetId)
        `when`(repository.findById(actorId)).thenReturn(Optional.of(actor))
        `when`(repository.findById(targetId)).thenReturn(Optional.of(target))

        val response = service.grantAdmin(actorId, targetId, "token")

        assertTrue(response.admin)
        assertEquals(actorId, response.adminGrantedBy)
        assertNotNull(response.adminGrantedAt)
        assertTrue(target.isAdmin)
    }

    @Test
    fun `grant admin rejects self banned existing and missing users`() {
        val actorId = UUID.randomUUID()
        val bannedId = UUID.randomUUID()
        val existingAdminId = UUID.randomUUID()
        val missingId = UUID.randomUUID()
        val actor = admin(actorId)
        val bannedTarget = user(bannedId).apply { bannedAt = Instant.now() }
        val existingAdmin = admin(existingAdminId)
        `when`(repository.findById(actorId)).thenReturn(Optional.of(actor))
        `when`(repository.findById(bannedId)).thenReturn(Optional.of(bannedTarget))
        `when`(repository.findById(existingAdminId)).thenReturn(Optional.of(existingAdmin))
        `when`(repository.findById(missingId)).thenReturn(Optional.empty())

        val selfEx = assertThrows<ResponseStatusException> {
            service.grantAdmin(actorId, actorId, "token")
        }
        assertEquals(HttpStatus.BAD_REQUEST, selfEx.statusCode)

        val bannedEx = assertThrows<ResponseStatusException> {
            service.grantAdmin(actorId, bannedId, "token")
        }
        assertEquals(HttpStatus.CONFLICT, bannedEx.statusCode)

        val existingEx = assertThrows<ResponseStatusException> {
            service.grantAdmin(actorId, existingAdminId, "token")
        }
        assertEquals(HttpStatus.CONFLICT, existingEx.statusCode)

        val missingEx = assertThrows<ResponseStatusException> {
            service.grantAdmin(actorId, missingId, "token")
        }
        assertEquals(HttpStatus.NOT_FOUND, missingEx.statusCode)
    }

    @Test
    fun `revoke admin clears delegation metadata`() {
        val actorId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val actor = admin(actorId)
        val target = admin(targetId).apply {
            adminGrantedBy = actorId
            adminGrantedAt = Instant.now()
        }
        `when`(repository.findById(actorId)).thenReturn(Optional.of(actor))
        `when`(repository.findById(targetId)).thenReturn(Optional.of(target))
        `when`(repository.existsByAdminGrantedByAndIsAdminTrue(targetId)).thenReturn(false)

        val response = service.revokeAdmin(actorId, targetId, "token")

        assertFalse(response.admin)
        assertNull(response.adminGrantedBy)
        assertNull(response.adminGrantedAt)
        assertFalse(target.isAdmin)
    }

    @Test
    fun `revoke admin rejects missing rights and delegated subtree`() {
        val actorId = UUID.randomUUID()
        val plainUserId = UUID.randomUUID()
        val selfId = actorId
        val bootstrapId = UUID.randomUUID()
        val foreignId = UUID.randomUUID()
        val delegatedId = UUID.randomUUID()
        val actor = admin(actorId)
        val plainUser = user(plainUserId)
        val bootstrapAdmin = admin(bootstrapId)
        val foreignAdmin = admin(foreignId).apply { adminGrantedBy = UUID.randomUUID() }
        val delegatedAdmin = admin(delegatedId).apply { adminGrantedBy = actorId }
        `when`(repository.findById(actorId)).thenReturn(Optional.of(actor))
        `when`(repository.findById(plainUserId)).thenReturn(Optional.of(plainUser))
        `when`(repository.findById(selfId)).thenReturn(Optional.of(actor))
        `when`(repository.findById(bootstrapId)).thenReturn(Optional.of(bootstrapAdmin))
        `when`(repository.findById(foreignId)).thenReturn(Optional.of(foreignAdmin))
        `when`(repository.findById(delegatedId)).thenReturn(Optional.of(delegatedAdmin))
        `when`(repository.existsByAdminGrantedByAndIsAdminTrue(delegatedId)).thenReturn(true)

        assertEquals(HttpStatus.CONFLICT, assertThrows<ResponseStatusException> {
            service.revokeAdmin(actorId, plainUserId, "token")
        }.statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows<ResponseStatusException> {
            service.revokeAdmin(actorId, selfId, "token")
        }.statusCode)
        assertEquals(HttpStatus.FORBIDDEN, assertThrows<ResponseStatusException> {
            service.revokeAdmin(actorId, bootstrapId, "token")
        }.statusCode)
        assertEquals(HttpStatus.FORBIDDEN, assertThrows<ResponseStatusException> {
            service.revokeAdmin(actorId, foreignId, "token")
        }.statusCode)
        assertEquals(HttpStatus.CONFLICT, assertThrows<ResponseStatusException> {
            service.revokeAdmin(actorId, delegatedId, "token")
        }.statusCode)
    }

    @Test
    fun `ban rejects admin target`() {
        val actorId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val actor = admin(actorId)
        val target = admin(targetId)
        `when`(repository.findById(actorId)).thenReturn(Optional.of(actor))
        `when`(repository.findById(targetId)).thenReturn(Optional.of(target))

        val ex = assertThrows<ResponseStatusException> {
            service.banUser(actorId, targetId, "reason", "token")
        }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
    }

    @Test
    fun `ban user stores normalized reason and unban clears moderation state`() {
        val actorId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val actor = admin(actorId)
        val target = user(targetId)
        `when`(repository.findById(actorId)).thenReturn(Optional.of(actor))
        `when`(repository.findById(targetId)).thenReturn(Optional.of(target))

        val banned = service.banUser(actorId, targetId, "  spam  ", "token")

        assertTrue(banned.banned)
        assertEquals("spam", banned.banReason)
        assertEquals(actorId, banned.bannedBy)
        assertNotNull(banned.bannedAt)

        val unbanned = service.unbanUser(actorId, targetId, "token")

        assertFalse(unbanned.banned)
        assertNull(unbanned.banReason)
        assertNull(unbanned.bannedAt)
        assertNull(target.bannedBy)
    }

    @Test
    fun `ban and unban reject invalid states`() {
        val actorId = UUID.randomUUID()
        val selfBanTargetId = actorId
        val longReasonTargetId = UUID.randomUUID()
        val duplicateBanTargetId = UUID.randomUUID()
        val unbanTargetId = UUID.randomUUID()
        val actor = admin(actorId)
        val longReasonTarget = user(longReasonTargetId)
        val duplicateBanTarget = user(duplicateBanTargetId).apply { bannedAt = Instant.now() }
        val unbanTarget = user(unbanTargetId)
        `when`(repository.findById(actorId)).thenReturn(Optional.of(actor))
        `when`(repository.findById(selfBanTargetId)).thenReturn(Optional.of(actor))
        `when`(repository.findById(longReasonTargetId)).thenReturn(Optional.of(longReasonTarget))
        `when`(repository.findById(duplicateBanTargetId)).thenReturn(Optional.of(duplicateBanTarget))
        `when`(repository.findById(unbanTargetId)).thenReturn(Optional.of(unbanTarget))

        assertEquals(HttpStatus.BAD_REQUEST, assertThrows<ResponseStatusException> {
            service.banUser(actorId, selfBanTargetId, null, "token")
        }.statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows<ResponseStatusException> {
            service.banUser(actorId, longReasonTargetId, "x".repeat(281), "token")
        }.statusCode)
        assertEquals(HttpStatus.CONFLICT, assertThrows<ResponseStatusException> {
            service.banUser(actorId, duplicateBanTargetId, null, "token")
        }.statusCode)
        assertEquals(HttpStatus.CONFLICT, assertThrows<ResponseStatusException> {
            service.unbanUser(actorId, unbanTargetId, "token")
        }.statusCode)
    }

    @Test
    fun `overview returns admin and banned counters`() {
        val actorId = UUID.randomUUID()
        val actor = admin(actorId)
        `when`(repository.findById(actorId)).thenReturn(Optional.of(actor))
        `when`(repository.countByIsAdminTrue()).thenReturn(4)
        `when`(repository.countByBannedAtIsNotNull()).thenReturn(2)

        val response = service.getOverview(actorId)

        assertEquals(4, response.totalAdmins)
        assertEquals(2, response.bannedUsers)
        assertEquals(0, response.deckReports)
    }

    @Test
    fun `search users maps media and permissions`() {
        val actorId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val avatarId = UUID.randomUUID()
        val actor = admin(actorId)
        val target = user(targetId).apply {
            avatarMediaId = avatarId
            adminGrantedBy = actorId
            bannedAt = Instant.now()
            avatarUrl = "https://fallback.example/avatar.png"
        }
        `when`(repository.findById(actorId)).thenReturn(Optional.of(actor))
        `when`(repository.searchUsers("query", PageRequest.of(0, 20))).thenReturn(PageImpl(listOf(target)))
        `when`(mediaResolveCache.resolveAvatar(avatarId, "token")).thenReturn(
            MediaResolved(avatarId, url = "https://cdn.example/avatar.png", expiresAt = Instant.now().plusSeconds(60))
        )

        val page = service.searchUsers(actorId, " query ", 1, 20, "token")
        val entry = page.content.single()

        assertEquals("https://cdn.example/avatar.png", entry.avatarUrl)
        assertFalse(entry.bannableByCurrentAdmin)
        assertTrue(entry.unbannableByCurrentAdmin)
        assertFalse(entry.canPromoteToAdmin)
        assertTrue(entry.assignedByCurrentAdmin)
        assertFalse(entry.revocableByCurrentAdmin)
    }

    @Test
    fun `list admins caps page size and marks revocable admins`() {
        val actorId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val actor = admin(actorId)
        val delegated = admin(targetId).apply {
            adminGrantedBy = actorId
            adminGrantedAt = Instant.now()
        }
        `when`(repository.findById(actorId)).thenReturn(Optional.of(actor))
        `when`(repository.findAdminUsers("", PageRequest.of(0, 50))).thenReturn(PageImpl(listOf(delegated)))
        `when`(repository.existsByAdminGrantedByAndIsAdminTrue(targetId)).thenReturn(false)

        val page = service.listAdmins(actorId, null, 1, 999, "token")
        val entry = page.content.single()

        assertTrue(entry.revocableByCurrentAdmin)
        assertTrue(entry.admin)
    }

    @Test
    fun `list banned users returns requested page and falls back to stored avatar url`() {
        val actorId = UUID.randomUUID()
        val avatarId = UUID.randomUUID()
        val actor = admin(actorId)
        val banned = user(UUID.randomUUID()).apply {
            bannedAt = Instant.now()
            banReason = "spam"
            avatarMediaId = avatarId
            avatarUrl = "https://fallback.example/avatar.png"
        }
        `when`(repository.findById(actorId)).thenReturn(Optional.of(actor))
        `when`(repository.findBannedUsers("ban", PageRequest.of(1, 10))).thenReturn(PageImpl(listOf(banned)))
        `when`(mediaResolveCache.resolveAvatar(avatarId, "token")).thenReturn(
            MediaResolved(avatarId, url = "", expiresAt = Instant.now().plusSeconds(60))
        )

        val page = service.listBannedUsers(actorId, "ban", 2, 10, "token")
        val entry = page.content.single()

        assertEquals(banned.username, entry.username)
        assertEquals("spam", entry.banReason)
        assertEquals("https://fallback.example/avatar.png", entry.avatarUrl)
    }

    @Test
    fun `internal moderation state mirrors user flags`() {
        val userId = UUID.randomUUID()
        val user = admin(userId).apply { bannedAt = Instant.now() }
        `when`(repository.findById(userId)).thenReturn(Optional.of(user))

        val state = service.getInternalModerationState(userId)

        assertEquals(userId, state.id)
        assertTrue(state.admin)
        assertTrue(state.banned)
    }

    @Test
    fun `admin operations reject inactive admin and invalid paging`() {
        val userId = UUID.randomUUID()
        val plain = user(userId)
        val bannedAdmin = admin(userId).apply { bannedAt = Instant.now() }
        val actor = admin(userId)
        `when`(repository.findById(userId)).thenReturn(Optional.of(plain), Optional.of(bannedAdmin), Optional.of(actor))

        assertEquals(HttpStatus.FORBIDDEN, assertThrows<ResponseStatusException> {
            service.getOverview(userId)
        }.statusCode)
        assertEquals(HttpStatus.FORBIDDEN, assertThrows<ResponseStatusException> {
            service.getOverview(userId)
        }.statusCode)

        val pagingEx = assertThrows<ResponseStatusException> {
            service.searchUsers(userId, null, 0, 0, "token")
        }
        assertEquals(HttpStatus.BAD_REQUEST, pagingEx.statusCode)
        verifyNoInteractions(mediaResolveCache)
    }

    private fun admin(id: UUID) = User(
        id = id,
        email = "admin-$id@example.com",
        username = "admin-$id",
        isAdmin = true
    )

    private fun user(id: UUID) = User(
        id = id,
        email = "user-$id@example.com",
        username = "user-$id"
    )
}
