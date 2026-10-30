package com.qualityos.common.exception;

public class ConflictException extends QualityOsException {

    public ConflictException(String message) {
        super(ErrorCode.CONFLICT, message);
    }
}
