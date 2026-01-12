package app.mnema.user.media.client

import app.mnema.user.media.config.MediaClientProps
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.util.UUID

@Component
class MediaApiClient(
    private val mediaRestClient: RestClient,
    private val props: MediaClientProps
) {
    fun resolve(mediaIds: List<UUID>, bearerToken: String? = null): List<MediaResolved> {
        if (mediaIds.isEmpty()) return emptyList()

        val payload = mapOf("mediaIds" to mediaIds)
        var request = mediaRestClient.post()
            .uri("/resolve")
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload)

        val token = resolveToken(bearerToken)
        if (!token.isNullOrBlank()) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }

        return request.retrieve()
            .body(object : ParameterizedTypeReference<List<MediaResolved>>() {}) ?: emptyList()
    }

    private fun resolveToken(bearerToken: String?): String? {
        if (!bearerToken.isNullOrBlank()) {
            return bearerToken
        }
        return props.internalToken
    }
}
