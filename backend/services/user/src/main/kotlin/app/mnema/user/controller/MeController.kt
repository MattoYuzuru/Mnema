package app.mnema.user.controller

import app.mnema.user.entity.User
import app.mnema.user.repository.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.*

@RestController
@RequestMapping("/me")
class MeController(
    private val repo: UserRepository
) {
    data class MeResponse(
        val id: UUID,
        val email: String,
        val username: String,
        val bio: String?,
        val avatarUrl: String?,
        val isAdmin: Boolean
    )

    data class MeUpdateRequest(
        val username: String? = null,
        val bio: String? = null,
        val avatarUrl: String? = null
    )

    private fun toDto(user: User) = MeResponse(
        id = user.id,
        email = user.email,
        username = user.username,
        bio = user.bio,
        avatarUrl = user.avatarUrl,
        isAdmin = user.isAdmin
    )

    @GetMapping
    @Transactional
    fun getOrCreate(@AuthenticationPrincipal jwt: Jwt): MeResponse {
        val userIdStr = jwt.getClaimAsString("user_id")
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "user_id claim missing")
        val email = jwt.getClaimAsString("email")
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "email claim missing")

        val userId = UUID.fromString(userIdStr)

        val existing = repo.findById(userId)
        if (existing.isPresent) {
            return toDto(existing.get())
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
            return toDto(migrated)
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
            toDto(repo.save(user))
        } catch (ex: DataIntegrityViolationException) {
            val existingAfterInsert = repo.findById(userId)
                .orElseGet { repo.findByEmailIgnoreCase(email).orElseThrow { ex } }
            toDto(existingAfterInsert)
        }
    }

    @PatchMapping
    @Transactional
    fun update(@AuthenticationPrincipal jwt: Jwt, @RequestBody req: MeUpdateRequest): MeResponse {
        val userIdStr = jwt.getClaimAsString("user_id")
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "user_id claim missing")
        val userId = UUID.fromString(userIdStr)

        val user = repo.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        req.username?.let { newUsername ->
            if (repo.existsByUsernameIgnoreCaseAndIdNot(newUsername, userId)) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Username already taken")
            }
            user.username = newUsername
        }

        req.bio?.let { user.bio = it }
        req.avatarUrl?.let { user.avatarUrl = it }

        return toDto(user)
    }
}
