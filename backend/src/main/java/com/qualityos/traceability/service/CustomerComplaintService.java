package com.qualityos.traceability.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.organization.service.OrganizationService;
import com.qualityos.quality.dto.QualityDtos.CustomerComplaint;
import com.qualityos.quality.dto.QualityDtos.NcrOutcome;
import com.qualityos.quality.service.NcrService;
import com.qualityos.traceability.dto.TraceabilityDtos.CreateComplaintRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.ProductionBatchView;
import com.qualityos.traceability.entity.DispatchItem;
import com.qualityos.traceability.entity.DispatchRecord;
import com.qualityos.traceability.entity.DispatchShipment;
import com.qualityos.traceability.repository.CustomerRepository;
import com.qualityos.traceability.repository.DispatchItemRepository;
import com.qualityos.traceability.repository.DispatchRecordRepository;
import com.qualityos.traceability.repository.DispatchShipmentRepository;
import com.qualityos.traceability.repository.TraceCorrectionRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A customer's complaint about goods they kept, such as a phone call about a defect. It raises an NCR linked to the
 * dispatch; goods that come back are recorded as a return instead.
 */
@Service
public class CustomerComplaintService {

    private final DispatchRecordRepository dispatches;
    private final DispatchItemRepository items;
    private final DispatchShipmentRepository shipments;
    private final TraceCorrectionRepository corrections;
    private final CustomerRepository customers;
    private final ProductionService production;
    private final OrganizationService organizations;
    private final FacilityScope scope;
    private final AuditService audit;
    private final NcrService ncrs;

    CustomerComplaintService(DispatchRecordRepository dispatches, DispatchItemRepository items,
            DispatchShipmentRepository shipments, TraceCorrectionRepository corrections, CustomerRepository customers,
            ProductionService production, OrganizationService organizations, FacilityScope scope, AuditService audit,
            NcrService ncrs) {
        this.dispatches = dispatches;
        this.items = items;
        this.shipments = shipments;
        this.corrections = corrections;
        this.customers = customers;
        this.production = production;
        this.organizations = organizations;
        this.scope = scope;
        this.audit = audit;
        this.ncrs = ncrs;
    }

    /** The NCR is placed in the plant that made the batch complained about, or the dispatch's first batch. */
    @Transactional
    public NcrOutcome complain(UUID dispatchId, CreateComplaintRequest request) {
        DispatchRecord dispatch = load(dispatchId);
        if (corrections.existsByDispatchId(dispatchId)) {
            throw new BusinessRuleException("This dispatch was reversed as a mistake, so the customer never had it.");
        }
        DispatchShipment shipment = shipments.findByDispatchId(dispatchId).orElseThrow(() ->
                new BusinessRuleException("Confirm this shipment before recording a complaint about it."));
        LocalDate today = LocalDate.now(ZoneId.of(organizations.getCurrent().timezone()));
        if (request.receivedOn().isBefore(shipment.getShippedOn()) || request.receivedOn().isAfter(today)) {
            throw new BusinessRuleException("The complaint date must fall between the shipment and today.");
        }
        List<DispatchItem> lines = items.findByDispatchIdIn(List.of(dispatchId));
        List<DispatchItem> about = request.dispatchItemId() == null ? lines : lines.stream()
                .filter(line -> line.getId().equals(request.dispatchItemId())).toList();
        if (about.isEmpty()) {
            throw new BusinessRuleException("This line is not on the dispatch.");
        }
        Map<UUID, ProductionBatchView> made = production.views(about.stream()
                        .map(DispatchItem::getProductionBatchId).distinct().toList()).stream()
                .collect(Collectors.toMap(ProductionBatchView::id, Function.identity()));
        List<String> batchNumbers = about.stream().map(line -> made.get(line.getProductionBatchId()).batchNumber())
                .distinct().toList();
        NcrOutcome raised = ncrs.raiseFromCustomerComplaint(new CustomerComplaint(
                dispatch.getId(),
                dispatch.getDispatchNumber(),
                customers.findById(dispatch.getCustomerId()).orElseThrow().getName(),
                request.reference().trim(),
                request.receivedOn(),
                request.severity(),
                request.description().trim(),
                batchNumbers.size() == 1 ? batchNumbers.getFirst() : null,
                about.stream().map(line -> "%s × %s".formatted(made.get(line.getProductionBatchId()).batchNumber(),
                        line.getQuantity().stripTrailingZeros().toPlainString())).toList(),
                made.get(about.getFirst().getProductionBatchId()).facilityId()));
        audit.record("CUSTOMER_COMPLAINT_RECORDED", "DispatchRecord", dispatch.getId(), null,
                Map.of("dispatch", dispatch.getDispatchNumber(), "reference", request.reference().trim(),
                        "ncr", raised.ncrNumber()));
        return raised;
    }

    @Transactional(readOnly = true)
    public List<NcrOutcome> forDispatch(UUID dispatchId) {
        load(dispatchId);
        return ncrs.forDispatch(dispatchId);
    }

    private DispatchRecord load(UUID id) {
        DispatchRecord dispatch = dispatches.findById(id).orElseThrow(() -> new ResourceNotFoundException("Dispatch", id));
        scope.require(items.facilitiesOf(id), "Dispatch", id.toString());
        return dispatch;
    }
}
