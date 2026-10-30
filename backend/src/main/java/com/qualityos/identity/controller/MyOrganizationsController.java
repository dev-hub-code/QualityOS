package com.qualityos.identity.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.dto.AuthDtos.CreateOrganizationRequest;
import com.qualityos.identity.dto.AuthDtos.OrganizationSummary;
import com.qualityos.identity.dto.AuthDtos.TokenResponse;
import com.qualityos.identity.service.AuthService;
import com.qualityos.identity.service.TenantAccessService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Organisations the signed-in user belongs to. Lives here because it depends on memberships. */
@RestController
@RequestMapping("/api/v1/organizations")
@Tag(name = "Organizations")
public class MyOrganizationsController {

    private final TenantAccessService access;
    private final AuthService auth;

    MyOrganizationsController(TenantAccessService access, AuthService auth) {
        this.access = access;
        this.auth = auth;
    }

    @GetMapping
    public ApiResponse<List<OrganizationSummary>> list() {
        return ApiResponse.ok(access.organizationsOf(TenantContext.getCurrentUserId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TokenResponse> create(@Valid @RequestBody CreateOrganizationRequest request) {
        return ApiResponse.ok(auth.createOrganization(request.name()));
    }
}
