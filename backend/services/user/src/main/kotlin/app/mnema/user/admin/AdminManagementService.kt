package app.mnema.user.admin

import app.mnema.user.entity.User
import app.mnema.user.media.service.MediaResolveCache
import app.mnema.user.repository.UserRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@Service
class AdminManagementService(
    private val userRepository: UserRepository,
    private val mediaResolveCache: MediaResolveCache
) {
    companion object {
        private const val MAX_BAN_REASON_LENGTH = 280
        private const val MAX_LIMIT = 50
    }

    data class AdminOverviewResponse(
        val totalAdmins: Long,
        val bannedUsers: Long,
        val deckReports: Long = 0,
        val templateReports: Long = 0,
        val cardReports: Long = 0
    )

    data class AdminUserEntry(
        val id: UUID,
        val email: String,
        val username: String,
        val bio: String?,
        val avatarUrl: String?,
        val avatarMediaId: UUID?,
        val admin: Boolean,
        val adminGrantedBy: UUID?,
        val adminGrantedAt: Instant?,
        val banned: Boolean,
        val bannedBy: UUID?,
        val bannedAt: Instant?,
        val banReason: String?,
        val assignedByCurrentAdmin: Boolean,
        val revocableByCurrentAdmin: Boolean,
        val bannableByCurrentAdmin: Boolean,
        val unbannableByCurrentAdmin: Boolean,
        val canPromoteToAdmin: Boolean,
        val createdAt: Instant,
        val updatedAt: Instant
    )

    data class InternalModerationState(
        val id: UUID,
        val admin: Boolean,
        val banned: Boolean
    )

    @Transactional(readOnly = true)
    fun getOverview(currentAdminId: UUID): AdminOverviewResponse {
        requireActiveAdmin(currentAdminId)
        return AdminOverviewResponse(
            totalAdmins = userRepository.countByIsAdminTrue(),
            bannedUsers = userRepository.countByBannedAtIsNotNull()
        )
    }

    @Transactional(readOnly = true)
    fun searchUsers(currentAdminId: UUID, query: String?, page: Int, limit: Int, bearerToken: String): Page<AdminUserEntry> {
        val actor = requireActiveAdmin(currentAdminId)
        val pageable = pageRequest(page, limit)
        return userRepository.searchUsers(normalizeQuery(query), pageable)
            .map { toAdminEntry(actor, it, bearerToken) }
    }

    @Transactional(readOnly = true)
    fun listAdmins(currentAdminId: UUID, query: String?, page: Int, limit: Int, bearerToken: String): Page<AdminUserEntry> {
        val actor = requireActiveAdmin(currentAdminId)
        val pageable = pageRequest(page, limit)
        return userRepository.findAdminUsers(normalizeQuery(query), pageable)
            .map { toAdminEntry(actor, it, bearerToken) }
    }

    @Transactional(readOnly = true)
    fun listBannedUsers(currentAdminId: UUID, query: String?, page: Int, limit: Int, bearerToken: String): Page<AdminUserEntry> {
        val actor = requireActiveAdmin(currentAdminId)
        val pageable = pageRequest(page, limit)
        return userRepository.findBannedUsers(normalizeQuery(query), pageable)
            .map { toAdminEntry(actor, it, bearerToken) }
    }

    @Transactional
    fun grantAdmin(currentAdminId: UUID, targetUserId: UUID, bearerToken: String): AdminUserEntry {
        val actor = requireActiveAdmin(currentAdminId)
        val target = requireUser(targetUserId)

        if (actor.id == target.id) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Admins cannot upgrade themselves")
        }
        if (target.bannedAt != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Banned users cannot be promoted to admin")
        }
        if (target.isAdmin) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "User is already an admin")
        }

        target.isAdmin = true
        target.adminGrantedBy = actor.id
        target.adminGrantedAt = Instant.now()

        return toAdminEntry(actor, target, bearerToken)
    }

    @Transactional
    fun revokeAdmin(currentAdminId: UUID, targetUserId: UUID, bearerToken: String): AdminUserEntry {
        val actor = requireActiveAdmin(currentAdminId)
        val target = requireUser(targetUserId)

        if (!target.isAdmin) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "User is not an admin")
        }
        if (target.id == actor.id) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Admins cannot downgrade themselves")
        }
        if (target.adminGrantedBy == null) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Bootstrap admins can only be changed directly in the database")
        }
        if (target.adminGrantedBy != actor.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "You can only downgrade admins that you promoted")
        }
        if (userRepository.existsByAdminGrantedByAndIsAdminTrue(target.id)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Downgrade blocked while the admin still has promoted admins")
        }

        target.isAdmin = false
        target.adminGrantedBy = null
        target.adminGrantedAt = null

        return toAdminEntry(actor, target, bearerToken)
    }

    @Transactional
    fun banUser(currentAdminId: UUID, targetUserId: UUID, reason: String?, bearerToken: String): AdminUserEntry {
        val actor = requireActiveAdmin(currentAdminId)
        val target = requireUser(targetUserId)

        if (target.id == actor.id) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Admins cannot ban themselves")
        }
        if (target.isAdmin) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Admin users cannot be banned from the panel")
        }
        if (target.bannedAt != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "User is already banned")
        }

        val normalizedReason = reason?.trim()?.takeIf(String::isNotBlank)
        if (normalizedReason != null && normalizedReason.length > MAX_BAN_REASON_LENGTH) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Ban reason is too long")
        }

        target.bannedBy = actor.id
        target.bannedAt = Instant.now()
        target.banReason = normalizedReason

        return toAdminEntry(actor, target, bearerToken)
    }

    @Transactional
    fun unbanUser(currentAdminId: UUID, targetUserId: UUID, bearerToken: String): AdminUserEntry {
        val actor = requireActiveAdmin(currentAdminId)
        val target = requireUser(targetUserId)

        if (target.bannedAt == null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "User is not banned")
        }

        target.bannedAt = null
        target.bannedBy = null
        target.banReason = null

        return toAdminEntry(actor, target, bearerToken)
    }

    @Transactional(readOnly = true)
    fun getInternalModerationState(userId: UUID): InternalModerationState {
        val user = requireUser(userId)
        return InternalModerationState(
            id = user.id,
            admin = user.isAdmin,
            banned = user.bannedAt != null
        )
    }

    private fun requireActiveAdmin(userId: UUID): User {
        val user = requireUser(userId)
        if (!user.isAdmin) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required")
        }
        if (user.bannedAt != null) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Banned users cannot access the admin panel")
        }
        return user
    }

    private fun requireUser(userId: UUID): User = userRepository.findById(userId)
        .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

    private fun pageRequest(page: Int, limit: Int): PageRequest {
        if (page < 1 || limit < 1) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "page and limit must be >= 1")
        }
        return PageRequest.of(page - 1, limit.coerceAtMost(MAX_LIMIT))
    }

    private fun normalizeQuery(query: String?): String = query?.trim().orEmpty()

    private fun toAdminEntry(currentAdmin: User, user: User, bearerToken: String): AdminUserEntry {
        val assignedByCurrentAdmin = user.adminGrantedBy == currentAdmin.id
        val revocable = user.isAdmin
            && assignedByCurrentAdmin
            && !userRepository.existsByAdminGrantedByAndIsAdminTrue(user.id)
            && user.id != currentAdmin.id
        val canPromote = !user.isAdmin && user.bannedAt == null && user.id != currentAdmin.id

        return AdminUserEntry(
            id = user.id,
            email = user.email,
            username = user.username,
            bio = user.bio,
            avatarUrl = resolveAvatarUrl(user, bearerToken),
            avatarMediaId = user.avatarMediaId,
            admin = user.isAdmin,
            adminGrantedBy = user.adminGrantedBy,
            adminGrantedAt = user.adminGrantedAt,
            banned = user.bannedAt != null,
            bannedBy = user.bannedBy,
            bannedAt = user.bannedAt,
            banReason = user.banReason,
            assignedByCurrentAdmin = assignedByCurrentAdmin,
            revocableByCurrentAdmin = revocable,
            bannableByCurrentAdmin = !user.isAdmin && user.bannedAt == null && user.id != currentAdmin.id,
            unbannableByCurrentAdmin = user.bannedAt != null,
            canPromoteToAdmin = canPromote,
            createdAt = user.createdAt,
            updatedAt = user.updatedAt
        )
    }

    private fun resolveAvatarUrl(user: User, bearerToken: String): String? {
        val mediaId = user.avatarMediaId ?: return user.avatarUrl
        val resolved = mediaResolveCache.resolveAvatar(mediaId, bearerToken)
        return resolved?.url?.takeIf(String::isNotBlank) ?: user.avatarUrl
    }
}
