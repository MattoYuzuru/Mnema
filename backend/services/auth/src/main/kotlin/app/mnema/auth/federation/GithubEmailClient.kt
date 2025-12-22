package app.mnema.auth.federation

import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class GithubEmailClient(
    builder: RestClient.Builder
) {
    private val client = builder
        .baseUrl("https://api.github.com")
        .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
        .build()

    fun fetchPrimaryEmail(token: String): GithubEmail? =
        runCatching {
            client.get()
                .uri("/user/emails")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .body(object : ParameterizedTypeReference<List<GithubEmail>>() {}) ?: emptyList()
        }.onFailure { err ->
            log.warn("Unable to load GitHub emails", err)
        }.getOrDefault(emptyList())
            .sortedWith(
                compareByDescending<GithubEmail> { it.primary }
                    .thenByDescending { it.verified }
            )
            .firstOrNull()

    companion object {
        private val log = LoggerFactory.getLogger(GithubEmailClient::class.java)
    }
}

data class GithubEmail(
    val email: String,
    val primary: Boolean,
    val verified: Boolean
)
