package com.qualityos.auditmanagement.controller;

import com.qualityos.auditmanagement.dto.AuditDtos.AnswerItemRequest;
import com.qualityos.auditmanagement.dto.AuditDtos.AuditDetail;
import com.qualityos.auditmanagement.dto.AuditDtos.AuditReport;
import com.qualityos.auditmanagement.dto.AuditDtos.AuditSummary;
import com.qualityos.auditmanagement.dto.AuditDtos.CompleteAuditRequest;
import com.qualityos.auditmanagement.dto.AuditDtos.CreateAuditRequest;
import com.qualityos.auditmanagement.dto.AuditDtos.FindingRequest;
import com.qualityos.auditmanagement.dto.AuditDtos.NoteRequest;
import com.qualityos.auditmanagement.dto.AuditDtos.ReplanAuditRequest;
import com.qualityos.auditmanagement.entity.QualityAudit;
import com.qualityos.auditmanagement.service.AuditPdfRenderer;
import com.qualityos.auditmanagement.service.QualityAuditService;
import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/api/v1/audits")
@Tag(name = "Audits")
@PreAuthorize(Require.AUDIT_VIEW)
public class QualityAuditController {

    private static final Set<String> SORTABLE = Set.of("auditNumber", "plannedOn", "status", "title");

    private final QualityAuditService audits;
    private final AuditPdfRenderer pdf;

    QualityAuditController(QualityAuditService audits, AuditPdfRenderer pdf) {
        this.audits = audits;
        this.pdf = pdf;
    }

    @GetMapping
    public ApiResponse<PageResponse<AuditSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) QualityAudit.Status status,
            @RequestParam(required = false) QualityAudit.Type type,
            @RequestParam(required = false) UUID facilityId) {
        return ApiResponse.ok(audits.list(q, status, type, facilityId, Pages.of(page, size, sort, SORTABLE, "plannedOn")));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Require.AUDIT_MANAGE)
    public ApiResponse<AuditDetail> create(@Valid @RequestBody CreateAuditRequest request) {
        return ApiResponse.ok(audits.create(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<AuditDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(audits.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Require.AUDIT_MANAGE)
    public ApiResponse<AuditDetail> replan(@PathVariable UUID id, @Valid @RequestBody ReplanAuditRequest request) {
        return ApiResponse.ok(audits.replan(id, request));
    }

    @PostMapping("/{id}/start")
    @PreAuthorize(Require.AUDIT_MANAGE)
    public ApiResponse<AuditDetail> start(@PathVariable UUID id) {
        return ApiResponse.ok(audits.start(id));
    }

    @PutMapping("/{id}/items/{itemId}")
    @PreAuthorize(Require.AUDIT_MANAGE)
    public ApiResponse<AuditDetail> answer(
            @PathVariable UUID id, @PathVariable UUID itemId, @Valid @RequestBody AnswerItemRequest request) {
        return ApiResponse.ok(audits.answer(id, itemId, request));
    }

    @PostMapping("/{id}/findings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Require.AUDIT_MANAGE)
    public ApiResponse<AuditDetail> addFinding(@PathVariable UUID id, @Valid @RequestBody FindingRequest request) {
        return ApiResponse.ok(audits.addFinding(id, request));
    }

    @PostMapping("/{id}/findings/{findingId}/ncr")
    @PreAuthorize(Require.AUDIT_MANAGE)
    public ApiResponse<AuditDetail> raiseNcr(@PathVariable UUID id, @PathVariable UUID findingId) {
        return ApiResponse.ok(audits.raiseNcr(id, findingId));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize(Require.AUDIT_MANAGE)
    public ApiResponse<AuditDetail> complete(@PathVariable UUID id, @Valid @RequestBody CompleteAuditRequest request) {
        return ApiResponse.ok(audits.complete(id, request.summary()));
    }

    @PostMapping("/{id}/request-closure")
    @PreAuthorize(Require.AUDIT_MANAGE)
    public ApiResponse<AuditDetail> requestClosure(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return ApiResponse.ok(audits.requestClosure(id, request.note()));
    }

    @PostMapping("/{id}/send-back")
    @PreAuthorize(Require.AUDIT_CLOSE)
    public ApiResponse<AuditDetail> sendBack(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return ApiResponse.ok(audits.sendBack(id, request.note()));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize(Require.AUDIT_CLOSE)
    public ApiResponse<AuditDetail> close(@PathVariable UUID id) {
        return ApiResponse.ok(audits.close(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(Require.AUDIT_MANAGE)
    public ApiResponse<AuditDetail> cancel(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return ApiResponse.ok(audits.cancel(id, request.note()));
    }

    @GetMapping("/{id}/report")
    public ApiResponse<AuditReport> report(@PathVariable UUID id) {
        return ApiResponse.ok(audits.report(id));
    }

    @GetMapping(value = "/{id}/report.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> reportPdf(@PathVariable UUID id) {
        AuditReport report = audits.report(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(report.audit().auditNumber() + ".pdf").build().toString())
                .body(pdf.render(report));
    }
}
