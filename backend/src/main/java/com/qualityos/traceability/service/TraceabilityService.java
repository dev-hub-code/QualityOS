package com.qualityos.traceability.service;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.security.Permissions;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.inspection.dto.InspectionDtos.InspectionHistory;
import com.qualityos.inspection.service.InspectionService;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.LotView;
import com.qualityos.materialreceipt.service.MaterialReceiptService;
import com.qualityos.product.service.ProductService;
import com.qualityos.purchaseorder.service.PurchaseOrderService;
import com.qualityos.quality.dto.QualityDtos.CapaHistory;
import com.qualityos.quality.dto.QualityDtos.NcrHistory;
import com.qualityos.quality.service.NcrService;
import com.qualityos.traceability.dto.TraceabilityDtos.CorrectionImpact;
import com.qualityos.traceability.dto.TraceabilityDtos.DispatchLine;
import com.qualityos.traceability.dto.TraceabilityDtos.DispatchResponse;
import com.qualityos.traceability.dto.TraceabilityDtos.ProductionBatchView;
import com.qualityos.traceability.dto.TraceabilityDtos.ReversalView;
import com.qualityos.traceability.dto.TraceabilityDtos.SearchHit;
import com.qualityos.traceability.dto.TraceabilityDtos.TimelineEvent;
import com.qualityos.traceability.dto.TraceabilityDtos.TraceEdge;
import com.qualityos.traceability.dto.TraceabilityDtos.TraceNode;
import com.qualityos.traceability.dto.TraceabilityDtos.TraceReport;
import com.qualityos.traceability.entity.BatchRelationship;
import com.qualityos.traceability.entity.FinishedProduct;
import com.qualityos.traceability.entity.MaterialCorrection;
import com.qualityos.traceability.entity.ProductionBatch;
import com.qualityos.traceability.repository.BatchRelationshipRepository;
import com.qualityos.traceability.repository.FinishedProductRepository;
import com.qualityos.traceability.repository.MaterialCorrectionRepository;
import com.qualityos.traceability.repository.ProductionBatchRepository;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Walks the genealogy. Both walks run in the caller's transaction, so row-level security applies to them, and they
 * also name the tenant. {@code UNION} rather than {@code UNION ALL} keeps a damaged graph from looping forever.
 */
@Service
public class TraceabilityService {

    private static final int MIN_SEARCH = 2;
    private static final int SEARCH_LIMIT = 25;
    private static final String LOT = "LOT";
    private static final String BATCH = "PRODUCTION_BATCH";

    private static final String DOWNSTREAM = """
            WITH RECURSIVE down(batch_id) AS (
                SELECT r.child_batch_id FROM batch_relationships r
                WHERE r.tenant_id = ? AND r.%s = ? AND NOT EXISTS (
                    SELECT 1 FROM trace_corrections c WHERE c.batch_relationship_id = r.id)
                UNION
                SELECT r.child_batch_id FROM batch_relationships r
                JOIN down d ON r.parent_batch_id = d.batch_id
                WHERE r.tenant_id = ? AND NOT EXISTS (
                    SELECT 1 FROM trace_corrections c WHERE c.batch_relationship_id = r.id)
            )
            SELECT batch_id FROM down
            """;

    private static final String UPSTREAM = """
            WITH RECURSIVE up(batch_id) AS (
                SELECT r.parent_batch_id FROM batch_relationships r
                WHERE r.tenant_id = ? AND r.child_batch_id = ? AND r.parent_batch_id IS NOT NULL AND NOT EXISTS (
                    SELECT 1 FROM trace_corrections c WHERE c.batch_relationship_id = r.id)
                UNION
                SELECT r.parent_batch_id FROM batch_relationships r
                JOIN up u ON r.child_batch_id = u.batch_id
                WHERE r.tenant_id = ? AND r.parent_batch_id IS NOT NULL AND NOT EXISTS (
                    SELECT 1 FROM trace_corrections c WHERE c.batch_relationship_id = r.id)
            )
            SELECT batch_id FROM up
            """;

    private final JdbcTemplate jdbc;
    private final ProductionBatchRepository batches;
    private final BatchRelationshipRepository relationships;
    private final FinishedProductRepository finishedProducts;
    private final MaterialCorrectionRepository corrections;
    private final ProductionService production;
    private final DispatchService dispatches;
    private final DispatchReturnService returns;
    private final MaterialReceiptService receipts;
    private final PurchaseOrderService purchaseOrders;
    private final ProductService products;
    private final InspectionService inspections;
    private final NcrService ncrs;

    TraceabilityService(
            JdbcTemplate jdbc,
            ProductionBatchRepository batches,
            BatchRelationshipRepository relationships,
            FinishedProductRepository finishedProducts,
            MaterialCorrectionRepository corrections,
            ProductionService production,
            DispatchService dispatches,
            DispatchReturnService returns,
            MaterialReceiptService receipts,
            PurchaseOrderService purchaseOrders,
            ProductService products,
            InspectionService inspections,
            NcrService ncrs) {
        this.jdbc = jdbc;
        this.batches = batches;
        this.relationships = relationships;
        this.finishedProducts = finishedProducts;
        this.corrections = corrections;
        this.production = production;
        this.dispatches = dispatches;
        this.returns = returns;
        this.receipts = receipts;
        this.purchaseOrders = purchaseOrders;
        this.products = products;
        this.inspections = inspections;
        this.ncrs = ncrs;
    }

    /** Finds lots, batches and serial numbers by any number a person is likely to have in hand. */
    @Transactional(readOnly = true)
    public List<SearchHit> search(String term) {
        if (term == null || term.trim().length() < MIN_SEARCH) {
            throw new BusinessRuleException("Enter at least " + MIN_SEARCH + " characters.");
        }
        String query = term.trim();
        PageRequest limit = PageRequest.of(0, SEARCH_LIMIT);
        Map<String, SearchHit> hits = new LinkedHashMap<>();
        finishedProducts.findBySerialNumberContainingIgnoreCaseOrderBySerialNumber(query, limit).forEach(unit -> {
            ProductionBatchView batch = production.batch(unit.getProductionBatchId());
            hits.putIfAbsent(batch.batchNumber(), hit(batch, "Serial number " + unit.getSerialNumber()));
        });
        for (LotView lot : receipts.searchLots(query, SEARCH_LIMIT)) {
            hits.putIfAbsent(lot.internalBatch(), new SearchHit(lot.internalBatch(), LOT,
                    lot.materialCode() + " – " + lot.materialName(),
                    "Supplier batch " + lot.supplierBatch() + ", receipt " + lot.grnNumber()));
        }
        List<ProductionBatch> found = new ArrayList<>(
                batches.findByBatchNumberContainingIgnoreCaseOrderByBatchNumber(query, limit));
        List<UUID> matchingProducts = products.idsMatching(query);
        if (!matchingProducts.isEmpty()) {
            found.addAll(batches.findByProductIdInOrderByBatchNumber(matchingProducts, limit));
        }
        production.views(found.stream().map(ProductionBatch::getId).collect(Collectors.toSet()))
                .forEach(batch -> hits.putIfAbsent(batch.batchNumber(), hit(batch, "Production batch")));
        return List.copyOf(hits.values());
    }

    private static SearchHit hit(ProductionBatchView batch, String matchedOn) {
        return new SearchHit(batch.batchNumber(), BATCH, batch.productCode() + " – " + batch.productName(), matchedOn);
    }

    @Transactional(readOnly = true)
    public TraceReport report(String batchNumber, Set<String> permissions) {
        Optional<ProductionBatch> batch = batches.findByBatchNumber(batchNumber);
        if (batch.isPresent()) {
            UUID id = batch.get().getId();
            return build(Set.of(), Set.of(id), upstream(id), downstream("parent_batch_id", id), id, null, permissions);
        }
        LotView lot = receipts.lotByNumber(batchNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Batch", batchNumber));
        return build(Set.of(lot.itemId()), Set.of(), Set.of(), downstream("parent_lot_id", lot.itemId()),
                null, lot.itemId(), permissions);
    }

    private TraceReport build(
            Set<UUID> rootLots,
            Set<UUID> rootBatches,
            Set<UUID> upstreamBatches,
            Set<UUID> downstreamBatches,
            UUID subjectBatch,
            UUID subjectLot,
            Set<String> permissions) {
        Set<UUID> allBatches = new HashSet<>(rootBatches);
        allBatches.addAll(upstreamBatches);
        allBatches.addAll(downstreamBatches);

        List<BatchRelationship> edges = relationships.findActiveByChildBatchIdIn(orNone(allBatches));
        // Going backward, every lot that fed the chain counts. Going forward, only the lot being traced does.
        Set<UUID> backwardBatches = new HashSet<>(rootBatches);
        backwardBatches.addAll(upstreamBatches);
        Set<UUID> backwardLots = edges.stream()
                .filter(edge -> backwardBatches.contains(edge.getChildBatchId()))
                .map(BatchRelationship::getParentLotId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> allLots = new HashSet<>(rootLots);
        allLots.addAll(backwardLots);

        Map<UUID, LotView> lots = receipts.lots(allLots).stream()
                .collect(Collectors.toMap(LotView::itemId, Function.identity()));
        Map<UUID, ProductionBatchView> views = production.views(allBatches).stream()
                .collect(Collectors.toMap(ProductionBatchView::id, Function.identity()));
        Map<UUID, String> orderNumbers = purchaseOrders.numbers(
                lots.values().stream().map(LotView::purchaseOrderId).collect(Collectors.toSet()));

        TraceNode subject = subjectBatch != null
                ? node(views.get(subjectBatch))
                : node(lots.get(subjectLot), orderNumbers);
        List<TraceNode> backward = new ArrayList<>();
        backwardLots.stream().map(lots::get).sorted(Comparator.comparing(LotView::internalBatch))
                .forEach(lot -> backward.add(node(lot, orderNumbers)));
        upstreamBatches.stream().map(views::get).sorted(Comparator.comparing(ProductionBatchView::batchNumber))
                .forEach(view -> backward.add(node(view)));
        List<TraceNode> forward = downstreamBatches.stream().map(views::get)
                .sorted(Comparator.comparing(ProductionBatchView::batchNumber))
                .map(TraceabilityService::node)
                .toList();

        List<TraceEdge> links = edges.stream()
                .map(edge -> toEdge(edge, lots, views))
                .filter(Objects::nonNull)
                .toList();

        List<DispatchResponse> shipped = dispatches.forBatches(allBatches.stream()
                .filter(id -> !upstreamBatches.contains(id)).toList());
        List<ReversalView> returned = returns.forDispatches(shipped.stream().map(DispatchResponse::id).toList());
        List<String> customers = shipped.stream()
                .filter(dispatch -> dispatch.reversalReason() == null)
                .map(DispatchResponse::customerName)
                .distinct().sorted().toList();
        List<String> serials = finishedProducts.findByProductionBatchIdInOrderBySerialNumber(orNone(allBatches)).stream()
                .map(FinishedProduct::getSerialNumber)
                .toList();

        Set<UUID> traced = new HashSet<>(allLots);
        traced.addAll(allBatches);
        List<MaterialCorrection> applied = corrections.findAppliedFor(orNone(traced));

        boolean seesInspections = permissions.contains(Permissions.INSPECTION_VIEW);
        boolean seesNcrs = permissions.contains(Permissions.NCR_VIEW);
        boolean seesCapas = permissions.contains(Permissions.CAPA_VIEW);
        List<InspectionHistory> inspected = inspections.history(allLots, allBatches);
        List<String> numbers = new ArrayList<>(views.values().stream().map(ProductionBatchView::batchNumber).toList());
        lots.values().forEach(lot -> numbers.add(lot.internalBatch()));
        List<NcrHistory> raised = ncrs.history(inspected.stream().map(InspectionHistory::id).toList(), numbers);
        List<CapaHistory> corrective = ncrs.capaHistory(raised.stream().map(NcrHistory::id).toList());

        List<InspectionHistory> visibleInspections = seesInspections ? inspected : List.of();
        List<NcrHistory> visibleNcrs = seesNcrs ? raised : List.of();
        List<CapaHistory> visibleCapas = seesCapas ? corrective : List.of();

        return new TraceReport(
                subject,
                backward,
                forward,
                links,
                shipped,
                returned,
                customers,
                serials,
                visibleInspections,
                visibleNcrs,
                visibleCapas,
                timeline(lots.values(), views.values(), shipped, visibleInspections, visibleNcrs, visibleCapas,
                        applied, returned));
    }

    private static TraceEdge toEdge(
            BatchRelationship edge, Map<UUID, LotView> lots, Map<UUID, ProductionBatchView> views) {
        ProductionBatchView child = views.get(edge.getChildBatchId());
        String parent = null;
        if (edge.getParentLotId() != null && lots.containsKey(edge.getParentLotId())) {
            parent = lots.get(edge.getParentLotId()).internalBatch();
        } else if (edge.getParentBatchId() != null && views.containsKey(edge.getParentBatchId())) {
            parent = views.get(edge.getParentBatchId()).batchNumber();
        }
        return parent == null ? null : new TraceEdge(parent, child.batchNumber(), edge.getQuantity());
    }

    private static List<TimelineEvent> timeline(
            Collection<LotView> lots,
            Collection<ProductionBatchView> batches,
            List<DispatchResponse> shipped,
            List<InspectionHistory> inspected,
            List<NcrHistory> raised,
            List<CapaHistory> corrective,
            List<MaterialCorrection> applied,
            List<ReversalView> returned) {
        List<TimelineEvent> events = new ArrayList<>();
        returned.forEach(reversal -> events.add(new TimelineEvent(reversal.createdAt(), "RETURNED",
                reversal.reversalNumber() + ": returned by " + reversal.customerName() + " against "
                        + reversal.dispatchNumber(),
                null, reversal.status(), "/dispatch-reversals/" + reversal.id())));
        applied.forEach(correction -> events.add(new TimelineEvent(correction.getAppliedAt(), "CORRECTED",
                correction.getCorrectionNumber() + ": " + correction.getReason(), null, null,
                "/material-corrections/" + correction.getId())));
        lots.forEach(lot -> events.add(new TimelineEvent(
                lot.receivedOn().atStartOfDay().toInstant(ZoneOffset.UTC), "RECEIVED",
                "Received " + lot.materialCode() + " from " + lot.supplierName() + " on " + lot.grnNumber(),
                lot.internalBatch(), lot.disposition(), null)));
        batches.forEach(batch -> {
            events.add(new TimelineEvent(batch.createdAt(), "PRODUCTION_STARTED",
                    "Production started: " + batch.productCode(), batch.batchNumber(), null, null));
            if (batch.completedAt() != null) {
                events.add(new TimelineEvent(batch.completedAt(), "PRODUCTION_COMPLETED",
                        "Production completed", batch.batchNumber(), batch.status(), null));
            }
        });
        inspected.forEach(inspection -> events.add(new TimelineEvent(inspection.createdAt(), "INSPECTION",
                inspection.inspectionType() + " inspection " + inspection.inspectionNumber(),
                inspection.batchNumber(),
                inspection.disposition() != null ? inspection.disposition() : inspection.status(),
                "/inspections/" + inspection.id())));
        raised.forEach(ncr -> events.add(new TimelineEvent(ncr.createdAt(), "NCR",
                ncr.ncrNumber() + ": " + ncr.title(), ncr.batchNumber(), ncr.status(), "/ncrs/" + ncr.id())));
        corrective.forEach(capa -> events.add(new TimelineEvent(capa.createdAt(), "CAPA",
                capa.capaNumber() + ": " + capa.title(), null, capa.status(), "/capas/" + capa.id())));
        shipped.forEach(dispatch -> {
            String shippedBatches = dispatch.lines().stream()
                    .map(DispatchLine::batchNumber).distinct().collect(Collectors.joining(", "));
            events.add(new TimelineEvent(dispatch.createdAt(), "DISPATCHED",
                    "Dispatched to " + dispatch.customerName() + " on " + dispatch.dispatchNumber(),
                    shippedBatches, null, null));
            if (dispatch.reversedAt() != null) {
                events.add(new TimelineEvent(dispatch.reversedAt(), "DISPATCH_REVERSED",
                        dispatch.dispatchNumber() + " reversed: " + dispatch.reversalReason(),
                        shippedBatches, null, null));
            }
        });
        events.sort(Comparator.comparing(TimelineEvent::at));
        return events;
    }

    /** How far a lot has gone: the batches it reached, and the customers who still hold what was made from it. */
    @Transactional(readOnly = true)
    public CorrectionImpact lotImpact(UUID lotId, boolean inspected) {
        Set<UUID> reached = downstream("parent_lot_id", lotId);
        return impact(inspected, reached, reached);
    }

    /** How far a batch has gone: later batches that used it, and customers who hold it or what was made from it. */
    @Transactional(readOnly = true)
    public CorrectionImpact batchImpact(UUID batchId) {
        Set<UUID> later = downstream("parent_batch_id", batchId);
        Set<UUID> shipped = new HashSet<>(later);
        shipped.add(batchId);
        return impact(false, later, shipped);
    }

    private CorrectionImpact impact(boolean inspected, Set<UUID> later, Set<UUID> shipped) {
        return new CorrectionImpact(
                inspected,
                production.views(later).stream().map(ProductionBatchView::batchNumber).sorted().toList(),
                dispatches.forBatches(shipped).stream()
                        .filter(dispatch -> dispatch.reversalReason() == null)
                        .map(DispatchResponse::customerName)
                        .distinct()
                        .sorted()
                        .toList());
    }

    private Set<UUID> downstream(String parentColumn, UUID id) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return Set.copyOf(jdbc.queryForList(DOWNSTREAM.formatted(parentColumn), UUID.class, tenantId, id, tenantId));
    }

    private Set<UUID> upstream(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return Set.copyOf(jdbc.queryForList(UPSTREAM, UUID.class, tenantId, id, tenantId));
    }

    private static Collection<UUID> orNone(Collection<UUID> ids) {
        return ids.isEmpty() ? List.of(new UUID(0, 0)) : ids;
    }

    private static TraceNode node(LotView lot, Map<UUID, String> orderNumbers) {
        return new TraceNode(lot.itemId(), LOT, lot.internalBatch(), lot.materialCode(), lot.materialName(), lot.quantity(),
                lot.disposition() != null ? lot.disposition() : lot.inspectionStatus(), lot.supplierName(),
                lot.supplierBatch(), lot.grnNumber(), orderNumbers.get(lot.purchaseOrderId()));
    }

    private static TraceNode node(ProductionBatchView batch) {
        return new TraceNode(batch.id(), BATCH, batch.batchNumber(), batch.productCode(), batch.productName(),
                batch.producedQuantity() != null ? batch.producedQuantity() : batch.plannedQuantity(),
                batch.status(), null, null, null, null);
    }
}
