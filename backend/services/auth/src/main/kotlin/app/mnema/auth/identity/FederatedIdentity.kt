package app.mnema.auth.identity

import app.mnema.auth.user.AuthUser
import jakarta.persistence.*
import org.hibernate.annotations.UuidGenerator
import java.time.Instant
import java.util.*

@Entity
@Table(name = "accounts", schema = "auth")
class FederatedIdentity(
    @Id
    @UuidGenerator
    @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    var user: AuthUser,

    @Column(nullable = false)
    var provider: String,

    @Column(name = "provider_sub", nullable = false)
    var providerSub: String,

    @Column(nullable = false)
    var email: String,

    @Column(name = "email_verified", nullable = false)
    var emailVerified: Boolean = false,

    var name: String? = null,

    @Column(name = "picture_url")
    var pictureUrl: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null
) {
    fun updateProfile(
        normalizedEmail: String,
        verified: Boolean,
        displayName: String?,
        avatarUrl: String?,
        lastLogin: Instant
    ) {
        email = normalizedEmail
        emailVerified = verified
        if (!displayName.isNullOrBlank()) {
            name = displayName
        }
        if (!avatarUrl.isNullOrBlank()) {
            pictureUrl = avatarUrl
        }
        lastLoginAt = lastLogin
    }
}
