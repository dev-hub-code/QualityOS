package com.qualityos.subscription.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.PublicPlan;
import com.qualityos.subscription.service.SubscriptionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The price list on the public website. Open to anyone, so it carries only what a visitor may see. */
@RestController
@Tag(name = "Subscription")
public class PublicPlanController {

    private final SubscriptionService subscriptions;

    PublicPlanController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @GetMapping("/api/v1/public/plans")
    public ApiResponse<List<PublicPlan>> plans() {
        return ApiResponse.ok(subscriptions.publicPlans());
    }
}
