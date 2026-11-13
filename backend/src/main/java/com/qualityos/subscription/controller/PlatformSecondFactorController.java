package com.qualityos.subscription.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.identity.service.OperatorSecondFactor;
import com.qualityos.subscription.dto.PlatformOperationsDtos.ConfirmSecondFactorRequest;
import com.qualityos.subscription.dto.PlatformOperationsDtos.SecondFactorEnrolment;
import com.qualityos.subscription.dto.PlatformOperationsDtos.SecondFactorState;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The one part of the Control Center open to an operator who has not set up their authenticator app yet. */
@RestController
@RequestMapping("/api/v1/platform/second-factor")
@Tag(name = "Platform operations")
@PreAuthorize(Require.PLATFORM_ADMIN)
@Validated
class PlatformSecondFactorController {

    private final OperatorSecondFactor secondFactor;

    PlatformSecondFactorController(OperatorSecondFactor secondFactor) {
        this.secondFactor = secondFactor;
    }

    @GetMapping
    public ApiResponse<SecondFactorState> state() {
        return ApiResponse.ok(new SecondFactorState(secondFactor.state().name()));
    }

    @PostMapping("/enrolment")
    public ApiResponse<SecondFactorEnrolment> begin() {
        OperatorSecondFactor.Enrolment enrolment = secondFactor.begin();
        return ApiResponse.ok(new SecondFactorEnrolment(enrolment.secret(), enrolment.otpauthUri()));
    }

    @PostMapping("/enrolment/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@Valid @RequestBody ConfirmSecondFactorRequest request) {
        secondFactor.confirm(request.code());
    }
}
