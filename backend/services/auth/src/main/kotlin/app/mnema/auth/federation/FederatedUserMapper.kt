package app.mnema.auth.federation

import app.mnema.auth.identity.FederatedUserInfo
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*

@Component
class FederatedUserMapper {
    fun from(authentication: OAuth2AuthenticationToken): FederatedUserInfo? {
        val provider = authentication.authorizedClientRegistrationId.lowercase(Locale.ROOT)
        val principal = authentication.principal

        return when (provider) {
            "google" -> mapGoogle(provider, principal)
            "github" -> mapGithub(provider, principal)
            "yandex" -> mapYandex(provider, principal)
            else -> null
        }
    }

    private fun mapGoogle(provider: String, principal: OAuth2User): FederatedUserInfo? {
        val oidcUser = principal as? OidcUser ?: return null
        return FederatedUserInfo(
            provider = provider,
            providerSub = oidcUser.subject,
            email = oidcUser.email,
            emailVerified = oidcUser.emailVerified,
            name = oidcUser.fullName ?: oidcUser.preferredUsername ?: oidcUser.givenName,
            pictureUrl = oidcUser.picture
        )
    }

    private fun mapGithub(provider: String, principal: OAuth2User): FederatedUserInfo? {
        val id = principal.getAttribute<Any>("id")?.toString() ?: return null
        val email = principal.getAttribute<String>("email")
        val verified = principal.getAttribute<Boolean>("email_verified") ?: false
        val login = principal.getAttribute<String>("login")
        val avatar = principal.getAttribute<String>("avatar_url")
        val name = principal.getAttribute<String>("name") ?: login

        return FederatedUserInfo(
            provider = provider,
            providerSub = id,
            email = email,
            emailVerified = verified,
            name = name,
            pictureUrl = avatar
        )
    }

    private fun mapYandex(provider: String, principal: OAuth2User): FederatedUserInfo? {
        val id = principal.getAttribute<Any>("id")?.toString() ?: return null
        val defaultEmail = principal.getAttribute<String>("default_email")
        val emails = principal.getAttribute<List<String>>("emails").orEmpty()
        val picture = principal.getAttribute<String>("default_avatar_id")
            ?.takeIf { it.isNotBlank() }
            ?.let { "https://avatars.yandex.net/get-yapic/${encode(it)}/islands-200" }
        val name =
            principal.getAttribute<String>("real_name")
                ?: principal.getAttribute<String>("display_name")
                ?: principal.getAttribute<String>("first_name")

        return FederatedUserInfo(
            provider = provider,
            providerSub = id,
            email = defaultEmail ?: emails.firstOrNull(),
            emailVerified = true,
            name = name,
            pictureUrl = picture
        )
    }

    private fun <T> List<T>?.orEmpty(): List<T> = this ?: emptyList()

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)
}
