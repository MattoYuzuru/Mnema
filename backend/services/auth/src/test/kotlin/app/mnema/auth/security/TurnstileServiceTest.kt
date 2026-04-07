package app.mnema.auth.security

import app.mnema.auth.config.TurnstileProps
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class TurnstileServiceTest {

    @Test
    fun `verify short circuits when feature disabled`() {
        val service = TurnstileService(TurnstileProps(), RestClient.builder())

        assertTrue(service.verify(null, null))
        assertFalse(service.enabled())
    }

    @Test
    fun `verify rejects blank token when enabled`() {
        val service = TurnstileService(TurnstileProps("site-key", "secret-key"), RestClient.builder())

        assertFalse(service.verify(" ", "127.0.0.1"))
        assertTrue(service.enabled())
        assertTrue(service.siteKey() == "site-key")
    }

    @Test
    fun `verify returns true for successful api response`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://challenges.cloudflare.com/turnstile/v0/siteverify"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"success":true}""", MediaType.APPLICATION_JSON))

        val service = TurnstileService(TurnstileProps("site-key", "secret-key"), builder)

        assertTrue(service.verify("captcha-token", "127.0.0.1"))
        server.verify()
    }

    @Test
    fun `verify returns false when upstream fails`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://challenges.cloudflare.com/turnstile/v0/siteverify"))
            .andRespond(withServerError())

        val service = TurnstileService(TurnstileProps("site-key", "secret-key"), builder)

        assertFalse(service.verify("captcha-token", null))
        server.verify()
    }
}
