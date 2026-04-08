package app.mnema.auth

import app.mnema.auth.config.AuthFeaturesProps
import app.mnema.auth.federation.FederatedUserMapper
import app.mnema.auth.identity.FederatedIdentity
import app.mnema.auth.identity.FederatedIdentityResult
import app.mnema.auth.identity.FederatedIdentityService
import app.mnema.auth.identity.FederatedUserInfo
import app.mnema.auth.user.AuthUser
import app.mnema.auth.user.LoginModerationService
import com.nimbusds.jose.jwk.JWKMatcher
import com.nimbusds.jose.jwk.JWKSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.web.server.ResponseStatusException
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Base64

class SecurityConfigTest {

    private val identityService = mock(FederatedIdentityService::class.java)
    private val userMapper = mock(FederatedUserMapper::class.java)
    private val config = SecurityConfig(AuthFeaturesProps(federatedEnabled = true), identityService, userMapper)

    @Test
    fun `federated success handler redirects banned users back to login page`() {
        val loginModerationService = mock(LoginModerationService::class.java)
        val handler = config.federatedSuccessHandler(loginModerationService, "http://localhost:3005/login?from=oauth")
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        request.getSession(true)
        val authentication = oauthAuthentication()
        val info = FederatedUserInfo("google", "sub-123", "user@example.com", true, "User", null)
        val user = AuthUser(
            id = java.util.UUID.randomUUID(),
            email = "user@example.com",
            emailVerified = true,
            createdAt = Instant.now()
        )
        val identity = FederatedIdentity(
            user = user,
            provider = "google",
            providerSub = "sub-123",
            email = "user@example.com"
        )
        `when`(userMapper.from(authentication)).thenReturn(info)
        `when`(identityService.synchronize(info)).thenReturn(FederatedIdentityResult(user, identity))
        doThrow(ResponseStatusException(HttpStatus.LOCKED, "banned"))
            .`when`(loginModerationService).assertLoginAllowed(user.id)

        handler.onAuthenticationSuccess(request, response, authentication)

        assertEquals("http://localhost:3005/login?from=oauth&authError=banned", response.redirectedUrl)
        verify(loginModerationService).assertLoginAllowed(user.id)
    }

    @Test
    fun `jwk source accepts configured pem keys and authorization server settings exposes issuer`() {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val pair = generator.generateKeyPair()
        val publicPem = pem("PUBLIC KEY", pair.public.encoded)
        val privatePem = pem("PRIVATE KEY", pair.private.encoded)

        val jwks = config.jwkSource(publicPem, privatePem)
            .get(JWKSelector(JWKMatcher.Builder().build()), null)

        assertEquals("https://issuer.example", config.authorizationServerSettings("https://issuer.example").issuer)
        assertEquals(1, jwks.size)
        assertNotNull(jwks.single().keyID)
    }

    private fun oauthAuthentication(): OAuth2AuthenticationToken {
        val principal = DefaultOAuth2User(
            listOf(SimpleGrantedAuthority("ROLE_USER")),
            mapOf("sub" to "sub-123"),
            "sub"
        )
        return OAuth2AuthenticationToken(principal, principal.authorities, "google")
    }

    private fun pem(type: String, encoded: ByteArray): String =
        "-----BEGIN $type-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(encoded) +
            "\n-----END $type-----"
}
