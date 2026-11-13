package com.qualityos.purchaseorder.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.DocumentNumberService;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.material.dto.MaterialDtos.MaterialReference;
import com.qualityos.material.service.MaterialService;
import com.qualityos.organization.service.FacilityService;
import com.qualityos.purchaseorder.dto.PurchaseOrderDtos.ItemRequest;
import com.qualityos.purchaseorder.dto.PurchaseOrderDtos.ItemResponse;
import com.qualityos.purchaseorder.dto.PurchaseOrderDtos.PurchaseOrderDetail;
import com.qualityos.purchaseorder.dto.PurchaseOrderDtos.PurchaseOrderSummary;
import com.qualityos.purchaseorder.dto.PurchaseOrderDtos.ReceiptLine;
import com.qualityos.purchaseorder.dto.PurchaseOrderDtos.ReceiptTarget;
import com.qualityos.purchaseorder.dto.PurchaseOrderDtos.SavePurchaseOrderRequest;
import com.qualityos.purchaseorder.dto.PurchaseOrderDtos.UnitPrice;
import com.qualityos.purchaseorder.entity.PurchaseOrder;
import com.qualityos.purchaseorder.entity.PurchaseOrderItem;
import com.qualityos.purchaseorder.repository.PurchaseOrderRepository;
import com.qualityos.supplier.dto.SupplierDtos.SupplierReference;
import com.qualityos.supplier.service.SupplierService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
public class PurchaseOrderService {

    private static final String NUMBER_PREFIX = "PO";

    private static final String ENTITY = "PurchaseOrder";

    private final PurchaseOrderRepository orders;
    private final SupplierService suppliers;
    private final FacilityService facilities;
    private final MaterialService materials;
    private final DocumentNumberService numbers;
    private final AuditService audit;
    private final EntityManager entityManager;
    private final FacilityScope scope;

    PurchaseOrderService(
            PurchaseOrderRepository orders,
            SupplierService suppliers,
            FacilityService facilities,
            MaterialService materials,
            DocumentNumberService numbers,
            AuditService audit,
            EntityManager entityManager,
            FacilityScope scope) {
        this.scope = scope;
        this.orders = orders;
        this.suppliers = suppliers;
        this.facilities = facilities;
        this.materials = materials;
        this.numbers = numbers;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public PageResponse<PurchaseOrderSummary> list(
            String search, PurchaseOrder.Status status, UUID supplierId, Pageable pageable) {
        Specification<PurchaseOrder> filter = Specification.<PurchaseOrder>where(Searches.contains(search, "poNumber"))
                .and(Searches.equalTo("status", status))
                .and(Searches.equalTo("supplierId", supplierId));
        Page<PurchaseOrder> page = orders.findAll(scope.limit(filter, "facilityId"), pageable);
        Map<UUID, SupplierReference> supplierNames =
                suppliers.references(page.map(PurchaseOrder::getSupplierId).toSet());
        return PageResponse.from(page, order -> new PurchaseOrderSummary(
                order.getId(),
                order.getPoNumber(),
                order.getSupplierId(),
                supplierNames.get(order.getSupplierId()).name(),
                order.getStatus().name(),
                order.getOrderDate(),
                order.getExpectedDate()));
    }

    @Transactional(readOnly = true)
    public PurchaseOrderDetail get(UUID id) {
        return toDetail(loadWithItems(id));
    }

    @Transactional
    public PurchaseOrderDetail create(SavePurchaseOrderRequest request) {
        PurchaseOrder order = new PurchaseOrder(numbers.next(NUMBER_PREFIX));
        apply(order, request);
        orders.saveAndFlush(order);
        audit.record("PURCHASE_ORDER_CREATED", "PurchaseOrder", order.getId());
        return toDetail(order);
    }

    @Transactional
    public PurchaseOrderDetail update(UUID id, SavePurchaseOrderRequest request) {
        PurchaseOrder order = loadWithItems(id);
        order.requireDraft();
        order.requireVersion(request.version());
        // Item changes do not touch the order row, so without this two edits could overwrite each other.
        entityManager.lock(order, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        order.getItems().clear();
        orders.flush();
        apply(order, request);
        orders.saveAndFlush(order);
        audit.record("PURCHASE_ORDER_UPDATED", "PurchaseOrder", id);
        return toDetail(order);
    }

    @Transactional
    public PurchaseOrderDetail issue(UUID id) {
        PurchaseOrder order = loadWithItems(id);
        suppliers.requireApproved(order.getSupplierId());
        order.issue();
        orders.saveAndFlush(order);
        audit.record("PURCHASE_ORDER_ISSUED", "PurchaseOrder", id);
        return toDetail(order);
    }

    @Transactional
    public PurchaseOrderDetail cancel(UUID id) {
        PurchaseOrder order = loadWithItems(id);
        order.cancel();
        orders.saveAndFlush(order);
        audit.record("PURCHASE_ORDER_CANCELLED", "PurchaseOrder", id);
        return toDetail(order);
    }

    @Transactional
    public PurchaseOrderDetail close(UUID id) {
        PurchaseOrder order = loadWithItems(id);
        order.close();
        orders.saveAndFlush(order);
        audit.record("PURCHASE_ORDER_CLOSED", "PurchaseOrder", id);
        return toDetail(order);
    }

    /**
     * Records received quantities under a row lock.
     *
     * @param quantities total received in this receipt for each order item
     */
    /** The price on each purchase order line, for costing what was received against it. */
    @Transactional(readOnly = true)
    public Map<UUID, UnitPrice> linePrices(Collection<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        return orders.items(itemIds).stream().collect(Collectors.toMap(PurchaseOrderItem::getId,
                item -> new UnitPrice(item.getMaterialId(), item.getUomId(), item.getUnitPrice())));
    }

    /**
     * Each material's average price per unit, weighted by quantity ordered, over priced lines of orders that were
     * issued; one per unit it was bought in.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<UnitPrice>> averagePrices(Collection<UUID> materialIds) {
        if (materialIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<UUID, BigDecimal[]>> sums = new HashMap<>();
        for (PurchaseOrderItem item : orders.pricedItems(materialIds)) {
            BigDecimal[] sum = sums.computeIfAbsent(item.getMaterialId(), ignored -> new HashMap<>())
                    .computeIfAbsent(item.getUomId(), ignored -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            sum[0] = sum[0].add(item.getOrderedQuantity().multiply(item.getUnitPrice()));
            sum[1] = sum[1].add(item.getOrderedQuantity());
        }
        Map<UUID, List<UnitPrice>> prices = new HashMap<>();
        sums.forEach((material, byUnit) -> byUnit.forEach((unit, sum) -> {
            if (sum[1].signum() > 0) {
                prices.computeIfAbsent(material, ignored -> new ArrayList<>()).add(new UnitPrice(material,
                        unit, sum[0].divide(sum[1], 6, RoundingMode.HALF_UP)));
            }
        }));
        return prices;
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> numbers(Collection<UUID> ids) {
        return orders.findAllById(ids).stream()
                .collect(Collectors.toMap(PurchaseOrder::getId, PurchaseOrder::getPoNumber));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ReceiptTarget recordReceipt(UUID id, Map<UUID, BigDecimal> quantities) {
        PurchaseOrder order = orders.findForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Purchase order", id));
        scope.requireAllowed(order.getFacilityId(), ENTITY, id.toString());
        suppliers.requireApproved(order.getSupplierId());
        Map<UUID, PurchaseOrderItem> items = itemsOf(order);
        quantities.forEach((itemId, quantity) -> {
            PurchaseOrderItem item = items.get(itemId);
            if (item == null) {
                throw new BusinessRuleException("A received item does not belong to this purchase order.");
            }
            order.receive(item, quantity);
        });
        return new ReceiptTarget(
                order.getId(),
                order.getSupplierId(),
                order.getFacilityId(),
                quantities.keySet().stream().map(items::get)
                        .map(item -> new ReceiptLine(item.getId(), item.getMaterialId(), item.getUomId()))
                        .toList());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reverseReceipt(UUID id, Map<UUID, BigDecimal> quantities) {
        PurchaseOrder order = orders.findForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Purchase order", id));
        scope.requireAllowed(order.getFacilityId(), ENTITY, id.toString());
        Map<UUID, PurchaseOrderItem> items = itemsOf(order);
        quantities.forEach((itemId, quantity) -> order.reverseReceipt(items.get(itemId), quantity));
    }

    private static Map<UUID, PurchaseOrderItem> itemsOf(PurchaseOrder order) {
        return order.getItems().stream().collect(Collectors.toMap(PurchaseOrderItem::getId, Function.identity()));
    }

    private PurchaseOrder loadWithItems(UUID id) {
        PurchaseOrder order = orders.findWithItems(id).orElseThrow(() -> new ResourceNotFoundException("Purchase order", id));
        scope.requireAllowed(order.getFacilityId(), ENTITY, id.toString());
        return order;
    }

    private void apply(PurchaseOrder order, SavePurchaseOrderRequest request) {
        suppliers.reference(request.supplierId());
        facilities.requireActive(request.facilityId());
        scope.requireAllowed(request.facilityId(), ENTITY, request.facilityId().toString());
        if (request.expectedDate() != null && request.expectedDate().isBefore(request.orderDate())) {
            throw new BusinessRuleException("The delivery date cannot be before the order date.");
        }
        order.setSupplierId(request.supplierId());
        order.setFacilityId(request.facilityId());
        order.setOrderDate(request.orderDate());
        order.setExpectedDate(request.expectedDate());
        order.setNotes(request.notes());
        Set<UUID> seen = new HashSet<>();
        for (ItemRequest item : request.items()) {
            if (!seen.add(item.materialId())) {
                throw new BusinessRuleException("Each material can appear once. Combine the quantities.");
            }
            MaterialReference material = materials.requireActive(item.materialId());
            order.addItem(material.id(), material.uomId(), item.quantity(), item.unitPrice());
        }
    }

    private PurchaseOrderDetail toDetail(PurchaseOrder order) {
        Map<UUID, MaterialReference> referenced = materials.references(
                order.getItems().stream().map(PurchaseOrderItem::getMaterialId).toList());
        return new PurchaseOrderDetail(
                order.getId(),
                order.getPoNumber(),
                order.getSupplierId(),
                suppliers.reference(order.getSupplierId()).name(),
                order.getFacilityId(),
                order.getStatus().name(),
                order.getOrderDate(),
                order.getExpectedDate(),
                order.getNotes(),
                order.getItems().stream().map(item -> {
                    MaterialReference material = referenced.get(item.getMaterialId());
                    return new ItemResponse(
                            item.getId(),
                            item.getLineNumber(),
                            item.getMaterialId(),
                            material.code(),
                            material.name(),
                            item.getUomId(),
                            item.getOrderedQuantity(),
                            item.getReceivedQuantity(),
                            item.getUnitPrice());
                }).toList(),
                order.getVersion() == null ? 0 : order.getVersion());
    }
}
