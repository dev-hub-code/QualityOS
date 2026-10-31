package com.qualityos.auditmanagement.controller;

import com.qualityos.auditmanagement.dto.AuditDtos.ChecklistDetail;
import com.qualityos.auditmanagement.dto.AuditDtos.ChecklistSummary;
import com.qualityos.auditmanagement.dto.AuditDtos.SaveChecklistRequest;
import com.qualityos.auditmanagement.service.AuditChecklistService;
import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/audit-checklists")
@Tag(name = "Audits")
@PreAuthorize(Require.AUDIT_VIEW)
public class AuditChecklistController {

    private static final Set<String> SORTABLE = Set.of("code", "name");

    private final AuditChecklistService checklists;

    AuditChecklistController(AuditChecklistService checklists) {
        this.checklists = checklists;
    }

    @GetMapping
    public ApiResponse<PageResponse<ChecklistSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q) {
        return ApiResponse.ok(checklists.list(q, Pages.of(page, size, sort, SORTABLE, "code")));
    }

    @GetMapping("/{id}")
    public ApiResponse<ChecklistDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(checklists.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Require.AUDIT_MANAGE)
    public ApiResponse<ChecklistDetail> create(@Valid @RequestBody SaveChecklistRequest request) {
        return ApiResponse.ok(checklists.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Require.AUDIT_MANAGE)
    public ApiResponse<ChecklistDetail> update(@PathVariable UUID id, @Valid @RequestBody SaveChecklistRequest request) {
        return ApiResponse.ok(checklists.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Require.AUDIT_MANAGE)
    public void archive(@PathVariable UUID id) {
        checklists.archive(id);
    }
}
