package app.mnema.user.controller

import app.mnema.user.entity.User
import app.mnema.user.repository.UserRepository
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.transaction.annotation.Transactional
import java.util.*

@RestController
@RequestMapping("/users")
class UserController(private val repo: UserRepository) {

    data class CreateUserReq(
        val email: String,
        val username: String,
        val bio: String? = null,
        val avatarUrl: String? = null,
        val avatarMediaId: UUID? = null,
    )

    data class UpdateUserReq(
        val email: String? = null,
        val username: String? = null,
        val bio: String? = null,
        val avatarUrl: String? = null,
        val avatarMediaId: UUID? = null,
    )

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_user.read') and @userAuthz.isAdmin(authentication)")
    fun get(@PathVariable id: UUID): User =
        repo.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SCOPE_user.write') and @userAuthz.isAdmin(authentication)")
    fun create(@Valid @RequestBody req: CreateUserReq): User {
        if (repo.existsByUsernameIgnoreCase(req.username)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Username already taken")
        }
        if (repo.existsByEmailIgnoreCase(req.email)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Email already in use")
        }
        return repo.save(
            User(
                email = req.email,
                username = req.username,
                bio = req.bio,
                avatarUrl = req.avatarUrl,
                avatarMediaId = req.avatarMediaId
            )
        )
    }

    @PatchMapping("/{id}")
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write') and @userAuthz.isAdmin(authentication)")
    fun partialUpdate(@PathVariable id: UUID, @Valid @RequestBody req: UpdateUserReq): User {
        val user = repo.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        req.username?.let { newUsername ->
            if (repo.existsByUsernameIgnoreCaseAndIdNot(newUsername, id)) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Username already taken")
            }
            user.username = newUsername
        }

        req.email?.let { newEmail ->
            if (repo.existsByEmailIgnoreCaseAndIdNot(newEmail, id)) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Email already in use")
            }
            user.email = newEmail
        }

        req.bio?.let { user.bio = it }
        req.avatarUrl?.let { user.avatarUrl = it }
        req.avatarMediaId?.let { user.avatarMediaId = it }

        return user
    }

    @PutMapping("/{id}")
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write') and @userAuthz.isAdmin(authentication)")
    fun fullUpdate(@PathVariable id: UUID, @Valid @RequestBody req: CreateUserReq): User {
        val user = repo.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User not found") }

        if (repo.existsByUsernameIgnoreCaseAndIdNot(req.username, id)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Username already taken")
        }
        if (repo.existsByEmailIgnoreCaseAndIdNot(req.email, id)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Email already in use")
        }

        user.username = req.username
        user.email = req.email
        user.bio = req.bio
        user.avatarUrl = req.avatarUrl
        user.avatarMediaId = req.avatarMediaId

        return user
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('SCOPE_user.write') and @userAuthz.isAdmin(authentication)")
    fun delete(@PathVariable id: UUID) {
        if (!repo.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }
        repo.deleteById(id)
    }
}
