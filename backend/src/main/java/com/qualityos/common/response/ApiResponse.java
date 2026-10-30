package com.qualityos.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.qualityos.common.config.CorrelationIdFilter;
import java.time.Instant;
import org.slf4j.MDC;

/**
 * Envelope returned by every {@code /api/v1} endpoint, for success and failure alike.
 *
 * @param <T> payload type; absent on failure
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data,
        ApiError error,
        Instant timestamp,
        String correlationId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, Instant.now(), currentCorrelationId());
    }

    public static ApiResponse<Void> failure(ApiError error) {
        return new ApiResponse<>(false, null, error, Instant.now(), currentCorrelationId());
    }

    private static String currentCorrelationId() {
        return MDC.get(CorrelationIdFilter.MDC_KEY);
    }
}
