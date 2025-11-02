package app.mnema.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "accounts", schema = "auth")
class Account(
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID? = null,

    @Column(nullable = false)
    var provider: String, // google

    @Column(name = "provider_sub", nullable = false)
    var providerSub: String, // google sub

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
)