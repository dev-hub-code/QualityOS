package com.qualityos.materialreceipt.service;

import com.qualityos.common.attachment.AttachmentPolicy;
import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.DocumentNumberService;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.mail.EmailContent;
import com.qualityos.common.mail.EmailTemplate;
import com.qualityos.common.security.Permissions;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.notification.service.NotificationService;
import com.qualityos.notification.service.NotificationType;
import com.qualityos.identity.service.FacilityScope;
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
import com.qualityos.purchaseorder.dto.PurchaseOrderDtos.UnitPrice;
import com.qualityos.purchaseorder.service.PurchaseOrderService;
import com.qualityos.supplier.dto.SupplierDtos.SupplierReference;
import com.qualityos.supplier.service.SupplierService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
    private final FacilityScope scope;
    private final NotificationService notifications;
    private final TenantAccessService access;
    private final com.qualityos.organization.service.OrganizationService organizations;

    private final EntityManager entityManager;

    MaterialReceiptService(
            MaterialReceiptRepository receipts,
            MaterialReceiptItemRepository items,
            PurchaseOrderService purchaseOrders,
            SupplierService suppliers,
            MaterialService materials,
            DocumentNumberService numbers,
            AuditService audit,
            FacilityScope scope,
            NotificationService notifications,
            TenantAccessService access,
            com.qualityos.organization.service.OrganizationService organizations,
            EntityManager entityManager) {
        this.organizations = organizations;
        this.scope = scope;
        this.notifications = notifications;
        this.access = access;
        this.entityManager = entityManager;
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
        Page<MaterialReceipt> page = receipts.findAll(scope.limit(filter, "facilityId"), pageable);
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
        // The organisation's day, not the server's: in India the server's UTC date is still yesterday until 05:30.
        if (request.receivedOn().isAfter(today())) {
            throw new BusinessRuleException("The received date cannot be in the future.");
        }
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
            MaterialReference material = referenced.get(line.materialId());
            MaterialReceiptItem lot = new MaterialReceiptItem(
                    receipt,
                    line.itemId(),
                    line.materialId(),
                    line.uomId(),
                    item.supplierBatch().trim(),
                    numbers.next("LOT"),
                    item.quantity(),
                    material.inspectionRequired());
            lot.expiresOn(expiryOf(item, material, request.receivedOn()));
            receipt.getItems().add(lot);
        }
        receipts.saveAndFlush(receipt);
        audit.record("MATERIAL_RECEIVED", "MaterialReceipt", receipt.getId());
        tellInspectors(receipt, referenced);
        return toDetail(receipt);
    }

    /** As entered, or the receipt date plus the material's shelf life. A lot cannot arrive already expired. */
    private static LocalDate expiryOf(ReceiptItemRequest item, MaterialReference material, LocalDate receivedOn) {
        LocalDate expiry = item.expiryDate() != null ? item.expiryDate()
                : material.shelfLifeDays() == null ? null : receivedOn.plusDays(material.shelfLifeDays());
        if (expiry != null && expiry.isBefore(receivedOn)) {
            throw new BusinessRuleException("%s (%s) expired on %s, before it was received."
                    .formatted(item.supplierBatch().trim(), material.code(), expiry));
        }
        return expiry;
    }

    /** Today where the organisation is, which decides whether a lot has expired. */
    private LocalDate today() {
        return LocalDate.now(java.time.ZoneId.of(organizations.getCurrent().timezone()));
    }

    /** Lots that need inspection go to those who start inspections at that facility. */
    private void tellInspectors(MaterialReceipt receipt, Map<UUID, MaterialReference> referenced) {
        List<String> lots = receipt.getItems().stream().filter(MaterialReceiptItem::isInspectionRequired)
                .map(item -> item.getInternalBatch() + " (" + referenced.get(item.getMaterialId()).code() + ")")
                .toList();
        if (lots.isEmpty()) {
            return;
        }
        String supplier = suppliers.references(Set.of(receipt.getSupplierId())).values().stream().findFirst()
                .map(SupplierReference::name).orElse("the supplier");
        String list = String.join(", ", lots);
        notifications.notify(scope.allowedIn(receipt.getFacilityId(), access.holdersOf(Permissions.INSPECTION_CREATE)),
                NotificationType.MATERIAL_AWAITING_INSPECTION,
                receipt.getGrnNumber() + ": " + lots.size() + (lots.size() == 1 ? " lot" : " lots") + " to inspect", list, "/inspections",
                new EmailContent(EmailTemplate.MATERIAL_RECEIVED, Map.of("receiptNumber", receipt.getGrnNumber(),
                        "supplierName", supplier, "lots", list)));
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
        if (receipt.getItems().stream().anyMatch(item -> item.getTransferredQuantity().signum() > 0)) {
            throw new BusinessRuleException("This receipt cannot be voided: some of it was sent to another plant.");
        }
        if (receipt.getItems().stream().anyMatch(item -> item.getReservedQuantity().signum() > 0)) {
            throw new BusinessRuleException("This receipt cannot be voided: some of it is reserved for work orders.");
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
        Page<MaterialReceiptItem> page = scope.restriction()
                .map(allowed -> items.findByInspectionStatusAndReceiptFacilityIdIn(
                        InspectionStatus.PENDING, allowed, pageable))
                .orElseGet(() -> items.findByInspectionStatus(InspectionStatus.PENDING, pageable));
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

    /** Sends part of a lot to another plant. Only released, unexpired stock not set aside can go. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LotView transferOut(UUID itemId, BigDecimal quantity) {
        MaterialReceiptItem item = lockUsable(itemId);
        item.requireNotExpired(today());
        item.transferOut(quantity);
        items.flush();
        return lots(List.of(item)).getFirst();
    }

    /** A transfer cancelled in transit: the stock goes back to its lot. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void returnTransferred(UUID itemId, BigDecimal quantity) {
        lockItem(itemId).returnTransferred(quantity);
        items.flush();
    }

    /**
     * Receives what was sent from a lot as a lot of its own at {@code facilityId}, numbered after it: the first part
     * of LOT-7 received elsewhere is LOT-7-T1.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LotView receiveTransferred(UUID sourceId, BigDecimal quantity, UUID facilityId) {
        MaterialReceiptItem source = lockItem(sourceId);
        String number = source.getInternalBatch() + "-T" + (items.countByTransferredFromId(sourceId) + 1);
        MaterialReceiptItem lot = items.saveAndFlush(
                MaterialReceiptItem.receivedFrom(source, number, quantity, facilityId));
        return lots(List.of(lot)).getFirst();
    }

    /** Every lot received elsewhere from these lots, and from those in turn. */
    @Transactional(readOnly = true)
    public Set<UUID> transferredOn(Collection<UUID> lotIds) {
        Set<UUID> found = new HashSet<>();
        Collection<UUID> next = lotIds;
        while (!next.isEmpty()) {
            next = items.findByTransferredFromIdIn(next).stream().map(MaterialReceiptItem::getId)
                    .filter(found::add).toList();
        }
        return found;
    }

    /** Takes stock from a lot for production, in the lot's unit. The lot is locked while it does. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(UUID itemId, BigDecimal quantity) {
        consume(itemId, unit -> quantity);
    }

    /**
     * @param inLotUnit turns the lot's unit into the quantity to take, once the lot is known to be reachable
     * @return the quantity taken, in the lot's unit
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal consume(UUID itemId, java.util.function.Function<UUID, BigDecimal> inLotUnit) {
        MaterialReceiptItem item = lockUsable(itemId);
        item.requireNotExpired(today());
        BigDecimal quantity = inLotUnit.apply(item.getUomId());
        item.consume(quantity);
        items.flush();
        return quantity;
    }

    /**
     * Sets stock aside for a work order, or gives it back, in the lot's unit. The lot is locked while it does, so two
     * orders cannot both set aside its last kilogram.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LotView reserve(UUID itemId, BigDecimal quantity) {
        MaterialReceiptItem item = lockUsable(itemId);
        item.requireNotExpired(today());
        item.reserve(quantity);
        items.flush();
        return lots(List.of(item)).getFirst();
    }

    /**
     * Sets aside as much as the lot has free, up to {@code most}, without refusing when it has less: another order may
     * have taken some since the lot was listed.
     *
     * @return what was set aside, possibly nothing
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal reserveUpTo(UUID itemId, BigDecimal most) {
        MaterialReceiptItem item = lockUsable(itemId);
        if (!item.isReleased() || item.isExpiredOn(today())) {
            return BigDecimal.ZERO;
        }
        BigDecimal amount = most.min(item.freeQuantity()).max(BigDecimal.ZERO);
        if (amount.signum() > 0) {
            item.reserve(amount);
            items.flush();
        }
        return amount;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void unreserve(UUID itemId, BigDecimal quantity) {
        lockItem(itemId).unreserve(quantity);
        items.flush();
    }

    /** Takes stock that was set aside, in the lot's unit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consumeReserved(UUID itemId, BigDecimal quantity) {
        MaterialReceiptItem item = lockUsable(itemId);
        item.requireNotExpired(today());
        item.consumeReserved(quantity);
        items.flush();
    }

    /** Released, unexpired lots of the materials, in the facility, with stock not yet set aside, first expiring first. */
    @Transactional(readOnly = true)
    public List<LotView> freeLots(Collection<UUID> materialIds, UUID facilityId) {
        if (materialIds.isEmpty()) {
            return List.of();
        }
        LocalDate today = today();
        return lots(firstExpiringFirst(items.findLotsOf(materialIds, facilityId).stream()
                .filter(item -> item.isReleased() && item.freeQuantity().signum() > 0 && !item.isExpiredOn(today))
                .toList()));
    }

    /**
     * Locks the lot's row. The lot may have been read earlier in this transaction, so it is read again once locked:
     * otherwise a change another transaction made in between would look like a conflict.
     */
    private MaterialReceiptItem lockItem(UUID itemId) {
        MaterialReceiptItem item = items.findWithReceipt(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Batch", itemId));
        entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
        return item;
    }

    private MaterialReceiptItem lockUsable(UUID itemId) {
        MaterialReceiptItem item = lockItem(itemId);
        scope.requireAllowed(item.currentFacilityId(), "Batch", itemId.toString());
        if (item.getReceipt().getStatus() == MaterialReceipt.Status.VOIDED) {
            throw new BusinessRuleException("This receipt was voided.");
        }
        return item;
    }

    @Transactional
    public void restore(UUID itemId, BigDecimal quantity) {
        lockItem(itemId).restore(quantity);
        items.flush();
    }

    /**
     * Applies an approved correction. A supplier belongs to the whole receipt, so it moves every lot on it. A
     * change of quantity also moves the purchase order's received total, which keeps the two in step.
     */
    @Transactional
    public AppliedLotCorrection correct(UUID itemId, LotCorrection correction) {
        MaterialReceiptItem item = lockItem(itemId);
        if (item.getTransferredFromId() != null) {
            throw new BusinessRuleException("This lot was received from another plant. Correct the lot it came from.");
        }
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
        // Stock sent to other plants is the same material from the same supplier batch, so what it is follows the
        // correction; how much each plant received does not.
        if (correction.materialId() != null || correction.supplierBatch() != null) {
            for (MaterialReceiptItem moved : items.findAllById(transferredOn(List.of(itemId)))) {
                moved.correct(correction.materialId(), correction.supplierBatch(), null);
                if (correction.materialId() != null) {
                    affected.add(moved.getId());
                }
            }
        }
        items.flush();
        return new AppliedLotCorrection(List.copyOf(affected));
    }

    @Transactional(readOnly = true)
    public List<LotView> releasedLots(UUID materialId) {
        LocalDate today = today();
        return lots(firstExpiringFirst(items.findLots(materialId).stream()
                .filter(item -> item.isReleased() && item.freeQuantity().signum() > 0 && !item.isExpiredOn(today))
                .filter(item -> scope.allows(item.currentFacilityId()))
                .toList()));
    }

    /** What each lot cost: its purchase order line's price, per that line's unit. Lots without a price are left out. */
    @Transactional(readOnly = true)
    public Map<UUID, UnitPrice> lotPrices(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<MaterialReceiptItem> found = items.findByIdIn(ids);
        Map<UUID, UnitPrice> lines = purchaseOrders.linePrices(found.stream()
                .map(MaterialReceiptItem::getPurchaseOrderItemId).filter(Objects::nonNull).toList());
        Map<UUID, UnitPrice> prices = new HashMap<>();
        for (MaterialReceiptItem item : found) {
            UnitPrice line = item.getPurchaseOrderItemId() == null ? null : lines.get(item.getPurchaseOrderItemId());
            if (line != null && line.price() != null) {
                prices.put(item.getId(), line);
            }
        }
        return prices;
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

    /** First expiry, first out: lots that expire sooner come first, then lots without an expiry, oldest first. */
    private static List<MaterialReceiptItem> firstExpiringFirst(List<MaterialReceiptItem> oldestFirst) {
        return oldestFirst.stream().sorted(java.util.Comparator.comparing(MaterialReceiptItem::getExpiryDate,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))).toList();
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
                    item.getUomId(),
                    item.getInspectionStatus().name(),
                    item.getDisposition(),
                    item.isReleased(),
                    item.getReservedQuantity(),
                    item.currentFacilityId(),
                    item.getExpiryDate());
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

    /** Not limited to the caller's plants: the hold follows a calibration decision, wherever the lot is. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void holdForReinspection(UUID itemId, String reason) {
        MaterialReceiptItem item =
                items.findWithReceipt(itemId).orElseThrow(() -> new ResourceNotFoundException("Received batch", itemId));
        if (item.holdForReinspection()) {
            items.flush();
            audit.record("LOT_HELD_FOR_REINSPECTION", "MaterialReceipt", item.getReceipt().getId(), null,
                    Map.of("batch", item.getInternalBatch(), "reason", reason));
        }
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
        return receipts.findById(entityId).filter(receipt -> scope.allows(receipt.getFacilityId())).isPresent();
    }

    private MaterialReceiptItem loadItem(UUID id) {
        MaterialReceiptItem item =
                items.findWithReceipt(id).orElseThrow(() -> new ResourceNotFoundException("Received batch", id));
        scope.requireAllowed(item.currentFacilityId(), "Received batch", id.toString());
        return item;
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
                item.currentFacilityId(),
                material.id(),
                material.code(),
                material.name(),
                item.getSupplierBatch(),
                item.getInternalBatch(),
                item.getQuantity());
    }

    private MaterialReceipt load(UUID id) {
        MaterialReceipt receipt =
                receipts.findWithItems(id).orElseThrow(() -> new ResourceNotFoundException("Material receipt", id));
        scope.requireAllowed(receipt.getFacilityId(), "Material receipt", id.toString());
        return receipt;
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
                receipt.getItems().stream().filter(item -> item.getTransferredFromId() == null).map(item -> {
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
                            item.getDisposition(),
                            item.getExpiryDate());
                }).toList());
    }
}
