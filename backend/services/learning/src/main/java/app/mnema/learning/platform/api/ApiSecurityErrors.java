package app.mnema.learning.platform.api;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

/** Security filters use the same public problem vocabulary as MVC, without exception details. */
@Component
public final class ApiSecurityErrors {
    private final ObjectMapper mapper;

    public ApiSecurityErrors(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void unauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(request, response, ApiErrorCode.AUTHENTICATION_REQUIRED);
    }

    public void forbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(request, response, ApiErrorCode.ACCESS_DENIED);
    }

    public void unavailable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.RETRY_AFTER, "1");
        write(request, response, ApiErrorCode.IDENTITY_UNAVAILABLE);
    }

    /** A filter's {@code 429 RATE_LIMITED}: the same problem and {@code Retry-After} as the MVC handler writes, with the member {@code retryAfter}. */
    public void rateLimited(HttpServletRequest request, HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        write(request, response, ApiErrorCode.RATE_LIMITED, retryAfterSeconds);
    }

    private void write(HttpServletRequest request, HttpServletResponse response, ApiErrorCode code) throws IOException {
        write(request, response, code, null);
    }

    private void write(HttpServletRequest request, HttpServletResponse response, ApiErrorCode code, Long retryAfter) throws IOException {
        var problem = ProblemDetail.forStatusAndDetail(code.status(), code.detail());
        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());
        if (retryAfter != null) problem.setProperty("retryAfter", retryAfter);
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
