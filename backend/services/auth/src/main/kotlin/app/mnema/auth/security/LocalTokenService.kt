package app.mnema.auth.security

import app.mnema.auth.user.AuthUser
import java.time.Duration
import java.time.Instant
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component

@Component
class LocalTokenService(
    private val jwtEncoder: JwtEncoder,
    @Value("\${auth.issuer}") private val issuer: String
) {
    fun generateTokens(
        user: AuthUser,
        scopes: Set<String>,
        ttl: Duration,
        now: Instant = Instant.now()
    ): LocalTokenResponse {
        val accessClaims = baseClaims(user, scopes, now, now.plus(ttl))
        val accessToken = encode(accessClaims)

        return LocalTokenResponse(
            accessToken = accessToken,
            expiresIn = ttl.seconds,
            tokenType = "Bearer",
            scope = scopes.joinToString(" ")
        )
    }

    private fun baseClaims(
        user: AuthUser,
        scopes: Set<String>,
        issuedAt: Instant,
        expiresAt: Instant
    ): JwtClaimsSet {
        val claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .subject(user.id?.toString() ?: user.email)
            .claim("email", user.email)
            .claim("email_verified", user.emailVerified)
            .claim("scope", scopes.joinToString(" "))

        user.id?.toString()?.let { claims.claim("user_id", it) }
        user.username?.let { claims.claim("username", it) }
        user.name?.let { claims.claim("name", it) }
        user.pictureUrl?.let { claims.claim("picture", it) }

        return claims.build()
    }

    private fun encode(claimsSet: JwtClaimsSet): String {
        return jwtEncoder.encode(JwtEncoderParameters.from(claimsSet)).tokenValue
    }
}

data class LocalTokenResponse(
    val accessToken: String,
    val expiresIn: Long,
    val tokenType: String,
    val scope: String
)
