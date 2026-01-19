package app.mnema.user.security

import app.mnema.user.repository.UserRepository
import java.util.UUID
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

@Component("userAuthz")
class UserAuthorizationService(
    private val userRepository: UserRepository
) {
    fun isAdmin(authentication: Authentication?): Boolean {
        val userId = resolveUserId(authentication) ?: return false
        return userRepository.findById(userId)
            .map { it.isAdmin }
            .orElse(false)
    }

    private fun resolveUserId(authentication: Authentication?): UUID? {
        val jwt = when (authentication) {
            is JwtAuthenticationToken -> authentication.token
            else -> authentication?.principal as? Jwt
        } ?: return null

        val rawId = jwt.getClaimAsString("user_id") ?: return null
        return runCatching { UUID.fromString(rawId) }.getOrNull()
    }
}
