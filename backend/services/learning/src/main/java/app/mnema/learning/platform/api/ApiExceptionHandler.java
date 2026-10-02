package app.mnema.learning.platform.api;

import app.mnema.learning.catalog.item.BulkSelectionTooLargeException;
import app.mnema.learning.catalog.item.ExemplarLimitReachedException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.media.MediaStorageUnavailableException;
import app.mnema.learning.media.MediaUploadConflictException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.usage.SpecNotSupportedException;
import app.mnema.learning.usage.UsageLimitReachedException;
import app.mnema.learning.study.session.StudySessionExpiredException;
import app.mnema.learning.study.attempt.PresentationExpiredException;
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

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<Object> handleInvalidRequest(InvalidRequestException exception, HttpServletRequest request) {
        return response(ApiErrorCode.INVALID_REQUEST, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(CapabilityUnavailableException.class)
    ResponseEntity<Object> handleCapabilityUnavailable(CapabilityUnavailableException exception,
                                                        HttpServletRequest request) {
        return response(ApiErrorCode.CAPABILITY_UNAVAILABLE, request, exception);
    }

    @ExceptionHandler(UsageLimitReachedException.class)
    ResponseEntity<Object> handleUsageLimitReached(UsageLimitReachedException exception, HttpServletRequest request) {
        return response(ApiErrorCode.USAGE_LIMIT_REACHED, request, exception);
    }

    @ExceptionHandler(SpecNotSupportedException.class)
    ResponseEntity<Object> handleSpecNotSupported(SpecNotSupportedException exception, HttpServletRequest request) {
        return response(ApiErrorCode.SPEC_NOT_SUPPORTED, request, exception);
    }

    @ExceptionHandler(MediaUploadConflictException.class)
    ResponseEntity<Object> handleMediaUploadConflict(MediaUploadConflictException exception, HttpServletRequest request) {
        return response(ApiErrorCode.MEDIA_UPLOAD_CONFLICT, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(MediaStorageUnavailableException.class)
    ResponseEntity<Object> handleMediaStorageUnavailable(MediaStorageUnavailableException exception, HttpServletRequest request) {
        return response(ApiErrorCode.MEDIA_STORAGE_UNAVAILABLE, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Object> handleResourceNotFound(ResourceNotFoundException exception, HttpServletRequest request) {
        return response(ApiErrorCode.RESOURCE_NOT_FOUND, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(StudySessionExpiredException.class)
    ResponseEntity<Object> handleStudySessionExpired(
            StudySessionExpiredException exception,
            HttpServletRequest request
    ) {
        return response(ApiErrorCode.SESSION_EXPIRED, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(PresentationExpiredException.class)
    ResponseEntity<Object> handlePresentationExpired(
            PresentationExpiredException exception,
            HttpServletRequest request
    ) {
        return response(ApiErrorCode.PRESENTATION_EXPIRED, request.getRequestURI(), new HttpHeaders());
    }

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

    @ExceptionHandler(VersionPreconditionRequiredException.class)
    ResponseEntity<Object> handleVersionPreconditionRequired(
            VersionPreconditionRequiredException exception,
            HttpServletRequest request
    ) {
        return response(ApiErrorCode.PRECONDITION_REQUIRED, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(ResourceLimitExceededException.class)
    ResponseEntity<Object> handleResourceLimitExceeded(
            ResourceLimitExceededException exception,
            HttpServletRequest request
    ) {
        return response(ApiErrorCode.RESOURCE_LIMIT_EXCEEDED, request, exception);
    }

    @ExceptionHandler(ExemplarLimitReachedException.class)
    ResponseEntity<Object> handleExemplarLimit(ExemplarLimitReachedException exception, HttpServletRequest request) {
        return response(ApiErrorCode.EXEMPLAR_LIMIT_REACHED, request, exception);
    }

    @ExceptionHandler(BulkSelectionTooLargeException.class)
    ResponseEntity<Object> handleBulkSelectionTooLarge(BulkSelectionTooLargeException exception,
                                                       HttpServletRequest request) {
        return response(ApiErrorCode.BULK_SELECTION_TOO_LARGE, request, exception);
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
        return response(code, status, requestUri, headers, ProblemExtension.none());
    }

    /** A problem whose exception carries extension members: the one way every such code is written. */
    private ResponseEntity<Object> response(ApiErrorCode code, HttpServletRequest request,
                                            ProblemExtension.ProblemExtensionSource source) {
        return response(code, code.status(), request.getRequestURI(), new HttpHeaders(), source.extension());
    }

    private ResponseEntity<Object> response(ApiErrorCode code, String requestUri, HttpHeaders headers) {
        return response(code, code.status(), requestUri, headers, ProblemExtension.none());
    }

    private ResponseEntity<Object> response(
            ApiErrorCode code,
            HttpStatusCode responseStatus,
            String requestUri,
            HttpHeaders headers,
            ProblemExtension extension
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(responseStatus, code.detail());
        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setInstance(URI.create(requestUri));
        problem.setProperty("code", code.name());
        extension.members().forEach(problem::setProperty);

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        responseHeaders.setCacheControl("private, no-store");
        return new ResponseEntity<>(problem, responseHeaders, responseStatus);
    }
}
