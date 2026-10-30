package com.qualityos.common.exception;

/**
 * Also used for records owned by another tenant, so that responses never reveal that such a record
 * exists.
 */
public class ResourceNotFoundException extends QualityOsException {

    public ResourceNotFoundException(String resource, Object id) {
        super(ErrorCode.RESOURCE_NOT_FOUND, "%s %s was not found".formatted(resource, id));
    }
}
