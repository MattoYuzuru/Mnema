package app.mnema.auth.user

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class LoginModerationService(
    private val userModerationClient: UserModerationClient
) {
    fun assertLoginAllowed(userId: UUID?) {
        if (userId == null) {
            return
        }

        val state = userModerationClient.getModerationState(userId) ?: return
        if (state.banned) {
            throw ResponseStatusException(HttpStatus.LOCKED, "banned")
        }
    }
}
