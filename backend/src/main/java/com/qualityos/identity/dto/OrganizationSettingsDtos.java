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
     * @param actionEvidenceRequired a CAPA action needs a file attached before it can be completed
     * @param version null until first saved; send it back unchanged on update
     */
    public record OrganizationSettings(
            boolean singlePersonReview,
            boolean selfReviewInEffect,
            long activeMembers,
            SeverityThreshold capaRequiredFrom,
            SeverityThreshold effectivenessRequiredFrom,
            boolean actionEvidenceRequired,
            Long version) {
    }

    /**
     * Every rule is sent, so that a save never depends on what the server holds besides the version. The evidence rule,
     * added later, may be left out by an older client, and then stays as it is.
     */
    public record UpdateOrganizationSettingsRequest(
            @NotNull Boolean singlePersonReview,
            @NotNull SeverityThreshold capaRequiredFrom,
            @NotNull SeverityThreshold effectivenessRequiredFrom,
            Boolean actionEvidenceRequired,
            Long version) {
    }
}
