package com.qualityos.identity.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.identity.service.SupportAccessService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** An organisation's administrators decide whether ProductionOS support may read its records, and see what it read. */
@RestController
@RequestMapping("/api/v1/support-access")
@Tag(name = "Support access")
@PreAuthorize("hasAuthority('" + Permissions.TENANT_MANAGE + "')")
public class SupportAccessController {

    /** @param allowChanges let support make changes too, for at most 24 hours; read-only when left out */
    public record GrantRequest(@NotNull Integer durationHours, @Size(max = 500) String note, Boolean allowChanges) {
    }

    private final SupportAccessService support;

    SupportAccessController(SupportAccessService support) {
        this.support = support;
    }

    @GetMapping
    public ApiResponse<SupportAccessService.Overview> overview() {
        return ApiResponse.ok(support.overview());
    }

    @PostMapping
    public ApiResponse<SupportAccessService.Overview> grant(@Valid @RequestBody GrantRequest request) {
        return ApiResponse.ok(support.grant(request.durationHours(), request.note(), Boolean.TRUE.equals(request.allowChanges())));
    }

    @PostMapping("/revoke")
    public ApiResponse<SupportAccessService.Overview> revoke() {
        return ApiResponse.ok(support.revoke());
    }
}
