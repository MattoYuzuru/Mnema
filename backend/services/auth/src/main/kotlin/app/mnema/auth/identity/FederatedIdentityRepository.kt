package app.mnema.auth.identity

import java.util.Optional
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface FederatedIdentityRepository : JpaRepository<FederatedIdentity, UUID> {
    fun findByProviderAndProviderSub(provider: String, providerSub: String): Optional<FederatedIdentity>
}
