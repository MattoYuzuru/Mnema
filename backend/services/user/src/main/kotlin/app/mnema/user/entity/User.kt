package app.mnema.user.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.Email
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users", schema = "app_user")
data class User(
    @Id
    @Column(columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @field:Email
    @Column(nullable = false, unique = true)
    val email: String,

    @Column(nullable = false, name = "username", unique = true)
    val username: String,

    @Column(name = "bio")
    val bio: String? = null,

    @Column(nullable = false, name = "is_admin")
    val isAdmin: Boolean = false,

    @Column(name = "avatar_url")
    val avatarUrl: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant? = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant? = Instant.now()
)
