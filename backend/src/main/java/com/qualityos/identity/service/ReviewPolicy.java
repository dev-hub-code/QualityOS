package com.qualityos.identity.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.identity.entity.Membership;
import com.qualityos.identity.repository.MembershipRepository;
import com.qualityos.organization.service.TenantSettingsService;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Decides whether someone may review their own work. Normally a second person must, which a one-person organisation
 * cannot provide. It may switch on {@code singlePersonReview}, which applies only while it has a single active member
 * and asks for a reason each time, so the exception stays visible in the audit log.
 */
@Service
public class ReviewPolicy {

    private final TenantSettingsService settings;
    private final MembershipRepository memberships;
    private final AuditService audit;

    ReviewPolicy(TenantSettingsService settings, MembershipRepository memberships, AuditService audit) {
        this.settings = settings;
        this.memberships = memberships;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public long activeMembers() {
        return memberships.countByStatus(Membership.Status.ACTIVE);
    }

    /** The setting is on and nobody else could do the review. */
    @Transactional(readOnly = true)
    public boolean selfReviewInEffect() {
        return settings.get().singlePersonReview() && activeMembers() == 1;
    }

    /**
     * Whether {@code actor} may take a review step on work done by {@code author}. A different person always may.
     * The same person may only under the policy, with a reason, and the step is then recorded as {@code SELF_REVIEW}.
     * A false answer leaves the caller's own rule to refuse, with its usual message.
     *
     * @param step a short name for the step, such as {@code INSPECTION_APPROVE}
     */
    @Transactional
    public boolean permits(UUID actor, UUID author, String entityType, UUID entityId, String step, String reason) {
        if (!actor.equals(author)) {
            return true;
        }
        if (!selfReviewInEffect()) {
            return false;
        }
        if (!StringUtils.hasText(reason)) {
            throw new BusinessRuleException("You are reviewing your own work. Add a note saying why.");
        }
        audit.record("SELF_REVIEW", entityType, entityId, null, Map.of("step", step, "reason", reason.trim()));
        return true;
    }
}
