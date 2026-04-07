package app.mnema.user.entity

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UserTest {

    @Test
    fun `pre persist initializes created and updated timestamps`() {
        val user = User(
            email = "user@example.com",
            username = "mnema",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH
        )

        user.prePersist()

        assertTrue(user.createdAt.isAfter(Instant.EPOCH))
        assertEquals(user.createdAt, user.updatedAt)
    }

    @Test
    fun `pre update refreshes updated timestamp`() {
        val user = User(
            email = "user@example.com",
            username = "mnema",
            updatedAt = Instant.EPOCH
        )

        user.preUpdate()

        assertTrue(user.updatedAt.isAfter(Instant.EPOCH))
    }
}
