package com.qualityos.traceability.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.DocumentNumberService;
import com.qualityos.common.response.PageResponse;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.organization.service.OrganizationService;
import com.qualityos.traceability.dto.TraceabilityDtos.CreateReversalRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.ProductionBatchView;
import com.qualityos.traceability.dto.TraceabilityDtos.ReturnLineRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.ReturnedGoods;
import com.qualityos.traceability.dto.TraceabilityDtos.ReversalItemView;
import com.qualityos.traceability.dto.TraceabilityDtos.ReversalSummary;
import com.qualityos.traceability.dto.TraceabilityDtos.ReversalView;
import com.qualityos.traceability.entity.Customer;
import com.qualityos.traceability.entity.DispatchItem;
import com.qualityos.traceability.entity.DispatchRecord;
import com.qualityos.traceability.entity.DispatchReversal;
import com.qualityos.traceability.entity.DispatchReversalItem;
import com.qualityos.traceability.entity.DispatchShipment;
import com.qualityos.traceability.entity.FinishedProduct;
import com.qualityos.traceability.repository.CustomerRepository;
import com.qualityos.traceability.repository.DispatchItemRepository;
import com.qualityos.traceability.repository.DispatchRecordRepository;
import com.qualityos.traceability.repository.DispatchReversalItemRepository;
import com.qualityos.traceability.repository.DispatchReversalRepository;
import com.qualityos.traceability.repository.DispatchShipmentRepository;
import com.qualityos.traceability.repository.FinishedProductRepository;
import com.qualityos.traceability.repository.ProductionBatchRepository;
import com.qualityos.traceability.repository.TraceCorrectionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Physical returns against a dispatch. Goods wait in quarantine until an inspection decides them, and only an
 * accepted decision puts them back in stock. Credit notes and refunds are handled elsewhere.
 */
@Service
public class DispatchReturnService {

    private static final String ENTITY = "DispatchReversal";

    private final DispatchReversalRepository reversals;
    private final DispatchReversalItemRepository reversalItems;
    private final DispatchRecordRepository dispatches;
    private final DispatchItemRepository dispatchItems;
    private final CustomerRepository customers;
    private final ProductionBatchRepository batches;
    private final FinishedProductRepository finishedProducts;
    private final TraceCorrectionRepository corrections;
    private final DispatchShipmentRepository shipments;
    private final ProductionService production;
    private final OrganizationService organizations;
    private final TenantAccessService access;
    private final DocumentNumberService numbers;
    private final AuditService audit;
    private final FacilityScope scope;

    DispatchReturnService(
            DispatchReversalRepository reversals,
            DispatchReversalItemRepository reversalItems,
            DispatchRecordRepository dispatches,
            DispatchItemRepository dispatchItems,
            CustomerRepository customers,
            ProductionBatchRepository batches,
            FinishedProductRepository finishedProducts,
            TraceCorrectionRepository corrections,
            DispatchShipmentRepository shipments,
            ProductionService production,
            OrganizationService organizations,
            TenantAccessService access,
            DocumentNumberService numbers,
            AuditService audit,
            FacilityScope scope) {
        this.scope = scope;
        this.reversals = reversals;
        this.reversalItems = reversalItems;
        this.dispatches = dispatches;
        this.dispatchItems = dispatchItems;
        this.customers = customers;
        this.batches = batches;
        this.finishedProducts = finishedProducts;
        this.corrections = corrections;
        this.shipments = shipments;
        this.production = production;
        this.organizations = organizations;
        this.access = access;
        this.numbers = numbers;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<ReversalView> list(Pageable pageable) {
        Page<DispatchReversal> page = scope.restriction()
                .map(allowed -> reversals.findWithin(allowed, pageable))
                .orElseGet(() -> reversals.findAll(pageable));
        Map<UUID, ReversalView> views = views(page.getContent()).stream()
                .collect(Collectors.toMap(ReversalView::id, Function.identity()));
        return PageResponse.from(page, reversal -> views.get(reversal.getId()));
    }

    @Transactional(readOnly = true)
    public ReversalView get(UUID id) {
        return views(List.of(load(id))).getFirst();
    }

    @Transactional(readOnly = true)
    public List<ReversalView> forDispatch(UUID dispatchId) {
        requireDispatch(dispatchId);
        return forDispatches(List.of(dispatchId));
    }

    @Transactional(readOnly = true)
    public List<ReversalView> forDispatches(Collection<UUID> dispatchIds) {
        return dispatchIds.isEmpty() ? List.of() : views(reversals.findByDispatchIdInOrderByCreatedAt(dispatchIds));
    }

    /** Locks the dispatch first, so that two returns are counted one after the other and neither races a cancellation. */
    @Transactional
    public ReversalView create(UUID dispatchId, CreateReversalRequest request) {
        DispatchRecord dispatch = dispatches.findForUpdate(dispatchId)
                .orElseThrow(() -> new ResourceNotFoundException("Dispatch", dispatchId));
        scope.require(dispatchItems.facilitiesOf(dispatchId), "Dispatch", dispatchId.toString());
        if (corrections.existsByDispatchId(dispatchId)) {
            throw new BusinessRuleException("This dispatch was reversed as a mistake, so nothing can come back.");
        }
        DispatchShipment shipment = shipments.findByDispatchId(dispatchId).orElseThrow(() ->
                new BusinessRuleException("Confirm this shipment before recording a return against it."));
        String reference = request.returnReference().trim();
        LocalDate today = LocalDate.now(ZoneId.of(organizations.getCurrent().timezone()));
        if (request.reversalDate().isBefore(shipment.getShippedOn())
                || (!request.isExpected() && request.reversalDate().isAfter(today))) {
            throw new BusinessRuleException("The return date must fall between the shipment and today.");
        }
        Map<UUID, DispatchItem> lines = dispatchItems.findByDispatchIdIn(List.of(dispatchId)).stream()
                .collect(Collectors.toMap(DispatchItem::getId, Function.identity()));
        request.items().forEach(line -> {
            if (!lines.containsKey(line.dispatchItemId())) {
                throw new BusinessRuleException("A returned line does not belong to this dispatch.");
            }
        });
        lines.values().stream()
                .map(DispatchItem::getProductionBatchId)
                .distinct()
                .sorted()
                .forEach(batch -> batches.findForUpdate(batch).orElseThrow());
        if (reversals.existsByDispatchIdAndReturnReference(dispatchId, reference)) {
            throw new ConflictException("Return " + reference + " is already recorded against this dispatch.");
        }
        Map<UUID, BigDecimal> requested = request.items().stream().collect(Collectors.toMap(
                ReturnLineRequest::dispatchItemId, ReturnLineRequest::quantity, BigDecimal::add));
        List<DispatchReversalItem> earlier = reversalItems.findByDispatchItemIdIn(lines.keySet()).stream()
                .filter(item -> !item.isCancelled())
                .toList();
        requested.forEach((lineId, quantity) -> {
            BigDecimal already = earlier.stream()
                    .filter(item -> item.getDispatchItemId().equals(lineId))
                    .map(DispatchReversalItem::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (already.add(quantity).compareTo(lines.get(lineId).getQuantity()) > 0) {
                throw new BusinessRuleException("You cannot return more than was dispatched.");
            }
        });
        DispatchReversal reversal = reversals.saveAndFlush(new DispatchReversal(
                numbers.next("RET"), dispatchId, reference, request.reversalDate(), request.reason().trim(),
                request.isExpected()));
        Set<String> alreadyReturned = earlier.stream()
                .flatMap(item -> item.getSerialNumbers().stream())
                .collect(Collectors.toCollection(HashSet::new));
        request.items().forEach(line -> {
            List<String> serials = line.serialNumbers() == null ? List.of() : line.serialNumbers();
            requireSerials(line, serials, alreadyReturned);
            alreadyReturned.addAll(serials);
            reversalItems.save(new DispatchReversalItem(reversal.getId(), line.dispatchItemId(),
                    lines.get(line.dispatchItemId()).getProductionBatchId(), line.quantity(), serials, request.isExpected()));
        });
        reversalItems.flush();
        audit.record("DISPATCH_RETURN_RECORDED", ENTITY, reversal.getId(), null,
                Map.of("number", reversal.getReversalNumber(), "dispatch", dispatch.getDispatchNumber(),
                        "reference", reference));
        return views(List.of(reversal)).getFirst();
    }

    /** Physical receipt of an announced return. From here the goods wait in quarantine for an inspection. */
    @Transactional
    public ReversalView receive(UUID id, LocalDate receivedOn) {
        DispatchReversal reversal = requireExpected(id);
        DispatchRecord dispatch = dispatches.findForUpdate(reversal.getDispatchId()).orElseThrow();
        LocalDate shippedOn = shipments.findByDispatchId(dispatch.getId()).orElseThrow().getShippedOn();
        LocalDate today = LocalDate.now(ZoneId.of(organizations.getCurrent().timezone()));
        if (receivedOn.isAfter(today) || receivedOn.isBefore(shippedOn)) {
            throw new BusinessRuleException("The return date must fall between the shipment and today.");
        }
        reversal.received(receivedOn);
        reversalItems.findByReversalIdIn(List.of(id)).forEach(DispatchReversalItem::received);
        reversals.flush();
        audit.record("DISPATCH_RETURN_RECEIVED", ENTITY, id);
        return views(List.of(reversal)).getFirst();
    }

    /** The goods never came, so their quantity stops counting against the dispatch. */
    @Transactional
    public ReversalView cancel(UUID id) {
        DispatchReversal reversal = requireExpected(id);
        dispatches.findForUpdate(reversal.getDispatchId()).orElseThrow();
        reversalItems.findByReversalIdIn(List.of(id)).forEach(DispatchReversalItem::cancel);
        reversal.cancel(Instant.now());
        reversals.flush();
        audit.record("DISPATCH_RETURN_CANCELLED", ENTITY, id);
        return views(List.of(reversal)).getFirst();
    }

    private DispatchReversal requireExpected(UUID id) {
        DispatchReversal reversal = load(id);
        if (reversal.getStatus() != DispatchReversal.Status.EXPECTED) {
            throw new BusinessRuleException("This return is not waiting to arrive.");
        }
        return reversal;
    }

    /** The dispatch lines this serial number came back from. */
    @Transactional(readOnly = true)
    public Set<UUID> returnedLines(String serial) {
        return reversalItems.findBySerial(serial).stream()
                .filter(item -> item.getStatus() != DispatchReversalItem.Status.EXPECTED)
                .map(DispatchReversalItem::getDispatchItemId)
                .collect(Collectors.toSet());
    }

    private void requireSerials(ReturnLineRequest line, List<String> serials, Set<String> alreadyReturned) {
        if (serials.isEmpty()) {
            return;
        }
        if (BigDecimal.valueOf(serials.size()).compareTo(line.quantity()) != 0) {
            throw new BusinessRuleException("The number of serial numbers must match the quantity.");
        }
        List<FinishedProduct> units = finishedProducts.findBySerialNumberIn(serials);
        boolean valid = units.size() == serials.size()
                && units.stream().allMatch(unit -> line.dispatchItemId().equals(unit.getDispatchItemId()))
                && serials.stream().noneMatch(alreadyReturned::contains);
        if (!valid) {
            throw new BusinessRuleException("A serial number was not on this line, or has already come back.");
        }
    }

    @Transactional(readOnly = true)
    public ReversalSummary summary(UUID dispatchId) {
        requireDispatch(dispatchId);
        List<DispatchItem> lines = dispatchItems.findByDispatchIdIn(List.of(dispatchId));
        List<DispatchReversalItem> all = reversalItems.findByDispatchItemIdIn(
                lines.stream().map(DispatchItem::getId).toList());
        List<DispatchReversalItem> returned = all.stream()
                .filter(item -> !item.isCancelled() && item.getStatus() != DispatchReversalItem.Status.EXPECTED)
                .toList();
        BigDecimal expected = total(all, DispatchReversalItem.Status.EXPECTED);
        BigDecimal dispatched = lines.stream().map(DispatchItem::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reversed = total(returned, null);
        BigDecimal accepted = total(returned, DispatchReversalItem.Status.ACCEPTED);
        BigDecimal rejected = total(returned, DispatchReversalItem.Status.REJECTED);
        String status = reversed.signum() == 0
                ? "NOT_REVERSED"
                : reversed.compareTo(dispatched) == 0 ? "FULLY_REVERSED" : "PARTIALLY_REVERSED";
        return new ReversalSummary(dispatchId, status, dispatched, reversed, dispatched.subtract(reversed),
                expected, reversed.subtract(accepted).subtract(rejected), accepted, rejected);
    }

    private static BigDecimal total(List<DispatchReversalItem> items, DispatchReversalItem.Status status) {
        return items.stream()
                .filter(item -> status == null || item.getStatus() == status)
                .map(DispatchReversalItem::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Joins the inspection's transaction: an inspection cannot start without marking the goods as under inspection. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReturnedGoods startInspection(UUID itemId) {
        DispatchReversalItem item = lockItem(itemId);
        item.inspectionStarted();
        reversals.findById(item.getReversalId()).orElseThrow().inspectionStarted();
        return new ReturnedGoods(item.getId(), item.getProductionBatchId(), item.getQuantity());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void inspectionCancelled(UUID itemId) {
        lockItem(itemId).inspectionCancelled();
    }

    /**
     * Only an accepted decision returns stock. Rejected or reworked goods stay out of it, and a hold leaves them
     * waiting. A send-back has no disposition, and changes nothing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInspectionOutcome(UUID itemId, String disposition, boolean finalDecision) {
        DispatchReversalItem item = lockItem(itemId);
        if (!finalDecision) {
            item.held(disposition);
            return;
        }
        boolean accepted = "ACCEPT".equals(disposition) || "CONDITIONAL_ACCEPTANCE".equals(disposition);
        item.decide(accepted, disposition);
        if (accepted) {
            batches.findForUpdate(item.getProductionBatchId()).orElseThrow().returnDispatched(item.getQuantity());
            finishedProducts.findBySerialNumberIn(item.getSerialNumbers())
                    .forEach(unit -> unit.setDispatchItemId(null));
        }
        audit.record("RETURNED_GOODS_DECIDED", ENTITY, item.getReversalId(), null,
                Map.of("disposition", disposition, "quantity", item.getQuantity()));
        reversalItems.flush();
        List<DispatchReversalItem> all = reversalItems.findByReversalIdIn(List.of(item.getReversalId()));
        if (all.stream().allMatch(DispatchReversalItem::isDecided)) {
            reversals.findById(item.getReversalId()).orElseThrow().close(Instant.now());
        }
    }

    private List<ReversalView> views(List<DispatchReversal> found) {
        Map<UUID, List<DispatchReversalItem>> items = reversalItems
                .findByReversalIdIn(found.stream().map(DispatchReversal::getId).toList()).stream()
                .collect(Collectors.groupingBy(DispatchReversalItem::getReversalId));
        Map<UUID, DispatchRecord> records = dispatches
                .findAllById(found.stream().map(DispatchReversal::getDispatchId).toList()).stream()
                .collect(Collectors.toMap(DispatchRecord::getId, Function.identity()));
        Map<UUID, Customer> named = customers
                .findAllById(records.values().stream().map(DispatchRecord::getCustomerId).toList()).stream()
                .collect(Collectors.toMap(Customer::getId, Function.identity()));
        Map<UUID, ProductionBatchView> made = production.views(items.values().stream()
                        .flatMap(List::stream)
                        .map(DispatchReversalItem::getProductionBatchId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList())
                .stream().collect(Collectors.toMap(ProductionBatchView::id, Function.identity()));
        Map<UUID, String> names = access.activeMembers();
        return found.stream()
                .sorted(Comparator.comparing(DispatchReversal::getCreatedAt))
                .map(reversal -> {
                    DispatchRecord dispatch = records.get(reversal.getDispatchId());
                    return new ReversalView(
                            reversal.getId(),
                            reversal.getReversalNumber(),
                            dispatch.getId(),
                            dispatch.getDispatchNumber(),
                            named.get(dispatch.getCustomerId()).getName(),
                            reversal.getReturnReference(),
                            reversal.getReversalDate(),
                            reversal.getReason(),
                            reversal.getStatus().name(),
                            names.get(reversal.getCreatedBy()),
                            reversal.getCreatedAt(),
                            reversal.getClosedAt(),
                            items.getOrDefault(reversal.getId(), List.of()).stream().map(item -> {
                                ProductionBatchView batch = made.get(item.getProductionBatchId());
                                return new ReversalItemView(item.getId(), item.getDispatchItemId(), batch.batchNumber(),
                                        batch.productCode(), item.getQuantity(), item.getSerialNumbers(),
                                        item.getStatus().name(), item.getDisposition());
                            }).toList());
                })
                .toList();
    }

    private DispatchRecord requireDispatch(UUID id) {
        DispatchRecord dispatch =
                dispatches.findById(id).orElseThrow(() -> new ResourceNotFoundException("Dispatch", id));
        scope.require(dispatchItems.facilitiesOf(id), "Dispatch", id.toString());
        return dispatch;
    }

    private DispatchReversal load(UUID id) {
        DispatchReversal reversal = reversals.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dispatch reversal", id));
        scope.require(dispatchItems.facilitiesOf(reversal.getDispatchId()), "Dispatch reversal", id.toString());
        return reversal;
    }

    private DispatchReversalItem lockItem(UUID id) {
        return reversalItems.findForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Returned goods", id));
    }
}
