package com.qualityos.organization.controller;

import com.qualityos.organization.service.OrganizationLogoService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartFile;
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
    private final OrganizationLogoService logos;

    OrganizationController(OrganizationService organizations, OrganizationLogoService logos) {
        this.organizations = organizations;
        this.logos = logos;
    }

    /**
     * Any member: the app shows it. The browser revalidates each time, and the version is the time it was last changed,
     * so a new logo shows at once.
     */
    @GetMapping("/current/logo")
    public ResponseEntity<byte[]> logo(WebRequest request) {
        return logos.current()
                .map(logo -> {
                    String version = String.valueOf(logo.updatedAt().toEpochMilli());
                    if (request.checkNotModified(version)) {
                        return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(version).<byte[]>build();
                    }
                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(logo.contentType()))
                            .cacheControl(CacheControl.noCache().cachePrivate())
                            .eTag(version)
                            .header("X-Content-Type-Options", "nosniff")
                            .body(logo.data());
                })
                // No logo is an ordinary answer, not a missing page: a 404 here filled every page's console.
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping(path = "/current/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.TENANT_MANAGE + "')")
    public ApiResponse<LogoResponse> uploadLogo(@RequestPart MultipartFile file) {
        OrganizationLogoService.Logo saved = logos.save(file);
        return ApiResponse.ok(new LogoResponse(saved.contentType(), saved.data().length, saved.updatedAt()));
    }

    @DeleteMapping("/current/logo")
    @PreAuthorize("hasAuthority('" + Permissions.TENANT_MANAGE + "')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeLogo() {
        logos.remove();
    }

    public record LogoResponse(String contentType, int sizeBytes, java.time.Instant updatedAt) {
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
