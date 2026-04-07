package app.mnema.user.media.client

import app.mnema.user.media.config.MediaClientProps
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class MediaApiClientTest {

    @Test
    fun `resolve returns empty list for empty input`() {
        val client = MediaApiClient(RestClient.builder().build(), MediaClientProps("https://media.mnema.app", "internal-token"))

        assertTrue(client.resolve(emptyList()).isEmpty())
    }

    @Test
    fun `resolve prefers bearer token over internal token`() {
        val mediaId = UUID.randomUUID()
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://media.mnema.app/resolve"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer bearer-token"))
            .andRespond(
                withSuccess(
                    """[{"mediaId":"$mediaId","url":"https://cdn.example/avatar.png","expiresAt":"2026-04-07T10:15:30Z"}]""",
                    MediaType.APPLICATION_JSON
                )
            )

        val client = MediaApiClient(
            builder.baseUrl("https://media.mnema.app").build(),
            MediaClientProps("https://media.mnema.app", "internal-token")
        )

        val response = client.resolve(listOf(mediaId), "bearer-token")

        assertEquals(1, response.size)
        assertEquals(mediaId, response.first().mediaId)
        assertEquals("https://cdn.example/avatar.png", response.first().url)
        assertEquals(Instant.parse("2026-04-07T10:15:30Z"), response.first().expiresAt)
        server.verify()
    }

    @Test
    fun `resolve falls back to internal token`() {
        val mediaId = UUID.randomUUID()
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://media.mnema.app/resolve"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer internal-token"))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

        val client = MediaApiClient(
            builder.baseUrl("https://media.mnema.app").build(),
            MediaClientProps("https://media.mnema.app", "internal-token")
        )

        val response = client.resolve(listOf(mediaId))

        assertTrue(response.isEmpty())
        server.verify()
    }
}
