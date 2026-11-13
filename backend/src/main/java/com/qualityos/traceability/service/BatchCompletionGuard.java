package com.qualityos.traceability.service;

import java.util.UUID;

/**
 * Lets another module refuse completing a batch by hand, when it completes the batch itself under its own rules. A
 * work order's batch, for one, is completed from the work order.
 */
public interface BatchCompletionGuard {

    /** Throws when the batch must not be completed directly. */
    void checkDirectCompletion(UUID batchId);
}
