package com.qualityos.subscription.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.identity.service.SupportAccessService;
import com.qualityos.subscription.dto.SubscriptionDtos.ReasonRequest;
import com.qualityos.subscription.service.PlatformStepUp;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Support access to a customer's records, which only the customer can grant. */
@RestController
@RequestMapping("/api/v1/platform/organisations/{tenantId}")
@Tag(name = "Platform administration")
@PreAuthorize(Require.PLATFORM_ADMIN)
public class PlatformSupportController {

    /** @param openUntil when the organisation's grant ends, or null when it has not granted access */
    public record SupportAccessStatus(Instant openUntil) {
    }

    private final SupportAccessService support;
    private final PlatformStepUp stepUp;

    PlatformSupportController(SupportAccessService support, PlatformStepUp stepUp) {
        this.support = support;
        this.stepUp = stepUp;
    }

    @GetMapping("/support-access")
    public ApiResponse<SupportAccessStatus> status(@PathVariable UUID tenantId) {
        return ApiResponse.ok(new SupportAccessStatus(support.openUntil(tenantId).orElse(null)));
    }

    /**
     * The token comes back to the web tier, which sets it as the session and never hands it to page scripts. Needs the
     * password again, recently, like every serious operator action.
     */
    @PostMapping("/support-session")
    @PreAuthorize(Require.PLATFORM_CUSTOMER_MANAGE)
    public ApiResponse<SupportAccessService.Session> start(
            @PathVariable UUID tenantId, @Valid @RequestBody ReasonRequest request) {
        stepUp.require();
        return ApiResponse.ok(support.startSession(tenantId, request.reason()));
    }
}
