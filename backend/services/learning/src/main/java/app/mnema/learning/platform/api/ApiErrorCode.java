package app.mnema.learning.platform.api;

import org.springframework.http.HttpStatus;

import java.net.URI;

enum ApiErrorCode {
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication required", "Valid authentication is required."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied", "The operation is not permitted."),
    IDENTITY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Identity unavailable", "Authentication is temporarily unavailable."),
    IDEMPOTENCY_CONFLICT(
            HttpStatus.CONFLICT,
            "Idempotency conflict",
            "The command identifier was already used for a different command."
    ),
    CAPABILITY_UNAVAILABLE(
            HttpStatus.CONFLICT,
            "Capability unavailable",
            "The requested learning capability is not available."
    ),
    USAGE_LIMIT_REACHED(
            HttpStatus.CONFLICT,
            "Usage limit reached",
            "The remaining AI budget does not cover this operation."
    ),
    USAGE_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Usage unavailable",
            "The usage budget is busy; retry the request."
    ),
    RATE_LIMITED(
            HttpStatus.TOO_MANY_REQUESTS,
            "Rate limited",
            "Too many requests; retry after the time in Retry-After."
    ),
    SPEECH_CONSENT_REQUIRED(
            HttpStatus.CONFLICT,
            "Speech consent required",
            "Voice input needs the account's consent for the processing region."
    ),
    SPEECH_CONSENT_OUTDATED(
            HttpStatus.CONFLICT,
            "Speech consent outdated",
            "The consent names another version or processing region than the current one."
    ),
    PAYLOAD_TOO_LARGE(
            HttpStatus.CONTENT_TOO_LARGE,
            "Payload too large",
            "The request body exceeds the allowed size."
    ),
    SPEC_NOT_SUPPORTED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "Spec not supported",
            "The generation spec is valid but not supported yet."
    ),
    GENERATION_STATE_CONFLICT(
            HttpStatus.CONFLICT,
            "Generation state conflict",
            "The operation is not allowed in the current state."
    ),
    EDIT_IN_PROGRESS(
            HttpStatus.CONFLICT,
            "Edit in progress",
            "Another edit of this artifact is still running."
    ),
    SOURCE_UNAVAILABLE(
            HttpStatus.CONFLICT,
            "Source unavailable",
            "A pinned source is no longer available."
    ),
    ASSESSMENT_STATE_CONFLICT(
            HttpStatus.CONFLICT,
            "Assessment state conflict",
            "The operation is not allowed in the current assessment state."
    ),
    DISPUTE_NOT_ALLOWED(
            HttpStatus.CONFLICT,
            "Dispute not allowed",
            "This grade can no longer be disputed."
    ),
    MEDIA_UPLOAD_CONFLICT(HttpStatus.CONFLICT, "Media upload conflict", "The upload state or parts do not match the command."),
    MEDIA_STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Media storage unavailable", "Media storage is temporarily unavailable."),
    VERSION_CONFLICT(
            HttpStatus.PRECONDITION_FAILED,
            "Version conflict",
            "The resource changed after the supplied version was read."
    ),
    PRECONDITION_REQUIRED(
            HttpStatus.PRECONDITION_REQUIRED,
            "Precondition required",
            "A current resource version is required for this command."
    ),
    RESOURCE_LIMIT_EXCEEDED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "Resource limit exceeded",
            "The account or resource limit would be exceeded."
    ),
    EXEMPLAR_LIMIT_REACHED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "Exemplar limit reached",
            "The deck already has the maximum number of exemplars."
    ),
    BULK_SELECTION_TOO_LARGE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "Bulk selection too large",
            "The selection exceeds the bulk operation limit."
    ),
    INVALID_REQUEST(
            HttpStatus.BAD_REQUEST,
            "Invalid request",
            "The request is invalid or cannot be read."
    ),
    RESOURCE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "Resource not found",
            "The requested resource does not exist."
    ),
    SESSION_EXPIRED(
            HttpStatus.GONE,
            "Study session expired",
            "The Study session is no longer available."
    ),
    PRESENTATION_EXPIRED(
            HttpStatus.GONE,
            "Study presentation expired",
            "The Study presentation is no longer available."
    ),
    METHOD_NOT_ALLOWED(
            HttpStatus.METHOD_NOT_ALLOWED,
            "Method not allowed",
            "The request method is not supported for this resource."
    ),
    INTERNAL_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal error",
            "The request could not be completed."
    );

    private final HttpStatus status;
    private final String title;
    private final String detail;

    ApiErrorCode(HttpStatus status, String title, String detail) {
        this.status = status;
        this.title = title;
        this.detail = detail;
    }

    HttpStatus status() {
        return status;
    }

    String title() {
        return title;
    }

    String detail() {
        return detail;
    }

    URI type() {
        return URI.create("urn:mnema:problem:" + name().toLowerCase().replace('_', '-'));
    }
}
