package app.mnema.user.auth

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

class AuthAccountClientTest {

    @Test
    fun `delete account trims base url and sends bearer token`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://auth.mnema.app/auth/account"))
            .andExpect(method(HttpMethod.DELETE))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
            .andRespond(withNoContent())

        val client = AuthAccountClient(builder, "https://auth.mnema.app/")

        client.deleteAccount("test-token")

        server.verify()
    }

    @Test
    fun `delete account propagates upstream failure`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://auth.mnema.app/auth/account"))
            .andRespond(withServerError())

        val client = AuthAccountClient(builder, "https://auth.mnema.app")

        assertThrows(RestClientResponseException::class.java) {
            client.deleteAccount("test-token")
        }
        server.verify()
    }
}
