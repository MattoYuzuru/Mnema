package app.mnema.user.security

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException
import java.time.Duration
import java.time.Instant

class InternalTokenAuthFilter(
    private val props: UserInternalAuthProps
) : OncePerRequestFilter() {
    companion object {
        private const val AUTHORIZATION = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
    }

    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        if (hasAuthentication()) {
            filterChain.doFilter(request, response)
            return
        }

        val internalToken = props.internalToken
        if (internalToken.isBlank()) {
            filterChain.doFilter(request, response)
            return
        }

        val auth = request.getHeader(AUTHORIZATION)
        if (auth == null || !auth.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response)
            return
        }

        val token = auth.removePrefix(BEARER_PREFIX).trim()
        if (token != internalToken) {
            filterChain.doFilter(request, response)
            return
        }

        val jwt = Jwt.withTokenValue(token)
            .header("alg", "none")
            .claim("scope", "user.internal")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plus(Duration.ofHours(1)))
            .build()

        val authorities = listOf(SimpleGrantedAuthority("SCOPE_user.internal"))
        val authentication: AbstractAuthenticationToken = JwtAuthenticationToken(jwt, authorities)
        authentication.isAuthenticated = true
        org.springframework.security.core.context.SecurityContextHolder.getContext().authentication = authentication

        filterChain.doFilter(request, response)
    }

    private fun hasAuthentication(): Boolean {
        val context = org.springframework.security.core.context.SecurityContextHolder.getContext()
        val authentication = context?.authentication ?: return false
        if (authentication is AnonymousAuthenticationToken) {
            return false
        }
        return authentication.isAuthenticated
    }
}
