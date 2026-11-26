package com.qualityos.traceability.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.common.persistence.DocumentNumberService;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.LotView;
import com.qualityos.materialreceipt.service.MaterialReceiptService;
import com.qualityos.organization.service.FacilityService;
import com.qualityos.organization.service.OrganizationService;
import com.qualityos.traceability.dto.TraceabilityDtos.CreateTransferRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.ProductionBatchView;
import com.qualityos.traceability.dto.TraceabilityDtos.TransferLine;
import com.qualityos.traceability.dto.TraceabilityDtos.TransferLineRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.TransferResponse;
import com.qualityos.traceability.entity.BatchRelationship;
import com.qualityos.traceability.entity.ProductionBatch;
import com.qualityos.traceability.entity.StockTransfer;
import com.qualityos.traceability.entity.StockTransferItem;
import com.qualityos.traceability.repository.BatchRelationshipRepository;
import com.qualityos.traceability.repository.ProductionBatchRepository;
import com.qualityos.traceability.repository.StockTransferItemRepository;
import com.qualityos.traceability.repository.StockTransferRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Moves stock between plants. Sending takes the quantity off its lot or batch at once, so nothing in transit can be
 * used or shipped twice. Receiving turns each line into a lot or batch of its own at the other plant, numbered after
 * where it came from, with the same inspection result. A batch received this way is recorded as made from the batch it
 * came from, so traces, recalls and holds reach it; a lot received this way names its source lot, which traces follow.
 */
@Service
public class StockTransferService {

    private static final String ENTITY = "StockTransfer";
    private static final String RESOURCE = "Stock transfer";

    private final StockTransferRepository transfers;
    private final StockTransferItemRepository lines;
    private final ProductionBatchRepository batches;
    private final BatchRelationshipRepository relationships;
    private final MaterialReceiptService receipts;
    private final ProductionService production;
    private final FacilityService facilities;
    private final FacilityScope scope;
    private final OrganizationService organizations;
    private final DocumentNumberService numbers;
    private final AuditService audit;

    StockTransferService(
            StockTransferRepository transfers,
            StockTransferItemRepository lines,
            ProductionBatchRepository batches,
            BatchRelationshipRepository relationships,
            MaterialReceiptService receipts,
            ProductionService production,
            FacilityService facilities,
            FacilityScope scope,
            OrganizationService organizations,
            DocumentNumberService numbers,
            AuditService audit) {
        this.transfers = transfers;
        this.lines = lines;
        this.batches = batches;
        this.relationships = relationships;
        this.receipts = receipts;
        this.production = production;
        this.facilities = facilities;
        this.scope = scope;
        this.organizations = organizations;
        this.numbers = numbers;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<TransferResponse> list(StockTransfer.Status status, UUID facilityId, Pageable pageable) {
        Specification<StockTransfer> filter = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (facilityId != null) {
            filter = filter.and((root, query, cb) -> cb.or(
                    cb.equal(root.get("fromFacilityId"), facilityId), cb.equal(root.get("toFacilityId"), facilityId)));
        }
        Optional<Set<UUID>> restriction = scope.restriction();
        if (restriction.isPresent()) {
            Set<UUID> allowed = restriction.get();
            filter = filter.and((root, query, cb) -> cb.or(
                    root.get("fromFacilityId").in(allowed), root.get("toFacilityId").in(allowed)));
        }
        Page<StockTransfer> page = transfers.findAll(filter, pageable);
        Map<UUID, TransferResponse> views = responses(page.getContent());
        return PageResponse.from(page, transfer -> views.get(transfer.getId()));
    }

    @Transactional(readOnly = true)
    public TransferResponse get(UUID id) {
        StockTransfer transfer = load(id);
        return responses(List.of(transfer)).get(transfer.getId());
    }

    /** Sends the stock. The sender must be able to reach the plant it leaves from. */
    @Transactional
    public TransferResponse send(CreateTransferRequest request) {
        UUID from = request.fromFacilityId();
        UUID to = request.toFacilityId();
        facilities.requireActive(from);
        facilities.requireActive(to);
        scope.requireAllowed(from, "Facility", from.toString());
        if (request.sentOn().isAfter(today())) {
            throw new BusinessRuleException("A transfer cannot be dated in the future.");
        }
        Set<UUID> seen = new HashSet<>();
        for (TransferLineRequest line : request.items()) {
            if ((line.lotId() == null) == (line.batchId() == null)) {
                throw new BusinessRuleException("Each line names either a lot or a batch.");
            }
            if (!seen.add(line.lotId() != null ? line.lotId() : line.batchId())) {
                throw new BusinessRuleException("Each lot or batch can appear once on a transfer.");
            }
        }
        StockTransfer transfer = transfers.saveAndFlush(new StockTransfer(numbers.next("STR"), from, to,
                request.sentOn(), StringUtils.hasText(request.note()) ? request.note().trim() : null));
        // Locks are taken in one order, so two transfers of the same stock never wait on each other.
        request.items().stream()
                .sorted(java.util.Comparator.comparing(line -> line.lotId() != null ? line.lotId() : line.batchId()))
                .forEach(line -> {
                    if (line.lotId() != null) {
                        LotView lot = receipts.transferOut(line.lotId(), line.quantity());
                        if (!lot.facilityId().equals(from)) {
                            throw new BusinessRuleException(
                                    "Lot " + lot.internalBatch() + " is not at the plant it is sent from.");
                        }
                        lines.save(StockTransferItem.ofLot(transfer.getId(), line.lotId(), line.quantity()));
                    } else {
                        ProductionBatch batch = lockBatch(line.batchId());
                        if (!batch.getFacilityId().equals(from)) {
                            throw new BusinessRuleException(
                                    "Batch " + batch.getBatchNumber() + " is not at the plant it is sent from.");
                        }
                        production.requireWholeOfProduct(batch.getProductId(), line.quantity());
                        batch.transferOut(line.quantity());
                        lines.save(StockTransferItem.ofBatch(transfer.getId(), line.batchId(), line.quantity()));
                    }
                });
        lines.flush();
        batches.flush();
        audit.record("STOCK_TRANSFER_SENT", ENTITY, transfer.getId(), null, Map.of(
                "number", transfer.getTransferNumber(), "lines", request.items().size()));
        return get(transfer.getId());
    }

    /** Receives everything on the transfer at the plant it was sent to. The receiver must be able to reach it. */
    @Transactional
    public TransferResponse receive(UUID id, LocalDate receivedOn) {
        StockTransfer transfer = lock(id);
        scope.requireAllowed(transfer.getToFacilityId(), RESOURCE, id.toString());
        if (receivedOn.isAfter(today())) {
            throw new BusinessRuleException("Goods cannot be received in the future.");
        }
        transfer.receive(receivedOn, TenantContext.getCurrentUserId());
        Instant now = Instant.now();
        for (StockTransferItem line : lines.findByTransferIdInOrderByCreatedAt(List.of(id))) {
            if (line.getSourceLotId() != null) {
                LotView lot = receipts.receiveTransferred(line.getSourceLotId(), line.getQuantity(),
                        transfer.getToFacilityId());
                line.receivedAsLot(lot.itemId());
            } else {
                // The receiving plant may not reach the plant the batch left; the transfer already says it may have this.
                ProductionBatch source = batches.findForUpdate(line.getSourceBatchId()).orElseThrow();
                String number = source.getBatchNumber() + "-T" + (batches.countByTransferredFromId(source.getId()) + 1);
                ProductionBatch received = batches.saveAndFlush(ProductionBatch.receivedFrom(
                        source, number, line.getQuantity(), transfer.getToFacilityId(), now));
                relationships.save(new BatchRelationship(null, source.getId(), received.getId(), line.getQuantity()));
                line.receivedAsBatch(received.getId());
            }
            lines.save(line);
        }
        lines.flush();
        transfers.saveAndFlush(transfer);
        audit.record("STOCK_TRANSFER_RECEIVED", ENTITY, id, null, Map.of(
                "number", transfer.getTransferNumber(), "receivedOn", receivedOn.toString()));
        return get(id);
    }

    /** For goods that never left, or came back: the stock returns to where it was sent from. */
    @Transactional
    public TransferResponse cancel(UUID id, String reason) {
        StockTransfer transfer = lock(id);
        scope.requireAllowed(transfer.getFromFacilityId(), RESOURCE, id.toString());
        transfer.cancel(reason.trim());
        for (StockTransferItem line : lines.findByTransferIdInOrderByCreatedAt(List.of(id))) {
            if (line.getSourceLotId() != null) {
                receipts.returnTransferred(line.getSourceLotId(), line.getQuantity());
            } else {
                batches.findForUpdate(line.getSourceBatchId()).orElseThrow().returnTransferred(line.getQuantity());
            }
        }
        batches.flush();
        transfers.saveAndFlush(transfer);
        audit.record("STOCK_TRANSFER_CANCELLED", ENTITY, id, null, Map.of(
                "number", transfer.getTransferNumber(), "reason", reason.trim()));
        return get(id);
    }

    private ProductionBatch lockBatch(UUID id) {
        ProductionBatch batch = batches.findForUpdate(id)
                .orElseThrow(() -> new BusinessRuleException("This batch does not exist."));
        scope.requireAllowed(batch.getFacilityId(), "Batch", id.toString());
        return batch;
    }

    /** Either end of the transfer is enough to see it. */
    private StockTransfer load(UUID id) {
        StockTransfer transfer = transfers.findById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        requireEitherEnd(transfer);
        return transfer;
    }

    private StockTransfer lock(UUID id) {
        StockTransfer transfer = transfers.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        requireEitherEnd(transfer);
        return transfer;
    }

    private void requireEitherEnd(StockTransfer transfer) {
        if (!scope.allows(transfer.getFromFacilityId()) && !scope.allows(transfer.getToFacilityId())) {
            throw new ResourceNotFoundException(RESOURCE, transfer.getId());
        }
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of(organizations.getCurrent().timezone()));
    }

    private Map<UUID, TransferResponse> responses(Collection<StockTransfer> found) {
        if (found.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<StockTransferItem>> byTransfer = lines.findByTransferIdInOrderByCreatedAt(
                found.stream().map(StockTransfer::getId).toList()).stream()
                .collect(Collectors.groupingBy(StockTransferItem::getTransferId));
        List<StockTransferItem> all = byTransfer.values().stream().flatMap(List::stream).toList();
        Map<UUID, LotView> lots = receipts.lots(all.stream()
                        .flatMap(line -> Stream.of(line.getSourceLotId(), line.getReceivedLotId()))
                        .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(LotView::itemId, Function.identity()));
        Map<UUID, ProductionBatchView> views = production.views(all.stream()
                        .flatMap(line -> Stream.of(line.getSourceBatchId(), line.getReceivedBatchId()))
                        .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(ProductionBatchView::id, Function.identity()));
        Map<UUID, String> places = facilities.names(found.stream()
                .flatMap(transfer -> Stream.of(transfer.getFromFacilityId(), transfer.getToFacilityId()))
                .collect(Collectors.toSet()));
        Map<UUID, TransferResponse> responses = new HashMap<>();
        for (StockTransfer transfer : found) {
            List<TransferLine> items = byTransfer.getOrDefault(transfer.getId(), List.of()).stream()
                    .map(line -> line(line, lots, views)).toList();
            responses.put(transfer.getId(), new TransferResponse(
                    transfer.getId(),
                    transfer.getTransferNumber(),
                    transfer.getFromFacilityId(),
                    places.get(transfer.getFromFacilityId()),
                    transfer.getToFacilityId(),
                    places.get(transfer.getToFacilityId()),
                    transfer.getStatus().name(),
                    transfer.getSentOn(),
                    transfer.getReceivedOn(),
                    transfer.getNote(),
                    transfer.getCancelReason(),
                    transfer.getCreatedAt(),
                    items));
        }
        return responses;
    }

    private static TransferLine line(
            StockTransferItem line, Map<UUID, LotView> lots, Map<UUID, ProductionBatchView> views) {
        if (line.getSourceLotId() != null) {
            LotView source = lots.get(line.getSourceLotId());
            LotView received = line.getReceivedLotId() == null ? null : lots.get(line.getReceivedLotId());
            return new TransferLine("LOT", source.itemId(), source.internalBatch(), source.materialCode(),
                    source.materialName(), line.getQuantity(), source.uomId(),
                    received == null ? null : received.itemId(), received == null ? null : received.internalBatch());
        }
        ProductionBatchView source = views.get(line.getSourceBatchId());
        ProductionBatchView received = line.getReceivedBatchId() == null ? null : views.get(line.getReceivedBatchId());
        return new TransferLine("BATCH", source.id(), source.batchNumber(), source.productCode(), source.productName(),
                line.getQuantity(), source.uomId(),
                received == null ? null : received.id(), received == null ? null : received.batchNumber());
    }
}
