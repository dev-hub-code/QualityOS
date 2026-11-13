package com.qualityos.production.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.inspection.dto.TemplateDtos.TemplateSummary;
import com.qualityos.inspection.service.InspectionTemplateService;
import com.qualityos.product.dto.ProductDtos.ProductReference;
import com.qualityos.product.service.ProductService;
import com.qualityos.production.dto.ProductionQualityDtos.CheckpointResponse;
import com.qualityos.production.dto.ProductionQualityDtos.SaveCheckpointRequest;
import com.qualityos.production.entity.QualityCheckpoint;
import com.qualityos.production.entity.WorkCenter;
import com.qualityos.production.repository.QualityCheckpointRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where production needs an inspection. A checkpoint names an existing inspection template: in-process (IPQC) for the
 * stages of an operation, final (FQC) for release. Changes reach orders released afterwards, never earlier ones.
 */
@Service
public class QualityCheckpointService {

    private static final String ENTITY = "QualityCheckpoint";

    private final QualityCheckpointRepository checkpoints;
    private final InspectionTemplateService templates;
    private final ProductService products;
    private final WorkCenterService workCenters;
    private final AuditService audit;

    QualityCheckpointService(
            QualityCheckpointRepository checkpoints,
            InspectionTemplateService templates,
            ProductService products,
            WorkCenterService workCenters,
            AuditService audit) {
        this.checkpoints = checkpoints;
        this.templates = templates;
        this.products = products;
        this.workCenters = workCenters;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<CheckpointResponse> list(boolean includeArchived) {
        List<QualityCheckpoint> rows = checkpoints.findAll().stream()
                .filter(checkpoint -> includeArchived || checkpoint.getArchivedAt() == null)
                .sorted(java.util.Comparator.comparing(QualityCheckpoint::getStage)
                        .thenComparing(QualityCheckpoint::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return responses(rows);
    }

    @Transactional
    public CheckpointResponse create(SaveCheckpointRequest request) {
        QualityCheckpoint checkpoint = new QualityCheckpoint(request.stage());
        apply(checkpoint, request);
        checkpoints.saveAndFlush(checkpoint);
        audit.record("QUALITY_CHECKPOINT_CREATED", ENTITY, checkpoint.getId(), null, Map.of(
                "name", checkpoint.getName(), "stage", checkpoint.getStage().name()));
        return responses(List.of(checkpoint)).getFirst();
    }

    @Transactional
    public CheckpointResponse update(UUID id, SaveCheckpointRequest request) {
        QualityCheckpoint checkpoint = load(id);
        if (checkpoint.getArchivedAt() != null) {
            throw new BusinessRuleException("This checkpoint is archived.");
        }
        if (checkpoint.getStage() != request.stage()) {
            throw new BusinessRuleException("A checkpoint keeps its stage. Archive it and add another.");
        }
        apply(checkpoint, request);
        checkpoints.saveAndFlush(checkpoint);
        audit.record("QUALITY_CHECKPOINT_UPDATED", ENTITY, id);
        return responses(List.of(checkpoint)).getFirst();
    }

    /** Orders already released keep it; new ones no longer take it. */
    @Transactional
    public void archive(UUID id) {
        QualityCheckpoint checkpoint = load(id);
        if (checkpoint.getArchivedAt() == null) {
            checkpoint.setArchivedAt(Instant.now());
            audit.record("QUALITY_CHECKPOINT_ARCHIVED", ENTITY, id);
        }
    }

    /** The checkpoints in use, for release. */
    @Transactional(readOnly = true)
    public List<QualityCheckpoint> active() {
        return checkpoints.findByArchivedAtIsNullOrderByStageAscNameAsc();
    }

    private void apply(QualityCheckpoint checkpoint, SaveCheckpointRequest request) {
        TemplateSummary template = templates.get(request.templateId()).template();
        String wanted = request.stage() == QualityCheckpoint.Stage.FINAL_RELEASE ? "FQC" : "IPQC";
        if (!wanted.equals(template.inspectionType())) {
            throw new BusinessRuleException(request.stage() == QualityCheckpoint.Stage.FINAL_RELEASE
                    ? "Final release takes a final (FQC) inspection template."
                    : "An operation takes an in-process (IPQC) inspection template.");
        }
        if (template.publishedVersion() == null) {
            throw new BusinessRuleException("Template %s has no published version yet.".formatted(template.code()));
        }
        if (template.materialId() != null
                || (template.productId() != null && !template.productId().equals(request.productId()))) {
            throw new BusinessRuleException(request.productId() == null
                    ? "Template %s is for one product. Choose that product, or a general template."
                            .formatted(template.code())
                    : "Template %s is for a different item.".formatted(template.code()));
        }
        if (request.productId() != null) {
            products.requireActive(request.productId());
        }
        if (request.stage() == QualityCheckpoint.Stage.FINAL_RELEASE
                && (request.workCenterId() != null || request.operationSequence() != null)) {
            throw new BusinessRuleException("Final release is for the whole batch, not one work centre or operation.");
        }
        if (request.workCenterId() != null) {
            workCenters.requireUsable(request.workCenterId());
        }
        checkpoint.setName(request.name().trim());
        checkpoint.setTemplateId(request.templateId());
        checkpoint.setProductId(request.productId());
        checkpoint.setWorkCenterId(request.workCenterId());
        checkpoint.setOperationSequence(request.operationSequence());
        checkpoint.setMandatory(request.mandatory() == null || request.mandatory());
    }

    private QualityCheckpoint load(UUID id) {
        return checkpoints.findById(id).orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
    }

    private List<CheckpointResponse> responses(List<QualityCheckpoint> rows) {
        Map<UUID, TemplateSummary> templateRefs = new HashMap<>();
        rows.stream().map(QualityCheckpoint::getTemplateId).distinct()
                .forEach(templateId -> templateRefs.put(templateId, templates.get(templateId).template()));
        Map<UUID, ProductReference> productRefs = products.references(rows.stream()
                .map(QualityCheckpoint::getProductId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, WorkCenter> centres = workCenters.byId(rows.stream()
                .map(QualityCheckpoint::getWorkCenterId).filter(Objects::nonNull).collect(Collectors.toSet()));
        return rows.stream().map(checkpoint -> {
            TemplateSummary template = templateRefs.get(checkpoint.getTemplateId());
            ProductReference product = checkpoint.getProductId() == null ? null
                    : productRefs.get(checkpoint.getProductId());
            WorkCenter centre = checkpoint.getWorkCenterId() == null ? null
                    : centres.get(checkpoint.getWorkCenterId());
            return new CheckpointResponse(checkpoint.getId(), checkpoint.getName(), checkpoint.getStage(),
                    checkpoint.getTemplateId(), template.code(), template.name(), template.inspectionType(),
                    checkpoint.getProductId(), product == null ? null : product.code(), checkpoint.getWorkCenterId(),
                    centre == null ? null : centre.getCode(), checkpoint.getOperationSequence(),
                    checkpoint.isMandatory(), checkpoint.getArchivedAt() != null);
        }).toList();
    }
}
