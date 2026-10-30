package com.qualityos.common.exception;

import com.qualityos.common.response.ApiError;
import com.qualityos.common.response.ApiError.FieldViolation;
import com.qualityos.common.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates every failure into the {@link ApiResponse} envelope. Spring MVC's own exceptions arrive
 * through {@link #handleExceptionInternal}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String VALIDATION_MESSAGE = "Request validation failed";

    @ExceptionHandler(QualityOsException.class)
    ResponseEntity<ApiResponse<Void>> handleDomainException(QualityOsException ex) {
        return respond(ex.errorCode(), new ApiError(ex.errorCode().name(), ex.getMessage()));
    }

    /** Thrown by method security after the filter chain, so it needs handling here too. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        ErrorCode code = ErrorCode.FORBIDDEN;
        return respond(code, new ApiError(code.name(), "You do not have permission to do this."));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return validationFailure(violations);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return validationFailure(List.of(new FieldViolation(ex.getName(), "Invalid value")));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiResponse<Void>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        ErrorCode code = ErrorCode.CONCURRENT_MODIFICATION;
        return respond(code, new ApiError(code.name(), "The record was changed by someone else. Reload and retry."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        ErrorCode code = ErrorCode.CONFLICT;
        return respond(code, new ApiError(code.name(), "The request conflicts with existing data."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return respond(code, new ApiError(code.name(), "An unexpected error occurred."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex, validationBody(toViolations(ex.getBindingResult())), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldViolation(
                                result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                .toList();
        return handleExceptionInternal(ex, validationBody(violations), headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ErrorCode code = ErrorCode.MALFORMED_REQUEST;
        ApiResponse<Void> body = ApiResponse.failure(new ApiError(code.name(), "The request body could not be read."));
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Object envelope = body instanceof ApiResponse<?> ? body : ApiResponse.failure(forStatus(status, ex));
        return super.handleExceptionInternal(ex, envelope, headers, status, request);
    }

    private static ApiError forStatus(HttpStatusCode status, Exception ex) {
        ErrorCode code = switch (status.value()) {
            case 400 -> ErrorCode.REQUEST_FAILED;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.REQUEST_FAILED;
        };
        if (status.is5xxServerError()) {
            log.error("Request failed", ex);
            return new ApiError(code.name(), "An unexpected error occurred.");
        }
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return new ApiError(code.name(), resolved != null ? resolved.getReasonPhrase() : "Request failed");
    }

    private static List<FieldViolation> toViolations(BindingResult result) {
        return Stream.concat(
                        result.getFieldErrors().stream()
                                .map(e -> new FieldViolation(e.getField(), e.getDefaultMessage())),
                        result.getGlobalErrors().stream()
                                .map(e -> new FieldViolation(e.getObjectName(), e.getDefaultMessage())))
                .toList();
    }

    private static ResponseEntity<ApiResponse<Void>> validationFailure(List<FieldViolation> violations) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).body(validationBody(violations));
    }

    private static ApiResponse<Void> validationBody(List<FieldViolation> violations) {
        return ApiResponse.failure(new ApiError(ErrorCode.VALIDATION_FAILED.name(), VALIDATION_MESSAGE, violations));
    }

    private static ResponseEntity<ApiResponse<Void>> respond(ErrorCode code, ApiError error) {
        return ResponseEntity.status(code.status()).body(ApiResponse.failure(error));
    }
}
