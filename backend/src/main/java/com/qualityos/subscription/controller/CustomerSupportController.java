package com.qualityos.subscription.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.subscription.dto.CustomerSupportDtos.CustomerTicket;
import com.qualityos.subscription.dto.CustomerSupportDtos.RaiseTicketRequest;
import com.qualityos.subscription.dto.CustomerSupportDtos.ReplyRequest;
import com.qualityos.subscription.dto.CustomerSupportDtos.SupportMessage;
import com.qualityos.subscription.service.CustomerSupport;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Any member may ask for help. Someone who manages the organisation sees every request it raised. */
@RestController
@RequestMapping("/api/v1/support/tickets")
@Tag(name = "Support requests")
class CustomerSupportController {

    private final CustomerSupport support;

    CustomerSupportController(CustomerSupport support) {
        this.support = support;
    }

    @GetMapping
    public ApiResponse<List<CustomerTicket>> tickets(Authentication caller) {
        return ApiResponse.ok(support.tickets(managesOrganisation(caller)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Map<String, UUID>> raise(@Valid @RequestBody RaiseTicketRequest request) {
        return ApiResponse.ok(Map.of("id", support.raise(request.subject(), request.message())));
    }

    @GetMapping("/{id}")
    public ApiResponse<CustomerTicket> ticket(@PathVariable UUID id, Authentication caller) {
        return ApiResponse.ok(support.ticket(id, managesOrganisation(caller)));
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<List<SupportMessage>> messages(@PathVariable UUID id, Authentication caller) {
        return ApiResponse.ok(support.messages(id, managesOrganisation(caller)));
    }

    @PostMapping("/{id}/replies")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reply(@PathVariable UUID id, @Valid @RequestBody ReplyRequest request, Authentication caller) {
        support.reply(id, request.body(), managesOrganisation(caller));
    }

    private static boolean managesOrganisation(Authentication caller) {
        return caller.getAuthorities().stream().anyMatch(authority ->
                Permissions.TENANT_MANAGE.equals(authority.getAuthority()));
    }
}
