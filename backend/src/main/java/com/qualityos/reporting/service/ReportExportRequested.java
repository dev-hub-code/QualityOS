package com.qualityos.reporting.service;

import java.util.UUID;

record ReportExportRequested(UUID tenantId, UUID userId, UUID exportId) {
}
