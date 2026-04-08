package app.mnema.user.controller

import app.mnema.user.admin.AdminManagementService
import org.springframework.data.domain.Page
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.util.UUID

@RestController
@RequestMapping("/admin")
class AdminController(
    private val adminManagementService: AdminManagementService
) {
    data class BanUserRequest(
        val reason: String? = null
    )

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('SCOPE_user.read') and @userAuthz.isAdmin(authentication)")
    fun overview(@AuthenticationPrincipal jwt: Jwt): AdminManagementService.AdminOverviewResponse =
        adminManagementService.getOverview(requireUserId(jwt))

    @GetMapping("/users/search")
    @PreAuthorize("hasAuthority('SCOPE_user.read') and @userAuthz.isAdmin(authentication)")
    fun searchUsers(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") limit: Int
    ): Page<AdminManagementService.AdminUserEntry> =
        adminManagementService.searchUsers(requireUserId(jwt), query, page, limit, jwt.tokenValue)

    @GetMapping("/admins")
    @PreAuthorize("hasAuthority('SCOPE_user.read') and @userAuthz.isAdmin(authentication)")
    fun admins(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") limit: Int
    ): Page<AdminManagementService.AdminUserEntry> =
        adminManagementService.listAdmins(requireUserId(jwt), query, page, limit, jwt.tokenValue)

    @GetMapping("/banned-users")
    @PreAuthorize("hasAuthority('SCOPE_user.read') and @userAuthz.isAdmin(authentication)")
    fun bannedUsers(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") limit: Int
    ): Page<AdminManagementService.AdminUserEntry> =
        adminManagementService.listBannedUsers(requireUserId(jwt), query, page, limit, jwt.tokenValue)

    @PostMapping("/users/{userId}/grant-admin")
    @PreAuthorize("hasAuthority('SCOPE_user.write') and @userAuthz.isAdmin(authentication)")
    fun grantAdmin(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable userId: UUID
    ): AdminManagementService.AdminUserEntry =
        adminManagementService.grantAdmin(requireUserId(jwt), userId, jwt.tokenValue)

    @PostMapping("/users/{userId}/revoke-admin")
    @PreAuthorize("hasAuthority('SCOPE_user.write') and @userAuthz.isAdmin(authentication)")
    fun revokeAdmin(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable userId: UUID
    ): AdminManagementService.AdminUserEntry =
        adminManagementService.revokeAdmin(requireUserId(jwt), userId, jwt.tokenValue)

    @PostMapping("/users/{userId}/ban")
    @PreAuthorize("hasAuthority('SCOPE_user.write') and @userAuthz.isAdmin(authentication)")
    fun banUser(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable userId: UUID,
        @RequestBody(required = false) request: BanUserRequest?
    ): AdminManagementService.AdminUserEntry =
        adminManagementService.banUser(requireUserId(jwt), userId, request?.reason, jwt.tokenValue)

    @PostMapping("/users/{userId}/unban")
    @PreAuthorize("hasAuthority('SCOPE_user.write') and @userAuthz.isAdmin(authentication)")
    fun unbanUser(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable userId: UUID
    ): AdminManagementService.AdminUserEntry =
        adminManagementService.unbanUser(requireUserId(jwt), userId, jwt.tokenValue)

    private fun requireUserId(jwt: Jwt): UUID {
        val raw = jwt.getClaimAsString("user_id")
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "user_id claim missing")
        return runCatching { UUID.fromString(raw) }
            .getOrElse { throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "user_id claim invalid") }
    }
}
