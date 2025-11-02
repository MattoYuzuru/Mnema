package app.mnema.auth

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface AccountRepository : JpaRepository<Account, UUID> {
    fun findByProviderAndProviderSub(provider: String, providerSub: String): Optional<Account>
}