package app.mnema.user.security

import app.mnema.user.entity.User
import app.mnema.user.repository.UserRepository
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

class UserAuthorizationServiceTest {

    private val repository = mock(UserRepository::class.java)
    private val service = UserAuthorizationService(repository)

    @Test
    fun `returns false when authentication is missing`() {
        assertFalse(service.isAdmin(null))
    }

    @Test
    fun `returns false for invalid user id claim`() {
        val jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("user_id", "not-a-uuid")
            .build()

        assertFalse(service.isAdmin(JwtAuthenticationToken(jwt)))
    }

    @Test
    fun `returns repository admin flag`() {
        val userId = UUID.randomUUID()
        val jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("user_id", userId.toString())
            .build()
        `when`(repository.findById(userId)).thenReturn(
            Optional.of(
                User(
                    id = userId,
                    email = "admin@example.com",
                    username = "admin",
                    isAdmin = true
                )
            )
        )

        assertTrue(service.isAdmin(JwtAuthenticationToken(jwt)))
    }
}
