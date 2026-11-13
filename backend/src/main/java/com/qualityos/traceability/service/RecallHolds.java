package com.qualityos.traceability.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which batches are under an open recall. The recall module implements this, because it depends on traceability and
 * not the other way round.
 */
public interface RecallHolds {

    /**
     * The numbers of the open recalls covering each batch, through the batch itself or anything it was made from.
     * Batches under no open recall are left out.
     */
    Map<UUID, List<String>> openRecalls(Collection<UUID> batchIds);
}
