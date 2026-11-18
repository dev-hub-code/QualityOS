package com.qualityos.reporting.service;

import com.qualityos.common.tenant.TenantContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Starts an export as soon as the request for it is saved, away from the request. The job catches any it misses. */
@Component
class ReportExportStarter {

    private final ReportExports exports;

    ReportExportStarter(ReportExports exports) {
        this.exports = exports;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void start(ReportExportRequested event) {
        TenantContext.runAs(event.tenantId(), event.userId(), () -> exports.run(event.exportId()));
    }
}
