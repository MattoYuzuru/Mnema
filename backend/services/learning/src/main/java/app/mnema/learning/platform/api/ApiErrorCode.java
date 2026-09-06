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
