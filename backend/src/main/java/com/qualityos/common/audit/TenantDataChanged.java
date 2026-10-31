package com.qualityos.common.audit;

import java.util.UUID;

/** A critical action was recorded, so anything derived from this organisation's data may be out of date. */
public record TenantDataChanged(UUID tenantId) {
}
