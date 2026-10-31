package com.qualityos.traceability.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.DocumentNumberService;
import com.qualityos.common.response.PageResponse;
import com.qualityos.organization.service.OrganizationService;
import com.qualityos.traceability.dto.TraceabilityDtos.CreateDispatchRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.CustomerResponse;
import com.qualityos.traceability.dto.TraceabilityDtos.DispatchItemRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.DispatchLine;
import com.qualityos.traceability.dto.TraceabilityDtos.DispatchResponse;
import com.qualityos.traceability.dto.TraceabilityDtos.ProductionBatchView;
import com.qualityos.traceability.dto.TraceabilityDtos.SaveCustomerRequest;
import com.qualityos.traceability.entity.Customer;
import com.qualityos.traceability.entity.DispatchItem;
import com.qualityos.traceability.entity.DispatchRecord;
import com.qualityos.traceability.entity.DispatchReversalItem;
import com.qualityos.traceability.entity.FinishedProduct;
import com.qualityos.traceability.entity.ProductionBatch;
import com.qualityos.traceability.entity.TraceCorrection;
import com.qualityos.traceability.repository.CustomerRepository;
import com.qualityos.traceability.repository.DispatchItemRepository;
import com.qualityos.traceability.repository.DispatchRecordRepository;
import com.qualityos.traceability.repository.DispatchReversalItemRepository;
import com.qualityos.traceability.repository.DispatchReversalRepository;
import com.qualityos.traceability.repository.FinishedProductRepository;
import com.qualityos.traceability.repository.ProductionBatchRepository;
import com.qualityos.traceability.repository.TraceCorrectionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DispatchService {

    private final DispatchRecordRepository dispatches;
    private final DispatchItemRepository items;
    private final CustomerRepository customers;
    private final ProductionBatchRepository batches;
    private final FinishedProductRepository finishedProducts;
    private final TraceCorrectionRepository corrections;
    private final DispatchReversalRepository reversals;
    private final DispatchReversalItemRepository reversalItems;
    private final OrganizationService organizations;
    private final ProductionService production;
    private final DocumentNumberService numbers;
    private final AuditService audit;

    DispatchService(
            DispatchRecordRepository dispatches,
            DispatchItemRepository items,
            CustomerRepository customers,
            ProductionBatchRepository batches,
            FinishedProductRepository finishedProducts,
            TraceCorrectionRepository corrections,
            DispatchReversalRepository reversals,
            DispatchReversalItemRepository reversalItems,
            OrganizationService organizations,
            ProductionService production,
            DocumentNumberService numbers,
            AuditService audit) {
        this.dispatches = dispatches;
        this.items = items;
        this.customers = customers;
        this.batches = batches;
        this.finishedProducts = finishedProducts;
        this.corrections = corrections;
        this.reversals = reversals;
        this.reversalItems = reversalItems;
        this.organizations = organizations;
        this.production = production;
        this.numbers = numbers;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<CustomerResponse> customers() {
        return customers.findByArchivedAtIsNullOrderByCode().stream().map(DispatchService::toResponse).toList();
    }

    @Transactional
    public CustomerResponse addCustomer(SaveCustomerRequest request) {
        String code = request.code().trim();
        if (customers.existsByCode(code)) {
            throw new ConflictException("A customer with this code already exists.");
        }
        Customer customer = customers.save(new Customer(code, request.name().trim(), request.gstin()));
        audit.record("CUSTOMER_CREATED", "Customer", customer.getId());
        return toResponse(customer);
    }

    @Transactional
    public void archiveCustomer(UUID id) {
        customers.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", id))
                .setArchivedAt(Instant.now());
        audit.record("CUSTOMER_ARCHIVED", "Customer", id);
    }

    @Transactional(readOnly = true)
    public PageResponse<DispatchResponse> list(Pageable pageable) {
        Page<DispatchRecord> page = dispatches.findAll(pageable);
        return PageResponse.from(page, responses(page.getContent())::get);
    }

    /** Batches are locked in id order, so that two dispatches of the same batches cannot deadlock. */
    @Transactional
    public DispatchResponse create(CreateDispatchRequest request) {
        Customer customer = customers.findById(request.customerId())
                .filter(found -> found.getArchivedAt() == null)
                .orElseThrow(() -> new BusinessRuleException("This customer does not exist."));
        LocalDate today = LocalDate.now(ZoneId.of(organizations.getCurrent().timezone()));
        if (request.dispatchedOn().isAfter(today)) {
            throw new BusinessRuleException("A dispatch cannot be dated in the future.");
        }
        DispatchRecord dispatch = dispatches.saveAndFlush(new DispatchRecord(
                numbers.next("DSP"), customer.getId(), request.dispatchedOn(), request.reference()));
        request.items().stream()
                .sorted(Comparator.comparing(DispatchItemRequest::productionBatchId))
                .forEach(line -> ship(dispatch, line));
        audit.record("MATERIAL_DISPATCHED", "DispatchRecord", dispatch.getId(), null,
                Map.of("number", dispatch.getDispatchNumber(), "customer", customer.getCode()));
        return responses(List.of(dispatch)).get(dispatch);
    }

    /**
     * Undoes a dispatch that was recorded by mistake. Stock and serial numbers return to their batches, and the record
     * stays with the reason. Traces keep showing it as reversed, and no longer count its customer as affected.
     */
    @Transactional
    public DispatchResponse reverse(UUID id, String reason) {
        DispatchRecord dispatch = dispatches.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dispatch", id));
        if (corrections.existsByDispatchId(id)) {
            throw new BusinessRuleException("This dispatch was already reversed.");
        }
        if (reversals.existsByDispatchId(id)) {
            throw new BusinessRuleException("Goods have come back against this dispatch, so it really shipped.");
        }
        List<DispatchItem> lines = items.findByDispatchIdIn(List.of(id));
        lines.stream()
                .sorted(Comparator.comparing(DispatchItem::getProductionBatchId))
                .forEach(line -> batches.findForUpdate(line.getProductionBatchId())
                        .orElseThrow(() -> new ResourceNotFoundException("Production batch", line.getProductionBatchId()))
                        .returnDispatched(line.getQuantity()));
        finishedProducts.findByDispatchItemIdIn(lines.stream().map(DispatchItem::getId).toList())
                .forEach(unit -> unit.setDispatchItemId(null));
        corrections.saveAndFlush(TraceCorrection.ofDispatch(id, reason.trim()));
        audit.record("DISPATCH_REVERSED", "DispatchRecord", id, null, Map.of("reason", reason.trim()));
        return responses(List.of(dispatch)).get(dispatch);
    }

    private void ship(DispatchRecord dispatch, DispatchItemRequest line) {
        ProductionBatch batch = batches.findForUpdate(line.productionBatchId())
                .orElseThrow(() -> new BusinessRuleException("This batch does not exist."));
        batch.dispatch(line.quantity());
        DispatchItem item = items.saveAndFlush(new DispatchItem(dispatch.getId(), batch.getId(), line.quantity()));
        List<String> serials = line.serialNumbers() == null ? List.of() : line.serialNumbers();
        if (serials.isEmpty()) {
            return;
        }
        if (BigDecimal.valueOf(serials.size()).compareTo(line.quantity()) != 0) {
            throw new BusinessRuleException("The number of serial numbers must match the quantity.");
        }
        List<FinishedProduct> units = finishedProducts.findBySerialNumberIn(serials);
        boolean valid = units.size() == serials.size() && units.stream().allMatch(unit ->
                unit.getProductionBatchId().equals(batch.getId()) && unit.getDispatchItemId() == null);
        if (!valid) {
            throw new BusinessRuleException("A serial number is unknown, already shipped, or from another batch.");
        }
        units.forEach(unit -> unit.setDispatchItemId(item.getId()));
    }

    /** Everything shipped from these batches. */
    @Transactional(readOnly = true)
    public List<DispatchResponse> forBatches(Collection<UUID> batchIds) {
        if (batchIds.isEmpty()) {
            return List.of();
        }
        List<UUID> dispatchIds = items.findByProductionBatchIdIn(batchIds).stream()
                .map(DispatchItem::getDispatchId).distinct().toList();
        List<DispatchRecord> found = dispatches.findAllById(dispatchIds);
        Map<DispatchRecord, DispatchResponse> responses = responses(found);
        return found.stream()
                .sorted(Comparator.comparing(DispatchRecord::getCreatedAt))
                .map(responses::get)
                .toList();
    }

    private Map<DispatchRecord, DispatchResponse> responses(List<DispatchRecord> records) {
        List<DispatchItem> lines = items.findByDispatchIdIn(records.stream().map(DispatchRecord::getId).toList());
        Map<UUID, ProductionBatchView> shipped = production.views(lines.stream()
                        .map(DispatchItem::getProductionBatchId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(ProductionBatchView::id, Function.identity()));
        Map<UUID, Customer> named = customers.findAllById(records.stream().map(DispatchRecord::getCustomerId).toList())
                .stream().collect(Collectors.toMap(Customer::getId, Function.identity()));
        Map<UUID, List<DispatchItem>> byDispatch =
                lines.stream().collect(Collectors.groupingBy(DispatchItem::getDispatchId));
        Map<UUID, TraceCorrection> voided = corrections
                .findByDispatchIdIn(records.stream().map(DispatchRecord::getId).toList()).stream()
                .collect(Collectors.toMap(TraceCorrection::getDispatchId, Function.identity()));
        Map<UUID, BigDecimal> returned = reversalItems
                .findByDispatchItemIdIn(lines.stream().map(DispatchItem::getId).toList()).stream()
                .collect(Collectors.toMap(
                        DispatchReversalItem::getDispatchItemId, DispatchReversalItem::getQuantity, BigDecimal::add));
        return records.stream().collect(Collectors.toMap(Function.identity(), record -> new DispatchResponse(
                record.getId(),
                record.getDispatchNumber(),
                named.get(record.getCustomerId()).getName(),
                record.getDispatchedOn(),
                record.getReference(),
                byDispatch.getOrDefault(record.getId(), List.of()).stream().map(line -> {
                    ProductionBatchView batch = shipped.get(line.getProductionBatchId());
                    return new DispatchLine(line.getId(), batch.batchNumber(), batch.productCode(), batch.productName(),
                            line.getQuantity(), returned.getOrDefault(line.getId(), BigDecimal.ZERO));
                }).toList(),
                record.getCreatedAt(),
                voided.containsKey(record.getId()) ? voided.get(record.getId()).getReason() : null,
                voided.containsKey(record.getId()) ? voided.get(record.getId()).getCreatedAt() : null)));
    }

    private static CustomerResponse toResponse(Customer customer) {
        return new CustomerResponse(customer.getId(), customer.getCode(), customer.getName(), customer.getGstin());
    }
}
