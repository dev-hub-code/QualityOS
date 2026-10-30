package com.qualityos.organization.dto;

import java.util.UUID;

public record OrganizationResponse(
        UUID id,
        String name,
        String legalName,
        String gstin,
        String industry,
        String countryCode,
        String timezone,
        String currency,
        String locale,
        String address,
        boolean onboarded,
        long version) {
}
