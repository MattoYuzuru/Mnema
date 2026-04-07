package app.mnema.auth.federation

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.test.util.ReflectionTestUtils

class FederatedOAuth2UserServiceTest {

    private val githubEmailClient = mock(GithubEmailClient::class.java)
    private val delegate = mock(DefaultOAuth2UserService::class.java)
    private val service = FederatedOAuth2UserService(githubEmailClient).also {
        ReflectionTestUtils.setField(it, "delegate", delegate)
    }

    @Test
    fun `load user returns non github user without enrichment`() {
        val loaded = oauthUser(mapOf("sub" to "google-user", "email" to "user@example.com"), "sub")
        val request = request("google")
        `when`(delegate.loadUser(request)).thenReturn(loaded)

        val response = service.loadUser(request)

        assertSame(loaded, response)
        verifyNoInteractions(githubEmailClient)
    }

    @Test
    fun `load user enriches github email when provider response is incomplete`() {
        val loaded = oauthUser(
            mapOf(
                "id" to "42",
                "login" to "octocat",
                "email" to "",
                "email_verified" to null
            ),
            "id"
        )
        val request = request("github")
        `when`(delegate.loadUser(request)).thenReturn(loaded)
        `when`(githubEmailClient.fetchPrimaryEmail("github-token"))
            .thenReturn(GithubEmail("octo@example.com", primary = true, verified = true))

        val response = service.loadUser(request)

        assertEquals("octo@example.com", response.getAttribute<String>("email"))
        assertEquals(true, response.getAttribute<Boolean>("email_verified"))
    }

    @Test
    fun `load user marks github email as unverified when fallback returns nothing`() {
        val loaded = oauthUser(mapOf("id" to "42", "login" to "octocat"), "id")
        val request = request("github")
        `when`(delegate.loadUser(request)).thenReturn(loaded)
        `when`(githubEmailClient.fetchPrimaryEmail("github-token")).thenReturn(null)

        val response = service.loadUser(request)

        assertEquals(false, response.attributes["email_verified"])
    }

    private fun request(registrationId: String): OAuth2UserRequest =
        OAuth2UserRequest(
            ClientRegistration.withRegistrationId(registrationId)
                .clientId("client-id")
                .clientSecret("client-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://idp.example.com/oauth/authorize")
                .tokenUri("https://idp.example.com/oauth/token")
                .userInfoUri("https://idp.example.com/userinfo")
                .userNameAttributeName("id")
                .clientName(registrationId)
                .build(),
            OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "github-token",
                Instant.parse("2026-04-07T10:15:30Z"),
                Instant.parse("2026-04-07T11:15:30Z")
            )
        )

    private fun oauthUser(attributes: Map<String, Any?>, userNameAttributeName: String): OAuth2User =
        DefaultOAuth2User(
            listOf(SimpleGrantedAuthority("ROLE_USER")),
            LinkedHashMap(attributes),
            userNameAttributeName
        )
}
