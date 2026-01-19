package app.mnema.auth.user

import java.util.Optional
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface AuthUserRepository : JpaRepository<AuthUser, UUID> {
    fun findByEmail(email: String): Optional<AuthUser>
    fun findByEmailIgnoreCase(email: String): Optional<AuthUser>
    fun findByUsernameIgnoreCase(username: String): Optional<AuthUser>
    fun existsByUsernameIgnoreCase(username: String): Boolean
}
