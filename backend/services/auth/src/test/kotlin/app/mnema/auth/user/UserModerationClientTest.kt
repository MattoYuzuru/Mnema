package app.mnema.auth.user

import app.mnema.auth.config.UserClientProps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.util.UUID

class UserModerationClientTest {

    @Test
    fun `returns moderation state for existing user`() {
        val userId = UUID.randomUUID()
        val builder = RestClient.builder().baseUrl("http://user.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = UserModerationClient(builder.build(), UserClientProps("http://user.test", "internal-secret"))
        server.expect(requestTo("http://user.test/internal/users/$userId/moderation"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer internal-secret"))
            .andRespond(
                withSuccess(
                    """{"id":"$userId","admin":true,"banned":false}""",
                    MediaType.APPLICATION_JSON
                )
            )

        val state = client.getModerationState(userId)

        assertEquals(userId, state?.id)
        assertEquals(true, state?.admin)
        assertEquals(false, state?.banned)
        server.verify()
    }

    @Test
    fun `returns null for missing moderation state`() {
        val userId = UUID.randomUUID()
        val builder = RestClient.builder().baseUrl("http://user.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = UserModerationClient(builder.build(), UserClientProps("http://user.test", "internal-secret"))
        server.expect(requestTo("http://user.test/internal/users/$userId/moderation"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))

        val state = client.getModerationState(userId)

        assertNull(state)
        server.verify()
    }

    @Test
    fun `rethrows non not found moderation errors`() {
        val userId = UUID.randomUUID()
        val builder = RestClient.builder().baseUrl("http://user.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = UserModerationClient(builder.build(), UserClientProps("http://user.test", "internal-secret"))
        server.expect(requestTo("http://user.test/internal/users/$userId/moderation"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        assertThrows<RestClientResponseException> {
            client.getModerationState(userId)
        }
        server.verify()
    }
}
