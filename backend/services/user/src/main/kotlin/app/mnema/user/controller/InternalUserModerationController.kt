package app.mnema.user.controller

import app.mnema.user.admin.AdminManagementService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/internal/users")
class InternalUserModerationController(
    private val adminManagementService: AdminManagementService
) {
    @GetMapping("/{userId}/moderation")
    fun moderation(@PathVariable userId: UUID): AdminManagementService.InternalModerationState =
        adminManagementService.getInternalModerationState(userId)
}
