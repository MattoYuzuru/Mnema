package app.mnema.user.controller

import app.mnema.user.admin.AdminManagementService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class AdminControllerTest {

    private val service = mock(AdminManagementService::class.java)
    private val controller = AdminController(service)

    @Test
    fun `overview and list endpoints delegate current admin context`() {
        val actorId = UUID.randomUUID()
        val jwt = jwt(actorId)
        val overview = AdminManagementService.AdminOverviewResponse(3, 1)
        val page = PageImpl(emptyList<AdminManagementService.AdminUserEntry>())
        `when`(service.getOverview(actorId)).thenReturn(overview)
        `when`(service.searchUsers(actorId, "nick", 2, 15, jwt.tokenValue)).thenReturn(page)
        `when`(service.listAdmins(actorId, "root", 1, 20, jwt.tokenValue)).thenReturn(page)
        `when`(service.listBannedUsers(actorId, "spam", 3, 5, jwt.tokenValue)).thenReturn(page)

        assertEquals(overview, controller.overview(jwt))
        assertEquals(page, controller.searchUsers(jwt, "nick", 2, 15))
        assertEquals(page, controller.admins(jwt, "root", 1, 20))
        assertEquals(page, controller.bannedUsers(jwt, "spam", 3, 5))
    }

    @Test
    fun `write endpoints forward target id token and optional ban reason`() {
        val actorId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val jwt = jwt(actorId)
        val response = AdminManagementService.AdminUserEntry(
            id = targetId,
            email = "user@example.com",
            username = "user",
            bio = null,
            avatarUrl = null,
            avatarMediaId = null,
            admin = false,
            adminGrantedBy = null,
            adminGrantedAt = null,
            banned = false,
            bannedBy = null,
            bannedAt = null,
            banReason = null,
            assignedByCurrentAdmin = false,
            revocableByCurrentAdmin = false,
            bannableByCurrentAdmin = true,
            unbannableByCurrentAdmin = false,
            canPromoteToAdmin = true,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now()
        )
        `when`(service.grantAdmin(actorId, targetId, jwt.tokenValue)).thenReturn(response)
        `when`(service.revokeAdmin(actorId, targetId, jwt.tokenValue)).thenReturn(response)
        `when`(service.banUser(actorId, targetId, "spam", jwt.tokenValue)).thenReturn(response)
        `when`(service.unbanUser(actorId, targetId, jwt.tokenValue)).thenReturn(response)

        assertEquals(response, controller.grantAdmin(jwt, targetId))
        assertEquals(response, controller.revokeAdmin(jwt, targetId))
        assertEquals(response, controller.banUser(jwt, targetId, AdminController.BanUserRequest("spam")))
        assertEquals(response, controller.unbanUser(jwt, targetId))
        verify(service).banUser(actorId, targetId, "spam", jwt.tokenValue)
    }

    @Test
    fun `controller rejects missing and invalid user id claims`() {
        val missingClaimJwt = Jwt.withTokenValue("token").header("alg", "none").claim("sub", "user-123").build()
        val invalidClaimJwt = Jwt.withTokenValue("token").header("alg", "none").claim("user_id", "oops").build()

        val missingEx = assertThrows<ResponseStatusException> {
            controller.overview(missingClaimJwt)
        }
        val invalidEx = assertThrows<ResponseStatusException> {
            controller.overview(invalidClaimJwt)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, missingEx.statusCode)
        assertEquals(HttpStatus.UNAUTHORIZED, invalidEx.statusCode)
    }

    private fun jwt(userId: UUID): Jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("user_id", userId.toString())
            .build()
}
