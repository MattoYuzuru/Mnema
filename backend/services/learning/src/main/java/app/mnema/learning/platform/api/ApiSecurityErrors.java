package app.mnema.learning.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private void write(HttpServletRequest request, HttpServletResponse response, ApiErrorCode code) throws IOException {
        var problem = ProblemDetail.forStatusAndDetail(code.status(), code.detail());
        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
