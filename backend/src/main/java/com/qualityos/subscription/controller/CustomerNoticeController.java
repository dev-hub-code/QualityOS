package com.qualityos.subscription.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.subscription.dto.PlatformConsoleDtos.CustomerNotices;
import com.qualityos.subscription.service.PlatformConsole;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Platform notices")
class CustomerNoticeController {

    private final PlatformConsole console;

    CustomerNoticeController(PlatformConsole console) {
        this.console = console;
    }

    @GetMapping("/api/v1/platform-notices")
    public ApiResponse<CustomerNotices> notices() {
        return ApiResponse.ok(console.customerNotices());
    }
}
