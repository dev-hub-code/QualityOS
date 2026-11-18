package com.qualityos.inspection.service;

import com.qualityos.calibration.service.ReinspectionRequired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Once a decision to re-inspect has been saved, and the goods held, starts the new inspection on its own. It runs after
 * the decision commits and in its own transaction, so that anything stopping it (the plan's monthly limit, a plant
 * the person cannot reach, a template since archived) never undoes the decision. The held goods then wait in the queue
 * for someone to start their inspection by hand, as before.
 */
@Component
class ReinspectionStarter {

    private static final Logger LOG = LoggerFactory.getLogger(ReinspectionStarter.class);

    private final InspectionService inspections;
    private final TransactionTemplate tx;

    ReinspectionStarter(InspectionService inspections, PlatformTransactionManager transactionManager) {
        this.inspections = inspections;
        this.tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void start(ReinspectionRequired event) {
        try {
            tx.executeWithoutResult(status -> inspections.startReinspection(event.inspectionId()));
        } catch (RuntimeException e) {
            LOG.info("Re-inspection of {} was not started automatically: {}", event.inspectionId(), e.getMessage());
        }
    }
}
