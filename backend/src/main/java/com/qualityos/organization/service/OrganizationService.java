package com.qualityos.organization.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.organization.dto.OrganizationResponse;
import com.qualityos.organization.dto.UpdateOrganizationRequest;
import com.qualityos.organization.entity.Tenant;
import com.qualityos.organization.mapper.OrganizationMapper;
import com.qualityos.organization.repository.TenantRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class OrganizationService {

    private final TenantRepository tenants;
    private final OrganizationMapper mapper;
    private final AuditService audit;

    OrganizationService(TenantRepository tenants, OrganizationMapper mapper, AuditService audit) {
        this.tenants = tenants;
        this.mapper = mapper;
        this.audit = audit;
    }

    /** The id must be the current tenant, so that row-level security admits the new row. */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrganizationResponse create(UUID id, String name) {
        Tenant tenant = tenants.saveAndFlush(new Tenant(id, name));
        audit.record("ORGANIZATION_CREATED", "Organization", id);
        return mapper.toResponse(tenant);
    }

    @Transactional(readOnly = true)
    public OrganizationResponse getCurrent() {
        return mapper.toResponse(loadCurrent());
    }

    /** Only the tenant in the token is reachable, even for other organisations the user belongs to. */
    @Transactional(readOnly = true)
    public OrganizationResponse get(UUID id) {
        return mapper.toResponse(requireCurrent(id));
    }

    @Transactional
    public OrganizationResponse update(UUID id, UpdateOrganizationRequest request) {
        Tenant tenant = requireCurrent(id);
        tenant.requireVersion(request.version());
        Map<String, Object> previous = snapshot(tenant);
        tenant.setName(request.name());
        tenant.setLegalName(request.legalName());
        tenant.setGstin(StringUtils.hasText(request.gstin()) ? request.gstin() : null);
        tenant.setIndustry(request.industry());
        tenant.setCountryCode(request.countryCode());
        tenant.setTimezone(request.timezone());
        tenant.setCurrency(request.currency());
        tenant.setLocale(request.locale());
        tenant.setAddress(request.address());
        if (tenant.getOnboardedAt() == null) {
            tenant.setOnboardedAt(Instant.now());
        }
        tenants.saveAndFlush(tenant);
        audit.record("ORGANIZATION_UPDATED", "Organization", id, previous, snapshot(tenant));
        return mapper.toResponse(tenant);
    }

    /** Every member loses access on their next request. Nothing the organisation holds is touched. */
    @Transactional
    public void suspend(String reason) {
        Tenant tenant = loadCurrent();
        if (tenant.getStatus() == Tenant.Status.SUSPENDED) {
            throw new ConflictException("This organisation is already suspended.");
        }
        tenant.setStatus(Tenant.Status.SUSPENDED);
        tenant.setSuspendedAt(Instant.now());
        tenant.setSuspensionReason(reason);
        tenants.saveAndFlush(tenant);
        audit.record("ORGANIZATION_SUSPENDED", "Organization", tenant.getId(), null, Map.of("reason", reason));
    }

    /** Only undoes an operator's suspension. A sign-up that expired has no one left to return. */
    @Transactional
    public void reactivate(String reason) {
        Tenant tenant = loadCurrent();
        if (tenant.getSuspendedAt() == null) {
            throw new ConflictException("This organisation was not suspended by an operator.");
        }
        tenant.setStatus(Tenant.Status.ACTIVE);
        tenant.setSuspendedAt(null);
        tenant.setSuspensionReason(null);
        tenants.saveAndFlush(tenant);
        audit.record("ORGANIZATION_REACTIVATED", "Organization", tenant.getId(), null, Map.of("reason", reason));
    }

    private Tenant requireCurrent(UUID id) {
        if (!id.equals(TenantContext.getCurrentTenantId())) {
            throw new ResourceNotFoundException("Organization", id);
        }
        return loadCurrent();
    }

    private Tenant loadCurrent() {
        UUID id = TenantContext.getCurrentTenantId();
        return tenants.findById(id).orElseThrow(() -> new ResourceNotFoundException("Organization", id));
    }

    private static Map<String, Object> snapshot(Tenant tenant) {
        return Map.of(
                "name", tenant.getName(),
                "gstin", String.valueOf(tenant.getGstin()),
                "timezone", tenant.getTimezone(),
                "currency", tenant.getCurrency());
    }
}
