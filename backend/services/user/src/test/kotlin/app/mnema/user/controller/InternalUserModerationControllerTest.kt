package app.mnema.user.controller

import app.mnema.user.admin.AdminManagementService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

class InternalUserModerationControllerTest {

    private val service = mock(AdminManagementService::class.java)
    private val controller = InternalUserModerationController(service)

    @Test
    fun `moderation delegates to admin management service`() {
        val userId = UUID.randomUUID()
        val expected = AdminManagementService.InternalModerationState(userId, admin = true, banned = false)
        `when`(service.getInternalModerationState(userId)).thenReturn(expected)

        val response = controller.moderation(userId)

        assertEquals(expected, response)
    }
}
