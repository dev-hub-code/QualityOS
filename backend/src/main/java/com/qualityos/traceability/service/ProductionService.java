package com.qualityos.traceability.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.DocumentNumberService;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.LotView;
import com.qualityos.materialreceipt.service.MaterialReceiptService;
import com.qualityos.organization.service.FacilityService;
import com.qualityos.product.dto.ProductDtos.ProductReference;
import com.qualityos.product.service.ProductService;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.traceability.dto.TraceabilityDtos.AddSerialsRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.BatchDetail;
import com.qualityos.traceability.dto.TraceabilityDtos.CompleteBatchRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.ConsumeRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.Consumption;
import com.qualityos.traceability.dto.TraceabilityDtos.ConsumptionRef;
import com.qualityos.traceability.dto.TraceabilityDtos.CreateBatchRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.ProductionBatchView;
import com.qualityos.traceability.entity.BatchRelationship;
import com.qualityos.traceability.entity.FinishedProduct;
import com.qualityos.traceability.entity.ProductionBatch;
import com.qualityos.traceability.entity.TraceCorrection;
import com.qualityos.traceability.repository.BatchRelationshipRepository;
import com.qualityos.traceability.repository.FinishedProductRepository;
import com.qualityos.traceability.repository.ProductionBatchRepository;
import com.qualityos.traceability.repository.TraceCorrectionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductionService {

    private static final String ENTITY = "ProductionBatch";

    private final ProductionBatchRepository batches;
    private final BatchRelationshipRepository relationships;
    private final FinishedProductRepository finishedProducts;
    private final TraceCorrectionRepository corrections;
    private final MaterialReceiptService receipts;
    private final ProductService products;
    private final FacilityService facilities;
    private final DocumentNumberService numbers;
    private final AuditService audit;
    private final FacilityScope scope;

    ProductionService(
            ProductionBatchRepository batches,
            BatchRelationshipRepository relationships,
            FinishedProductRepository finishedProducts,
            TraceCorrectionRepository corrections,
            MaterialReceiptService receipts,
            ProductService products,
            FacilityService facilities,
            DocumentNumberService numbers,
            AuditService audit,
            FacilityScope scope) {
        this.batches = batches;
        this.relationships = relationships;
        this.finishedProducts = finishedProducts;
        this.corrections = corrections;
        this.receipts = receipts;
        this.products = products;
        this.facilities = facilities;
        this.numbers = numbers;
        this.audit = audit;
        this.scope = scope;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductionBatchView> list(
            String search, ProductionBatch.Status status, UUID productId, Pageable pageable) {
        Specification<ProductionBatch> filter = Specification
                .<ProductionBatch>where(Searches.contains(search, "batchNumber"))
                .and(Searches.equalTo("status", status))
                .and(Searches.equalTo("productId", productId));
        Optional<Set<UUID>> restriction = scope.restriction();
        if (restriction.isPresent()) {
            filter = filter.and((root, query, builder) -> root.get("facilityId").in(restriction.get()));
        }
        Page<ProductionBatch> page = batches.findAll(filter, pageable);
        Map<UUID, ProductReference> referenced = products.references(page.map(ProductionBatch::getProductId).toSet());
        return PageResponse.from(page, batch -> toView(batch, referenced.get(batch.getProductId())));
    }

    @Transactional(readOnly = true)
    public Optional<ProductionBatchView> byNumber(String batchNumber) {
        return batches.findByBatchNumber(batchNumber).map(this::view);
    }

    @Transactional(readOnly = true)
    public ProductionBatchView batch(UUID id) {
        return view(load(id));
    }

    @Transactional(readOnly = true)
    public List<ProductionBatchView> views(Collection<UUID> ids) {
        List<ProductionBatch> found = batches.findAllById(ids);
        Map<UUID, ProductReference> referenced = products.references(
                found.stream().map(ProductionBatch::getProductId).collect(Collectors.toSet()));
        return found.stream().map(batch -> toView(batch, referenced.get(batch.getProductId()))).toList();
    }

    @Transactional(readOnly = true)
    public BatchDetail detail(UUID id) {
        ProductionBatch batch = load(id);
        scope.require(Set.of(batch.getFacilityId()), "Batch", id.toString());
        return new BatchDetail(view(batch), consumptions(batch.getId()), serials(batch.getId()));
    }

    @Transactional
    public BatchDetail create(CreateBatchRequest request) {
        products.requireExists(request.productId());
        facilities.requireActive(request.facilityId());
        ProductionBatch batch = batches.saveAndFlush(new ProductionBatch(
                numbers.next("PB"), request.productId(), request.facilityId(), request.plannedQuantity()));
        audit.record("PRODUCTION_BATCH_STARTED", ENTITY, batch.getId(), null, Map.of("number", batch.getBatchNumber()));
        return detail(batch.getId());
    }

    /** Locks the source, so that two batches drawing on the same lot cannot both take its last kilogram. */
    @Transactional
    public BatchDetail consume(UUID id, ConsumeRequest request) {
        if ((request.receiptItemId() == null) == (request.parentBatchId() == null)) {
            throw new BusinessRuleException("Choose one lot or batch to consume.");
        }
        ProductionBatch batch = lock(id);
        batch.requireInProgress();
        if (request.receiptItemId() != null) {
            receipts.consume(request.receiptItemId(), request.quantity());
        } else {
            if (request.parentBatchId().equals(id)) {
                throw new BusinessRuleException("A batch cannot consume itself.");
            }
            lock(request.parentBatchId()).use(request.quantity());
        }
        relationships.save(new BatchRelationship(
                request.receiptItemId(), request.parentBatchId(), id, request.quantity()));
        audit.record("BATCH_CONSUMED", ENTITY, id, null, Map.of("quantity", request.quantity()));
        return detail(id);
    }

    /**
     * Undoes a mistaken entry while the batch is still in progress. The stock returns to its source, and the entry
     * stays on record with the reason. Afterwards the batch's genealogy no longer includes that source.
     */
    @Transactional
    public BatchDetail reverseConsumption(UUID id, UUID relationshipId, String reason) {
        lock(id).requireInProgress();
        BatchRelationship edge = relationships.findById(relationshipId)
                .filter(found -> found.getChildBatchId().equals(id))
                .orElseThrow(() -> new ResourceNotFoundException("Consumption", relationshipId));
        if (corrections.existsByBatchRelationshipId(relationshipId)) {
            throw new BusinessRuleException("This entry was already reversed.");
        }
        if (edge.getParentLotId() != null) {
            receipts.restore(edge.getParentLotId(), edge.getQuantity());
        } else {
            lock(edge.getParentBatchId()).returnUsed(edge.getQuantity());
        }
        corrections.save(TraceCorrection.ofConsumption(relationshipId, reason.trim()));
        audit.record("BATCH_CONSUMPTION_REVERSED", ENTITY, id, null, Map.of("reason", reason.trim()));
        return detail(id);
    }

    /**
     * Applies an approved correction of material used, at any stage of the batch. The old entry is reversed rather
     * than edited, and the replacement joins the genealogy, so every trace that passes through this batch follows it.
     *
     * @param replacementLot the lot actually used, or null to keep the same source
     * @return the replacement entry
     */
    @Transactional
    public UUID replaceConsumption(UUID relationshipId, UUID replacementLot, BigDecimal quantity, String reason) {
        BatchRelationship edge = relationships.findById(relationshipId)
                .orElseThrow(() -> new ResourceNotFoundException("Consumption", relationshipId));
        lock(edge.getChildBatchId());
        if (corrections.existsByBatchRelationshipId(relationshipId)) {
            throw new BusinessRuleException("This entry was already reversed.");
        }
        if (edge.getParentLotId() != null) {
            receipts.restore(edge.getParentLotId(), edge.getQuantity());
        } else {
            lock(edge.getParentBatchId()).returnUsed(edge.getQuantity());
        }
        UUID lot = replacementLot != null ? replacementLot : edge.getParentLotId();
        if (lot != null) {
            receipts.consume(lot, quantity);
        } else {
            lock(edge.getParentBatchId()).use(quantity);
        }
        corrections.save(TraceCorrection.ofConsumption(relationshipId, reason));
        BatchRelationship replacement = relationships.save(new BatchRelationship(
                lot, lot == null ? edge.getParentBatchId() : null, edge.getChildBatchId(), quantity));
        audit.record("BATCH_CONSUMPTION_CORRECTED", ENTITY, edge.getChildBatchId(), null, Map.of("reason", reason));
        return replacement.getId();
    }

    @Transactional
    public BatchDetail complete(UUID id, CompleteBatchRequest request) {
        ProductionBatch batch = lock(id);
        if (relationships.findActiveByChildBatchIdIn(List.of(id)).isEmpty()) {
            throw new BusinessRuleException("Record what went into this batch first, or it cannot be traced.");
        }
        batch.complete(request.producedQuantity(), Instant.now());
        audit.record("PRODUCTION_BATCH_COMPLETED", ENTITY, id, null, Map.of("produced", request.producedQuantity()));
        return detail(id);
    }

    @Transactional
    public BatchDetail addSerials(UUID id, AddSerialsRequest request) {
        ProductionBatch batch = lock(id);
        if (batch.getProducedQuantity() == null) {
            throw new BusinessRuleException("Register serial numbers once the batch is complete.");
        }
        Set<String> serials = new HashSet<>(request.serialNumbers().stream().map(String::trim).toList());
        long total = finishedProducts.countByProductionBatchId(id) + serials.size();
        if (BigDecimal.valueOf(total).compareTo(batch.getProducedQuantity()) > 0) {
            throw new BusinessRuleException("This batch has more serial numbers than units.");
        }
        if (!finishedProducts.findBySerialNumberIn(serials).isEmpty()) {
            throw new ConflictException("One of these serial numbers is already registered.");
        }
        serials.forEach(serial -> finishedProducts.save(new FinishedProduct(serial, id)));
        audit.record("SERIALS_REGISTERED", ENTITY, id, null, Map.of("count", serials.size()));
        return detail(id);
    }

    /** Joins the inspection's transaction: an inspection cannot be recorded without its effect on the batch. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireInspectable(UUID id, String inspectionType) {
        lock(id).requireInspectable(inspectionType);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInspectionOutcome(UUID id, String inspectionType, String disposition) {
        ProductionBatch batch = lock(id);
        ProductionBatch.Status previous = batch.getStatus();
        batch.applyInspectionOutcome(inspectionType, disposition);
        batches.saveAndFlush(batch);
        audit.record("PRODUCTION_BATCH_STATUS_CHANGED", ENTITY, id,
                Map.of("status", previous), Map.of("status", batch.getStatus()));
    }

    /** @return the consumption, as long as it still stands */
    @Transactional(readOnly = true)
    public Optional<ConsumptionRef> activeConsumption(UUID relationshipId) {
        return relationships.findById(relationshipId)
                .filter(edge -> !corrections.existsByBatchRelationshipId(relationshipId))
                .map(edge -> new ConsumptionRef(
                        edge.getId(), edge.getChildBatchId(), edge.getParentLotId(), edge.getParentBatchId(), edge.getQuantity()));
    }

    private List<Consumption> consumptions(UUID id) {
        List<BatchRelationship> edges = relationships.findByChildBatchIdInOrderByCreatedAt(List.of(id));
        Map<UUID, LotView> lots = receipts.lots(edges.stream()
                        .map(BatchRelationship::getParentLotId).filter(java.util.Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(LotView::itemId, Function.identity()));
        Map<UUID, ProductionBatchView> parents = views(edges.stream()
                        .map(BatchRelationship::getParentBatchId).filter(java.util.Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(ProductionBatchView::id, Function.identity()));
        Map<UUID, String> reasons = corrections
                .findByBatchRelationshipIdIn(edges.stream().map(BatchRelationship::getId).toList()).stream()
                .collect(Collectors.toMap(TraceCorrection::getBatchRelationshipId, TraceCorrection::getReason));
        return edges.stream().map(edge -> {
            String reason = reasons.get(edge.getId());
            if (edge.getParentLotId() != null) {
                LotView lot = lots.get(edge.getParentLotId());
                return new Consumption(edge.getId(), lot.internalBatch(),
                        lot.materialCode() + " – " + lot.materialName(), edge.getQuantity(), edge.getCreatedAt(), reason);
            }
            ProductionBatchView parent = parents.get(edge.getParentBatchId());
            return new Consumption(edge.getId(), parent.batchNumber(),
                    parent.productCode() + " – " + parent.productName(), edge.getQuantity(), edge.getCreatedAt(), reason);
        }).toList();
    }

    private List<String> serials(UUID id) {
        return finishedProducts.findByProductionBatchIdOrderBySerialNumber(id).stream()
                .map(FinishedProduct::getSerialNumber)
                .toList();
    }

    private ProductionBatchView view(ProductionBatch batch) {
        return toView(batch, products.references(Set.of(batch.getProductId())).get(batch.getProductId()));
    }

    private static ProductionBatchView toView(ProductionBatch batch, ProductReference product) {
        return new ProductionBatchView(
                batch.getId(),
                batch.getBatchNumber(),
                batch.getProductId(),
                product.code(),
                product.name(),
                batch.getFacilityId(),
                batch.getStatus().name(),
                batch.getPlannedQuantity(),
                batch.getProducedQuantity(),
                batch.availableQuantity(),
                batch.getCreatedAt(),
                batch.getCompletedAt());
    }

    private ProductionBatch load(UUID id) {
        return batches.findById(id).orElseThrow(() -> new ResourceNotFoundException("Production batch", id));
    }

    private ProductionBatch lock(UUID id) {
        return batches.findForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Production batch", id));
    }
}
