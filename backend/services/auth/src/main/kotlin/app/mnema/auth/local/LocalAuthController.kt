package app.mnema.auth.local

import app.mnema.auth.security.TurnstileService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth")
class LocalAuthController(
    private val authService: LocalAuthService,
    private val turnstileService: TurnstileService
) {
    data class RegisterRequest(
        @field:NotBlank val email: String,
        @field:NotBlank val username: String,
        @field:Size(min = 8, max = 128) val password: String,
        val turnstileToken: String? = null
    )

    data class LoginRequest(
        @field:NotBlank val login: String,
        @field:NotBlank val password: String,
        val turnstileToken: String? = null
    )

    data class TokenResponse(
        val access_token: String,
        val expires_in: Long,
        val token_type: String,
        val scope: String
    )

    data class TurnstileConfigResponse(
        val enabled: Boolean,
        val siteKey: String?
    )

    data class PasswordStatusResponse(
        val hasPassword: Boolean
    )

    data class PasswordChangeRequest(
        val currentPassword: String? = null,
        @field:Size(min = 8, max = 128) val newPassword: String
    )

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@RequestBody req: RegisterRequest, request: HttpServletRequest): TokenResponse {
        val ip = clientIp(request)
        val result = authService.register(req, ip)
        return TokenResponse(
            access_token = result.accessToken,
            expires_in = result.expiresIn,
            token_type = result.tokenType,
            scope = result.scope
        )
    }

    @PostMapping("/login")
    fun login(@RequestBody req: LoginRequest, request: HttpServletRequest): TokenResponse {
        val ip = clientIp(request)
        val result = authService.login(req, ip)
        return TokenResponse(
            access_token = result.accessToken,
            expires_in = result.expiresIn,
            token_type = result.tokenType,
            scope = result.scope
        )
    }

    @GetMapping("/turnstile/config")
    fun turnstileConfig(): TurnstileConfigResponse =
        TurnstileConfigResponse(
            enabled = turnstileService.enabled(),
            siteKey = turnstileService.siteKey().ifBlank { null }
        )

    @GetMapping("/password/status")
    fun passwordStatus(@AuthenticationPrincipal jwt: Jwt): PasswordStatusResponse =
        PasswordStatusResponse(
            hasPassword = authService.passwordStatus(jwt)
        )

    @PostMapping("/password")
    fun changePassword(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody req: PasswordChangeRequest
    ): PasswordStatusResponse {
        authService.changePassword(jwt, req)
        return PasswordStatusResponse(hasPassword = true)
    }

    @DeleteMapping("/account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteAccount(@AuthenticationPrincipal jwt: Jwt) {
        authService.deleteAccount(jwt)
    }

    private fun clientIp(request: HttpServletRequest): String? {
        val forwarded = request.getHeader("X-Forwarded-For")
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
        return forwarded ?: request.remoteAddr
    }
}
