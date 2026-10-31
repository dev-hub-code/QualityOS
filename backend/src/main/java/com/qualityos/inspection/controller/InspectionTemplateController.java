package com.qualityos.inspection.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.inspection.dto.TemplateDtos.PublishRequest;
import com.qualityos.inspection.dto.TemplateDtos.SaveDraftRequest;
import com.qualityos.inspection.dto.TemplateDtos.SaveTemplateRequest;
import com.qualityos.inspection.dto.TemplateDtos.TemplateDetail;
import com.qualityos.inspection.dto.TemplateDtos.TemplateSummary;
import com.qualityos.inspection.entity.InspectionTemplate;
import com.qualityos.inspection.service.InspectionTemplateService;
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
@RequestMapping("/api/v1/inspection-templates")
@Tag(name = "Inspection templates")
public class InspectionTemplateController {

    private static final Set<String> SORTABLE = Set.of("code", "name", "inspectionType");

    private final InspectionTemplateService templates;

    InspectionTemplateController(InspectionTemplateService templates) {
        this.templates = templates;
    }

    @GetMapping
    @PreAuthorize(Require.TEMPLATE_VIEW)
    public ApiResponse<PageResponse<TemplateSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) InspectionTemplate.Type type,
            @RequestParam(required = false) UUID materialId) {
        return ApiResponse.ok(templates.list(q, type, materialId, Pages.of(page, size, sort, SORTABLE, "name")));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Require.TEMPLATE_VIEW)
    public ApiResponse<TemplateDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(templates.get(id));
    }

    @PostMapping
    @PreAuthorize(Require.TEMPLATE_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TemplateDetail> create(@Valid @RequestBody SaveTemplateRequest request) {
        return ApiResponse.ok(templates.create(request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Require.TEMPLATE_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID id) {
        templates.archive(id);
    }

    @PostMapping("/{id}/versions")
    @PreAuthorize(Require.TEMPLATE_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TemplateDetail> newVersion(@PathVariable UUID id) {
        return ApiResponse.ok(templates.newVersion(id));
    }

    @PutMapping("/{id}/versions/{versionId}")
    @PreAuthorize(Require.TEMPLATE_MANAGE)
    public ApiResponse<TemplateDetail> saveDraft(
            @PathVariable UUID id, @PathVariable UUID versionId, @Valid @RequestBody SaveDraftRequest request) {
        return ApiResponse.ok(templates.saveDraft(id, versionId, request));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize(Require.TEMPLATE_PUBLISH)
    public ApiResponse<TemplateDetail> publish(@PathVariable UUID id, @Valid @RequestBody PublishRequest request) {
        return ApiResponse.ok(templates.publish(id, request.effectiveFrom()));
    }
}
