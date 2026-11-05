package com.qualityos.identity.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.identity.dto.OrganizationSettingsDtos.OrganizationSettings;
import com.qualityos.identity.dto.OrganizationSettingsDtos.UpdateOrganizationSettingsRequest;
import com.qualityos.identity.service.ReviewPolicy;
import com.qualityos.organization.service.TenantSettingsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The current organisation's working rules. Lives here because whether a rule applies depends on memberships. */
@RestController
@RequestMapping("/api/v1/organizations/current/settings")
@Tag(name = "Organizations")
public class OrganizationSettingsController {

    private final TenantSettingsService settings;
    private final ReviewPolicy reviewPolicy;

    OrganizationSettingsController(TenantSettingsService settings, ReviewPolicy reviewPolicy) {
        this.settings = settings;
        this.reviewPolicy = reviewPolicy;
    }

    /** Any member: the rules decide which actions their screens offer. */
    @GetMapping
    public ApiResponse<OrganizationSettings> get() {
        return ApiResponse.ok(view(settings.get()));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('" + Permissions.TENANT_MANAGE + "')")
    public ApiResponse<OrganizationSettings> update(@Valid @RequestBody UpdateOrganizationSettingsRequest request) {
        return ApiResponse.ok(view(settings.update(new TenantSettingsService.Settings(
                request.singlePersonReview(),
                request.capaRequiredFrom(),
                request.effectivenessRequiredFrom(),
                request.version()))));
    }

    private OrganizationSettings view(TenantSettingsService.Settings saved) {
        long members = reviewPolicy.activeMembers();
        return new OrganizationSettings(
                saved.singlePersonReview(),
                saved.singlePersonReview() && members == 1,
                members,
                saved.capaRequiredFrom(),
                saved.effectivenessRequiredFrom(),
                saved.version());
    }
}
