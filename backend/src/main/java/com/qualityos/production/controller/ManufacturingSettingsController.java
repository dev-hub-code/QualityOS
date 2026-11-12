package com.qualityos.production.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.production.dto.ManufacturingDtos.ProductSettingsResponse;
import com.qualityos.production.dto.ManufacturingDtos.SettingsResponse;
import com.qualityos.production.dto.ManufacturingDtos.UpdateProductSettingsRequest;
import com.qualityos.production.dto.ManufacturingDtos.UpdateSettingsRequest;
import com.qualityos.production.service.ManufacturingSettingsService;
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
@RequestMapping("/api/v1/manufacturing")
@Tag(name = "Manufacturing")
@PreAuthorize(Require.MANUFACTURING_VIEW)
public class ManufacturingSettingsController {

    private final ManufacturingSettingsService settings;

    ManufacturingSettingsController(ManufacturingSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/settings")
    public ApiResponse<SettingsResponse> get() {
        return ApiResponse.ok(settings.get());
    }

    @PutMapping("/settings")
    @PreAuthorize(Require.MANUFACTURING_MANAGE)
    public ApiResponse<SettingsResponse> update(@Valid @RequestBody UpdateSettingsRequest request) {
        return ApiResponse.ok(settings.update(request));
    }

    @GetMapping("/products/{productId}/settings")
    public ApiResponse<ProductSettingsResponse> getProduct(@PathVariable UUID productId) {
        return ApiResponse.ok(settings.getProduct(productId));
    }

    @PutMapping("/products/{productId}/settings")
    @PreAuthorize(Require.MANUFACTURING_MANAGE)
    public ApiResponse<ProductSettingsResponse> updateProduct(
            @PathVariable UUID productId, @Valid @RequestBody UpdateProductSettingsRequest request) {
        return ApiResponse.ok(settings.updateProduct(productId, request));
    }
}
