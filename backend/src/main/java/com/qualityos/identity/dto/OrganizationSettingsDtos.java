package com.qualityos.identity.dto;

import com.qualityos.organization.service.SeverityThreshold;
import jakarta.validation.constraints.NotNull;

public final class OrganizationSettingsDtos {

    private OrganizationSettingsDtos() {
    }

    /**
     * @param singlePersonReview the setting as saved
     * @param selfReviewInEffect the setting is on and the organisation has one active member, so it applies now
     * @param capaRequiredFrom the least severe NCR that needs a closed CAPA before verification
     * @param effectivenessRequiredFrom the least severe NCR whose CAPA must have its fix verified
     * @param version null until first saved; send it back unchanged on update
     */
    public record OrganizationSettings(
            boolean singlePersonReview,
            boolean selfReviewInEffect,
            long activeMembers,
            SeverityThreshold capaRequiredFrom,
            SeverityThreshold effectivenessRequiredFrom,
            Long version) {
    }

    /** Every rule is sent, so that a save never depends on what the server holds besides the version. */
    public record UpdateOrganizationSettingsRequest(
            @NotNull Boolean singlePersonReview,
            @NotNull SeverityThreshold capaRequiredFrom,
            @NotNull SeverityThreshold effectivenessRequiredFrom,
            Long version) {
    }
}
