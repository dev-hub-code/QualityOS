package com.qualityos.material.service;

import com.qualityos.common.attachment.AttachmentPolicy;
import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.material.dto.MaterialDtos.CategoryResponse;
import com.qualityos.material.dto.MaterialDtos.MaterialDetail;
import com.qualityos.material.dto.MaterialDtos.MaterialReference;
import com.qualityos.material.dto.MaterialDtos.MaterialResponse;
import com.qualityos.material.dto.MaterialDtos.SaveMaterialRequest;
import com.qualityos.material.dto.MaterialDtos.SaveSpecificationRequest;
import com.qualityos.material.dto.MaterialDtos.SaveUnitRequest;
import com.qualityos.material.dto.MaterialDtos.SpecificationResponse;
import com.qualityos.material.dto.MaterialDtos.UnitResponse;
import com.qualityos.material.entity.Material;
import com.qualityos.material.entity.MaterialSpecification;
import com.qualityos.material.entity.ProductCategory;
import com.qualityos.material.entity.UnitOfMeasure;
import com.qualityos.material.mapper.MaterialMapper;
import com.qualityos.material.repository.MaterialRepository;
import com.qualityos.material.repository.MaterialSpecificationRepository;
import com.qualityos.material.repository.ProductCategoryRepository;
import com.qualityos.material.repository.UnitOfMeasureRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialService implements AttachmentPolicy {

    private final MaterialRepository materials;
    private final MaterialSpecificationRepository specifications;
    private final UnitOfMeasureRepository units;
    private final ProductCategoryRepository categories;
    private final MaterialMapper mapper;
    private final AuditService audit;

    MaterialService(
            MaterialRepository materials,
            MaterialSpecificationRepository specifications,
            UnitOfMeasureRepository units,
            ProductCategoryRepository categories,
            MaterialMapper mapper,
            AuditService audit) {
        this.materials = materials;
        this.specifications = specifications;
        this.units = units;
        this.categories = categories;
        this.mapper = mapper;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<MaterialResponse> list(String search, Pageable pageable) {
        Specification<Material> filter = Specification.<Material>where(Searches.notArchived())
                .and(Searches.contains(search, "code", "name", "grade"));
        return PageResponse.from(materials.findAll(filter, pageable), mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public MaterialDetail get(UUID id) {
        return new MaterialDetail(
                mapper.toResponse(load(id)),
                specifications.findByMaterialIdOrderBySortOrderAscNameAsc(id).stream()
                        .map(mapper::toResponse).toList());
    }

    @Transactional
    public MaterialResponse create(SaveMaterialRequest request) {
        String code = request.code().trim();
        if (materials.existsByCode(code)) {
            throw new ConflictException("A material with this code already exists.");
        }
        Material material = new Material(code);
        apply(material, request);
        materials.saveAndFlush(material);
        audit.record("MATERIAL_CREATED", "Material", material.getId());
        return mapper.toResponse(material);
    }

    @Transactional
    public MaterialResponse update(UUID id, SaveMaterialRequest request) {
        Material material = load(id);
        material.requireVersion(request.version());
        apply(material, request);
        materials.saveAndFlush(material);
        audit.record("MATERIAL_UPDATED", "Material", id);
        return mapper.toResponse(material);
    }

    @Transactional
    public void archive(UUID id) {
        load(id).setArchivedAt(Instant.now());
        audit.record("MATERIAL_ARCHIVED", "Material", id);
    }

    @Transactional
    public SpecificationResponse addSpecification(UUID materialId, SaveSpecificationRequest request) {
        load(materialId);
        requireValid(request);
        int nextOrder = specifications.findByMaterialIdOrderBySortOrderAscNameAsc(materialId).size();
        MaterialSpecification specification = specifications.save(new MaterialSpecification(
                materialId, request.name().trim(), request.nominalValue(), request.minValue(), request.maxValue(),
                request.textValue(), request.uomId(), request.notes(), nextOrder));
        audit.record("MATERIAL_SPECIFICATION_ADDED", "Material", materialId);
        return mapper.toResponse(specification);
    }

    @Transactional
    public void removeSpecification(UUID materialId, UUID specificationId) {
        MaterialSpecification specification = specifications.findById(specificationId)
                .filter(found -> found.getMaterialId().equals(materialId))
                .orElseThrow(() -> new ResourceNotFoundException("Specification", specificationId));
        specifications.delete(specification);
        audit.record("MATERIAL_SPECIFICATION_REMOVED", "Material", materialId);
    }

    /** Validates a specification; also used for product specifications. */
    @Transactional(readOnly = true)
    public void requireValid(SaveSpecificationRequest request) {
        if (request.minValue() != null && request.maxValue() != null
                && request.minValue().compareTo(request.maxValue()) > 0) {
            throw new BusinessRuleException("The minimum cannot be above the maximum.");
        }
        if (request.uomId() != null) {
            requireUnit(request.uomId());
        }
    }

    @Transactional(readOnly = true)
    public List<UnitResponse> listUnits() {
        return units.findVisible(TenantContext.getCurrentTenantId()).stream().map(mapper::toResponse).toList();
    }

    @Transactional
    public UnitResponse addUnit(SaveUnitRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String code = request.code().trim().toUpperCase(java.util.Locale.ROOT);
        if (units.existsVisibleByCode(code, tenantId)) {
            throw new ConflictException("This unit already exists.");
        }
        return mapper.toResponse(units.save(
                new UnitOfMeasure(tenantId, code, request.name().trim(), request.dimension().trim())));
    }

    @Transactional(readOnly = true)
    public void requireUnit(UUID id) {
        boolean visible = units.findVisible(TenantContext.getCurrentTenantId()).stream()
                .anyMatch(unit -> unit.getId().equals(id));
        if (!visible) {
            throw new BusinessRuleException("This unit of measure does not exist.");
        }
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> listCategories() {
        return categories.findAllByOrderByName().stream().map(mapper::toResponse).toList();
    }

    @Transactional
    public CategoryResponse addCategory(String name) {
        String trimmed = name.trim();
        if (categories.existsByName(trimmed)) {
            throw new ConflictException("This category already exists.");
        }
        return mapper.toResponse(categories.save(new ProductCategory(trimmed)));
    }

    @Transactional(readOnly = true)
    public void requireCategory(UUID id) {
        if (id != null && !categories.existsById(id)) {
            throw new BusinessRuleException("This category does not exist.");
        }
    }

    /** For new activity: archived materials cannot be ordered or used. */
    @Transactional(readOnly = true)
    public MaterialReference requireActive(UUID id) {
        Material material = load(id);
        if (material.getArchivedAt() != null) {
            throw new BusinessRuleException("Material %s is archived.".formatted(material.getCode()));
        }
        return mapper.toReference(material);
    }

    @Transactional(readOnly = true)
    public Map<UUID, MaterialReference> references(Collection<UUID> ids) {
        return materials.findAllById(ids).stream()
                .map(mapper::toReference)
                .collect(Collectors.toMap(MaterialReference::id, Function.identity()));
    }

    /** Ids of materials whose code or name contains the term. */
    @Transactional(readOnly = true)
    public List<UUID> idsMatching(String term) {
        return materials.findAll(Searches.<Material>contains(term, "code", "name")).stream()
                .map(Material::getId)
                .toList();
    }

    @Override
    public String entityType() {
        return "MATERIAL";
    }

    @Override
    public String viewPermission() {
        return Permissions.PRODUCT_VIEW;
    }

    @Override
    public String managePermission() {
        return Permissions.PRODUCT_MANAGE;
    }

    @Override
    public boolean parentExists(UUID entityId) {
        return materials.existsById(entityId);
    }

    private Material load(UUID id) {
        return materials.findById(id).orElseThrow(() -> new ResourceNotFoundException("Material", id));
    }

    private void apply(Material material, SaveMaterialRequest request) {
        requireUnit(request.uomId());
        requireCategory(request.categoryId());
        material.setName(request.name().trim());
        material.setDescription(request.description());
        material.setGrade(request.grade());
        material.setCategoryId(request.categoryId());
        material.setUomId(request.uomId());
        material.setInspectionRequired(request.inspectionRequired());
        material.setProperties(request.properties() == null ? new HashMap<>() : new HashMap<>(request.properties()));
    }
}
