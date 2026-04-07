package app.mnema.auth.security

import app.mnema.auth.user.AuthUser
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters

class LocalTokenServiceTest {

    @Test
    fun `encodes expected claims and ttl`() {
        val encoder = mock(JwtEncoder::class.java)
        val service = LocalTokenService(encoder, "https://auth.mnema.app")
        val now = Instant.parse("2026-04-07T10:15:30Z")
        val userId = UUID.randomUUID()
        val user = AuthUser(
            id = userId,
            email = "user@example.com",
            emailVerified = true,
            username = "mnema-user",
            name = "Mnema User",
            pictureUrl = "https://img.example/avatar.png"
        )

        `when`(encoder.encode(org.mockito.ArgumentMatchers.any(JwtEncoderParameters::class.java))).thenReturn(
            Jwt.withTokenValue("token-123")
                .header("alg", "none")
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(2)))
                .claim("scope", "openid profile")
                .build()
        )

        val response = service.generateTokens(user, setOf("openid", "profile"), Duration.ofHours(2), now)

        assertEquals("token-123", response.accessToken)
        assertEquals(7200, response.expiresIn)
        assertEquals("Bearer", response.tokenType)
        assertEquals("openid profile", response.scope)

        val captor = ArgumentCaptor.forClass(JwtEncoderParameters::class.java)
        verify(encoder).encode(captor.capture())
        val claims: JwtClaimsSet = captor.value.claims
        assertEquals("https://auth.mnema.app", claims.issuer.toString())
        assertEquals(userId.toString(), claims.subject)
        assertEquals("user@example.com", claims.claims["email"])
        assertEquals(userId.toString(), claims.claims["user_id"])
        assertEquals("mnema-user", claims.claims["username"])
    }
}
