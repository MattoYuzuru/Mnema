package app.mnema.user.controller

import app.mnema.user.entity.User
import app.mnema.user.repository.UserRepository
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.*

@RestController
@RequestMapping("/users")
class UserController(private val repo: UserRepository) {

    data class CreateUserReq(
        val email: String,
        val username: String,
        val bio: String? = null,
        val avatarUrl: String? = null,
    )

    data class UpdateUserReq(
        val email: String? = null,
        val username: String? = null,
        val bio: String? = null,
        val avatarUrl: String? = null,
    )

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): User = repo.findById(id)
        .orElseThrow { NoSuchElementException("User not found") }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody req: CreateUserReq): User {
        require(!repo.existsByUsername(req.username)) { "Username already taken" }
        return repo.save(
            User(
                email = req.email,
                username = req.username,
                bio = req.bio,
                avatarUrl = req.avatarUrl
            )
        )
    }

    @PatchMapping("/{id}")
    @ResponseStatus(HttpStatus.PARTIAL_CONTENT)
    fun partialUpdate(@PathVariable id: UUID, @Valid @RequestBody req: UpdateUserReq) {
        require(!repo.existsByUsername(req.username)) { "There is no such user" }
    }

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.RESET_CONTENT)
    fun fullUpdate(@PathVariable id: UUID, @Valid @RequestBody req: CreateUserReq) {
        require(repo.existsByUsername(req.username)) { "There is no such user" }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) = repo.deleteById(id)
}