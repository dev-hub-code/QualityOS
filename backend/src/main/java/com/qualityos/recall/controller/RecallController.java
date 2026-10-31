package com.qualityos.recall.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.recall.dto.RecallDtos.AcknowledgementRequest;
import com.qualityos.recall.dto.RecallDtos.ContactAttemptRequest;
import com.qualityos.recall.dto.RecallDtos.ContactStatusRequest;
import com.qualityos.recall.dto.RecallDtos.ContactView;
import com.qualityos.recall.dto.RecallDtos.CreateRecallRequest;
import com.qualityos.recall.dto.RecallDtos.ExceptionRequest;
import com.qualityos.recall.dto.RecallDtos.HistoryEntry;
import com.qualityos.recall.dto.RecallDtos.ReasonRequest;
import com.qualityos.recall.dto.RecallDtos.RecallDashboard;
import com.qualityos.recall.dto.RecallDtos.RecallDetail;
import com.qualityos.recall.dto.RecallDtos.RecallReport;
import com.qualityos.recall.dto.RecallDtos.RecallSummary;
import com.qualityos.recall.dto.RecallDtos.RequestClosureRequest;
import com.qualityos.recall.dto.RecallDtos.UpdateContactRequest;
import com.qualityos.recall.service.RecallPdfRenderer;
import com.qualityos.recall.service.RecallService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
@RequestMapping("/api/v1/recalls")
@Tag(name = "Recalls")
@PreAuthorize(Require.RECALL_VIEW)
public class RecallController {

    private static final Set<String> SORTABLE = Set.of("recallNumber", "recallDate", "status", "createdAt");

    private final RecallService recalls;
    private final RecallPdfRenderer pdf;

    RecallController(RecallService recalls, RecallPdfRenderer pdf) {
        this.recalls = recalls;
        this.pdf = pdf;
    }

    @GetMapping
    public ApiResponse<PageResponse<RecallSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(recalls.list(Pages.of(page, size, sort, SORTABLE, "createdAt")));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Require.RECALL_MANAGE)
    public ApiResponse<RecallDetail> create(@Valid @RequestBody CreateRecallRequest request, Authentication caller) {
        return ApiResponse.ok(recalls.create(request, permissions(caller)));
    }

    @GetMapping("/{id}")
    public ApiResponse<RecallDetail> get(@PathVariable UUID id, Authentication caller) {
        return ApiResponse.ok(recalls.get(id, permissions(caller)));
    }

    @GetMapping("/{id}/customers")
    public ApiResponse<List<ContactView>> customers(
            @PathVariable UUID id,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID assignedTo,
            Authentication caller) {
        return ApiResponse.ok(recalls.customers(id, status, customerId, assignedTo, permissions(caller)));
    }

    @PutMapping("/{id}/customers/{customerId}")
    @PreAuthorize(Require.RECALL_MANAGE)
    public ApiResponse<ContactView> update(
            @PathVariable UUID id,
            @PathVariable UUID customerId,
            @Valid @RequestBody UpdateContactRequest request,
            Authentication caller) {
        return ApiResponse.ok(recalls.update(id, customerId, request, permissions(caller)));
    }

    @PostMapping("/{id}/customers/{customerId}/contact-attempts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Require.RECALL_MANAGE)
    public ApiResponse<ContactView> attempt(
            @PathVariable UUID id,
            @PathVariable UUID customerId,
            @Valid @RequestBody ContactAttemptRequest request,
            Authentication caller) {
        return ApiResponse.ok(recalls.attempt(id, customerId, request, permissions(caller)));
    }

    @PostMapping("/{id}/customers/{customerId}/status")
    @PreAuthorize(Require.RECALL_MANAGE)
    public ApiResponse<ContactView> status(
            @PathVariable UUID id,
            @PathVariable UUID customerId,
            @Valid @RequestBody ContactStatusRequest request,
            Authentication caller) {
        return ApiResponse.ok(recalls.changeStatus(id, customerId, request.status(), permissions(caller)));
    }

    @PostMapping("/{id}/customers/{customerId}/exception")
    @PreAuthorize(Require.RECALL_CLOSE)
    public ApiResponse<ContactView> exception(
            @PathVariable UUID id,
            @PathVariable UUID customerId,
            @Valid @RequestBody ExceptionRequest request,
            Authentication caller) {
        return ApiResponse.ok(recalls.recordException(id, customerId, request.reason(), permissions(caller)));
    }

    @GetMapping("/{id}/contact-history")
    public ApiResponse<List<HistoryEntry>> history(@PathVariable UUID id) {
        return ApiResponse.ok(recalls.history(id));
    }

    @PostMapping("/{id}/acknowledgements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Require.RECALL_MANAGE)
    public ApiResponse<ContactView> acknowledge(
            @PathVariable UUID id, @Valid @RequestBody AcknowledgementRequest request, Authentication caller) {
        return ApiResponse.ok(recalls.acknowledge(id, request, permissions(caller)));
    }

    @GetMapping("/{id}/dashboard")
    public ApiResponse<RecallDashboard> dashboard(@PathVariable UUID id, Authentication caller) {
        return ApiResponse.ok(recalls.dashboard(id, permissions(caller)));
    }

    @PostMapping("/{id}/request-closure")
    @PreAuthorize(Require.RECALL_MANAGE)
    public ApiResponse<RecallDetail> requestClosure(
            @PathVariable UUID id, @Valid @RequestBody RequestClosureRequest request, Authentication caller) {
        return ApiResponse.ok(recalls.requestClosure(id, request, permissions(caller)));
    }

    @PostMapping("/{id}/send-back")
    @PreAuthorize(Require.RECALL_CLOSE)
    public ApiResponse<RecallDetail> sendBack(
            @PathVariable UUID id, @Valid @RequestBody ReasonRequest request, Authentication caller) {
        return ApiResponse.ok(recalls.sendBack(id, request.reason(), permissions(caller)));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize(Require.RECALL_CLOSE)
    public ApiResponse<RecallDetail> close(@PathVariable UUID id, Authentication caller) {
        return ApiResponse.ok(recalls.close(id, permissions(caller)));
    }

    @GetMapping("/{id}/report")
    public ApiResponse<RecallReport> report(@PathVariable UUID id, Authentication caller) {
        return ApiResponse.ok(recalls.report(id, permissions(caller)));
    }

    @GetMapping(value = "/{id}/report.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> reportPdf(@PathVariable UUID id, Authentication caller) {
        RecallReport report = recalls.report(id, permissions(caller));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(report.recall().recallNumber() + ".pdf").build().toString())
                .body(pdf.render(report));
    }

    private static Set<String> permissions(Authentication caller) {
        return caller.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }
}
