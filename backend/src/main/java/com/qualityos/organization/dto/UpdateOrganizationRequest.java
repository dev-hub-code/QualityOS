package com.qualityos.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateOrganizationRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 200) String legalName,
        @Pattern(regexp = "^$|^[0-9]{2}[A-Z0-9]{13}$", message = "must be a 15 character GSTIN") String gstin,
        @Size(max = 100) String industry,
        @NotBlank @Size(min = 2, max = 2) String countryCode,
        @NotBlank @Size(max = 64) String timezone,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotBlank @Size(max = 16) String locale,
        @Size(max = 1000) String address,
        @NotNull Long version) {
}
