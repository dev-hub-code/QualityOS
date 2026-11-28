package com.qualityos.material.service;

import com.qualityos.common.archive.ArchiveGuard;
import com.qualityos.common.attachment.AttachmentPolicy;
import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.material.dto.MaterialDtos.AddConversionRequest;
import com.qualityos.material.dto.MaterialDtos.CategoryResponse;
import com.qualityos.material.dto.MaterialDtos.ConversionResponse;
import com.qualityos.material.dto.MaterialDtos.ItemUnits;
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
import com.qualityos.material.entity.ItemUnitConversion;
import com.qualityos.material.entity.UnitOfMeasure;
import com.qualityos.material.mapper.MaterialMapper;
import com.qualityos.material.repository.MaterialRepository;
import com.qualityos.material.repository.MaterialSpecificationRepository;
import com.qualityos.material.repository.ProductCategoryRepository;
import com.qualityos.material.repository.UnitOfMeasureRepository;
import java.math.BigDecimal;
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
import org.springframework.dao.DataIntegrityViolationException;
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
    private final List<ArchiveGuard> archiveGuards;
    private final UnitChangeGuard unitGuard;
    private final UnitConverter converter;
    private final com.qualityos.material.repository.ItemUnitConversionRepository conversions;

    MaterialService(
            MaterialRepository materials,
            MaterialSpecificationRepository specifications,
            UnitOfMeasureRepository units,
            ProductCategoryRepository categories,
            MaterialMapper mapper,
            AuditService audit,
            List<ArchiveGuard> archiveGuards,
            UnitChangeGuard unitGuard,
            UnitConverter converter,
            com.qualityos.material.repository.ItemUnitConversionRepository conversions) {
        this.materials = materials;
        this.specifications = specifications;
        this.units = units;
        this.categories = categories;
        this.mapper = mapper;
        this.audit = audit;
        this.archiveGuards = archiveGuards;
        this.unitGuard = unitGuard;
        this.converter = converter;
        this.conversions = conversions;
    }

    @Transactional(readOnly = true)
    public PageResponse<MaterialResponse> list(String search, boolean archived, Pageable pageable) {
        Specification<Material> filter = Specification.<Material>where(Searches.archived(archived))
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
        if (materials.existsByCodeIgnoreCase(code)) {
            throw new ConflictException("A material with this code already exists.");
        }
        Material material = new Material(code);
        apply(material, request);
        materials.saveAndFlush(material);
        var created = mapper.toResponse(material);
        audit.record("MATERIAL_CREATED", "Material", material.getId(), null, audit.state(created));
        return created;
    }

    @Transactional
    public MaterialResponse update(UUID id, SaveMaterialRequest request) {
        Material material = load(id);
        var before = audit.state(mapper.toResponse(material));
        material.requireVersion(request.version());
        unitGuard.requireMaterialUnitFree(material.getCode(),
                material.getUomId() == null ? null : unitCode(material.getUomId()), id, material.getUomId(),
                request.uomId());
        apply(material, request);
        materials.saveAndFlush(material);
        audit.recordChange("MATERIAL_UPDATED", "Material", id, before, audit.state(mapper.toResponse(material)));
        return mapper.toResponse(material);
    }

    @Transactional
    public void archive(UUID id) {
        Material material = load(id);
        ArchiveGuard.check(archiveGuards, ArchiveGuard.Kind.MATERIAL, id, "material");
        material.setArchivedAt(Instant.now());
        audit.record("MATERIAL_ARCHIVED", "Material", id);
    }

    /** Brings an archived material back into lists and pickers. */
    @Transactional
    public void restore(UUID id) {
        var restored = load(id);
        if (restored.getArchivedAt() == null) {
            throw new BusinessRuleException("This material is not archived.");
        }
        restored.setArchivedAt(null);
        audit.record("MATERIAL_RESTORED", "Material", id);
    }

    @Transactional
    public SpecificationResponse addSpecification(UUID materialId, SaveSpecificationRequest request) {
        load(materialId);
        requireValid(request);
        int nextOrder = specifications.findByMaterialIdOrderBySortOrderAscNameAsc(materialId).size();
        MaterialSpecification specification = specifications.save(new MaterialSpecification(
                materialId, request.name().trim(), request.nominalValue(), request.minValue(), request.maxValue(),
                request.textValue(), request.uomId(), request.notes(), nextOrder));
        audit.record("MATERIAL_SPECIFICATION_ADDED", "Material", materialId, null, audit.state(mapper.toResponse(specification)));
        return mapper.toResponse(specification);
    }

    @Transactional
    public void removeSpecification(UUID materialId, UUID specificationId) {
        MaterialSpecification specification = specifications.findById(specificationId)
                .filter(found -> found.getMaterialId().equals(materialId))
                .orElseThrow(() -> new ResourceNotFoundException("Specification", specificationId));
        var before = audit.state(mapper.toResponse(specification));
        specifications.delete(specification);
        audit.record("MATERIAL_SPECIFICATION_REMOVED", "Material", materialId, before, null);
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
        if (!code.matches("[A-Z0-9%°µ/._-]+")) {
            throw new BusinessRuleException("A unit code is letters and digits, such as MG or KL, with no spaces.");
        }
        Kind kind = kindOf(request);
        UnitOfMeasure unit = units.save(
                new UnitOfMeasure(tenantId, code, request.name().trim(), kind.dimension(), kind.toBase()));
        audit.record("UNIT_ADDED", "UnitOfMeasure", unit.getId(), null, Map.of("code", code,
                "dimension", unit.getDimension(), "toBase", String.valueOf(kind.toBase())));
        return mapper.toResponse(unit);
    }

    private record Kind(String dimension, BigDecimal toBase) {
    }

    /**
     * What a new unit measures, and how it converts. A kind is matched to one already in use whatever its case, so
     * "mass" joins KG and GM; a kind that converts needs a factor, and one that does not may not have one.
     */
    private Kind kindOf(SaveUnitRequest request) {
        List<UnitOfMeasure> visible = units.findVisible(TenantContext.getCurrentTenantId());
        if (request.equalsUnitId() != null || request.equalsQuantity() != null) {
            if (request.equalsUnitId() == null || request.equalsQuantity() == null) {
                throw new BusinessRuleException("Say how much of which unit one of this equals, such as 0.001 GM.");
            }
            UnitOfMeasure other = visibleUnit(request.equalsUnitId());
            if (other.getToBase() == null) {
                throw new BusinessRuleException(other.getCode() + " does not convert to any other unit, so a unit "
                        + "cannot be defined from it.");
            }
            BigDecimal toBase = request.equalsQuantity().multiply(other.getToBase()).stripTrailingZeros();
            if (toBase.scale() > 12 || toBase.precision() - toBase.scale() > 12) {
                throw new BusinessRuleException("That unit is too small or too large to keep. Define it from a unit "
                        + "nearer its size.");
            }
            return new Kind(other.getDimension(), toBase);
        }
        String typed = request.dimension() == null || request.dimension().isBlank() ? "Other" : request.dimension().trim();
        List<UnitOfMeasure> sameKind = visible.stream().filter(u -> u.getDimension().equalsIgnoreCase(typed)).toList();
        String dimension = sameKind.isEmpty() ? typed : sameKind.getFirst().getDimension();
        boolean converts = sameKind.stream().anyMatch(u -> u.getToBase() != null);
        if (request.toBase() != null && !converts) {
            throw new BusinessRuleException(sameKind.isEmpty()
                    ? "No unit measures %s yet, so there is nothing to convert to. Define it from a unit it equals, such as 0.001 GM, or leave the factor out.".formatted(typed)
                    : "%s units do not convert to one another, so leave the factor out.".formatted(dimension));
        }
        if (request.toBase() == null && converts) {
            throw new BusinessRuleException(("%s units convert to one another. Say how much of another %s unit one of "
                    + "this equals, such as 0.001 GM.").formatted(dimension, dimension));
        }
        return new Kind(dimension, request.toBase());
    }

    /**
     * A quantity entered in one unit, in another unit of the same kind.
     *
     * @throws BusinessRuleException when the units measure different things, or the result is too small to keep
     */
    @Transactional(readOnly = true)
    public BigDecimal convert(BigDecimal quantity, UUID from, UUID to) {
        return convert(quantity, from, to, null);
    }

    /**
     * A quantity of one material or product in another unit: by shared factors between units of one kind, or by
     * the item's own conversions, such as 1 DRUM = 200 KG.
     *
     * @param itemId the material or product, or null for shared factors alone
     * @throws BusinessRuleException when nothing converts the units for this item, or the result is too small to keep
     */
    @Transactional(readOnly = true)
    public BigDecimal convert(BigDecimal quantity, UUID from, UUID to, UUID itemId) {
        if (from.equals(to)) {
            return quantity;
        }
        return rounded(quantity, from, to, itemId).converted();
    }

    /**
     * A quantity someone typed in one unit, in another unit of the same kind, refused unless it converts exactly.
     * Quantities are kept to six decimal places, so rounding a typed 1 PCS to 0.083333 DOZ would lose a little at
     * each step and leave stock that never quite runs out.
     *
     * @throws BusinessRuleException as {@link #convert}, or when the quantity does not convert exactly
     */
    @Transactional(readOnly = true)
    public BigDecimal convertEntered(BigDecimal quantity, UUID from, UUID to) {
        return convertEntered(quantity, from, to, null);
    }

    /** As {@link #convertEntered(BigDecimal, UUID, UUID)}, with the item's own conversions too. */
    @Transactional(readOnly = true)
    public BigDecimal convertEntered(BigDecimal quantity, UUID from, UUID to, UUID itemId) {
        if (from.equals(to)) {
            return quantity;
        }
        Converted result = rounded(quantity, from, to, itemId);
        if (result.exact().compareTo(result.converted()) != 0) {
            throw new BusinessRuleException(("%s %s is %s… %s, but quantities in %s are kept to 6 decimal places. "
                    + "Enter an amount that converts exactly, or enter it in %s.").formatted(
                    quantity.stripTrailingZeros().toPlainString(), result.from().getCode(),
                    result.exact().round(new java.math.MathContext(9)).toPlainString(), result.to().getCode(),
                    result.to().getCode(), result.to().getCode()));
        }
        return result.converted();
    }

    private record Converted(UnitOfMeasure from, UnitOfMeasure to, BigDecimal exact, BigDecimal converted) {
    }

    private Converted rounded(BigDecimal quantity, UUID from, UUID to, UUID itemId) {
        Map<UUID, UnitOfMeasure> visible = units.findVisible(TenantContext.getCurrentTenantId()).stream()
                .collect(java.util.stream.Collectors.toMap(UnitOfMeasure::getId, java.util.function.Function.identity()));
        UnitOfMeasure source = visible.get(from);
        UnitOfMeasure target = visible.get(to);
        if (source == null || target == null) {
            throw new BusinessRuleException("This unit of measure does not exist.");
        }
        UnitConverter.Item item = converter.item(itemId).orElse(null);
        BigDecimal exact = converter.exact(quantity, source, target, item, visible).orElseThrow(() ->
                new BusinessRuleException(item == null || UnitConverter.convertible(source, target)
                        ? "A quantity in %s cannot be converted to %s.".formatted(source.getCode(), target.getCode())
                        : ("A quantity of %s in %s cannot be converted to %s. Add a unit conversion to %s, such as "
                                + "1 %s = … %s.").formatted(item.code(), source.getCode(), target.getCode(),
                                item.code(), nonStock(source, target, item).getCode(),
                                visible.get(item.stockUnitId()).getCode())));
        BigDecimal converted = exact.setScale(6, java.math.RoundingMode.HALF_UP);
        if (converted.signum() <= 0) {
            throw new BusinessRuleException(("That is less than 0.000001 %s, too little to record. Keep an item "
                    + "used in amounts this small in a smaller unit.").formatted(target.getCode()));
        }
        return new Converted(source, target, exact.stripTrailingZeros(), converted);
    }

    private static UnitOfMeasure nonStock(UnitOfMeasure source, UnitOfMeasure target, UnitConverter.Item item) {
        return source.getId().equals(item.stockUnitId()) ? target : source;
    }

    /** An item's own conversions, with every unit its quantities may be entered in. */
    @Transactional(readOnly = true)
    public ItemUnits itemUnits(UUID itemId) {
        UnitConverter.Item item = converter.item(itemId).orElseThrow(() -> new ResourceNotFoundException("Item", itemId));
        Map<UUID, UnitOfMeasure> visible = visibleUnits();
        List<ItemUnitConversion> own = converter.rulesOf(itemId);
        List<UUID> enterable = visible.values().stream()
                .filter(unit -> converter.exact(BigDecimal.ONE, unit, visible.get(item.stockUnitId()), item, visible)
                        .isPresent())
                .map(UnitOfMeasure::getId).toList();
        return new ItemUnits(item.stockUnitId(), own.stream().map(rule -> new ConversionResponse(rule.getId(),
                rule.getUomId(), visible.get(rule.getUomId()).getCode(), rule.getFactor())).toList(), enterable);
    }

    /**
     * Adds "1 {unit} = {factor} {stock unit}" for one material or product. A unit that already converts to the stock
     * unit for every item needs no rule, and a kind of unit converts through one rule only, so the item's units never
     * disagree.
     */
    @Transactional
    public ItemUnits addConversion(UUID itemId, AddConversionRequest request) {
        UnitConverter.Item item = converter.item(itemId).orElseThrow(() -> new ResourceNotFoundException("Item", itemId));
        Map<UUID, UnitOfMeasure> visible = visibleUnits();
        UnitOfMeasure unit = visible.get(request.uomId());
        UnitOfMeasure stock = visible.get(item.stockUnitId());
        if (unit == null) {
            throw new ResourceNotFoundException("Unit", request.uomId());
        }
        if (unit.getId().equals(stock.getId())) {
            throw new BusinessRuleException("%s is kept in %s already.".formatted(item.code(), stock.getCode()));
        }
        if (UnitConverter.convertible(unit, stock)) {
            throw new BusinessRuleException("%s already converts to %s for every item.".formatted(unit.getCode(),
                    stock.getCode()));
        }
        for (ItemUnitConversion rule : converter.rulesOf(itemId)) {
            UnitOfMeasure ruled = visible.get(rule.getUomId());
            if (ruled.getId().equals(unit.getId())) {
                throw new ConflictException("%s already has a conversion for %s.".formatted(item.code(), unit.getCode()));
            }
            if (UnitConverter.convertible(ruled, unit)) {
                throw new BusinessRuleException(("%s already converts through %s, so %s converts with it. Remove "
                        + "that conversion first to define %s instead.").formatted(item.code(), ruled.getCode(),
                        unit.getCode(), unit.getCode()));
            }
        }
        ItemUnitConversion saved = conversions.save(new ItemUnitConversion(kindOf(itemId), itemId, unit.getId(),
                request.factor().stripTrailingZeros()));
        audit.record("ITEM_UNIT_CONVERSION_ADDED", kindName(saved), itemId, null, Map.of("unit", unit.getCode(),
                "factor", saved.getFactor().toPlainString(), "stockUnit", stock.getCode()));
        return itemUnits(itemId);
    }

    /** Removes a conversion that no BOM, recipe, material list, work order or purchase order still counts on. */
    @Transactional
    public ItemUnits removeConversion(UUID itemId, UUID conversionId) {
        ItemUnitConversion rule = conversions.findById(conversionId)
                .filter(found -> found.getItemId().equals(itemId))
                .orElseThrow(() -> new ResourceNotFoundException("Unit conversion", conversionId));
        Map<UUID, UnitOfMeasure> visible = visibleUnits();
        UnitOfMeasure unit = visible.get(rule.getUomId());
        List<UUID> covered = visible.values().stream()
                .filter(other -> other.getId().equals(unit.getId()) || UnitConverter.convertible(other, unit))
                .map(UnitOfMeasure::getId).toList();
        String use = unitGuard.useOfUnits(itemId, covered);
        if (use != null) {
            throw new BusinessRuleException("%s is entered in %s on %s, so this conversion is still needed."
                    .formatted(converter.item(itemId).map(UnitConverter.Item::code).orElse("This item"),
                            unit.getCode(), use));
        }
        conversions.delete(rule);
        audit.record("ITEM_UNIT_CONVERSION_REMOVED", kindName(rule), itemId,
                Map.of("unit", unit.getCode(), "factor", rule.getFactor().toPlainString()), null);
        return itemUnits(itemId);
    }

    private ItemUnitConversion.ItemKind kindOf(UUID itemId) {
        return materials.existsById(itemId) ? ItemUnitConversion.ItemKind.MATERIAL : ItemUnitConversion.ItemKind.PRODUCT;
    }

    private static String kindName(ItemUnitConversion rule) {
        return rule.getItemKind() == ItemUnitConversion.ItemKind.MATERIAL ? "Material" : "Product";
    }

    private Map<UUID, UnitOfMeasure> visibleUnits() {
        return units.findVisible(TenantContext.getCurrentTenantId()).stream()
                .collect(java.util.stream.Collectors.toMap(UnitOfMeasure::getId, java.util.function.Function.identity()));
    }

    /**
     * The code of a unit that measures rather than counts, such as KG or LTR, or null for a count (PCS) or no unit.
     * Sampling plans that count units need a counted lot.
     */
    @Transactional(readOnly = true)
    public String bulkUnitCode(UUID id) {
        if (id == null) {
            return null;
        }
        UnitOfMeasure unit = visibleUnit(id);
        return "Count".equalsIgnoreCase(unit.getDimension()) ? null : unit.getCode();
    }

    /** A material counted in pieces is ordered and received in whole pieces. */
    @Transactional(readOnly = true)
    public void requireWholeIfCounted(MaterialReference material, BigDecimal quantity) {
        if (quantity == null || material.uomId() == null || quantity.stripTrailingZeros().scale() <= 0) {
            return;
        }
        requireWholeInUnit(material.code(), material.uomId(), quantity);
    }

    /**
     * Anything counted, in pieces or any other unit of the Count dimension, comes in whole numbers.
     *
     * @param what names the item in the message, such as its code
     */
    @Transactional(readOnly = true)
    public void requireWholeInUnit(String what, UUID uomId, BigDecimal quantity) {
        if (quantity == null || uomId == null || quantity.stripTrailingZeros().scale() <= 0) {
            return;
        }
        UnitOfMeasure unit = visibleUnit(uomId);
        if (!"Count".equalsIgnoreCase(unit.getDimension())) {
            return;
        }
        // A pack of pieces may be split into whole pieces: 2.5 DOZ is 30 PCS.
        if (unit.getToBase() != null && quantity.multiply(unit.getToBase()).stripTrailingZeros().scale() <= 0) {
            return;
        }
        throw new BusinessRuleException(unit.getToBase() != null && unit.getToBase().compareTo(BigDecimal.ONE) > 0
                ? "%s is counted in %s, so give a quantity that makes whole pieces, not %s.".formatted(
                        what, unit.getCode(), quantity.stripTrailingZeros().toPlainString())
                : "%s is counted in %s, so give a whole number, not %s.".formatted(
                        what, unit.getCode(), quantity.stripTrailingZeros().toPlainString()));
    }

    /** A unit's code, such as KG. */
    @Transactional(readOnly = true)
    public String unitCode(UUID id) {
        return visibleUnit(id).getCode();
    }

    @Transactional(readOnly = true)
    public void requireUnit(UUID id) {
        visibleUnit(id);
    }

    private UnitOfMeasure visibleUnit(UUID id) {
        return units.findVisible(TenantContext.getCurrentTenantId()).stream()
                .filter(unit -> unit.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("This unit of measure does not exist."));
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
        ProductCategory category = categories.save(new ProductCategory(trimmed));
        var created = mapper.toResponse(category);
        audit.record("PRODUCT_CATEGORY_CREATED", "ProductCategory", category.getId(), null, audit.state(created));
        return created;
    }

    @Transactional
    public CategoryResponse renameCategory(UUID id, String name) {
        ProductCategory category = findCategory(id);
        String trimmed = name.trim();
        if (!trimmed.equals(category.getName()) && categories.existsByName(trimmed)) {
            throw new ConflictException("This category already exists.");
        }
        String previous = category.getName();
        category.setName(trimmed);
        audit.record("PRODUCT_CATEGORY_RENAMED", "ProductCategory", id, Map.of("name", previous), Map.of("name", trimmed));
        return mapper.toResponse(category);
    }

    /** The database refuses while a material or product holds the category, including an archived one. */
    @Transactional
    public void deleteCategory(UUID id) {
        var category = findCategory(id);
        String name = category.getName();
        categories.delete(category);
        try {
            categories.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("This category is in use, so it cannot be deleted.");
        }
        audit.record("PRODUCT_CATEGORY_DELETED", "ProductCategory", id, Map.of("name", name), null);
    }

    private ProductCategory findCategory(UUID id) {
        return categories.findById(id).orElseThrow(() -> new ResourceNotFoundException("Category", id.toString()));
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
        material.setShelfLifeDays(request.shelfLifeDays());
        material.setProperties(request.properties() == null ? new HashMap<>() : new HashMap<>(request.properties()));
    }
}
