package app.mnema.auth.federation

import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Component
import java.util.*

@Component
class FederatedOAuth2UserService(
    private val githubEmailClient: GithubEmailClient
) : OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private val delegate = DefaultOAuth2UserService()

    override fun loadUser(userRequest: OAuth2UserRequest): OAuth2User {
        val loaded = delegate.loadUser(userRequest)
        val registrationId = userRequest.clientRegistration.registrationId.lowercase(Locale.ROOT)

        if (registrationId != "github") {
            return loaded
        }

        val enrichedAttributes = LinkedHashMap(loaded.attributes)
        val email = enrichedAttributes["email"]?.toString()
        val alreadyVerified = enrichedAttributes["email_verified"] as? Boolean

        if (email.isNullOrBlank() || alreadyVerified == null) {
            val resolvedEmail = githubEmailClient.fetchPrimaryEmail(userRequest.accessToken.tokenValue)
            if (resolvedEmail != null) {
                enrichedAttributes["email"] = resolvedEmail.email
                enrichedAttributes["email_verified"] = resolvedEmail.verified
            } else if (alreadyVerified == null) {
                enrichedAttributes["email_verified"] = false
            }
        }

        return DefaultOAuth2User(
            loaded.authorities,
            enrichedAttributes,
            userRequest.clientRegistration.providerDetails.userInfoEndpoint.userNameAttributeName
        )
    }
}
