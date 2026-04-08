package app.mnema.auth.user

import java.util.UUID

data class UserModerationState(
    val id: UUID,
    val admin: Boolean,
    val banned: Boolean
)
