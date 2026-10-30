package com.qualityos.common.exception;

/** Base type for domain failures. The message is returned to API clients, so keep it safe to display. */
public class QualityOsException extends RuntimeException {

    private final ErrorCode errorCode;

    public QualityOsException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
