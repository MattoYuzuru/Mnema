package app.mnema.user.entity

import jakarta.persistence.*
import jakarta.validation.constraints.Email
import java.time.Instant
import java.util.*

@Entity
@Table(name = "users", schema = "app_user")
data class User(
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = UUID.randomUUID(), // TODO: delete UUID generation in code, use postgres

    @field:Email
    @Column(nullable = false, unique = true)
    var email: String,

    @Column(nullable = false, unique = true, name = "username")
    var username: String,

    @Column(name = "bio")
    var bio: String? = null,

    @Column(nullable = false, name = "is_admin")
    var isAdmin: Boolean = false,

    @Column(name = "avatar_url")
    var avatarUrl: String? = null,

    @Column(name = "avatar_media_id")
    var avatarMediaId: UUID? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    @PrePersist
    fun prePersist() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }
}
