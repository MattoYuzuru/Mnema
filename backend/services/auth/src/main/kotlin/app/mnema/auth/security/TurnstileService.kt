package app.mnema.auth.security

import app.mnema.auth.config.TurnstileProps
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient

@Component
class TurnstileService(
    private val props: TurnstileProps,
    builder: RestClient.Builder
) {
    private val client = builder
        .baseUrl("https://challenges.cloudflare.com")
        .build()
    private val log = LoggerFactory.getLogger(TurnstileService::class.java)

    fun enabled(): Boolean = props.enabled()

    fun siteKey(): String = props.siteKey

    fun verify(token: String?, remoteIp: String?): Boolean {
        if (!enabled()) return true
        if (token.isNullOrBlank()) return false

        val body = LinkedMultiValueMap<String, String>().apply {
            add("secret", props.secretKey)
            add("response", token)
            if (!remoteIp.isNullOrBlank()) {
                add("remoteip", remoteIp)
            }
        }

        return runCatching {
            client.post()
                .uri("/turnstile/v0/siteverify")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(TurnstileVerifyResponse::class.java)
        }.onFailure { err ->
            log.warn("Turnstile verification failed", err)
        }.getOrNull()?.success == true
    }
}

data class TurnstileVerifyResponse(
    val success: Boolean,
    @JsonProperty("error-codes")
    val errorCodes: List<String>? = null
)
