package com.qualityos.inspection.service;

import com.qualityos.common.attachment.AttachmentPolicy;
import com.qualityos.common.attachment.AttachmentService;
import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.DocumentNumberService;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.inspection.dto.InspectionDtos.ApproveRequest;
import com.qualityos.inspection.dto.InspectionDtos.CreateInspectionRequest;
import com.qualityos.inspection.dto.InspectionDtos.DecisionResponse;
import com.qualityos.inspection.dto.InspectionDtos.DispositionRequest;
import com.qualityos.inspection.dto.InspectionDtos.ExecuteRequest;
import com.qualityos.inspection.dto.InspectionDtos.InspectionDetail;
import com.qualityos.inspection.dto.InspectionDtos.InspectionSummary;
import com.qualityos.inspection.dto.InspectionDtos.ResultRequest;
import com.qualityos.inspection.dto.InspectionDtos.ResultResponse;
import com.qualityos.inspection.dto.TemplateDtos.ParameterSpec;
import com.qualityos.inspection.dto.TemplateDtos.SpecSnapshot;
import com.qualityos.inspection.entity.Inspection;
import com.qualityos.inspection.entity.Inspection.Disposition;
import com.qualityos.inspection.entity.Inspection.Evaluation;
import com.qualityos.inspection.entity.InspectionApproval;
import com.qualityos.inspection.entity.InspectionResult;
import com.qualityos.inspection.entity.InspectionTemplate;
import com.qualityos.inspection.entity.TemplateVersion;
import com.qualityos.inspection.repository.InspectionApprovalRepository;
import com.qualityos.inspection.repository.InspectionRepository;
import com.qualityos.inspection.repository.InspectionResultRepository;
import com.qualityos.inspection.service.EvaluationEngine.Outcome;
import com.qualityos.inspection.service.EvaluationEngine.Value;
import com.qualityos.material.dto.MaterialDtos.MaterialReference;
import com.qualityos.material.service.MaterialService;
import com.qualityos.materialreceipt.dto.MaterialReceiptDtos.ReceivedBatch;
import com.qualityos.materialreceipt.service.MaterialReceiptService;
import com.qualityos.supplier.dto.SupplierDtos.SupplierReference;
import com.qualityos.supplier.service.SupplierService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.qualityos.quality.dto.QualityDtos.InspectionFailure;
import com.qualityos.quality.service.NcrService;
import com.qualityos.traceability.dto.TraceabilityDtos.ProductionBatchView;
import com.qualityos.traceability.service.ProductionService;
import com.qualityos.inspection.dto.InspectionDtos.InspectionHistory;
import java.math.BigDecimal;
import java.util.Collection;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class InspectionService implements AttachmentPolicy {

    private static final String ENTITY = "Inspection";
    private static final String ATTACHMENT_TYPE = "INSPECTION";

    private final InspectionRepository inspections;
    private final InspectionResultRepository results;
    private final InspectionApprovalRepository approvals;
    private final InspectionTemplateService templates;
    private final MaterialReceiptService receipts;
    private final SupplierService suppliers;
    private final MaterialService materials;
    private final AttachmentService attachments;
    private final DocumentNumberService numbers;
    private final AuditService audit;
    private final InspectionAlerts inspectionAlerts;
    private final NcrService ncrs;
    private final ProductionService production;
    private final JsonMapper json;

    /** Lazy because the attachment service collects every policy, and this class is one. */
    InspectionService(
            InspectionRepository inspections,
            InspectionResultRepository results,
            InspectionApprovalRepository approvals,
            InspectionTemplateService templates,
            MaterialReceiptService receipts,
            SupplierService suppliers,
            MaterialService materials,
            @Lazy AttachmentService attachments,
            DocumentNumberService numbers,
            AuditService audit,
            InspectionAlerts inspectionAlerts,
            NcrService ncrs,
            ProductionService production,
            JsonMapper json) {
        this.inspections = inspections;
        this.results = results;
        this.approvals = approvals;
        this.templates = templates;
        this.receipts = receipts;
        this.suppliers = suppliers;
        this.materials = materials;
        this.attachments = attachments;
        this.numbers = numbers;
        this.audit = audit;
        this.inspectionAlerts = inspectionAlerts;
        this.ncrs = ncrs;
        this.production = production;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public PageResponse<InspectionSummary> list(
            String search, Inspection.Status status, UUID supplierId, Pageable pageable) {
        Specification<Inspection> filter =
                Specification.<Inspection>where(Searches.contains(search, "inspectionNumber", "batchNumber"))
                        .and(Searches.equalTo("status", status))
                        .and(Searches.equalTo("supplierId", supplierId));
        Page<Inspection> page = inspections.findAll(filter, pageable);
        Map<UUID, SupplierReference> supplierNames = suppliers.references(page.stream()
                .map(Inspection::getSupplierId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, MaterialReference> referenced = materials.references(page.stream()
                .map(Inspection::getMaterialId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, ProductionBatchView> made = production.views(page.stream()
                        .map(Inspection::getProductionBatchId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(ProductionBatchView::id, Function.identity()));
        return PageResponse.from(page, inspection -> {
            Subject subject;
            if (inspection.getMaterialId() != null) {
                MaterialReference material = referenced.get(inspection.getMaterialId());
                subject = new Subject(
                        supplierNames.get(inspection.getSupplierId()).name(), material.code(), material.name());
            } else {
                ProductionBatchView batch = made.get(inspection.getProductionBatchId());
                subject = new Subject(null, batch.productCode(), batch.productName());
            }
            return new InspectionSummary(
                    inspection.getId(),
                    inspection.getInspectionNumber(),
                    inspection.getInspectionType().name(),
                    subject.supplierName(),
                    subject.code(),
                    subject.name(),
                    inspection.getBatchNumber(),
                    inspection.getStatus().name(),
                    name(inspection.getEvaluatedResult()),
                    name(inspection.getFinalResult()),
                    name(inspection.getDisposition()),
                    inspection.getCreatedAt());
        });
    }

    @Transactional(readOnly = true)
    public InspectionDetail get(UUID id) {
        return toDetail(inspections.findById(id).orElseThrow(() -> new ResourceNotFoundException(ENTITY, id)));
    }

    /** Freezes the specification in force today. Later template changes never reach this inspection. */
    @Transactional
    public InspectionDetail create(CreateInspectionRequest request) {
        if ((request.receiptItemId() == null) == (request.productionBatchId() == null)) {
            throw new BusinessRuleException("Choose a received batch or a production batch to inspect.");
        }
        return toDetail(request.receiptItemId() != null ? inspectLot(request) : inspectProductionBatch(request));
    }

    private Inspection inspectLot(CreateInspectionRequest request) {
        ReceivedBatch batch = receipts.batch(request.receiptItemId());
        InspectionTemplate template = templates.requireUsableFor(request.templateId(), batch.materialId());
        TemplateVersion version = templates.versionInForce(template.getId());
        int samples = version.samplesFor(batch.quantity());
        receipts.startInspection(batch.itemId());
        return save(new Inspection(
                numbers.next("INS"),
                template.getInspectionType(),
                version.getId(),
                json.writeValueAsString(templates.snapshot(template.getId(), version)),
                batch.itemId(),
                null,
                batch.supplierId(),
                batch.facilityId(),
                batch.materialId(),
                batch.internalBatch(),
                batch.quantity(),
                samples));
    }

    private Inspection inspectProductionBatch(CreateInspectionRequest request) {
        ProductionBatchView batch = production.batch(request.productionBatchId());
        InspectionTemplate template = templates.requireUsableForProduct(request.templateId(), batch.productId());
        production.requireInspectable(batch.id(), template.getInspectionType().name());
        TemplateVersion version = templates.versionInForce(template.getId());
        BigDecimal quantity = batch.producedQuantity() != null ? batch.producedQuantity() : batch.plannedQuantity();
        return save(new Inspection(
                numbers.next("INS"),
                template.getInspectionType(),
                version.getId(),
                json.writeValueAsString(templates.snapshot(template.getId(), version)),
                null,
                batch.id(),
                null,
                batch.facilityId(),
                null,
                batch.batchNumber(),
                quantity,
                version.samplesFor(quantity)));
    }

    private Inspection save(Inspection inspection) {
        inspections.saveAndFlush(inspection);
        audit.record("INSPECTION_CREATED", ENTITY, inspection.getId());
        return inspection;
    }

    /** The trail of inspections for a set of lots and batches. Empty sets are fine. */
    @Transactional(readOnly = true)
    public List<InspectionHistory> history(Collection<UUID> lotIds, Collection<UUID> batchIds) {
        return inspections
                .findByReceiptItemIdInOrProductionBatchIdInOrderByCreatedAt(orNone(lotIds), orNone(batchIds))
                .stream()
                .map(inspection -> new InspectionHistory(
                        inspection.getId(),
                        inspection.getInspectionNumber(),
                        inspection.getInspectionType().name(),
                        inspection.getBatchNumber(),
                        inspection.getStatus().name(),
                        name(inspection.getFinalResult()),
                        name(inspection.getDisposition()),
                        inspection.getCreatedAt()))
                .toList();
    }

    /** Whether a lot has been inspected, which raises the bar for correcting it. */
    @Transactional(readOnly = true)
    public boolean hasInspection(UUID receiptItemId) {
        return !inspections.findByReceiptItemIdIn(List.of(receiptItemId)).isEmpty();
    }

    /** Keeps inspections of corrected lots, and their open NCRs, pointing at the right supplier and material. */
    @Transactional
    public void correctSubjects(Collection<UUID> receiptItemIds, UUID supplierId, UUID materialId) {
        List<Inspection> affected = inspections.findByReceiptItemIdIn(receiptItemIds);
        affected.forEach(inspection -> inspection.correctSubject(supplierId, materialId));
        ncrs.correctSubjects(affected.stream().map(Inspection::getId).toList(), supplierId, materialId);
    }

    private static Collection<UUID> orNone(Collection<UUID> ids) {
        return ids.isEmpty() ? List.of(new UUID(0, 0)) : ids;
    }

    private void recordOutcome(Inspection inspection, String disposition, boolean finalDecision) {
        if (inspection.getReceiptItemId() != null) {
            receipts.recordDisposition(inspection.getReceiptItemId(), disposition, finalDecision);
        } else {
            production.recordInspectionOutcome(
                    inspection.getProductionBatchId(), inspection.getInspectionType().name(), disposition);
        }
    }

    @Transactional
    public InspectionDetail cancel(UUID id) {
        Inspection inspection = lock(id);
        inspection.cancel();
        if (inspection.getReceiptItemId() != null) {
            receipts.releaseInspection(inspection.getReceiptItemId());
        }
        inspections.saveAndFlush(inspection);
        audit.record("INSPECTION_CANCELLED", ENTITY, id);
        return toDetail(inspection);
    }

    /** Saves any number of values, which may be a partial entry. A changed value leaves an audit event. */
    @Transactional
    public InspectionDetail execute(UUID id, ExecuteRequest request) {
        Inspection inspection = lock(id);
        inspection.recordingResults();
        inspections.saveAndFlush(inspection);
        SpecSnapshot specification = specificationOf(inspection);
        Map<String, ParameterSpec> parameters =
                specification.parameters().stream().collect(Collectors.toMap(ParameterSpec::code, Function.identity()));
        Map<String, InspectionResult> existing = new HashMap<>();
        results.findByInspectionIdOrderByParameterCodeAscSampleNumberAsc(id)
                .forEach(result -> existing.put(key(result.getParameterCode(), result.getSampleNumber()), result));

        for (ResultRequest entry : request.results()) {
            ParameterSpec parameter = parameters.get(entry.parameterCode());
            if (parameter == null) {
                throw new BusinessRuleException("%s is not part of this inspection.".formatted(entry.parameterCode()));
            }
            int allowedSamples = parameter.perSample() ? inspection.getSampleSize() : 1;
            if (entry.sampleNumber() > allowedSamples) {
                throw new BusinessRuleException(
                        "%s has only %d sample(s).".formatted(parameter.code(), allowedSamples));
            }
            Value value = typedValue(parameter, entry);
            InspectionResult result = existing.get(key(entry.parameterCode(), entry.sampleNumber()));
            String previous = result == null ? null : result.displayValue();
            if (result == null) {
                result = new InspectionResult(id, parameter.code(), entry.sampleNumber());
            }
            result.record(value.numeric(), value.text(), value.bool(), value.date(),
                    EvaluationEngine.evaluate(parameter, value), entry.remarks());
            results.save(result);
            if (previous != null && !previous.equals(result.displayValue())) {
                audit.record("INSPECTION_RESULT_CHANGED", ENTITY, id,
                        Map.of("parameter", parameter.code(), "sample", entry.sampleNumber(), "value", previous),
                        Map.of("value", result.displayValue()));
            }
        }
        results.flush();
        audit.record("INSPECTION_EXECUTED", ENTITY, id);
        return toDetail(inspection);
    }

    @Transactional
    public InspectionDetail submit(UUID id) {
        Inspection inspection = lock(id);
        SpecSnapshot specification = specificationOf(inspection);
        List<InspectionResult> recorded = results.findByInspectionIdOrderByParameterCodeAscSampleNumberAsc(id);
        requireComplete(inspection, specification, recorded);

        Map<String, List<Evaluation>> byParameter = recorded.stream().collect(Collectors.groupingBy(
                InspectionResult::getParameterCode,
                Collectors.mapping(InspectionResult::getEvaluation, Collectors.toList())));
        Outcome outcome = EvaluationEngine.overall(
                specification.parameters(), byParameter, specification.acceptanceNumber());
        inspection.submit(outcome.evaluation(), outcome.criticalFailure(), TenantContext.getCurrentUserId());
        inspections.saveAndFlush(inspection);
        decide(id, "SUBMITTED", outcome.evaluation().name());
        audit.record("INSPECTION_SUBMITTED", ENTITY, id, null, Map.of("evaluation", outcome.evaluation()));
        if (outcome.criticalFailure()) {
            recordOutcome(inspection, Disposition.HOLD.name(), false);
            decide(id, "HOLD", "Automatic hold: a critical parameter failed.");
            audit.record("MATERIAL_HELD", ENTITY, id);
        }
        inspectionAlerts.submitted(inspection, outcome.criticalFailure());
        return toDetail(inspection);
    }

    @Transactional
    public InspectionDetail approve(UUID id, ApproveRequest request) {
        Inspection inspection = lock(id);
        inspection.approve(request.result(), TenantContext.getCurrentUserId());
        inspections.saveAndFlush(inspection);
        decide(id, "APPROVED", request.note());
        audit.record("INSPECTION_APPROVED", ENTITY, id, null, Map.of("result", inspection.getFinalResult()));
        inspectionAlerts.approved(inspection);
        return toDetail(inspection);
    }

    @Transactional
    public InspectionDetail sendBack(UUID id, String note) {
        Inspection inspection = lock(id);
        UUID inspector = inspection.getSubmittedBy();
        inspection.sendBack(TenantContext.getCurrentUserId());
        inspections.saveAndFlush(inspection);
        recordOutcome(inspection, null, false);
        decide(id, "SENT_BACK", note);
        audit.record("INSPECTION_SENT_BACK", ENTITY, id, null, Map.of("note", note));
        inspectionAlerts.sentBack(inspection, inspector, note);
        return toDetail(inspection);
    }

    /** A conditional release of failed material is an exception, so it must state why. */
    @Transactional
    public InspectionDetail dispose(UUID id, DispositionRequest request) {
        Inspection inspection = lock(id);
        Disposition decision = request.disposition();
        if (decision == Disposition.CONDITIONAL_ACCEPTANCE && (request.note() == null || request.note().isBlank())) {
            throw new BusinessRuleException("Conditional acceptance needs a justification.");
        }
        Disposition previous = inspection.getDisposition();
        inspection.dispose(decision);
        inspections.saveAndFlush(inspection);
        recordOutcome(inspection, decision.name(), decision.isFinal());
        decide(id, decision.name(), request.note());
        audit.record("MATERIAL_DISPOSITION_RECORDED", ENTITY, id,
                previous == null ? null : Map.of("disposition", previous), Map.of("disposition", decision));
        inspectionAlerts.disposed(inspection);
        raiseNcrIfNeeded(inspection, decision);
        return toDetail(inspection);
    }

    @Override
    public String entityType() {
        return ATTACHMENT_TYPE;
    }

    @Override
    public String viewPermission() {
        return Permissions.INSPECTION_VIEW;
    }

    @Override
    public String managePermission() {
        return Permissions.INSPECTION_EXECUTE;
    }

    @Override
    public boolean parentExists(UUID entityId) {
        return inspections.existsById(entityId);
    }

    /** Evidence cannot change once the inspection is submitted. */
    @Override
    public boolean locked(UUID entityId) {
        return inspections.findById(entityId).map(inspection -> !inspection.isOpenForResults()).orElse(false);
    }

    private Inspection lock(UUID id) {
        return inspections.findForUpdate(id).orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
    }

    private void decide(UUID id, String decision, String note) {
        approvals.saveAndFlush(new InspectionApproval(id, decision, note));
    }

    /** Failed material always gets an NCR, however it was disposed of. So does good material rejected or reworked. */
    private void raiseNcrIfNeeded(Inspection inspection, Disposition decision) {
        boolean rejected = decision == Disposition.REJECT || decision == Disposition.REWORK;
        if (inspection.getFinalResult() != Inspection.Result.FAIL && !rejected) {
            return;
        }
        SpecSnapshot specification = specificationOf(inspection);
        Map<String, Boolean> criticality = specification.parameters().stream()
                .collect(Collectors.toMap(p -> p.code(), p -> "CRITICAL".equals(String.valueOf(p.criticality()))));
        List<String> failed = results.findByInspectionIdOrderByParameterCodeAscSampleNumberAsc(inspection.getId())
                .stream()
                .filter(result -> result.getEvaluation() == Evaluation.FAIL)
                .map(InspectionResult::getParameterCode)
                .distinct()
                .toList();
        String description = failed.isEmpty()
                ? "Material disposed of as " + decision + "."
                : "Failed parameters: " + String.join(", ", failed) + ". Disposition: " + decision + ".";
        ncrs.raiseFromInspection(new InspectionFailure(
                inspection.getId(),
                inspection.getInspectionNumber(),
                inspection.getSupplierId(),
                inspection.getMaterialId(),
                inspection.getBatchNumber(),
                failed.stream().anyMatch(code -> criticality.getOrDefault(code, false)),
                description,
                rejected ? inspection.getQuantity() : null));
    }

    private SpecSnapshot specificationOf(Inspection inspection) {
        return json.readValue(inspection.getSpecSnapshot(), SpecSnapshot.class);
    }

    private static String key(String code, int sample) {
        return code + "#" + sample;
    }

    private static Value typedValue(ParameterSpec parameter, ResultRequest entry) {
        Value value = switch (parameter.dataType()) {
            case NUMERIC -> new Value(entry.valueNumeric(), null, null, null);
            case TEXT -> new Value(null, entry.valueText(), null, null);
            case BOOLEAN -> new Value(null, null, entry.valueBoolean(), null);
            case DATE -> new Value(null, null, null, entry.valueDate());
        };
        if (value.numeric() == null && value.bool() == null && value.date() == null
                && (value.text() == null || value.text().isBlank())) {
            throw new BusinessRuleException("%s needs a value of type %s."
                    .formatted(parameter.code(), parameter.dataType()));
        }
        return value;
    }

    private void requireComplete(Inspection inspection, SpecSnapshot specification, List<InspectionResult> recorded) {
        Map<String, Long> counts = recorded.stream()
                .collect(Collectors.groupingBy(InspectionResult::getParameterCode, Collectors.counting()));
        Set<String> evidence = Set.copyOf(attachments.categories(ATTACHMENT_TYPE, inspection.getId()));
        List<String> problems = new ArrayList<>();
        for (ParameterSpec parameter : specification.parameters()) {
            long expected = parameter.perSample() ? inspection.getSampleSize() : 1;
            if (parameter.mandatory() && counts.getOrDefault(parameter.code(), 0L) < expected) {
                problems.add(parameter.code() + " is incomplete");
            }
            if (parameter.evidenceRequired() && !evidence.contains(parameter.code())) {
                problems.add(parameter.code() + " needs evidence");
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleException(String.join("; ", problems) + ".");
        }
    }

    private record Subject(String supplierName, String code, String name) {
    }

    /** What was inspected, in the same words whether it arrived from a supplier or was made here. */
    private Subject subjectOf(Inspection inspection) {
        if (inspection.getReceiptItemId() != null) {
            ReceivedBatch batch = receipts.batch(inspection.getReceiptItemId());
            return new Subject(batch.supplierName(), batch.materialCode(), batch.materialName());
        }
        ProductionBatchView batch = production.batch(inspection.getProductionBatchId());
        return new Subject(null, batch.productCode(), batch.productName());
    }

    private InspectionDetail toDetail(Inspection inspection) {
        Subject subject = subjectOf(inspection);
        return new InspectionDetail(
                inspection.getId(),
                inspection.getInspectionNumber(),
                inspection.getInspectionType().name(),
                inspection.getReceiptItemId(),
                inspection.getProductionBatchId(),
                subject.supplierName(),
                subject.code(),
                subject.name(),
                inspection.getBatchNumber(),
                inspection.getQuantity(),
                inspection.getSampleSize(),
                inspection.getStatus().name(),
                name(inspection.getEvaluatedResult()),
                name(inspection.getFinalResult()),
                name(inspection.getDisposition()),
                inspection.getSubmittedAt(),
                inspection.getApprovedAt(),
                specificationOf(inspection),
                results.findByInspectionIdOrderByParameterCodeAscSampleNumberAsc(inspection.getId()).stream()
                        .map(result -> new ResultResponse(
                                result.getParameterCode(),
                                result.getSampleNumber(),
                                result.getValueNumeric(),
                                result.getValueText(),
                                result.getValueBoolean(),
                                result.getValueDate(),
                                result.getEvaluation().name(),
                                result.getRemarks()))
                        .toList(),
                approvals.findByInspectionIdOrderByCreatedAtAsc(inspection.getId()).stream()
                        .map(decision -> new DecisionResponse(
                                decision.getDecision(),
                                decision.getNote(),
                                decision.getCreatedBy(),
                                decision.getCreatedAt()))
                        .toList());
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
