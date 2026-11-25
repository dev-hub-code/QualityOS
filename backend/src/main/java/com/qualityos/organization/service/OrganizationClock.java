package com.qualityos.organization.service;

import com.qualityos.common.tenant.TenantContext;
import com.qualityos.organization.repository.TenantRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * The calendar day at the current organisation. The server runs on UTC, which in India is still the previous day until
 * 05:30, so "today" for receipts, effective dates and due dates is read in the organisation's own time zone.
 */
@Component
public class OrganizationClock {

    private final TenantRepository tenants;

    OrganizationClock(TenantRepository tenants) {
        this.tenants = tenants;
    }

    public ZoneId zone() {
        return TenantContext.findCurrentTenantId()
                .flatMap(tenants::findById)
                .map(tenant -> ZoneId.of(tenant.getTimezone()))
                .orElse(ZoneId.of("UTC"));
    }

    public LocalDate today() {
        return LocalDate.now(zone());
    }
}
