package app.mnema.auth.local

import app.mnema.auth.config.AuthFeaturesProps
import app.mnema.auth.config.LocalAuthProps
import app.mnema.auth.identity.FederatedIdentityRepository
import app.mnema.auth.security.LocalTokenService
import app.mnema.auth.security.RateLimiter
import app.mnema.auth.security.TurnstileService
import app.mnema.auth.user.LoginModerationService
import app.mnema.auth.user.AuthUser
import app.mnema.auth.user.AuthUserRepository
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.web.server.ResponseStatusException

class LocalAuthServiceTest {

    private val userRepository = mock(AuthUserRepository::class.java)
    private val passwordEncoder = mock(PasswordEncoder::class.java)
    private val encoder = mock(JwtEncoder::class.java)
    private val turnstileService = mock(TurnstileService::class.java)
    private val identityRepository = mock(FederatedIdentityRepository::class.java)
    private val loginModerationService = mock(LoginModerationService::class.java)

    private val props = LocalAuthProps(
        accessTokenTtl = Duration.ofHours(8),
        maxFailedAttempts = 3,
        lockDuration = Duration.ofMinutes(15),
        registerLimit = 10,
        registerWindow = Duration.ofHours(1),
        loginLimit = 30,
        loginWindow = Duration.ofMinutes(10)
    )

    @Test
    fun `register normalizes email and returns token`() {
        val service = service(AuthFeaturesProps(federatedEnabled = true, requireEmailVerification = true))
        val request = LocalAuthController.RegisterRequest(
            email = " USER@Example.COM ",
            username = "mnema-user",
            password = "secret-password",
            turnstileToken = "captcha"
        )
        val saved = AuthUser(
            id = UUID.randomUUID(),
            email = "user@example.com",
            emailVerified = false,
            username = "mnema-user",
            passwordHash = "encoded"
        )

        `when`(turnstileService.verify("captcha", "127.0.0.1")).thenReturn(true)
        `when`(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.empty())
        `when`(userRepository.existsByUsernameIgnoreCase("mnema-user")).thenReturn(false)
        `when`(passwordEncoder.encode("secret-password")).thenReturn("encoded")
        `when`(userRepository.save(any(AuthUser::class.java))).thenReturn(saved)
        `when`(encoder.encode(any(JwtEncoderParameters::class.java))).thenReturn(
            Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.parse("2026-04-07T10:15:30Z"))
                .expiresAt(Instant.parse("2026-04-07T11:15:30Z"))
                .claim("scope", "openid profile")
                .build()
        )

        val response = service.register(request, "127.0.0.1")

        assertEquals("token", response.accessToken)
        assertEquals("openid profile email user.read user.write", response.scope)

        val captor = ArgumentCaptor.forClass(AuthUser::class.java)
        verify(userRepository).save(captor.capture())
        assertEquals("user@example.com", captor.value.email)
        assertFalse(captor.value.emailVerified)
        assertEquals("mnema-user", captor.value.username)
        assertEquals("encoded", captor.value.passwordHash)
    }

    @Test
    fun `register rejects oauth only email`() {
        val service = service()
        val existing = AuthUser(email = "user@example.com", passwordHash = null)

        `when`(turnstileService.verify("captcha", "127.0.0.1")).thenReturn(true)
        `when`(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(existing))

        val ex = assertThrows<ResponseStatusException> {
            service.register(
                LocalAuthController.RegisterRequest("user@example.com", "mnema", "secret-password", "captcha"),
                "127.0.0.1"
            )
        }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        assertEquals("oauth_only", ex.reason)
        verify(userRepository, never()).save(any(AuthUser::class.java))
    }

    @Test
    fun `register rejects local email already in use`() {
        val service = service()
        val existing = AuthUser(email = "user@example.com", passwordHash = "encoded")

        `when`(turnstileService.verify("captcha", "127.0.0.1")).thenReturn(true)
        `when`(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(existing))

        val ex = assertThrows<ResponseStatusException> {
            service.register(
                LocalAuthController.RegisterRequest("user@example.com", "mnema", "secret-password", "captcha"),
                "127.0.0.1"
            )
        }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        assertEquals("email_in_use", ex.reason)
    }

    @Test
    fun `register rejects duplicate username`() {
        val service = service()

        `when`(turnstileService.verify("captcha", "127.0.0.1")).thenReturn(true)
        `when`(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.empty())
        `when`(userRepository.existsByUsernameIgnoreCase("mnema")).thenReturn(true)

        val ex = assertThrows<ResponseStatusException> {
            service.register(
                LocalAuthController.RegisterRequest("user@example.com", "mnema", "secret-password", "captcha"),
                "127.0.0.1"
            )
        }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        assertEquals("username_in_use", ex.reason)
    }

    @Test
    fun `register rejects rate limited attempts`() {
        val service = service(
            localAuthProps = props.copy(registerLimit = 0)
        )

        val ex = assertThrows<ResponseStatusException> {
            service.register(
                LocalAuthController.RegisterRequest("user@example.com", "mnema", "secret-password", "captcha"),
                "127.0.0.1"
            )
        }

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.statusCode)
        assertEquals("Too many registration attempts", ex.reason)
    }

    @Test
    fun `register rejects captcha failure and invalid input`() {
        val service = service()
        `when`(turnstileService.verify("captcha", "127.0.0.1")).thenReturn(false)

        val captchaEx = assertThrows<ResponseStatusException> {
            service.register(
                LocalAuthController.RegisterRequest("user@example.com", "mnema", "secret-password", "captcha"),
                "127.0.0.1"
            )
        }
        assertEquals(HttpStatus.BAD_REQUEST, captchaEx.statusCode)
        assertEquals("Captcha verification failed", captchaEx.reason)

        `when`(turnstileService.verify("captcha", "127.0.0.1")).thenReturn(true)
        val invalidEmailEx = assertThrows<ResponseStatusException> {
            service.register(
                LocalAuthController.RegisterRequest("invalid", "mnema", "secret-password", "captcha"),
                "127.0.0.1"
            )
        }
        assertEquals("email_invalid", invalidEmailEx.reason)

        val usernameEx = assertThrows<ResponseStatusException> {
            service.register(
                LocalAuthController.RegisterRequest("user@example.com", "  ", "secret-password", "captcha"),
                "127.0.0.1"
            )
        }
        assertEquals("username_required", usernameEx.reason)

        val longPasswordEx = assertThrows<ResponseStatusException> {
            service.register(
                LocalAuthController.RegisterRequest("user@example.com", "mnema", "a".repeat(129), "captcha"),
                "127.0.0.1"
            )
        }
        assertEquals("password_too_long", longPasswordEx.reason)
    }

    @Test
    fun `register maps repository race to conflict`() {
        val service = service()

        `when`(turnstileService.verify("captcha", "127.0.0.1")).thenReturn(true)
        `when`(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.empty())
        `when`(userRepository.existsByUsernameIgnoreCase("mnema")).thenReturn(false)
        `when`(passwordEncoder.encode("secret-password")).thenReturn("encoded")
        `when`(userRepository.save(any(AuthUser::class.java))).thenThrow(DataIntegrityViolationException("duplicate"))

        val ex = assertThrows<ResponseStatusException> {
            service.register(
                LocalAuthController.RegisterRequest("user@example.com", "mnema", "secret-password", "captcha"),
                "127.0.0.1"
            )
        }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        assertEquals("email_or_username_in_use", ex.reason)
    }

    @Test
    fun `login registers failed attempts on password mismatch`() {
        val service = service()
        val user = AuthUser(
            email = "user@example.com",
            username = "mnema",
            passwordHash = "encoded",
            failedLoginAttempts = 0
        )

        `when`(turnstileService.verify("captcha", "127.0.0.1")).thenReturn(true)
        `when`(userRepository.findByUsernameIgnoreCase("mnema")).thenReturn(Optional.of(user))
        `when`(passwordEncoder.matches("wrong-password", "encoded")).thenReturn(false)
        `when`(userRepository.save(user)).thenReturn(user)

        val ex = assertThrows<ResponseStatusException> {
            service.login(LocalAuthController.LoginRequest("mnema", "wrong-password", "captcha"), "127.0.0.1")
        }

        assertEquals(HttpStatus.UNAUTHORIZED, ex.statusCode)
        assertEquals(1, user.failedLoginAttempts)
        verify(userRepository).save(user)
    }

    @Test
    fun `login rejects oauth account without password`() {
        val service = service()
        val user = AuthUser(email = "user@example.com", username = "mnema", passwordHash = null)

        `when`(turnstileService.verify("captcha", "127.0.0.1")).thenReturn(true)
        `when`(userRepository.findByUsernameIgnoreCase("mnema")).thenReturn(Optional.of(user))

        val ex = assertThrows<ResponseStatusException> {
            service.login(LocalAuthController.LoginRequest("mnema", "secret-password", "captcha"), "127.0.0.1")
        }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        assertEquals("password_not_set", ex.reason)
    }

    @Test
    fun `login supports email login and resets lock state on success`() {
        val service = service()
        val userId = UUID.randomUUID()
        val user = AuthUser(
            id = userId,
            email = "user@example.com",
            username = "mnema",
            passwordHash = "encoded",
            failedLoginAttempts = 2,
            lockedUntil = Instant.parse("2026-04-07T09:00:00Z")
        )

        stubToken()
        `when`(turnstileService.verify("captcha", "127.0.0.1")).thenReturn(true)
        `when`(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user))
        `when`(passwordEncoder.matches("secret-password", "encoded")).thenReturn(true)
        `when`(userRepository.save(user)).thenReturn(user)

        val response = service.login(
            LocalAuthController.LoginRequest(" USER@example.com ", "secret-password", "captcha"),
            "127.0.0.1"
        )

        assertEquals("token", response.accessToken)
        assertEquals(0, user.failedLoginAttempts)
        assertNull(user.lockedUntil)
        verify(userRepository).save(user)
    }

    @Test
    fun `login rejects rate limit malformed email and locked account`() {
        val limited = service(localAuthProps = props.copy(loginLimit = 0))
        val rateLimitEx = assertThrows<ResponseStatusException> {
            limited.login(LocalAuthController.LoginRequest("mnema", "secret-password", "captcha"), "127.0.0.1")
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rateLimitEx.statusCode)

        val invalid = service()
        `when`(turnstileService.verify("captcha", "127.0.0.1")).thenReturn(true)
        val emailEx = assertThrows<ResponseStatusException> {
            invalid.login(LocalAuthController.LoginRequest("user@", "secret-password", "captcha"), "127.0.0.1")
        }
        assertEquals("email_invalid", emailEx.reason)

        val lockedService = service()
        val lockedUser = AuthUser(
            email = "user@example.com",
            username = "mnema",
            passwordHash = "encoded",
            lockedUntil = Instant.now().plusSeconds(60)
        )
        `when`(turnstileService.verify("captcha", "127.0.0.1")).thenReturn(true)
        `when`(userRepository.findByUsernameIgnoreCase("mnema")).thenReturn(Optional.of(lockedUser))

        val lockedEx = assertThrows<ResponseStatusException> {
            lockedService.login(LocalAuthController.LoginRequest("mnema", "secret-password", "captcha"), "127.0.0.1")
        }
        assertEquals(HttpStatus.LOCKED, lockedEx.statusCode)
        assertEquals("Account temporarily locked", lockedEx.reason)
    }

    @Test
    fun `login rejects banned user after successful password check`() {
        val service = service()
        val userId = UUID.randomUUID()
        val user = AuthUser(
            id = userId,
            email = "user@example.com",
            username = "mnema",
            passwordHash = "encoded"
        )

        `when`(turnstileService.verify("captcha", "127.0.0.1")).thenReturn(true)
        `when`(userRepository.findByUsernameIgnoreCase("mnema")).thenReturn(Optional.of(user))
        `when`(passwordEncoder.matches("secret-password", "encoded")).thenReturn(true)
        `when`(userRepository.save(user)).thenReturn(user)
        `when`(loginModerationService.assertLoginAllowed(userId)).thenThrow(
            ResponseStatusException(HttpStatus.LOCKED, "banned")
        )

        val ex = assertThrows<ResponseStatusException> {
            service.login(LocalAuthController.LoginRequest("mnema", "secret-password", "captcha"), "127.0.0.1")
        }

        assertEquals(HttpStatus.LOCKED, ex.statusCode)
        assertEquals("banned", ex.reason)
    }

    @Test
    fun `register checks moderation state before issuing tokens`() {
        val service = service()
        val userId = UUID.randomUUID()
        val request = LocalAuthController.RegisterRequest(
            email = "user@example.com",
            username = "mnema",
            password = "secret-password",
            turnstileToken = "captcha"
        )
        val saved = AuthUser(
            id = userId,
            email = "user@example.com",
            emailVerified = false,
            username = "mnema",
            passwordHash = "encoded"
        )

        `when`(turnstileService.verify("captcha", "127.0.0.1")).thenReturn(true)
        `when`(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.empty())
        `when`(userRepository.existsByUsernameIgnoreCase("mnema")).thenReturn(false)
        `when`(passwordEncoder.encode("secret-password")).thenReturn("encoded")
        `when`(userRepository.save(any(AuthUser::class.java))).thenReturn(saved)
        stubToken()

        service.register(request, "127.0.0.1")

        verify(loginModerationService).assertLoginAllowed(userId)
    }

    @Test
    fun `password status uses email fallback and change password validates input`() {
        val service = service()
        val oauthUser = AuthUser(email = "user@example.com", passwordHash = null)
        val localUser = AuthUser(email = "local@example.com", passwordHash = "encoded")

        `when`(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(oauthUser))
        `when`(userRepository.findByEmailIgnoreCase("local@example.com")).thenReturn(Optional.of(localUser))

        assertFalse(service.passwordStatus(jwt(email = "USER@example.com")))
        assertTrue(service.passwordStatus(jwt(email = "LOCAL@example.com")))

        val blankEx = assertThrows<ResponseStatusException> {
            service.changePassword(jwt(email = "local@example.com"), LocalAuthController.PasswordChangeRequest(currentPassword = "old", newPassword = " "))
        }
        assertEquals("password_required", blankEx.reason)

        val tooShortEx = assertThrows<ResponseStatusException> {
            service.changePassword(jwt(email = "local@example.com"), LocalAuthController.PasswordChangeRequest(currentPassword = "old", newPassword = "short"))
        }
        assertEquals("password_too_short", tooShortEx.reason)

        val tooLongEx = assertThrows<ResponseStatusException> {
            service.changePassword(jwt(email = "local@example.com"), LocalAuthController.PasswordChangeRequest(currentPassword = "old", newPassword = "a".repeat(129)))
        }
        assertEquals("password_too_long", tooLongEx.reason)
    }

    @Test
    fun `change password rejects invalid current password and missing user`() {
        val service = service()
        val userId = UUID.randomUUID()
        val user = AuthUser(id = userId, email = "user@example.com", passwordHash = "encoded")
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        `when`(passwordEncoder.matches("wrong-current", "encoded")).thenReturn(false)

        val invalidCurrentEx = assertThrows<ResponseStatusException> {
            service.changePassword(
                jwt(userId = userId),
                LocalAuthController.PasswordChangeRequest(currentPassword = "wrong-current", newPassword = "new-secret")
            )
        }
        assertEquals(HttpStatus.UNAUTHORIZED, invalidCurrentEx.statusCode)
        assertEquals("Invalid credentials", invalidCurrentEx.reason)

        val missingUserEx = assertThrows<ResponseStatusException> {
            service.changePassword(
                Jwt.withTokenValue("token").header("alg", "none").claim("user_id", "not-a-uuid").build(),
                LocalAuthController.PasswordChangeRequest(currentPassword = "old", newPassword = "new-secret")
            )
        }
        assertEquals(HttpStatus.NOT_FOUND, missingUserEx.statusCode)
        assertEquals("User not found", missingUserEx.reason)
    }

    @Test
    fun `change password requires current password for local account`() {
        val service = service()
        val userId = UUID.randomUUID()
        val user = AuthUser(id = userId, email = "user@example.com", passwordHash = "encoded")
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))

        val ex = assertThrows<ResponseStatusException> {
            service.changePassword(jwt(userId = userId), LocalAuthController.PasswordChangeRequest(newPassword = "new-secret"))
        }

        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        assertEquals("Current password required", ex.reason)
    }

    @Test
    fun `change password supports email fallback for oauth account`() {
        val service = service()
        val user = AuthUser(email = "user@example.com", passwordHash = null)

        `when`(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user))
        `when`(passwordEncoder.encode("new-secret")).thenReturn("encoded-new")

        service.changePassword(jwt(email = "USER@example.com"), LocalAuthController.PasswordChangeRequest(newPassword = "new-secret"))

        assertEquals("encoded-new", user.passwordHash)
        verify(userRepository).save(user)
    }

    @Test
    fun `delete account fails when user cannot be resolved`() {
        val service = service()

        val ex = assertThrows<ResponseStatusException> {
            service.deleteAccount(Jwt.withTokenValue("token").header("alg", "none").claim("email", "missing@example.com").build())
        }

        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        assertEquals("User not found", ex.reason)
    }

    @Test
    fun `delete account removes identities before user`() {
        val service = service()
        val userId = UUID.randomUUID()
        val user = AuthUser(id = userId, email = "user@example.com")
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))

        service.deleteAccount(jwt(userId = userId))

        verify(identityRepository).deleteByUserId(userId)
        verify(userRepository).deleteById(userId)
    }

    private fun service(
        featureProps: AuthFeaturesProps = AuthFeaturesProps(),
        localAuthProps: LocalAuthProps = props
    ): LocalAuthService =
        LocalAuthService(
            userRepository,
            passwordEncoder,
            LocalTokenService(encoder, "https://auth.mnema.app"),
            localAuthProps,
            featureProps,
            RateLimiter(),
            turnstileService,
            identityRepository,
            loginModerationService
        )

    private fun stubToken(tokenValue: String = "token") {
        `when`(encoder.encode(any(JwtEncoderParameters::class.java))).thenReturn(
            Jwt.withTokenValue(tokenValue)
                .header("alg", "none")
                .issuedAt(Instant.parse("2026-04-07T10:15:30Z"))
                .expiresAt(Instant.parse("2026-04-07T11:15:30Z"))
                .claim("scope", "openid profile email user.read user.write")
                .build()
        )
    }

    private fun jwt(userId: UUID? = null, email: String? = null): Jwt {
        val builder = Jwt.withTokenValue("token").header("alg", "none")
        userId?.let { builder.claim("user_id", it.toString()) }
        email?.let { builder.claim("email", it) }
        return builder.build()
    }
}
