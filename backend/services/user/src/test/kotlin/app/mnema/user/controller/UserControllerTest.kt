package app.mnema.user.controller

import app.mnema.user.entity.User
import app.mnema.user.repository.UserRepository
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.any
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class UserControllerTest {

    private val repo = mock(UserRepository::class.java)
    private val controller = UserController(repo)

    @Test
    fun `get returns user and delete removes existing user`() {
        val id = UUID.randomUUID()
        val user = User(id = id, email = "user@example.com", username = "mnema")
        `when`(repo.findById(id)).thenReturn(Optional.of(user))
        `when`(repo.existsById(id)).thenReturn(true)

        assertEquals(user, controller.get(id))
        controller.delete(id)

        verify(repo).deleteById(id)
    }

    @Test
    fun `create rejects duplicate username or email`() {
        `when`(repo.existsByUsernameIgnoreCase("taken")).thenReturn(true)
        val usernameEx = assertThrows<ResponseStatusException> {
            controller.create(UserController.CreateUserReq("user@example.com", "taken"))
        }
        assertEquals(HttpStatus.CONFLICT, usernameEx.statusCode)

        `when`(repo.existsByUsernameIgnoreCase("free")).thenReturn(false)
        `when`(repo.existsByEmailIgnoreCase("used@example.com")).thenReturn(true)
        val emailEx = assertThrows<ResponseStatusException> {
            controller.create(UserController.CreateUserReq("used@example.com", "free"))
        }
        assertEquals(HttpStatus.CONFLICT, emailEx.statusCode)
    }

    @Test
    fun `create stores new user`() {
        val request = UserController.CreateUserReq(
            email = "user@example.com",
            username = "mnema",
            bio = "bio",
            avatarUrl = "https://img.example/avatar.png"
        )
        `when`(repo.existsByUsernameIgnoreCase("mnema")).thenReturn(false)
        `when`(repo.existsByEmailIgnoreCase("user@example.com")).thenReturn(false)
        `when`(repo.save(any(User::class.java))).thenAnswer { it.arguments[0] }

        val created = controller.create(request)

        assertEquals("mnema", created.username)
        assertEquals("bio", created.bio)
        assertEquals("https://img.example/avatar.png", created.avatarUrl)
    }

    @Test
    fun `partial update applies provided fields and checks conflicts`() {
        val id = UUID.randomUUID()
        val user = User(id = id, email = "old@example.com", username = "old")
        `when`(repo.findById(id)).thenReturn(Optional.of(user))
        `when`(repo.existsByUsernameIgnoreCaseAndIdNot("new-name", id)).thenReturn(false)
        `when`(repo.existsByEmailIgnoreCaseAndIdNot("new@example.com", id)).thenReturn(false)

        val updated = controller.partialUpdate(
            id,
            UserController.UpdateUserReq(
                email = "new@example.com",
                username = "new-name",
                bio = "updated bio",
                avatarUrl = "https://img.example/new.png",
                avatarMediaId = UUID.randomUUID()
            )
        )

        assertEquals("new@example.com", updated.email)
        assertEquals("new-name", updated.username)
        assertEquals("updated bio", updated.bio)
        assertEquals("https://img.example/new.png", updated.avatarUrl)
    }

    @Test
    fun `partial update rejects duplicate username`() {
        val id = UUID.randomUUID()
        val user = User(id = id, email = "old@example.com", username = "old")
        `when`(repo.findById(id)).thenReturn(Optional.of(user))
        `when`(repo.existsByUsernameIgnoreCaseAndIdNot("taken", id)).thenReturn(true)

        val ex = assertThrows<ResponseStatusException> {
            controller.partialUpdate(id, UserController.UpdateUserReq(username = "taken"))
        }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
    }

    @Test
    fun `full update replaces user fields and validates uniqueness`() {
        val id = UUID.randomUUID()
        val user = User(id = id, email = "old@example.com", username = "old", bio = "old")
        `when`(repo.findById(id)).thenReturn(Optional.of(user))
        `when`(repo.existsByUsernameIgnoreCaseAndIdNot("mnema", id)).thenReturn(false)
        `when`(repo.existsByEmailIgnoreCaseAndIdNot("user@example.com", id)).thenReturn(false)

        val updated = controller.fullUpdate(
            id,
            UserController.CreateUserReq(
                email = "user@example.com",
                username = "mnema",
                bio = null,
                avatarUrl = null,
                avatarMediaId = null
            )
        )

        assertEquals("user@example.com", updated.email)
        assertEquals("mnema", updated.username)
    }

    @Test
    fun `delete rejects missing user`() {
        val id = UUID.randomUUID()
        `when`(repo.existsById(id)).thenReturn(false)

        val ex = assertThrows<ResponseStatusException> {
            controller.delete(id)
        }

        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }
}
