package app.mnema.auth

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class AccountService(
    private val repo: AccountRepository
) {
    @Transactional
    fun upsertGoogleAccount(
        providerSub: String,
        email: String?,
        name: String?,
        picture: String?
    ): Account {
        val acc = repo.findByProviderAndProviderSub("google", providerSub)
            .orElseGet {
                Account(
                    provider = "google",
                    providerSub = providerSub,
                    email = email ?: "unknown",
                    emailVerified = false
                )
            }

        acc.email = email ?: acc.email
        acc.name = name ?: acc.name
        acc.pictureUrl = picture ?: acc.pictureUrl
        acc.lastLoginAt = Instant.now()

        return repo.save(acc)
    }
}
