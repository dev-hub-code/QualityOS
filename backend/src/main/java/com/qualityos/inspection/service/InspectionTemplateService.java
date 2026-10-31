package com.qualityos.inspection.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.inspection.dto.TemplateDtos.ParameterRequest;
import com.qualityos.inspection.dto.TemplateDtos.ParameterSpec;
import com.qualityos.inspection.dto.TemplateDtos.SaveDraftRequest;
import com.qualityos.inspection.dto.TemplateDtos.SaveTemplateRequest;
import com.qualityos.inspection.dto.TemplateDtos.SpecSnapshot;
import com.qualityos.inspection.dto.TemplateDtos.TemplateDetail;
import com.qualityos.inspection.dto.TemplateDtos.TemplateSummary;
import com.qualityos.inspection.dto.TemplateDtos.VersionResponse;
import com.qualityos.inspection.entity.InspectionParameter;
import com.qualityos.inspection.entity.InspectionTemplate;
import com.qualityos.inspection.entity.TemplateVersion;
import com.qualityos.inspection.entity.TemplateVersion.SamplingType;
import com.qualityos.inspection.entity.TemplateVersion.Status;
import com.qualityos.inspection.repository.InspectionTemplateRepository;
import com.qualityos.inspection.repository.TemplateVersionRepository;
import com.qualityos.material.service.MaterialService;
import com.qualityos.product.service.ProductService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InspectionTemplateService {

    private final InspectionTemplateRepository templates;
    private final TemplateVersionRepository versions;
    private final MaterialService materials;
    private final ProductService products;
    private final AuditService audit;
    private final EntityManager entityManager;

    InspectionTemplateService(
            InspectionTemplateRepository templates,
            TemplateVersionRepository versions,
            MaterialService materials,
            ProductService products,
            AuditService audit,
            EntityManager entityManager) {
        this.templates = templates;
        this.versions = versions;
        this.materials = materials;
        this.products = products;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public PageResponse<TemplateSummary> list(
            String search, InspectionTemplate.Type type, UUID materialId, Pageable pageable) {
        Specification<InspectionTemplate> filter = Specification.<InspectionTemplate>where(Searches.notArchived())
                .and(Searches.contains(search, "code", "name"))
                .and(Searches.equalTo("inspectionType", type))
                .and(appliesToMaterial(materialId));
        return PageResponse.from(templates.findAll(filter, pageable), this::toSummary);
    }

    @Transactional(readOnly = true)
    public TemplateDetail get(UUID id) {
        InspectionTemplate template = load(id);
        return new TemplateDetail(
                toSummary(template),
                versions.findByTemplateIdOrderByVersionNumberDesc(id).stream()
                        .map(InspectionTemplateService::toResponse).toList());
    }

    @Transactional
    public TemplateDetail create(SaveTemplateRequest request) {
        String code = request.code().trim();
        if (templates.existsByCode(code)) {
            throw new ConflictException("A template with this code already exists.");
        }
        if (request.materialId() != null && request.productId() != null) {
            throw new BusinessRuleException("A template applies to a material or a product, not both.");
        }
        if (request.materialId() != null) {
            materials.requireActive(request.materialId());
        }
        if (request.productId() != null) {
            products.requireExists(request.productId());
        }
        InspectionTemplate template = new InspectionTemplate(code);
        template.setName(request.name().trim());
        template.setInspectionType(request.inspectionType());
        template.setMaterialId(request.materialId());
        template.setProductId(request.productId());
        templates.saveAndFlush(template);
        versions.saveAndFlush(new TemplateVersion(template.getId(), 1));
        audit.record("TEMPLATE_CREATED", "InspectionTemplate", template.getId());
        return get(template.getId());
    }

    @Transactional
    public void archive(UUID id) {
        load(id).setArchivedAt(Instant.now());
        audit.record("TEMPLATE_ARCHIVED", "InspectionTemplate", id);
    }

    /** Starts the next version as a copy of the latest, which stays in force until the copy is published. */
    @Transactional
    public TemplateDetail newVersion(UUID id) {
        load(id);
        List<TemplateVersion> existing = versions.findByTemplateIdOrderByVersionNumberDesc(id);
        TemplateVersion latest = existing.getFirst();
        if (latest.getStatus() == Status.DRAFT) {
            throw new ConflictException("This template already has a draft version.");
        }
        TemplateVersion draft = new TemplateVersion(id, latest.getVersionNumber() + 1);
        draft.setSamplingType(latest.getSamplingType());
        draft.setSampleSize(latest.getSampleSize());
        draft.setSamplePercent(latest.getSamplePercent());
        draft.setAcceptanceNumber(latest.getAcceptanceNumber());
        versions.saveAndFlush(draft);
        latest.getParameters().forEach(parameter -> draft.getParameters().add(copyOf(parameter, draft)));
        versions.saveAndFlush(draft);
        audit.record("TEMPLATE_VERSION_CREATED", "InspectionTemplate", id, null,
                Map.of("version", draft.getVersionNumber()));
        return get(id);
    }

    @Transactional
    public TemplateDetail saveDraft(UUID id, UUID versionId, SaveDraftRequest request) {
        load(id);
        TemplateVersion draft = versions.findWithParameters(versionId)
                .filter(found -> found.getTemplateId().equals(id))
                .orElseThrow(() -> new ResourceNotFoundException("Template version", versionId));
        draft.requireDraft();
        validate(request);
        draft.setChangeNote(request.changeNote());
        draft.setSamplingType(request.samplingType());
        draft.setSampleSize(request.samplingType() == SamplingType.FIXED ? request.sampleSize() : null);
        draft.setSamplePercent(request.samplingType() == SamplingType.PERCENTAGE ? request.samplePercent() : null);
        draft.setAcceptanceNumber(request.acceptanceNumber());
        draft.getParameters().clear();
        entityManager.flush();
        int order = 0;
        for (ParameterRequest parameter : request.parameters()) {
            draft.getParameters().add(toEntity(parameter, draft, order++));
        }
        versions.saveAndFlush(draft);
        audit.record("TEMPLATE_DRAFT_SAVED", "InspectionTemplate", id);
        return get(id);
    }

    /** Publishing is the approval step: it needs its own permission, and the version is final from then on. */
    @Transactional
    public TemplateDetail publish(UUID id, LocalDate effectiveFrom) {
        load(id);
        TemplateVersion draft = versions.findByTemplateIdAndStatus(id, Status.DRAFT)
                .orElseThrow(() -> new BusinessRuleException("There is no draft to publish."));
        versions.findByTemplateIdAndStatus(id, Status.PUBLISHED).ifPresent(previous -> {
            if (effectiveFrom.isBefore(previous.getEffectiveFrom())) {
                throw new BusinessRuleException("The new version cannot take effect before the current one.");
            }
            previous.supersede(effectiveFrom);
            versions.saveAndFlush(previous);
        });
        draft.publish(effectiveFrom, TenantContext.getCurrentUserId());
        versions.saveAndFlush(draft);
        audit.record("TEMPLATE_PUBLISHED", "InspectionTemplate", id, null, Map.of(
                "version", draft.getVersionNumber(), "effectiveFrom", effectiveFrom.toString()));
        return get(id);
    }

    /** The version an inspection started today must use. */
    @Transactional(readOnly = true)
    public TemplateVersion versionInForce(UUID templateId) {
        return versions.findInForce(templateId, LocalDate.now()).stream().findFirst()
                .orElseThrow(() -> new BusinessRuleException("This template has no published version in force."));
    }

    @Transactional(readOnly = true)
    public SpecSnapshot snapshot(UUID templateId, TemplateVersion version) {
        InspectionTemplate template = load(templateId);
        return new SpecSnapshot(
                template.getCode(),
                template.getName(),
                version.getVersionNumber(),
                version.getSamplingType().name(),
                version.getAcceptanceNumber(),
                version.getParameters().stream().map(InspectionTemplateService::toSpec).toList());
    }

    @Transactional(readOnly = true)
    public InspectionTemplate requireUsableFor(UUID templateId, UUID materialId) {
        InspectionTemplate template = load(templateId);
        if (template.getArchivedAt() != null) {
            throw new BusinessRuleException("This template is archived.");
        }
        boolean otherMaterial = template.getMaterialId() != null && !template.getMaterialId().equals(materialId);
        if (otherMaterial || template.getProductId() != null) {
            throw new BusinessRuleException("This template is for a different item.");
        }
        return template;
    }

    private InspectionTemplate load(UUID id) {
        return templates.findById(id).orElseThrow(() -> new ResourceNotFoundException("Inspection template", id));
    }

    /** Includes generic templates, which apply to any material. */
    private static Specification<InspectionTemplate> appliesToMaterial(UUID materialId) {
        return (root, query, builder) -> materialId == null
                ? builder.conjunction()
                : builder.and(
                        builder.isNull(root.get("productId")),
                        builder.or(
                                builder.isNull(root.get("materialId")),
                                builder.equal(root.get("materialId"), materialId)));
    }

    private void validate(SaveDraftRequest request) {
        if (request.samplingType() == SamplingType.FIXED && request.sampleSize() == null) {
            throw new BusinessRuleException("Enter a sample size.");
        }
        if (request.samplingType() == SamplingType.PERCENTAGE && request.samplePercent() == null) {
            throw new BusinessRuleException("Enter a sample percentage.");
        }
        Set<String> codes = new HashSet<>();
        for (ParameterRequest parameter : request.parameters()) {
            if (!codes.add(parameter.code().trim())) {
                throw new BusinessRuleException("Parameter %s appears twice.".formatted(parameter.code()));
            }
            if (parameter.minValue() != null && parameter.maxValue() != null
                    && parameter.minValue().compareTo(parameter.maxValue()) > 0) {
                throw new BusinessRuleException("%s: the minimum is above the maximum.".formatted(parameter.code()));
            }
            if (parameter.uomId() != null) {
                materials.requireUnit(parameter.uomId());
            }
        }
    }

    private TemplateSummary toSummary(InspectionTemplate template) {
        List<TemplateVersion> all = versions.findByTemplateIdOrderByVersionNumberDesc(template.getId());
        return new TemplateSummary(
                template.getId(),
                template.getCode(),
                template.getName(),
                template.getInspectionType().name(),
                template.getMaterialId(),
                template.getProductId(),
                all.stream().filter(v -> v.getStatus() == Status.PUBLISHED).findFirst()
                        .map(TemplateVersion::getVersionNumber).orElse(null),
                all.stream().anyMatch(v -> v.getStatus() == Status.DRAFT));
    }

    private static VersionResponse toResponse(TemplateVersion version) {
        return new VersionResponse(
                version.getId(),
                version.getVersionNumber(),
                version.getStatus().name(),
                version.getChangeNote(),
                version.getSamplingType().name(),
                version.getSampleSize(),
                version.getSamplePercent(),
                version.getAcceptanceNumber(),
                version.getEffectiveFrom(),
                version.getEffectiveTo(),
                version.getPublishedAt(),
                version.getParameters().stream().map(InspectionTemplateService::toSpec).toList());
    }

    private static ParameterSpec toSpec(InspectionParameter parameter) {
        return new ParameterSpec(
                parameter.getCode(),
                parameter.getName(),
                parameter.getDescription(),
                parameter.getDataType(),
                parameter.getUomId(),
                parameter.getNominalValue(),
                parameter.getMinValue(),
                parameter.getMaxValue(),
                parameter.getTolerance(),
                parameter.getExpectedText(),
                parameter.getCriticality(),
                parameter.isMandatory(),
                parameter.isPerSample(),
                parameter.isEvidenceRequired(),
                parameter.getInspectionMethod(),
                parameter.getRemarks());
    }

    private static InspectionParameter toEntity(ParameterRequest request, TemplateVersion version, int order) {
        InspectionParameter parameter = new InspectionParameter(version);
        parameter.setCode(request.code().trim());
        parameter.setName(request.name().trim());
        parameter.setDescription(request.description());
        parameter.setDataType(request.dataType());
        parameter.setUomId(request.uomId());
        parameter.setNominalValue(request.nominalValue());
        parameter.setMinValue(request.minValue());
        parameter.setMaxValue(request.maxValue());
        parameter.setTolerance(request.tolerance());
        parameter.setExpectedText(request.expectedText());
        parameter.setCriticality(request.criticality());
        parameter.setMandatory(request.mandatory());
        parameter.setPerSample(request.perSample());
        parameter.setEvidenceRequired(request.evidenceRequired());
        parameter.setInspectionMethod(request.inspectionMethod());
        parameter.setRemarks(request.remarks());
        parameter.setSortOrder(order);
        return parameter;
    }

    private static InspectionParameter copyOf(InspectionParameter source, TemplateVersion version) {
        InspectionParameter copy = new InspectionParameter(version);
        copy.setCode(source.getCode());
        copy.setName(source.getName());
        copy.setDescription(source.getDescription());
        copy.setDataType(source.getDataType());
        copy.setUomId(source.getUomId());
        copy.setNominalValue(source.getNominalValue());
        copy.setMinValue(source.getMinValue());
        copy.setMaxValue(source.getMaxValue());
        copy.setTolerance(source.getTolerance());
        copy.setExpectedText(source.getExpectedText());
        copy.setCriticality(source.getCriticality());
        copy.setMandatory(source.isMandatory());
        copy.setPerSample(source.isPerSample());
        copy.setEvidenceRequired(source.isEvidenceRequired());
        copy.setInspectionMethod(source.getInspectionMethod());
        copy.setRemarks(source.getRemarks());
        copy.setSortOrder(source.getSortOrder());
        return copy;
    }
}
