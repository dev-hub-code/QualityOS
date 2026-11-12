package com.qualityos.production.service;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.service.ReviewPolicy;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.organization.service.OrganizationService;
import com.qualityos.product.dto.ProductDtos.ProductReference;
import com.qualityos.production.dto.ManufacturingDtos.ApproveRequest;
import com.qualityos.production.dto.ManufacturingDtos.RevisionResponse;
import com.qualityos.production.entity.RevisionedDefinition;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** What BOMs, recipes and routings share: approval, effective dates, and the check against circular structures. */
@Component
class RevisionSupport {

    /**
     * Products that {@code component} is made from, at any depth, through any BOM or recipe revision still in use.
     * UNION drops repeats, so the walk ends even if bad data ever held a loop.
     */
    private static final String USES = """
            WITH RECURSIVE uses(product_id) AS (
                SELECT l.component_product_id FROM bom_lines l JOIN boms b ON b.id = l.bom_id
                WHERE b.product_id = ? AND b.status <> 'OBSOLETE' AND l.component_product_id IS NOT NULL
                UNION
                SELECT l.component_product_id FROM uses u
                JOIN boms b ON b.product_id = u.product_id AND b.status <> 'OBSOLETE'
                JOIN bom_lines l ON l.bom_id = b.id
                WHERE l.component_product_id IS NOT NULL
            )
            SELECT EXISTS (SELECT 1 FROM uses WHERE product_id = ?)
            """;

    private final ManufacturingSettingsService settings;
    private final ReviewPolicy reviewPolicy;
    private final TenantAccessService access;
    private final OrganizationService organizations;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    RevisionSupport(
            ManufacturingSettingsService settings,
            ReviewPolicy reviewPolicy,
            TenantAccessService access,
            OrganizationService organizations,
            JdbcTemplate jdbc,
            JsonMapper json) {
        this.settings = settings;
        this.reviewPolicy = reviewPolicy;
        this.access = access;
        this.organizations = organizations;
        this.jdbc = jdbc;
        this.json = json;
    }

    LocalDate today() {
        return LocalDate.now(ZoneId.of(organizations.getCurrent().timezone()));
    }

    /** Refuses a component that is the product itself, or is already made, at any depth, from the product. */
    void requireNoCycle(UUID productId, ProductReference component, String productCode) {
        if (component.id().equals(productId)) {
            throw new BusinessRuleException("%s cannot be a component of itself.".formatted(productCode));
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(USES, Boolean.class, component.id(), productId))) {
            throw new BusinessRuleException("%s is already made from %s, so it cannot also be a component of it."
                    .formatted(component.code(), productCode));
        }
    }

    void submit(RevisionedDefinition revision) {
        revision.submit(TenantContext.getCurrentUserId(), Instant.now());
    }

    /**
     * Approves from the requested date, or today, and ends whichever approved revision would otherwise still be in
     * effect then. Under the organisation's rule, whoever submitted cannot approve, except as a recorded self-review.
     *
     * @param approved the product's other approved revisions of the same kind
     */
    void approve(
            RevisionedDefinition revision,
            List<? extends RevisionedDefinition> approved,
            ApproveRequest request,
            String entityType,
            String step,
            Object snapshot) {
        UUID actor = TenantContext.getCurrentUserId();
        String note = request == null ? null : request.note();
        if (settings.separateApprover() && !reviewPolicy.permits(
                actor, revision.getSubmittedBy(), entityType, revision.getId(), step, note)) {
            throw new BusinessRuleException("You submitted this revision, so someone else must approve it.");
        }
        LocalDate today = today();
        LocalDate from = request == null || request.effectiveFrom() == null ? today : request.effectiveFrom();
        if (from.isBefore(today)) {
            throw new BusinessRuleException("A revision cannot take effect before today.");
        }
        for (RevisionedDefinition other : approved) {
            if (other.getEffectiveTo() != null && other.getEffectiveTo().isBefore(from)) {
                continue;
            }
            if (!other.getEffectiveFrom().isBefore(from)) {
                throw new BusinessRuleException(("Revision %d takes effect on %s. Choose a later date, or retire it "
                        + "first.").formatted(other.getRevision(), other.getEffectiveFrom()));
            }
            other.endBefore(from);
        }
        revision.approve(actor, from, json.writeValueAsString(snapshot), Instant.now());
    }

    Map<UUID, String> members() {
        return access.activeMembers();
    }

    static RevisionResponse toResponse(
            RevisionedDefinition revision,
            String kind,
            ProductReference product,
            BigDecimal baseQuantity,
            Map<UUID, String> members,
            LocalDate today) {
        return new RevisionResponse(revision.getId(), kind, revision.getProductId(),
                product == null ? null : product.code(), product == null ? null : product.name(),
                product == null ? null : product.uomId(), revision.getRevision(), revision.getStatus().name(),
                baseQuantity, revision.getChangeNote(), revision.getReviewNote(), revision.getEffectiveFrom(),
                revision.getEffectiveTo(), revision.inEffectOn(today), revision.getSubmittedBy(),
                members.get(revision.getSubmittedBy()), revision.getSubmittedAt(), revision.getApprovedBy(),
                members.get(revision.getApprovedBy()), revision.getApprovedAt(), revision.getObsoletedAt(),
                revision.getCreatedAt());
    }
}
