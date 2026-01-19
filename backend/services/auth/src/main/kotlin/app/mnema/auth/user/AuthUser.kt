package app.mnema.auth.user

import app.mnema.auth.identity.FederatedIdentity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

@Entity
@Table(name = "users", schema = "auth")
class AuthUser(
    @Id
    @UuidGenerator
    @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @Column(nullable = false, unique = true)
    var email: String,

    @Column(name = "email_verified", nullable = false)
    var emailVerified: Boolean = false,

    @Column(name = "username")
    var username: String? = null,

    @Column(name = "password_hash")
    var passwordHash: String? = null,

    @Column(name = "failed_login_attempts", nullable = false)
    var failedLoginAttempts: Int = 0,

    @Column(name = "locked_until")
    var lockedUntil: Instant? = null,

    var name: String? = null,

    @Column(name = "picture_url")
    var pictureUrl: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null,

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    var identities: MutableSet<FederatedIdentity> = mutableSetOf()
) {
    fun touchLogin(now: Instant = Instant.now()) {
        lastLoginAt = now
    }

    fun isLocked(now: Instant = Instant.now()): Boolean {
        val until = lockedUntil ?: return false
        return !now.isAfter(until)
    }

    fun registerFailedLogin(now: Instant, maxAttempts: Int, lockDuration: Duration) {
        failedLoginAttempts += 1
        if (failedLoginAttempts >= maxAttempts) {
            lockedUntil = now.plus(lockDuration)
            failedLoginAttempts = 0
        }
    }

    fun resetFailedLogins() {
        failedLoginAttempts = 0
        lockedUntil = null
    }

    fun setPassword(hash: String) {
        passwordHash = hash
    }

    fun updateProfile(displayName: String?, avatarUrl: String?, verified: Boolean) {
        if (verified) {
            emailVerified = true
        }

        if (!displayName.isNullOrBlank()) {
            name = displayName
        }
        if (!avatarUrl.isNullOrBlank()) {
            pictureUrl = avatarUrl
        }
    }
}
