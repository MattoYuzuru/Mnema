package app.mnema.user.repository

import app.mnema.user.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface UserRepository : JpaRepository<User, UUID> {
    fun findByEmailIgnoreCase(email: String): Optional<User>
    fun existsByUsernameIgnoreCase(username: String): Boolean
    fun existsByEmailIgnoreCase(email: String): Boolean

    fun existsByUsernameIgnoreCaseAndIdNot(username: String, id: UUID): Boolean
    fun existsByEmailIgnoreCaseAndIdNot(email: String, id: UUID): Boolean
}
