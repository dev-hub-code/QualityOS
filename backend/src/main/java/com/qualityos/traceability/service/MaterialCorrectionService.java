package com.qualityos.traceability.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.DocumentNumberService;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.inspection.service.InspectionService;
import com.qualityos.material.dto.MaterialDtos.MaterialReference;
import com.qualityos.material.service.MaterialService;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.AppliedLotCorrection;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.LotCorrection;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.LotView;
import com.qualityos.materialreceipt.service.MaterialReceiptService;
import com.qualityos.notification.service.NotificationService;
import com.qualityos.notification.service.NotificationType;
import com.qualityos.supplier.dto.SupplierDtos.SupplierReference;
import com.qualityos.supplier.service.SupplierService;
import com.qualityos.traceability.dto.TraceabilityDtos.ApproveCorrectionRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.ConsumptionRef;
import com.qualityos.traceability.dto.TraceabilityDtos.CorrectionApprovalView;
import com.qualityos.traceability.dto.TraceabilityDtos.CorrectionChange;
import com.qualityos.traceability.dto.TraceabilityDtos.CorrectionDetail;
import com.qualityos.traceability.dto.TraceabilityDtos.CorrectionImpact;
import com.qualityos.traceability.dto.TraceabilityDtos.CorrectionSummary;
import com.qualityos.traceability.dto.TraceabilityDtos.CreateCorrectionRequest;
import com.qualityos.traceability.entity.MaterialCorrection;
import com.qualityos.traceability.entity.MaterialCorrection.Status;
import com.qualityos.traceability.entity.MaterialCorrection.TargetType;
import com.qualityos.traceability.entity.MaterialCorrectionApproval;
import com.qualityos.traceability.entity.MaterialCorrectionItem;
import com.qualityos.traceability.entity.MaterialCorrectionItem.Field;
import com.qualityos.traceability.repository.MaterialCorrectionApprovalRepository;
import com.qualityos.traceability.repository.MaterialCorrectionItemRepository;
import com.qualityos.traceability.repository.MaterialCorrectionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Amends a lot, or the material recorded against a batch, once it is too late to reverse the entry. Nobody approves
 * their own request. When the material has gone beyond the lot, two people must approve and acknowledge the impact.
 */
@Service
public class MaterialCorrectionService {

    private static final String ENTITY = "MaterialCorrection";

    private final MaterialCorrectionRepository corrections;
    private final MaterialCorrectionItemRepository items;
    private final MaterialCorrectionApprovalRepository approvals;
    private final MaterialReceiptService receipts;
    private final ProductionService production;
    private final TraceabilityService traceability;
    private final InspectionService inspections;
    private final MaterialService materials;
    private final SupplierService suppliers;
    private final TenantAccessService access;
    private final NotificationService notifications;
    private final DocumentNumberService numbers;
    private final AuditService audit;
    private final FacilityScope scope;

    MaterialCorrectionService(
            MaterialCorrectionRepository corrections,
            MaterialCorrectionItemRepository items,
            MaterialCorrectionApprovalRepository approvals,
            MaterialReceiptService receipts,
            ProductionService production,
            TraceabilityService traceability,
            InspectionService inspections,
            MaterialService materials,
            SupplierService suppliers,
            TenantAccessService access,
            NotificationService notifications,
            DocumentNumberService numbers,
            AuditService audit,
            FacilityScope scope) {
        this.scope = scope;
        this.corrections = corrections;
        this.items = items;
        this.approvals = approvals;
        this.receipts = receipts;
        this.production = production;
        this.traceability = traceability;
        this.inspections = inspections;
        this.materials = materials;
        this.suppliers = suppliers;
        this.access = access;
        this.notifications = notifications;
        this.numbers = numbers;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<CorrectionSummary> list(String search, Status status, Pageable pageable) {
        Specification<MaterialCorrection> filter = Specification
                .<MaterialCorrection>where(Searches.contains(search, "correctionNumber"))
                .and(Searches.equalTo("status", status));
        Page<MaterialCorrection> page = corrections.findAll(scope.limit(filter, "facilityId"), pageable);
        Map<UUID, String> names = access.activeMembers();
        return PageResponse.from(page, correction -> new CorrectionSummary(
                correction.getId(),
                correction.getCorrectionNumber(),
                correction.getTargetType().name(),
                correction.getStatus().name(),
                subject(correction),
                correction.getReason(),
                names.get(correction.getCreatedBy()),
                correction.getCreatedAt()));
    }

    @Transactional(readOnly = true)
    public CorrectionDetail get(UUID id) {
        return toDetail(load(id));
    }

    /** @param id a received lot or a production batch */
    @Transactional(readOnly = true)
    public List<CorrectionDetail> history(UUID id) {
        return corrections.findHistory(id).stream().map(this::toDetail).toList();
    }

    @Transactional
    public CorrectionDetail create(CreateCorrectionRequest request) {
        if ((request.receiptItemId() == null) == (request.consumptionId() == null)) {
            throw new BusinessRuleException("Choose one lot or one material entry to correct.");
        }
        return request.receiptItemId() != null ? createForLot(request) : createForConsumption(request);
    }

    private CorrectionDetail createForLot(CreateCorrectionRequest request) {
        LotView lot = lot(request.receiptItemId());
        UUID facilityId = receipts.batch(lot.itemId()).facilityId();
        List<Change> changes = new ArrayList<>();
        if (request.materialId() != null && !request.materialId().equals(lot.materialId())) {
            MaterialReference material = materials.references(Set.of(request.materialId())).get(request.materialId());
            if (material == null) {
                throw new BusinessRuleException("This material does not exist.");
            }
            changes.add(new Change(Field.MATERIAL, lot.materialId().toString(), material.id().toString(),
                    lot.materialCode(), material.code()));
        }
        if (request.supplierId() != null && !request.supplierId().equals(lot.supplierId())) {
            SupplierReference supplier = suppliers.requireApproved(request.supplierId());
            changes.add(new Change(Field.SUPPLIER, lot.supplierId().toString(), supplier.id().toString(),
                    lot.supplierName(), supplier.name()));
        }
        if (request.supplierBatch() != null && !request.supplierBatch().isBlank()
                && !request.supplierBatch().trim().equals(lot.supplierBatch())) {
            String batch = request.supplierBatch().trim();
            changes.add(new Change(Field.SUPPLIER_BATCH, lot.supplierBatch(), batch, lot.supplierBatch(), batch));
        }
        if (request.quantity() != null && request.quantity().compareTo(lot.quantity()) != 0) {
            changes.add(new Change(Field.QUANTITY, lot.quantity().toPlainString(), request.quantity().toPlainString(),
                    lot.quantity().toPlainString(), request.quantity().toPlainString()));
        }
        return save(facilityId, new MaterialCorrection(numbers.next("MC"), TargetType.LOT, lot.itemId(), null, null,
                request.reason().trim()), changes);
    }

    private CorrectionDetail createForConsumption(CreateCorrectionRequest request) {
        ConsumptionRef entry = consumption(request.consumptionId());
        UUID facilityId = production.batch(entry.childBatchId()).facilityId();
        scope.requireAllowed(facilityId, "Batch", entry.childBatchId().toString());
        List<Change> changes = new ArrayList<>();
        if (request.correctedLotId() != null && !request.correctedLotId().equals(entry.lotId())) {
            LotView replacement = lot(request.correctedLotId());
            if (!replacement.released()) {
                throw new BusinessRuleException("Batch " + replacement.internalBatch() + " has not been released for use.");
            }
            String original = entry.lotId() == null ? null : lot(entry.lotId()).internalBatch();
            changes.add(new Change(Field.SOURCE_LOT, entry.lotId() == null ? null : entry.lotId().toString(),
                    replacement.itemId().toString(), original, replacement.internalBatch()));
        }
        if (request.quantity() != null && request.quantity().compareTo(entry.quantity()) != 0) {
            changes.add(new Change(Field.QUANTITY, entry.quantity().toPlainString(), request.quantity().toPlainString(),
                    entry.quantity().toPlainString(), request.quantity().toPlainString()));
        }
        return save(facilityId, new MaterialCorrection(numbers.next("MC"), TargetType.CONSUMPTION, entry.lotId(), entry.id(),
                entry.childBatchId(), request.reason().trim()), changes);
    }

    private CorrectionDetail save(UUID facilityId, MaterialCorrection correction, List<Change> changes) {
        correction.setFacilityId(facilityId);
        if (changes.isEmpty()) {
            throw new BusinessRuleException("Nothing here differs from the record.");
        }
        corrections.saveAndFlush(correction);
        items.saveAll(changes.stream().map(change -> new MaterialCorrectionItem(correction.getId(), change.field(),
                change.original(), change.corrected(), change.originalLabel(), change.correctedLabel())).toList());
        audit.record("MATERIAL_CORRECTION_CREATED", ENTITY, correction.getId(), null,
                Map.of("number", correction.getCorrectionNumber(), "reason", correction.getReason()));
        return toDetail(correction);
    }

    @Transactional
    public CorrectionDetail submit(UUID id) {
        MaterialCorrection correction = lock(id);
        requireRequester(correction);
        correction.submit(needsAdditionalApproval(impactOf(correction)), Instant.now());
        audit.record("MATERIAL_CORRECTION_SUBMITTED", ENTITY, id);
        Set<UUID> approvers = new HashSet<>(access.holdersOf(Permissions.MATERIAL_CORRECTION_APPROVE));
        approvers.remove(correction.getCreatedBy());
        notifications.notify(approvers, NotificationType.MATERIAL_CORRECTION_AWAITING_APPROVAL,
                correction.getCorrectionNumber() + " needs approval", correction.getReason(), link(correction));
        return toDetail(correction);
    }

    /** Applies the correction in the same transaction as the last approval: if it cannot be applied, nothing changes. */
    @Transactional
    public CorrectionDetail approve(UUID id, ApproveCorrectionRequest request) {
        MaterialCorrection correction = lock(id);
        UUID approver = TenantContext.getCurrentUserId();
        if (approver.equals(correction.getCreatedBy())) {
            throw new BusinessRuleException("Someone other than the requester must approve this.");
        }
        if (correction.getStatus() != Status.PENDING_APPROVAL) {
            throw new BusinessRuleException("Only a correction awaiting approval can be approved.");
        }
        List<MaterialCorrectionApproval> recorded = approvals.findByCorrectionIdOrderByCreatedAt(id);
        if (recorded.stream().anyMatch(approval -> approval.getApproverId().equals(approver))) {
            throw new BusinessRuleException("A second person must give the other approval.");
        }
        if (needsAdditionalApproval(impactOf(correction))) {
            correction.requireAdditionalApproval();
            if (!request.impactAcknowledged()) {
                throw new BusinessRuleException("This material has gone further than the lot. Review and acknowledge the impact.");
            }
        }
        approvals.save(new MaterialCorrectionApproval(id, approver, request.note(), request.impactAcknowledged()));
        audit.record("MATERIAL_CORRECTION_APPROVAL_RECORDED", ENTITY, id);
        if (recorded.size() + 1 >= correction.approvalsNeeded()) {
            Instant now = Instant.now();
            correction.approved(now);
            audit.record("MATERIAL_CORRECTION_APPROVED", ENTITY, id);
            correction.applied(apply(correction), now);
            audit.record("MATERIAL_CORRECTION_APPLIED", ENTITY, id, originals(id), Map.of("number", correction.getCorrectionNumber()));
            notifyDecision(correction, " was approved and applied");
        }
        return toDetail(correction);
    }

    @Transactional
    public CorrectionDetail reject(UUID id, String note) {
        MaterialCorrection correction = lock(id);
        if (TenantContext.getCurrentUserId().equals(correction.getCreatedBy())) {
            throw new BusinessRuleException("Someone other than the requester must decide this. You can cancel it instead.");
        }
        correction.reject(note.trim(), Instant.now());
        audit.record("MATERIAL_CORRECTION_REJECTED", ENTITY, id, null, Map.of("note", note.trim()));
        notifyDecision(correction, " was rejected");
        return toDetail(correction);
    }

    @Transactional
    public CorrectionDetail cancel(UUID id, String note) {
        MaterialCorrection correction = lock(id);
        requireRequester(correction);
        correction.cancel(note.trim());
        audit.record("MATERIAL_CORRECTION_CANCELLED", ENTITY, id, null, Map.of("note", note.trim()));
        return toDetail(correction);
    }

    private UUID apply(MaterialCorrection correction) {
        Map<Field, MaterialCorrectionItem> changes = items.findByCorrectionIdIn(List.of(correction.getId())).stream()
                .collect(Collectors.toMap(MaterialCorrectionItem::getFieldName, item -> item));
        return correction.getTargetType() == TargetType.LOT
                ? applyToLot(correction, changes)
                : applyToConsumption(correction, changes);
    }

    private UUID applyToLot(MaterialCorrection correction, Map<Field, MaterialCorrectionItem> changes) {
        LotView lot = lot(correction.getReceiptItemId());
        requireUnchanged(changes.get(Field.MATERIAL), lot.materialId().toString());
        requireUnchanged(changes.get(Field.SUPPLIER), lot.supplierId().toString());
        requireUnchanged(changes.get(Field.SUPPLIER_BATCH), lot.supplierBatch());
        requireUnchanged(changes.get(Field.QUANTITY), lot.quantity().toPlainString());
        UUID material = idOf(changes.get(Field.MATERIAL));
        UUID supplier = idOf(changes.get(Field.SUPPLIER));
        AppliedLotCorrection applied = receipts.correct(lot.itemId(), new LotCorrection(
                material,
                supplier,
                valueOf(changes.get(Field.SUPPLIER_BATCH)),
                quantityOf(changes.get(Field.QUANTITY))));
        if (!applied.affectedItems().isEmpty()) {
            inspections.correctSubjects(applied.affectedItems(), supplier, material);
        }
        return null;
    }

    private UUID applyToConsumption(MaterialCorrection correction, Map<Field, MaterialCorrectionItem> changes) {
        ConsumptionRef entry = consumption(correction.getBatchRelationshipId());
        BigDecimal corrected = quantityOf(changes.get(Field.QUANTITY));
        return production.replaceConsumption(
                entry.id(),
                idOf(changes.get(Field.SOURCE_LOT)),
                corrected != null ? corrected : entry.quantity(),
                correction.getReason());
    }

    /** Someone may have changed the record after this request was written. Its "before" must still be true. */
    private static void requireUnchanged(MaterialCorrectionItem change, String current) {
        if (change != null && !Objects.equals(change.getOriginalValue(), current)) {
            throw new ConflictException("This record has changed since the correction was requested. Raise a new one.");
        }
    }

    private static String valueOf(MaterialCorrectionItem change) {
        return change == null ? null : change.getCorrectedValue();
    }

    private static UUID idOf(MaterialCorrectionItem change) {
        return change == null ? null : UUID.fromString(change.getCorrectedValue());
    }

    private static BigDecimal quantityOf(MaterialCorrectionItem change) {
        return change == null ? null : new BigDecimal(change.getCorrectedValue());
    }

    private Map<String, String> originals(UUID id) {
        return items.findByCorrectionIdIn(List.of(id)).stream().collect(Collectors.toMap(
                item -> item.getFieldName().name(), item -> Objects.toString(item.getOriginalLabel(), "")));
    }

    private CorrectionImpact impactOf(MaterialCorrection correction) {
        if (correction.getTargetType() == TargetType.LOT) {
            UUID lot = correction.getReceiptItemId();
            return traceability.lotImpact(lot, inspections.hasInspection(lot));
        }
        return traceability.batchImpact(correction.getProductionBatchId());
    }

    private boolean needsAdditionalApproval(CorrectionImpact impact) {
        return impact.inspected() || !impact.batches().isEmpty() || !impact.customers().isEmpty();
    }

    private void notifyDecision(MaterialCorrection correction, String outcome) {
        Set<UUID> affected = new HashSet<>(access.holdersOf(Permissions.MATERIAL_CORRECTION_APPROVE));
        affected.add(correction.getCreatedBy());
        affected.remove(TenantContext.getCurrentUserId());
        notifications.notify(affected, NotificationType.MATERIAL_CORRECTION_DECIDED,
                correction.getCorrectionNumber() + outcome, correction.getReason(), link(correction));
    }

    private static String link(MaterialCorrection correction) {
        return "/material-corrections/" + correction.getId();
    }

    private static void requireRequester(MaterialCorrection correction) {
        if (!TenantContext.getCurrentUserId().equals(correction.getCreatedBy())) {
            throw new BusinessRuleException("Only the person who requested this correction can do that.");
        }
    }

    private LotView lot(UUID id) {
        return receipts.lots(List.of(id)).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Batch", id));
    }

    private ConsumptionRef consumption(UUID id) {
        return production.activeConsumption(id)
                .orElseThrow(() -> new BusinessRuleException("This material entry does not exist or was already corrected."));
    }

    private String subject(MaterialCorrection correction) {
        if (correction.getTargetType() == TargetType.LOT) {
            return lot(correction.getReceiptItemId()).internalBatch();
        }
        return production.batch(correction.getProductionBatchId()).batchNumber();
    }

    private CorrectionDetail toDetail(MaterialCorrection correction) {
        Map<UUID, String> names = access.activeMembers();
        boolean open = correction.getStatus() == Status.DRAFT || correction.getStatus() == Status.PENDING_APPROVAL;
        return new CorrectionDetail(
                correction.getId(),
                correction.getCorrectionNumber(),
                correction.getTargetType().name(),
                correction.getStatus().name(),
                subject(correction),
                correction.getProductionBatchId(),
                correction.getReason(),
                correction.getCreatedBy(),
                names.get(correction.getCreatedBy()),
                correction.getCreatedAt(),
                correction.isAdditionalApproval(),
                correction.approvalsNeeded(),
                items.findByCorrectionIdIn(List.of(correction.getId())).stream()
                        .map(item -> new CorrectionChange(
                                item.getFieldName().name(), item.getOriginalLabel(), item.getCorrectedLabel()))
                        .toList(),
                approvals.findByCorrectionIdOrderByCreatedAt(correction.getId()).stream()
                        .map(approval -> new CorrectionApprovalView(
                                names.get(approval.getApproverId()), approval.getNote(), approval.getCreatedAt()))
                        .toList(),
                open ? impactOf(correction) : null,
                correction.getDecisionNote(),
                correction.getAppliedAt());
    }

    private MaterialCorrection load(UUID id) {
        return visible(corrections.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Material correction", id)));
    }

    private MaterialCorrection lock(UUID id) {
        return visible(corrections.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Material correction", id)));
    }

    private MaterialCorrection visible(MaterialCorrection correction) {
        scope.requireAllowed(correction.getFacilityId(), "Material correction", correction.getId().toString());
        return correction;
    }

    private record Change(Field field, String original, String corrected, String originalLabel, String correctedLabel) {
    }
}
