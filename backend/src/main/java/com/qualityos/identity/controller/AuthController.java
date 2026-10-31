package com.qualityos.identity.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.TenantJwtAuthenticationConverter;
import com.qualityos.identity.dto.AuthDtos.ChangePasswordRequest;
import com.qualityos.identity.dto.AuthDtos.EmailLanguageRequest;
import com.qualityos.identity.dto.AuthDtos.LoginRequest;
import com.qualityos.identity.dto.AuthDtos.AcceptInvitationRequest;
import com.qualityos.identity.dto.AuthDtos.InvitationPreview;
import com.qualityos.identity.dto.AuthDtos.MeResponse;
import com.qualityos.identity.dto.AuthDtos.PasswordResetConfirmation;
import com.qualityos.identity.dto.AuthDtos.PasswordResetRequest;
import com.qualityos.identity.dto.AuthDtos.TokenRequest;
import com.qualityos.identity.service.AccountEmailService;
import com.qualityos.identity.service.InvitationService;
import com.qualityos.identity.dto.AuthDtos.RefreshRequest;
import com.qualityos.identity.dto.AuthDtos.RegisterRequest;
import com.qualityos.identity.dto.AuthDtos.RegistrationPending;
import com.qualityos.identity.dto.AuthDtos.ResendVerificationRequest;
import com.qualityos.identity.dto.AuthDtos.SwitchTenantRequest;
import com.qualityos.identity.dto.AuthDtos.TokenResponse;
import com.qualityos.identity.dto.AuthDtos.VerificationStatus;
import com.qualityos.identity.service.AuthService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService auth;
    private final AccountEmailService accountEmails;
    private final InvitationService invitations;

    AuthController(AuthService auth, AccountEmailService accountEmails, InvitationService invitations) {
        this.auth = auth;
        this.accountEmails = accountEmails;
        this.invitations = invitations;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<?>> register(@Valid @RequestBody RegisterRequest request) {
        return auth.register(request)
                .<ResponseEntity<ApiResponse<?>>>map(tokens ->
                        ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(tokens)))
                .orElseGet(() -> ResponseEntity.accepted().body(ApiResponse.ok(new RegistrationPending(true))));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<?>> login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = auth.login(request);
        return result.session() != null
                ? ResponseEntity.ok(ApiResponse.ok(result.session()))
                : ResponseEntity.accepted().body(ApiResponse.ok(result.pending()));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(auth.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        auth.logout(request.refreshToken());
    }

    @PostMapping("/switch-tenant")
    public ApiResponse<TokenResponse> switchTenant(@Valid @RequestBody SwitchTenantRequest request) {
        return ApiResponse.ok(auth.switchTenant(request.tenantId(), request.refreshToken()));
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        auth.changePassword(request);
    }

    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        accountEmails.requestPasswordReset(request.email());
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmation request) {
        accountEmails.resetPassword(request);
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        accountEmails.resendVerification(request.email());
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody TokenRequest request) {
        accountEmails.verifyEmail(request.token());
    }

    @GetMapping("/verification-status")
    public ApiResponse<VerificationStatus> verificationStatus() {
        return ApiResponse.ok(accountEmails.verificationStatus());
    }

    /** Tokens travel in request bodies here, because paths end up in access logs. */
    @PostMapping("/invitations/preview")
    public ApiResponse<InvitationPreview> previewInvitation(@Valid @RequestBody TokenRequest request) {
        return ApiResponse.ok(invitations.preview(request.token()));
    }

    @PostMapping("/invitations/accept")
    public ApiResponse<TokenResponse> acceptInvitation(@Valid @RequestBody AcceptInvitationRequest request) {
        return ApiResponse.ok(invitations.accept(request));
    }

    @PutMapping("/me/email-language")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeEmailLanguage(@Valid @RequestBody EmailLanguageRequest request) {
        auth.changeEmailLanguage(request.language());
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(Authentication authentication) {
        Set<String> permissions = new TreeSet<>(authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> !authority.equals(TenantJwtAuthenticationConverter.MEMBER))
                .toList());
        return ApiResponse.ok(auth.me(permissions));
    }
}
