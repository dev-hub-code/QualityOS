package com.qualityos.organization.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.organization.dto.OrganizationResponse;
import com.qualityos.organization.dto.UpdateOrganizationRequest;
import com.qualityos.organization.service.OrganizationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations")
@Tag(name = "Organizations")
public class OrganizationController {

    private final OrganizationService organizations;

    OrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    @GetMapping("/{id}")
    public ApiResponse<OrganizationResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(organizations.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.TENANT_MANAGE + "')")
    public ApiResponse<OrganizationResponse> update(
            @PathVariable UUID id, @Valid @RequestBody UpdateOrganizationRequest request) {
        return ApiResponse.ok(organizations.update(id, request));
    }
}
