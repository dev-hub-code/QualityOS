package com.qualityos.materialreceipt.service;

import com.qualityos.common.attachment.AttachmentPolicy;
import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.DocumentNumberService;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.material.dto.MaterialDtos.MaterialReference;
import com.qualityos.material.service.MaterialService;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.CreateReceiptRequest;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.ReceiptDetail;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.ReceiptItemRequest;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.ReceiptItemResponse;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.ReceiptSummary;
import com.qualityos.materialreceipt.entity.MaterialReceipt;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.AppliedLotCorrection;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.LotCorrection;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.LotView;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.ReceivedBatch;
import com.qualityos.materialreceipt.entity.MaterialReceiptItem;
import com.qualityos.materialreceipt.entity.MaterialReceiptItem.InspectionStatus;
import com.qualityos.materialreceipt.repository.MaterialReceiptItemRepository;
import com.qualityos.materialreceipt.repository.MaterialReceiptRepository;
import com.qualityos.purchaseorder.dto.PurchaseOrderDtos.ReceiptLine;
import com.qualityos.purchaseorder.dto.PurchaseOrderDtos.ReceiptTarget;
import com.qualityos.purchaseorder.service.PurchaseOrderService;
import com.qualityos.supplier.dto.SupplierDtos.SupplierReference;
import com.qualityos.supplier.service.SupplierService;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialReceiptService implements AttachmentPolicy {

    private final MaterialReceiptRepository receipts;
    private final MaterialReceiptItemRepository items;
    private final PurchaseOrderService purchaseOrders;
    private final SupplierService suppliers;
    private final MaterialService materials;
    private final DocumentNumberService numbers;
    private final AuditService audit;

    MaterialReceiptService(
            MaterialReceiptRepository receipts,
            MaterialReceiptItemRepository items,
            PurchaseOrderService purchaseOrders,
            SupplierService suppliers,
            MaterialService materials,
            DocumentNumberService numbers,
            AuditService audit) {
        this.receipts = receipts;
        this.items = items;
        this.purchaseOrders = purchaseOrders;
        this.suppliers = suppliers;
        this.materials = materials;
        this.numbers = numbers;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<ReceiptSummary> list(String search, UUID purchaseOrderId, Pageable pageable) {
        Specification<MaterialReceipt> filter =
                Specification.<MaterialReceipt>where(Searches.contains(search, "grnNumber", "deliveryNote"))
                        .and(Searches.equalTo("purchaseOrderId", purchaseOrderId));
        Page<MaterialReceipt> page = receipts.findAll(filter, pageable);
        Map<UUID, SupplierReference> supplierNames =
                suppliers.references(page.map(MaterialReceipt::getSupplierId).toSet());
        return PageResponse.from(page, receipt -> new ReceiptSummary(
                receipt.getId(),
                receipt.getGrnNumber(),
                receipt.getPurchaseOrderId(),
                receipt.getSupplierId(),
                supplierNames.get(receipt.getSupplierId()).name(),
                receipt.getReceivedOn(),
                receipt.getStatus().name()));
    }

    @Transactional(readOnly = true)
    public ReceiptDetail get(UUID id) {
        return toDetail(load(id));
    }

    @Transactional
    public ReceiptDetail create(CreateReceiptRequest request) {
        Map<UUID, BigDecimal> totals = request.items().stream().collect(Collectors.toMap(
                ReceiptItemRequest::purchaseOrderItemId, ReceiptItemRequest::quantity, BigDecimal::add));
        ReceiptTarget target = purchaseOrders.recordReceipt(request.purchaseOrderId(), totals);
        Map<UUID, ReceiptLine> lines =
                target.lines().stream().collect(Collectors.toMap(ReceiptLine::itemId, Function.identity()));
        Map<UUID, MaterialReference> referenced =
                materials.references(target.lines().stream().map(ReceiptLine::materialId).toList());

        MaterialReceipt receipt = new MaterialReceipt(
                numbers.next("GRN"), target.purchaseOrderId(), target.supplierId(), target.facilityId(),
                request.receivedOn(), request.deliveryNote(), request.notes());
        for (ReceiptItemRequest item : request.items()) {
            ReceiptLine line = lines.get(item.purchaseOrderItemId());
            receipt.getItems().add(new MaterialReceiptItem(
                    receipt,
                    line.itemId(),
                    line.materialId(),
                    line.uomId(),
                    item.supplierBatch().trim(),
                    numbers.next("LOT"),
                    item.quantity(),
                    referenced.get(line.materialId()).inspectionRequired()));
        }
        receipts.saveAndFlush(receipt);
        audit.record("MATERIAL_RECEIVED", "MaterialReceipt", receipt.getId());
        return toDetail(receipt);
    }

    /** Takes the quantities back off the order. Refused once an inspection exists. */
    @Transactional
    public ReceiptDetail voidReceipt(UUID id, String reason) {
        MaterialReceipt receipt = load(id);
        boolean used = receipt.getItems().stream()
                .anyMatch(item -> item.getConsumedQuantity().signum() > 0);
        if (used) {
            throw new BusinessRuleException("This receipt cannot be voided: its material has gone into production.");
        }
        receipt.voidReceipt(reason);
        purchaseOrders.reverseReceipt(receipt.getPurchaseOrderId(), receipt.getItems().stream().collect(Collectors.toMap(
                MaterialReceiptItem::getPurchaseOrderItemId, MaterialReceiptItem::getQuantity, BigDecimal::add)));
        receipts.saveAndFlush(receipt);
        audit.record("MATERIAL_RECEIPT_VOIDED", "MaterialReceipt", id, null, Map.of("reason", reason));
        return toDetail(receipt);
    }

    /** The inspection queue. */
    @Transactional(readOnly = true)
    public PageResponse<ReceivedBatch> awaitingInspection(Pageable pageable) {
        Page<MaterialReceiptItem> page = items.findByInspectionStatus(InspectionStatus.PENDING, pageable);
        Map<UUID, SupplierReference> supplierNames = suppliers.references(
                page.map(item -> item.getReceipt().getSupplierId()).toSet());
        Map<UUID, MaterialReference> referenced =
                materials.references(page.map(MaterialReceiptItem::getMaterialId).toSet());
        return PageResponse.from(page, item -> toBatch(
                item, supplierNames.get(item.getReceipt().getSupplierId()), referenced.get(item.getMaterialId())));
    }

    @Transactional(readOnly = true)
    public ReceivedBatch batch(UUID itemId) {
        MaterialReceiptItem item = loadItem(itemId);
        return toBatch(
                item,
                suppliers.reference(item.getReceipt().getSupplierId()),
                materials.references(Set.of(item.getMaterialId())).get(item.getMaterialId()));
    }

    /** Claims a batch for inspection. The item's version stops this racing with a void. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LotView consume(UUID itemId, BigDecimal quantity) {
        MaterialReceiptItem item = items.findForUpdate(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Batch", itemId));
        if (item.getReceipt().getStatus() == MaterialReceipt.Status.VOIDED) {
            throw new BusinessRuleException("This receipt was voided.");
        }
        item.consume(quantity);
        items.flush();
        return lots(List.of(item)).getFirst();
    }

    @Transactional
    public void restore(UUID itemId, BigDecimal quantity) {
        items.findForUpdate(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Batch", itemId))
                .restore(quantity);
        items.flush();
    }

    /**
     * Applies an approved correction. A supplier belongs to the whole receipt, so it moves every lot on it. A
     * change of quantity also moves the purchase order's received total, which keeps the two in step.
     */
    @Transactional
    public AppliedLotCorrection correct(UUID itemId, LotCorrection correction) {
        MaterialReceiptItem item = items.findForUpdate(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Batch", itemId));
        MaterialReceipt receipt = item.getReceipt();
        if (receipt.getStatus() == MaterialReceipt.Status.VOIDED) {
            throw new BusinessRuleException("This receipt was voided.");
        }
        Set<UUID> affected = new HashSet<>();
        if (correction.materialId() != null) {
            affected.add(itemId);
        }
        if (correction.supplierId() != null) {
            suppliers.requireApproved(correction.supplierId());
            receipt.correctSupplier(correction.supplierId());
            receipt.getItems().forEach(line -> affected.add(line.getId()));
        }
        if (correction.quantity() != null) {
            BigDecimal change = correction.quantity().subtract(item.getQuantity());
            Map<UUID, BigDecimal> moved = Map.of(item.getPurchaseOrderItemId(), change.abs());
            if (change.signum() > 0) {
                purchaseOrders.recordReceipt(receipt.getPurchaseOrderId(), moved);
            } else if (change.signum() < 0) {
                purchaseOrders.reverseReceipt(receipt.getPurchaseOrderId(), moved);
            }
        }
        item.correct(correction.materialId(), correction.supplierBatch(), correction.quantity());
        items.flush();
        return new AppliedLotCorrection(List.copyOf(affected));
    }

    @Transactional(readOnly = true)
    public List<LotView> releasedLots(UUID materialId) {
        return lots(items.findLots(materialId).stream()
                .filter(item -> item.isReleased() && item.remainingQuantity().signum() > 0)
                .toList());
    }

    @Transactional(readOnly = true)
    public List<LotView> lots(Collection<UUID> ids) {
        return lots(items.findByIdIn(ids));
    }

    @Transactional(readOnly = true)
    public Optional<LotView> lotByNumber(String internalBatch) {
        return items.findByInternalBatch(internalBatch).map(item -> lots(List.of(item)).getFirst());
    }

    /** @param term a supplier batch, internal batch, receipt number, or material code or name */
    @Transactional(readOnly = true)
    public List<LotView> searchLots(String term, int limit) {
        String pattern = "%" + term.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_") + "%";
        List<UUID> matching = materials.idsMatching(term);
        return lots(items.search(pattern, matching.isEmpty() ? List.of(new UUID(0, 0)) : matching,
                PageRequest.of(0, limit)));
    }

    private List<LotView> lots(List<MaterialReceiptItem> found) {
        Map<UUID, SupplierReference> supplierNames = suppliers.references(
                found.stream().map(item -> item.getReceipt().getSupplierId()).collect(Collectors.toSet()));
        Map<UUID, MaterialReference> referenced = materials.references(
                found.stream().map(MaterialReceiptItem::getMaterialId).collect(Collectors.toSet()));
        return found.stream().map(item -> {
            MaterialReceipt receipt = item.getReceipt();
            MaterialReference material = referenced.get(item.getMaterialId());
            return new LotView(
                    item.getId(),
                    receipt.getGrnNumber(),
                    receipt.getReceivedOn(),
                    receipt.getPurchaseOrderId(),
                    receipt.getSupplierId(),
                    supplierNames.get(receipt.getSupplierId()).name(),
                    material.id(),
                    material.code(),
                    material.name(),
                    item.getSupplierBatch(),
                    item.getInternalBatch(),
                    item.getQuantity(),
                    item.remainingQuantity(),
                    item.getInspectionStatus().name(),
                    item.getDisposition(),
                    item.isReleased());
        }).toList();
    }

    public void startInspection(UUID itemId) {
        MaterialReceiptItem item = loadItem(itemId);
        if (item.getInspectionStatus() != InspectionStatus.PENDING) {
            throw new BusinessRuleException("This batch is not awaiting inspection.");
        }
        item.updateInspection(InspectionStatus.IN_PROGRESS, null);
        items.flush();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseInspection(UUID itemId) {
        loadItem(itemId).updateInspection(InspectionStatus.PENDING, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDisposition(UUID itemId, String disposition, boolean finalDecision) {
        loadItem(itemId).updateInspection(
                finalDecision ? InspectionStatus.COMPLETED : InspectionStatus.IN_PROGRESS, disposition);
    }

    @Override
    public String entityType() {
        return "MATERIAL_RECEIPT";
    }

    @Override
    public String viewPermission() {
        return Permissions.PURCHASE_VIEW;
    }

    @Override
    public String managePermission() {
        return Permissions.RECEIPT_MANAGE;
    }

    @Override
    public boolean parentExists(UUID entityId) {
        return receipts.existsById(entityId);
    }

    private MaterialReceiptItem loadItem(UUID id) {
        return items.findWithReceipt(id).orElseThrow(() -> new ResourceNotFoundException("Received batch", id));
    }

    private static ReceivedBatch toBatch(
            MaterialReceiptItem item, SupplierReference supplier, MaterialReference material) {
        MaterialReceipt receipt = item.getReceipt();
        return new ReceivedBatch(
                item.getId(),
                receipt.getId(),
                receipt.getGrnNumber(),
                receipt.getReceivedOn(),
                supplier.id(),
                supplier.name(),
                receipt.getFacilityId(),
                material.id(),
                material.code(),
                material.name(),
                item.getSupplierBatch(),
                item.getInternalBatch(),
                item.getQuantity());
    }

    private MaterialReceipt load(UUID id) {
        return receipts.findWithItems(id).orElseThrow(() -> new ResourceNotFoundException("Material receipt", id));
    }

    private ReceiptDetail toDetail(MaterialReceipt receipt) {
        Map<UUID, MaterialReference> referenced = materials.references(
                receipt.getItems().stream().map(MaterialReceiptItem::getMaterialId).collect(Collectors.toSet()));
        return new ReceiptDetail(
                receipt.getId(),
                receipt.getGrnNumber(),
                receipt.getPurchaseOrderId(),
                receipt.getSupplierId(),
                suppliers.reference(receipt.getSupplierId()).name(),
                receipt.getFacilityId(),
                receipt.getReceivedOn(),
                receipt.getDeliveryNote(),
                receipt.getNotes(),
                receipt.getStatus().name(),
                receipt.getVoidReason(),
                receipt.getItems().stream().map(item -> {
                    MaterialReference material = referenced.get(item.getMaterialId());
                    return new ReceiptItemResponse(
                            item.getId(),
                            item.getPurchaseOrderItemId(),
                            item.getMaterialId(),
                            material.code(),
                            material.name(),
                            item.getUomId(),
                            item.getSupplierBatch(),
                            item.getInternalBatch(),
                            item.getQuantity(),
                            item.isInspectionRequired(),
                            item.getInspectionStatus().name(),
                            item.getDisposition());
                }).toList());
    }
}
