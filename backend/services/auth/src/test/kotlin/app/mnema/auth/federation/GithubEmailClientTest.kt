package app.mnema.auth.federation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class GithubEmailClientTest {

    @Test
    fun `fetch primary email prefers primary and verified entries`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.github.com/user/emails"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer github-token"))
            .andRespond(
                withSuccess(
                    """
                    [
                      {"email":"secondary@example.com","primary":false,"verified":true},
                      {"email":"primary@example.com","primary":true,"verified":false},
                      {"email":"best@example.com","primary":true,"verified":true}
                    ]
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON
                )
            )

        val client = GithubEmailClient(builder)

        val email = client.fetchPrimaryEmail("github-token")

        requireNotNull(email)
        assertEquals("best@example.com", email.email)
        server.verify()
    }

    @Test
    fun `fetch primary email returns null for empty or failed response`() {
        val emptyBuilder = RestClient.builder()
        val emptyServer = MockRestServiceServer.bindTo(emptyBuilder).build()
        emptyServer.expect(requestTo("https://api.github.com/user/emails"))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

        assertNull(GithubEmailClient(emptyBuilder).fetchPrimaryEmail("github-token"))
        emptyServer.verify()

        val failingBuilder = RestClient.builder()
        val failingServer = MockRestServiceServer.bindTo(failingBuilder).build()
        failingServer.expect(requestTo("https://api.github.com/user/emails"))
            .andRespond(withServerError())

        assertNull(GithubEmailClient(failingBuilder).fetchPrimaryEmail("github-token"))
        failingServer.verify()
    }
}
