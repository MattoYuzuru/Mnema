package app.mnema.auth.identity

import app.mnema.auth.user.AuthUser
import app.mnema.auth.user.AuthUserRepository
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class FederatedIdentityServiceTest {

    private val identityRepository = mock(FederatedIdentityRepository::class.java)
    private val userRepository = mock(AuthUserRepository::class.java)
    private val service = FederatedIdentityService(identityRepository, userRepository)

    @Test
    fun `synchronize creates user and identity when both are missing`() {
        val info = FederatedUserInfo(
            provider = "GitHub",
            providerSub = "42",
            email = "USER@example.com",
            emailVerified = true,
            name = "Octo Cat",
            pictureUrl = "https://img.example/octo.png"
        )

        `when`(identityRepository.findByProviderAndProviderSub("github", "42")).thenReturn(Optional.empty())
        `when`(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty())
        `when`(userRepository.save(any(AuthUser::class.java))).thenAnswer { it.arguments[0] }
        `when`(identityRepository.save(any(FederatedIdentity::class.java))).thenAnswer { it.arguments[0] }

        val result = service.synchronize(info)

        assertEquals("user@example.com", result.user.email)
        assertTrue(result.user.emailVerified)
        assertEquals("github", result.identity.provider)
        assertEquals("42", result.identity.providerSub)
        assertEquals("user@example.com", result.identity.email)
    }

    @Test
    fun `synchronize updates existing identity and user profile`() {
        val user = AuthUser(
            id = UUID.randomUUID(),
            email = "user@example.com",
            emailVerified = false,
            name = "Old Name",
            pictureUrl = "https://img.example/old.png",
            lastLoginAt = Instant.EPOCH
        )
        val identity = FederatedIdentity(
            user = user,
            provider = "github",
            providerSub = "42",
            email = "user@example.com",
            emailVerified = false,
            name = "Old Name",
            pictureUrl = "https://img.example/old.png"
        )
        val info = FederatedUserInfo(
            provider = "GitHub",
            providerSub = "42",
            email = "USER@example.com",
            emailVerified = true,
            name = "New Name",
            pictureUrl = "https://img.example/new.png"
        )

        `when`(identityRepository.findByProviderAndProviderSub("github", "42")).thenReturn(Optional.of(identity))
        `when`(identityRepository.save(identity)).thenReturn(identity)
        `when`(userRepository.save(user)).thenReturn(user)

        val result = service.synchronize(info)

        assertSame(user, result.user)
        assertTrue(user.emailVerified)
        assertEquals("New Name", user.name)
        assertEquals("https://img.example/new.png", user.pictureUrl)
        assertEquals("user@example.com", identity.email)
        assertTrue(identity.emailVerified)
        assertEquals("New Name", identity.name)
    }

    @Test
    fun `synchronize rejects provider without email`() {
        val ex = assertThrows<IllegalArgumentException> {
            service.synchronize(
                FederatedUserInfo(
                    provider = "google",
                    providerSub = "sub",
                    email = null,
                    emailVerified = false,
                    name = null,
                    pictureUrl = null
                )
            )
        }

        assertEquals("OAuth2 provider google did not supply email", ex.message)
    }
}
