package app.mnema.user.Entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.Email
import java.util.UUID

@Entity
@Table(name = "users", schema = "app_users")
data class User(
    @Id
    @Column(columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @field:Email
    @Column(nullable = false, unique = true)
    val email: String,

    @Column(nullable = false, unique = true)
    val handle: String,

    @Column(name = "display_name")
    val displayName: String? = null,

    val bio: String? = null
)
