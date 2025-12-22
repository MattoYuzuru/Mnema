package app.mnema.auth.identity

import app.mnema.auth.user.AuthUser
import app.mnema.auth.user.AuthUserRepository
import java.time.Instant
import java.util.Locale
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FederatedIdentityService(
    private val identityRepository: FederatedIdentityRepository,
    private val userRepository: AuthUserRepository
) {
    @Transactional
    fun synchronize(info: FederatedUserInfo): FederatedIdentityResult {
        val providerKey = info.provider.lowercase(Locale.ROOT)
        val normalizedEmail = info.email?.lowercase(Locale.ROOT)
            ?: throw IllegalArgumentException("OAuth2 provider ${info.provider} did not supply email")
        val now = Instant.now()

        val existingIdentity = identityRepository.findByProviderAndProviderSub(providerKey, info.providerSub)
            .orElse(null)

        val user = when {
            existingIdentity != null -> existingIdentity.user
            else -> userRepository.findByEmail(normalizedEmail).orElseGet {
                userRepository.save(
                    AuthUser(
                        email = normalizedEmail,
                        emailVerified = info.emailVerified,
                        name = info.name,
                        pictureUrl = info.pictureUrl,
                        createdAt = now,
                        lastLoginAt = now
                    )
                )
            }
        }

        user.updateProfile(info.name, info.pictureUrl, info.emailVerified)
        user.touchLogin(now)

        val identity = existingIdentity?.apply {
            this.user = user
            updateProfile(normalizedEmail, info.emailVerified, info.name, info.pictureUrl, now)
        } ?: FederatedIdentity(
            user = user,
            provider = providerKey,
            providerSub = info.providerSub,
            email = normalizedEmail,
            emailVerified = info.emailVerified,
            name = info.name,
            pictureUrl = info.pictureUrl,
            createdAt = now,
            lastLoginAt = now
        )

        val savedIdentity = identityRepository.save(identity)
        val savedUser = userRepository.save(user)
        return FederatedIdentityResult(user = savedUser, identity = savedIdentity)
    }
}

data class FederatedUserInfo(
    val provider: String,
    val providerSub: String,
    val email: String?,
    val emailVerified: Boolean,
    val name: String?,
    val pictureUrl: String?
)

data class FederatedIdentityResult(
    val user: AuthUser,
    val identity: FederatedIdentity
)
