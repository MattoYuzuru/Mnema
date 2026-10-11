package app.mnema.learning.platform.api;

import app.mnema.learning.admin.support.SupportConflictException;
import app.mnema.learning.admin.support.SupportUnavailableException;
import app.mnema.learning.catalog.item.BulkSelectionTooLargeException;
import app.mnema.learning.catalog.item.ExemplarLimitReachedException;
import app.mnema.learning.generation.EditInProgressException;
import app.mnema.learning.generation.GenerationStateConflictException;
import app.mnema.learning.generation.SourceUnavailableException;
import app.mnema.learning.generation.StaleArtifactsException;
import app.mnema.learning.library.DeckInviteOnlyException;
import app.mnema.learning.library.PublicationRequiredException;
import app.mnema.learning.library.PublicationRequirementsException;
import app.mnema.learning.library.PublicReadBusyException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.media.MediaStorageUnavailableException;
import app.mnema.learning.media.MediaUploadConflictException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.promo.PromoRejectedException;
import app.mnema.learning.speech.PayloadTooLargeException;
import app.mnema.learning.speech.SpeechConsentOutdatedException;
import app.mnema.learning.speech.SpeechConsentRequiredException;
import app.mnema.learning.usage.SpecNotSupportedException;
import app.mnema.learning.usage.UsageContentionException;
import app.mnema.learning.usage.UsageLimitReachedException;
import app.mnema.learning.study.session.StudySessionExpiredException;
import app.mnema.learning.study.attempt.AssessmentStateConflictException;
import app.mnema.learning.study.attempt.DisputeNotAllowedException;
import app.mnema.learning.study.attempt.PresentationExpiredException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
        return response(ApiErrorCode.INVALID_REQUEST, request, exception);
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

    @ExceptionHandler(UsageContentionException.class)
    ResponseEntity<Object> handleUsageContention(UsageContentionException exception, HttpServletRequest request) {
        return response(ApiErrorCode.USAGE_UNAVAILABLE, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<Object> handleRateLimited(RateLimitedException exception, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()));
        return response(ApiErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS, request.getRequestURI(), headers, exception.extension());
    }

    @ExceptionHandler(BillingPlanBelowCurrentException.class)
    ResponseEntity<Object> handleBillingPlanBelowCurrent(BillingPlanBelowCurrentException exception, HttpServletRequest request) {
        return response(ApiErrorCode.BILLING_PLAN_BELOW_CURRENT, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(PaymentProviderUnavailableException.class)
    ResponseEntity<Object> handlePaymentProviderUnavailable(PaymentProviderUnavailableException exception, HttpServletRequest request) {
        return response(ApiErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(PromoRejectedException.class)
    ResponseEntity<Object> handlePromoRejected(PromoRejectedException exception, HttpServletRequest request) {
        ApiErrorCode code = switch (exception.reason()) {
            case INVALID -> ApiErrorCode.PROMO_INVALID;
            case EXHAUSTED -> ApiErrorCode.PROMO_EXHAUSTED;
            case ALREADY_USED -> ApiErrorCode.PROMO_ALREADY_USED;
            case NOT_ELIGIBLE -> ApiErrorCode.PROMO_NOT_ELIGIBLE;
            case VELOCITY -> ApiErrorCode.PROMO_VELOCITY;
        };
        return response(code, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(AccessForbiddenException.class)
    ResponseEntity<Object> handleAccessForbidden(AccessForbiddenException exception, HttpServletRequest request) {
        return response(ApiErrorCode.ACCESS_DENIED, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(DeckInviteOnlyException.class)
    ResponseEntity<Object> handleDeckInviteOnly(DeckInviteOnlyException exception, HttpServletRequest request) {
        return response(ApiErrorCode.DECK_INVITE_ONLY, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(PublicationRequiredException.class)
    ResponseEntity<Object> handlePublicationRequired(PublicationRequiredException exception, HttpServletRequest request) {
        return response(ApiErrorCode.PUBLICATION_REQUIRED, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(PublicationRequirementsException.class)
    ResponseEntity<Object> handlePublicationRequirements(PublicationRequirementsException exception, HttpServletRequest request) {
        return response(ApiErrorCode.PUBLICATION_REQUIREMENTS, request, exception);
    }

    @ExceptionHandler(PublicReadBusyException.class)
    ResponseEntity<Object> handlePublicReadBusy(PublicReadBusyException exception, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()));
        return response(ApiErrorCode.PUBLIC_READ_BUSY, ApiErrorCode.PUBLIC_READ_BUSY.status(), request.getRequestURI(), headers, exception.extension());
    }

    @ExceptionHandler(IdentityUnavailableException.class)
    ResponseEntity<Object> handleIdentityUnavailable(IdentityUnavailableException exception, HttpServletRequest request) {
        return response(ApiErrorCode.IDENTITY_UNAVAILABLE, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(SupportUnavailableException.class)
    ResponseEntity<Object> handleSupportUnavailable(SupportUnavailableException exception, HttpServletRequest request) {
        return response(ApiErrorCode.SUPPORT_UNAVAILABLE, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(SupportConflictException.class)
    ResponseEntity<Object> handleSupportConflict(SupportConflictException exception, HttpServletRequest request) {
        return response(ApiErrorCode.SUPPORT_CONFLICT, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(SpeechConsentRequiredException.class)
    ResponseEntity<Object> handleSpeechConsentRequired(SpeechConsentRequiredException exception, HttpServletRequest request) {
        return response(ApiErrorCode.SPEECH_CONSENT_REQUIRED, request, exception);
    }

    @ExceptionHandler(SpeechConsentOutdatedException.class)
    ResponseEntity<Object> handleSpeechConsentOutdated(SpeechConsentOutdatedException exception, HttpServletRequest request) {
        return response(ApiErrorCode.SPEECH_CONSENT_OUTDATED, request, exception);
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ResponseEntity<Object> handlePayloadTooLarge(PayloadTooLargeException exception, HttpServletRequest request) {
        return response(ApiErrorCode.PAYLOAD_TOO_LARGE, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(SpecNotSupportedException.class)
    ResponseEntity<Object> handleSpecNotSupported(SpecNotSupportedException exception, HttpServletRequest request) {
        return response(ApiErrorCode.SPEC_NOT_SUPPORTED, request, exception);
    }

    @ExceptionHandler(GenerationStateConflictException.class)
    ResponseEntity<Object> handleGenerationStateConflict(GenerationStateConflictException exception,
                                                         HttpServletRequest request) {
        return response(ApiErrorCode.GENERATION_STATE_CONFLICT, request, exception);
    }

    @ExceptionHandler(EditInProgressException.class)
    ResponseEntity<Object> handleEditInProgress(EditInProgressException exception, HttpServletRequest request) {
        return response(ApiErrorCode.EDIT_IN_PROGRESS, request, exception);
    }

    @ExceptionHandler(SourceUnavailableException.class)
    ResponseEntity<Object> handleSourceUnavailable(SourceUnavailableException exception, HttpServletRequest request) {
        return response(ApiErrorCode.SOURCE_UNAVAILABLE, request, exception);
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

    @ExceptionHandler(AssessmentStateConflictException.class)
    ResponseEntity<Object> handleAssessmentStateConflict(AssessmentStateConflictException exception,
                                                         HttpServletRequest request) {
        return response(ApiErrorCode.ASSESSMENT_STATE_CONFLICT, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(DisputeNotAllowedException.class)
    ResponseEntity<Object> handleDisputeNotAllowed(DisputeNotAllowedException exception, HttpServletRequest request) {
        return response(ApiErrorCode.DISPUTE_NOT_ALLOWED, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<Object> handleIdempotencyConflict(
            IdempotencyConflictException exception,
            HttpServletRequest request
    ) {
        return response(ApiErrorCode.IDEMPOTENCY_CONFLICT, request.getRequestURI(), new HttpHeaders());
    }

    @ExceptionHandler(StaleArtifactsException.class)
    ResponseEntity<Object> handleStaleArtifacts(StaleArtifactsException exception, HttpServletRequest request) {
        return response(ApiErrorCode.VERSION_CONFLICT, request, exception);
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
                loggablePath(request)
        );
        return response(ApiErrorCode.INTERNAL_ERROR, request.getRequestURI(), new HttpHeaders());
    }

    private static final java.util.regex.Pattern PUBLIC_DECK_PATH = java.util.regex.Pattern.compile("^(.*/public/decks/)[^/]+");

    /**
     * The request path for a log line. The public code of a deck is a credential of its link ({@code LINK} and {@code INVITE} decks are reachable by
     * it alone), so it never reaches a log: the route template the request was mapped to is logged (for example {@code /public/decks/{code}/items}),
     * and when no mapping was made, the segment after {@code /public/decks/} is redacted.
     */
    static String loggablePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || !uri.contains("/public/decks/")) return uri;
        Object template = request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (template instanceof String pattern && !pattern.isBlank()) return request.getContextPath() + pattern;
        return PUBLIC_DECK_PATH.matcher(uri).replaceFirst("$1{code}");
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
