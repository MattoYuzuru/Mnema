package app.mnema.user.controller

import app.mnema.user.entity.User
import app.mnema.user.media.service.MediaResolveCache
import app.mnema.user.repository.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.*

@RestController
@RequestMapping("/me")
class MeController(
    private val repo: UserRepository,
    private val mediaResolveCache: MediaResolveCache
) {
    companion object {
        private const val MAX_USERNAME_LENGTH = 50
        private const val MAX_BIO_LENGTH = 200
    }
    data class MeResponse(
        val id: UUID,
        val email: String,
        val username: String,
        val bio: String?,
        val avatarUrl: String?,
        val avatarMediaId: UUID?,
        val isAdmin: Boolean,
        val createdAt: Instant,
        val updatedAt: Instant
    )

    data class MeUpdateRequest(
        val username: String? = null,
        val bio: String? = null,
        val avatarUrl: String? = null,
        val avatarMediaId: UUID? = null
    )

    private fun toDto(user: User, resolvedAvatarUrl: String? = null) = MeResponse(
        id = user.id,
        email = user.email,
        username = user.username,
        bio = user.bio,
        avatarUrl = resolvedAvatarUrl ?: user.avatarUrl,
        avatarMediaId = user.avatarMediaId,
        isAdmin = user.isAdmin,
        createdAt = user.createdAt,
        updatedAt = user.updatedAt
    )

    @GetMapping
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    fun getOrCreate(@AuthenticationPrincipal jwt: Jwt): MeResponse {
        val userIdStr = jwt.getClaimAsString("user_id")
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "user_id claim missing")
        val email = jwt.getClaimAsString("email")
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "email claim missing")

        val userId = UUID.fromString(userIdStr)

        val existing = repo.findById(userId)
        if (existing.isPresent) {
            val user = existing.get()
            val avatarUrl = resolveAvatarUrl(user, jwt)
            return toDto(user, avatarUrl)
        }

        val existingByEmail = repo.findByEmailIgnoreCase(email)
        if (existingByEmail.isPresent) {
            val user = existingByEmail.get()
            if (user.id != userId) {
                repo.migrateId(user.id, userId)
            }
            val migrated = repo.findById(userId)
                .orElseThrow { ResponseStatusException(HttpStatus.CONFLICT, "Unable to migrate user profile") }
            migrated.avatarUrl = migrated.avatarUrl ?: jwt.getClaimAsString("picture")
            val avatarUrl = resolveAvatarUrl(migrated, jwt)
            return toDto(migrated, avatarUrl)
        }

        // Первый логин -> создаём профиль
        val baseUsername = email.substringBefore('@').take(32)
        var candidate = baseUsername.ifBlank { "user" }
        var suffix = 1

        while (repo.existsByUsernameIgnoreCase(candidate)) {
            candidate = "$baseUsername$suffix"
            suffix++
        }

        val user = User(
            id = userId,
            email = email,
            username = candidate,
            avatarUrl = jwt.getClaimAsString("picture")
        )

        return try {
            val saved = repo.save(user)
            val avatarUrl = resolveAvatarUrl(saved, jwt)
            toDto(saved, avatarUrl)
        } catch (ex: DataIntegrityViolationException) {
            val existingAfterInsert = repo.findById(userId)
                .orElseGet { repo.findByEmailIgnoreCase(email).orElseThrow { ex } }
            val avatarUrl = resolveAvatarUrl(existingAfterInsert, jwt)
            toDto(existingAfterInsert, avatarUrl)
        }
    }

    @PatchMapping
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    fun update(@AuthenticationPrincipal jwt: Jwt, @RequestBody req: MeUpdateRequest): MeResponse {
        val userIdStr = jwt.getClaimAsString("user_id")
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "user_id claim missing")
        val userId = UUID.fromString(userIdStr)

        val user = repo.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        req.username?.let { newUsername ->
            if (newUsername.length > MAX_USERNAME_LENGTH) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Username too long")
            }
            if (repo.existsByUsernameIgnoreCaseAndIdNot(newUsername, userId)) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Username already taken")
            }
            user.username = newUsername
        }

        req.bio?.let {
            if (it.length > MAX_BIO_LENGTH) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Bio too long")
            }
            user.bio = it
        }
        req.avatarUrl?.let { user.avatarUrl = it }
        req.avatarMediaId?.let {
            user.avatarMediaId = it
            user.avatarUrl = null
        }

        val avatarUrl = resolveAvatarUrl(user, jwt)
        return toDto(user, avatarUrl)
    }

    @DeleteMapping
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    fun delete(@AuthenticationPrincipal jwt: Jwt) {
        val userIdStr = jwt.getClaimAsString("user_id")
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "user_id claim missing")
        val userId = UUID.fromString(userIdStr)

        if (!repo.existsById(userId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }

        repo.deleteById(userId)
    }

    private fun resolveAvatarUrl(user: User, jwt: Jwt): String? {
        val mediaId = user.avatarMediaId
        if (mediaId != null) {
            val resolved = mediaResolveCache.resolveAvatar(mediaId, jwt.tokenValue)
            if (!resolved?.url.isNullOrBlank()) {
                return resolved.url
            }
            return null
        }
        return user.avatarUrl
    }
}
