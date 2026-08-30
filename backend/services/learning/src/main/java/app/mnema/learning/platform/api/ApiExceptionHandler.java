package app.mnema.learning.platform.api;

import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<Object> handleIdempotencyConflict(
            IdempotencyConflictException exception,
            HttpServletRequest request
    ) {
        return response(ApiErrorCode.IDEMPOTENCY_CONFLICT, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(VersionConflictException.class)
    ResponseEntity<Object> handleVersionConflict(VersionConflictException exception, HttpServletRequest request) {
        return response(ApiErrorCode.VERSION_CONFLICT, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error(
                "Unhandled API exception exception_type={} request_path={}",
                exception.getClass().getName(),
                request.getRequestURI()
        );
        return response(ApiErrorCode.INTERNAL_ERROR, request.getRequestURI(), new HttpHeaders());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        ApiErrorCode code;
        if (status.value() == 404) {
            code = ApiErrorCode.RESOURCE_NOT_FOUND;
        } else if (status.value() == 405) {
            code = ApiErrorCode.METHOD_NOT_ALLOWED;
        } else {
            code = status.is4xxClientError() ? ApiErrorCode.INVALID_REQUEST : ApiErrorCode.INTERNAL_ERROR;
        }
        String requestUri = request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI()
                : "/";
        return response(code, requestUri, headers);
    }

    private ResponseEntity<Object> response(ApiErrorCode code, String requestUri, HttpHeaders headers) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), code.detail());
        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setInstance(URI.create(requestUri));
        problem.setProperty("code", code.name());

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(problem, responseHeaders, code.status());
    }
}
