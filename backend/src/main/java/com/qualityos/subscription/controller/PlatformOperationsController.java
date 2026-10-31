package com.qualityos.subscription.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.job.JobMonitor;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.subscription.dto.PlatformOperationsDtos.AddOperatorRequest;
import com.qualityos.subscription.dto.PlatformOperationsDtos.AuditFilter;
import com.qualityos.subscription.dto.PlatformOperationsDtos.AuditList;
import com.qualityos.subscription.dto.PlatformOperationsDtos.ChangeOperatorRequest;
import com.qualityos.subscription.dto.PlatformOperationsDtos.EmailList;
import com.qualityos.subscription.dto.PlatformOperationsDtos.NotificationList;
import com.qualityos.subscription.dto.PlatformOperationsDtos.Operator;
import com.qualityos.subscription.dto.PlatformOperationsDtos.ReauthenticateRequest;
import com.qualityos.subscription.dto.PlatformOperationsDtos.RetryEmailRequest;
import com.qualityos.subscription.service.PlatformOperations;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
@Tag(name = "Platform operations")
@PreAuthorize(Require.PLATFORM_ADMIN)
@Validated
class PlatformOperationsController {

    private final PlatformOperations operations;

    PlatformOperationsController(PlatformOperations operations) {
        this.operations = operations;
    }

    @PostMapping("/reauthenticate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reauthenticate(@Valid @RequestBody ReauthenticateRequest request) {
        operations.reauthenticate(request.password());
    }

    @GetMapping("/operators")
    @PreAuthorize(Require.PLATFORM_AUDIT_VIEW)
    public ApiResponse<List<Operator>> operators() {
        return ApiResponse.ok(operations.operators());
    }

    @PostMapping("/operators")
    @PreAuthorize(Require.PLATFORM_OPERATOR_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addOperator(@Valid @RequestBody AddOperatorRequest request) {
        operations.add(request);
    }

    @PutMapping("/operators/{userId}")
    @PreAuthorize(Require.PLATFORM_OPERATOR_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeOperator(@PathVariable UUID userId, @Valid @RequestBody ChangeOperatorRequest request) {
        operations.change(userId, request);
    }

    @GetMapping("/audit-logs")
    @PreAuthorize(Require.PLATFORM_AUDIT_VIEW)
    public ApiResponse<AuditList> auditLogs(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(operations.auditLog(new AuditFilter(q, action, entityType, from, to), page, size));
    }

    @GetMapping(value = "/audit-logs/export", produces = "text/csv")
    @PreAuthorize(Require.PLATFORM_AUDIT_VIEW)
    public ResponseEntity<String> exportAuditLogs(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        StringBuilder csv = new StringBuilder(
                "Time,Actor,Action,Target type,Target,Organisation,Before,After,Reason,Correlation ID,IP address\n");
        operations.exportAuditLog(new AuditFilter(q, action, entityType, from, to))
                .forEach(row -> csv.append(Stream.of(row.occurredAt().toString(), row.actor(), row.action(),
                                row.entityType(), row.entityId(), row.organisation(), text(row.before()),
                                text(row.after()), row.reason(), row.correlationId(), row.ipAddress())
                        .map(PlatformOperationsController::cell)
                        .collect(Collectors.joining(","))).append('\n'));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-log.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.toString());
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String cell(String value) {
        String text = value == null ? "" : value;
        String safe = text.matches("^[=+\\-@\\t\\r].*") ? "'" + text : text;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    @GetMapping("/email-logs")
    public ApiResponse<EmailList> emailLogs(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean retryable,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(operations.emails(q, status, retryable, page, size));
    }

    @PostMapping("/organisations/{tenantId}/notifications/{notificationId}/retry-email")
    @PreAuthorize(Require.PLATFORM_CUSTOMER_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retryEmail(
            @PathVariable UUID tenantId, @PathVariable UUID notificationId, @Valid @RequestBody RetryEmailRequest request) {
        operations.retryEmail(tenantId, notificationId, request.reason());
    }

    @GetMapping("/notifications")
    public ApiResponse<NotificationList> notifications(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(operations.notices(type, page, size));
    }

    @GetMapping("/jobs")
    public ApiResponse<List<JobMonitor.JobOverview>> jobs() {
        return ApiResponse.ok(operations.jobs());
    }

    @GetMapping("/jobs/{job}/runs")
    public ApiResponse<List<JobMonitor.JobRun>> jobRuns(@PathVariable String job) {
        return ApiResponse.ok(operations.jobRuns(job));
    }
}
