package com.qualityos.common.exception;

import org.springframework.http.HttpStatus;

/** Stable error codes exposed to API clients. Add codes freely; never repurpose an existing one. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NO_ACTIVE_MEMBERSHIP(HttpStatus.FORBIDDEN),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN),
    REAUTHENTICATION_REQUIRED(HttpStatus.FORBIDDEN),
    REAUTHENTICATION_FAILED(HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    CONFLICT(HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_CONTENT),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS),
    REQUEST_FAILED(HttpStatus.BAD_REQUEST),
    PAYMENT_GATEWAY_UNAVAILABLE(HttpStatus.BAD_GATEWAY),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
