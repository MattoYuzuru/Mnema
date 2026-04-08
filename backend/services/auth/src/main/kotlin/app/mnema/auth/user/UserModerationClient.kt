package app.mnema.auth.user

import app.mnema.auth.config.UserClientProps
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.util.UUID

@Component
class UserModerationClient(
    @Qualifier("userRestClient")
    private val restClient: RestClient,
    private val props: UserClientProps
) {
    fun getModerationState(userId: UUID): UserModerationState? {
        return try {
            restClient.get()
                .uri("/internal/users/{userId}/moderation", userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${props.internalToken}")
                .retrieve()
                .body(UserModerationState::class.java)
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode.value() == 404) {
                return null
            }
            throw ex
        }
    }
}
