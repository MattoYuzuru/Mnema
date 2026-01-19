package app.mnema.user.auth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class AuthAccountClient(
    builder: RestClient.Builder,
    @Value("\${app.auth.base-url:\${spring.security.oauth2.resourceserver.jwt.issuer-uri}}")
    private val baseUrl: String
) {
    private val client = builder.baseUrl(trimBaseUrl(baseUrl)).build()
    private val log = LoggerFactory.getLogger(AuthAccountClient::class.java)

    fun deleteAccount(bearerToken: String) {
        runCatching {
            client.delete()
                .uri("/auth/account")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $bearerToken")
                .retrieve()
                .toBodilessEntity()
        }.onFailure { err ->
            log.warn("Failed to delete auth account", err)
            throw err
        }
    }

    private fun trimBaseUrl(value: String): String = value.trim().removeSuffix("/")
}
