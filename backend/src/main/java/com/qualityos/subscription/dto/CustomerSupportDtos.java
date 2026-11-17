package com.qualityos.subscription.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class CustomerSupportDtos {

    private CustomerSupportDtos() {
    }

    public record RaiseTicketRequest(
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 4000) String message) {
    }

    public record ReplyRequest(@NotBlank @Size(max = 4000) String body) {
    }

    public record CustomerTicket(
            UUID id, String subject, String status, String openedBy, Instant createdAt, Instant updatedAt) {
    }

    /** @param fromSupport true for a reply from support, which is shown without the operator's name */
    public record SupportMessage(UUID id, String author, boolean fromSupport, String body, Instant createdAt) {
    }
}
