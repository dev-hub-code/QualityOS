package com.qualityos.recall.service;

import com.qualityos.recall.repository.RecallBatchRepository;
import com.qualityos.recall.repository.RecallBatchRepository.OpenRecall;
import com.qualityos.traceability.service.ProductionService;
import com.qualityos.traceability.service.RecallHolds;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A batch is held while any recall that is not closed started from it, or from a batch it was made from. */
@Service
class OpenRecallHolds implements RecallHolds {

    private final ProductionService production;
    private final RecallBatchRepository batches;

    OpenRecallHolds(ProductionService production, RecallBatchRepository batches) {
        this.production = production;
        this.batches = batches;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<String>> openRecalls(Collection<UUID> batchIds) {
        Map<UUID, Set<UUID>> sources = production.madeFrom(batchIds);
        Set<UUID> all = sources.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
        if (all.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<String>> recallsByRoot = batches.findOpenByProductionBatchIdIn(all).stream()
                .collect(Collectors.groupingBy(OpenRecall::getProductionBatchId,
                        Collectors.mapping(OpenRecall::getRecallNumber, Collectors.toSet())));
        return sources.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue().stream()
                        .flatMap(source -> recallsByRoot.getOrDefault(source, Set.of()).stream())
                        .collect(Collectors.toCollection(TreeSet::new))))
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }
}
