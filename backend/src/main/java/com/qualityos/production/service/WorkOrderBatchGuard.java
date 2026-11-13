package com.qualityos.production.service;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.production.repository.WorkOrderRepository;
import com.qualityos.traceability.service.BatchCompletionGuard;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A work order's batch is completed only by receiving its finished goods on the work order, where operations, quality
 * gates and materials are reconciled first.
 */
@Component
class WorkOrderBatchGuard implements BatchCompletionGuard {

    private final WorkOrderRepository workOrders;

    WorkOrderBatchGuard(WorkOrderRepository workOrders) {
        this.workOrders = workOrders;
    }

    @Override
    public void checkDirectCompletion(UUID batchId) {
        workOrders.findByProductionBatchId(batchId).ifPresent(order -> {
            throw new BusinessRuleException("Batch is made by work order %s. Receive its finished goods there."
                    .formatted(order.getWorkOrderNumber()));
        });
    }
}
