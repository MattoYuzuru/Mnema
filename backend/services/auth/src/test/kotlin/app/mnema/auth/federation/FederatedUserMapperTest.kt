package app.mnema.auth.federation

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.core.user.DefaultOAuth2User

class FederatedUserMapperTest {

    private val mapper = FederatedUserMapper()

    @Test
    fun `maps google oidc principal`() {
        val idToken = OidcIdToken(
            "token",
            Instant.now(),
            Instant.now().plusSeconds(300),
            mapOf(
                "sub" to "google-sub",
                "email" to "user@example.com",
                "email_verified" to true,
                "name" to "Google User",
                "picture" to "https://img.example/google.png"
            )
        )
        val principal = DefaultOidcUser(listOf(SimpleGrantedAuthority("ROLE_USER")), idToken)
        val authentication = OAuth2AuthenticationToken(principal, principal.authorities, "google")

        val info = mapper.from(authentication)

        requireNotNull(info)
        assertEquals("google", info.provider)
        assertEquals("google-sub", info.providerSub)
        assertEquals("user@example.com", info.email)
        assertTrue(info.emailVerified)
        assertEquals("Google User", info.name)
    }

    @Test
    fun `maps github oauth principal`() {
        val principal = DefaultOAuth2User(
            listOf(SimpleGrantedAuthority("ROLE_USER")),
            mapOf(
                "id" to 42,
                "login" to "octocat",
                "name" to "Octo Cat",
                "email" to "octo@example.com",
                "email_verified" to true,
                "avatar_url" to "https://img.example/octo.png"
            ),
            "id"
        )
        val authentication = OAuth2AuthenticationToken(principal, principal.authorities, "github")

        val info = mapper.from(authentication)

        requireNotNull(info)
        assertEquals("42", info.providerSub)
        assertEquals("octo@example.com", info.email)
        assertEquals("Octo Cat", info.name)
    }

    @Test
    fun `maps github oauth principal with login fallback`() {
        val principal = DefaultOAuth2User(
            listOf(SimpleGrantedAuthority("ROLE_USER")),
            mapOf(
                "id" to 42,
                "login" to "octocat",
                "avatar_url" to "https://img.example/octo.png"
            ),
            "id"
        )
        val authentication = OAuth2AuthenticationToken(principal, principal.authorities, "github")

        val info = mapper.from(authentication)

        requireNotNull(info)
        assertEquals("octocat", info.name)
        assertEquals(false, info.emailVerified)
        assertEquals("https://img.example/octo.png", info.pictureUrl)
    }

    @Test
    fun `maps yandex principal with encoded avatar id`() {
        val principal = DefaultOAuth2User(
            listOf(SimpleGrantedAuthority("ROLE_USER")),
            mapOf(
                "id" to "ya-sub",
                "emails" to listOf("user@example.com"),
                "display_name" to "Yandex User",
                "default_avatar_id" to "12/34 56"
            ),
            "id"
        )
        val authentication = OAuth2AuthenticationToken(principal, principal.authorities, "yandex")

        val info = mapper.from(authentication)

        requireNotNull(info)
        assertEquals("ya-sub", info.providerSub)
        assertEquals("user@example.com", info.email)
        assertEquals("Yandex User", info.name)
        assertEquals("https://avatars.yandex.net/get-yapic/12%2F34+56/islands-200", info.pictureUrl)
    }

    @Test
    fun `returns null for google principal without oidc claims`() {
        val principal = DefaultOAuth2User(
            listOf(SimpleGrantedAuthority("ROLE_USER")),
            mapOf("sub" to "google-sub"),
            "sub"
        )
        val authentication = OAuth2AuthenticationToken(principal, principal.authorities, "google")

        assertNull(mapper.from(authentication))
    }

    @Test
    fun `returns null for unsupported provider`() {
        val principal = DefaultOAuth2User(
            listOf(SimpleGrantedAuthority("ROLE_USER")),
            mapOf("id" to "1"),
            "id"
        )
        val authentication = OAuth2AuthenticationToken(principal, principal.authorities, "discord")

        assertNull(mapper.from(authentication))
    }
}
