package app.mnema.auth.local

import app.mnema.auth.config.LocalAuthProps
import app.mnema.auth.security.LocalTokenResponse
import app.mnema.auth.security.LocalTokenService
import app.mnema.auth.security.RateLimiter
import app.mnema.auth.security.TurnstileService
import app.mnema.auth.identity.FederatedIdentityRepository
import app.mnema.auth.user.AuthUser
import app.mnema.auth.user.AuthUserRepository
import java.time.Instant
import java.util.Locale
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class LocalAuthService(
    private val userRepository: AuthUserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokenService: LocalTokenService,
    private val props: LocalAuthProps,
    private val rateLimiter: RateLimiter,
    private val turnstileService: TurnstileService,
    private val identityRepository: FederatedIdentityRepository
) {
    private val allowedUsername = Regex("^[A-Za-z0-9._-]{3,50}$")
    private val scopes = setOf("openid", "profile", "email", "user.read", "user.write")

    @Transactional
    fun register(req: LocalAuthController.RegisterRequest, ip: String?): LocalTokenResponse {
        val now = Instant.now()

        if (!rateLimiter.allow("register:${ip.orEmpty()}", props.registerLimit, props.registerWindow, now)) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many registration attempts")
        }
        if (!turnstileService.verify(req.turnstileToken, ip)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Captcha verification failed")
        }

        val email = normalizeEmail(req.email)
        val username = req.username.trim()
        val password = req.password

        if (!allowedUsername.matches(username)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Username must be 3-50 chars: letters, digits, . _ -")
        }
        if (password.length < 8) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Password too short")
        }
        val existingByEmail = userRepository.findByEmailIgnoreCase(email).orElse(null)
        if (existingByEmail != null) {
            if (existingByEmail.passwordHash.isNullOrBlank()) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "oauth_only")
            }
            throw ResponseStatusException(HttpStatus.CONFLICT, "email_in_use")
        }
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "username_in_use")
        }

        val user = AuthUser(
            email = email,
            emailVerified = false,
            username = username,
            passwordHash = passwordEncoder.encode(password),
            createdAt = now,
            lastLoginAt = now
        )

        val saved = try {
            userRepository.save(user)
        } catch (ex: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "email_or_username_in_use")
        }
        return tokenService.generateTokens(saved, scopes, props.accessTokenTtl, now)
    }

    @Transactional
    fun login(req: LocalAuthController.LoginRequest, ip: String?): LocalTokenResponse {
        val now = Instant.now()

        if (!rateLimiter.allow("login:${ip.orEmpty()}", props.loginLimit, props.loginWindow, now)) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts")
        }
        if (!turnstileService.verify(req.turnstileToken, ip)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Captcha verification failed")
        }

        val loginValue = req.login.trim()
        val user = findByLogin(loginValue)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")

        if (user.isLocked(now)) {
            throw ResponseStatusException(HttpStatus.LOCKED, "Account temporarily locked")
        }

        val hash = user.passwordHash
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "password_not_set")
        if (!passwordEncoder.matches(req.password, hash)) {
            user.registerFailedLogin(now, props.maxFailedAttempts, props.lockDuration)
            userRepository.save(user)
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
        }

        user.resetFailedLogins()
        user.touchLogin(now)
        userRepository.save(user)
        return tokenService.generateTokens(user, scopes, props.accessTokenTtl, now)
    }

    @Transactional(readOnly = true)
    fun passwordStatus(jwt: Jwt): Boolean {
        val user = resolveUser(jwt)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        return !user.passwordHash.isNullOrBlank()
    }

    @Transactional
    fun changePassword(jwt: Jwt, req: LocalAuthController.PasswordChangeRequest) {
        val user = resolveUser(jwt)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        val newPassword = req.newPassword
        if (newPassword.length < 8) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Password too short")
        }

        if (!user.passwordHash.isNullOrBlank()) {
            val current = req.currentPassword
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password required")
            if (!passwordEncoder.matches(current, user.passwordHash)) {
                throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials")
            }
        }

        user.setPassword(passwordEncoder.encode(newPassword))
        user.resetFailedLogins()
        userRepository.save(user)
    }

    @Transactional
    fun deleteAccount(jwt: Jwt) {
        val user = resolveUser(jwt)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        val userId = user.id
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        identityRepository.deleteByUserId(userId)
        userRepository.deleteById(userId)
    }

    private fun findByLogin(login: String): AuthUser? {
        val normalized = login.trim()
        return if (normalized.contains("@")) {
            userRepository.findByEmailIgnoreCase(normalizeEmail(normalized)).orElse(null)
        } else {
            userRepository.findByUsernameIgnoreCase(normalized).orElseGet {
                userRepository.findByEmailIgnoreCase(normalizeEmail(normalized)).orElse(null)
            }
        }
    }

    private fun normalizeEmail(email: String): String =
        email.trim().lowercase(Locale.ROOT)

    private fun resolveUser(jwt: Jwt): AuthUser? {
        val userId = jwt.getClaimAsString("user_id")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (userId != null) {
            return userRepository.findById(userId).orElse(null)
        }
        val email = jwt.getClaimAsString("email")?.trim()
        if (!email.isNullOrBlank()) {
            return userRepository.findByEmailIgnoreCase(normalizeEmail(email)).orElse(null)
        }
        return null
    }
}
