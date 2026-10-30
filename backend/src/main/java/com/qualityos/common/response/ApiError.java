package com.qualityos.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * @param code stable machine-readable code, see {@link com.qualityos.common.exception.ErrorCode}
 * @param message human-readable summary, safe to display
 * @param violations per-field problems; present only for validation failures
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String code, String message, List<FieldViolation> violations) {

    public ApiError(String code, String message) {
        this(code, message, List.of());
    }

    public record FieldViolation(String field, String message) {
    }
}
