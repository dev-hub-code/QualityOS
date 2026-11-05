package com.qualityos.identity.dto;

import jakarta.validation.constraints.NotNull;

public final class OrganizationSettingsDtos {

    private OrganizationSettingsDtos() {
    }

    /**
     * @param singlePersonReview the setting as saved
     * @param selfReviewInEffect the setting is on and the organisation has one active member, so it applies now
     * @param version null until first saved; send it back unchanged on update
     */
    public record OrganizationSettings(
            boolean singlePersonReview, boolean selfReviewInEffect, long activeMembers, Long version) {
    }

    public record UpdateOrganizationSettingsRequest(@NotNull Boolean singlePersonReview, Long version) {
    }
}
