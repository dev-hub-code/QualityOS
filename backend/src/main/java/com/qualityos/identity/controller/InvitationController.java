package com.qualityos.identity.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.identity.dto.UserRoleDtos.InvitationResponse;
import com.qualityos.identity.dto.UserRoleDtos.InviteRequest;
import com.qualityos.identity.service.InvitationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invitations")
@Tag(name = "Invitations")
@PreAuthorize("hasAuthority('" + Permissions.USER_MANAGE + "')")
public class InvitationController {

    private final InvitationService invitations;

    InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @GetMapping
    public ApiResponse<List<InvitationResponse>> list() {
        return ApiResponse.ok(invitations.listPending());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InvitationResponse> invite(@Valid @RequestBody InviteRequest request) {
        return ApiResponse.ok(invitations.invite(request));
    }

    @PostMapping("/{id}/resend")
    public ApiResponse<InvitationResponse> resend(@PathVariable UUID id) {
        return ApiResponse.ok(invitations.resend(id));
    }

    @PostMapping("/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) {
        invitations.revoke(id);
    }
}
