package com.qualityos.production.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.material.dto.MaterialDtos.MaterialReference;
import com.qualityos.material.service.MaterialService;
import com.qualityos.product.dto.ProductDtos.BillOfMaterialResponse;
import com.qualityos.product.dto.ProductDtos.ProductReference;
import com.qualityos.product.service.ProductService;
import com.qualityos.production.dto.ManufacturingDtos.ApproveRequest;
import com.qualityos.production.dto.ManufacturingDtos.BomDetail;
import com.qualityos.production.dto.ManufacturingDtos.CreateRevisionRequest;
import com.qualityos.production.dto.ManufacturingDtos.ExplosionResponse;
import com.qualityos.production.dto.ManufacturingDtos.ExplosionRow;
import com.qualityos.production.dto.ManufacturingDtos.LineResponse;
import com.qualityos.production.dto.ManufacturingDtos.ParameterResponse;
import com.qualityos.production.dto.ManufacturingDtos.RequirementTotal;
import com.qualityos.production.dto.ManufacturingDtos.RetireRequest;
import com.qualityos.production.dto.ManufacturingDtos.RevisionResponse;
import com.qualityos.production.dto.ManufacturingDtos.SaveLineRequest;
import com.qualityos.production.dto.ManufacturingDtos.SaveParameterRequest;
import com.qualityos.production.dto.ManufacturingDtos.ScaledLine;
import com.qualityos.production.dto.ManufacturingDtos.ScaledRecipe;
import com.qualityos.production.dto.ManufacturingDtos.SendBackRequest;
import com.qualityos.production.dto.ManufacturingDtos.UpdateRevisionRequest;
import com.qualityos.production.entity.Bom;
import com.qualityos.production.entity.BomLine;
import com.qualityos.production.entity.ProcessParameter;
import com.qualityos.production.entity.RevisionedDefinition.Status;
import com.qualityos.production.repository.BomLineRepository;
import com.qualityos.production.repository.BomRepository;
import com.qualityos.production.repository.ProcessParameterRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BOMs and recipes. They share a structure and a lifecycle; each call names which it is working on, and a record of
 * the other kind is not found. Parameters belong to recipes only.
 */
@Service
public class BomService {

    private static final List<Status> OPEN = List.of(Status.DRAFT, Status.SUBMITTED);
    private static final int MAX_DEPTH = 25;
    private static final String MATERIAL = "MATERIAL";
    private static final String PRODUCT = "PRODUCT";

    private final BomRepository boms;
    private final BomLineRepository lines;
    private final ProcessParameterRepository parameters;
    private final ProductService products;
    private final MaterialService materials;
    private final ManufacturingSettingsService settings;
    private final RevisionSupport support;
    private final AuditService audit;

    BomService(
            BomRepository boms,
            BomLineRepository lines,
            ProcessParameterRepository parameters,
            ProductService products,
            MaterialService materials,
            ManufacturingSettingsService settings,
            RevisionSupport support,
            AuditService audit) {
        this.boms = boms;
        this.lines = lines;
        this.parameters = parameters;
        this.products = products;
        this.materials = materials;
        this.settings = settings;
        this.support = support;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<RevisionResponse> list(
            Bom.Kind kind, String search, UUID productId, Status status, Pageable pageable) {
        Specification<Bom> filter = Specification.<Bom>where(Searches.equalTo("kind", kind))
                .and(Searches.equalTo("productId", productId))
                .and(Searches.equalTo("status", status));
        if (search != null && !search.isBlank()) {
            List<UUID> matching = products.idsMatching(search);
            filter = filter.and((root, query, cb) -> matching.isEmpty()
                    ? cb.disjunction() : root.get("productId").in(matching));
        }
        Page<Bom> page = boms.findAll(filter, pageable);
        Map<UUID, ProductReference> refs = products.references(
                page.getContent().stream().map(Bom::getProductId).toList());
        Map<UUID, String> members = support.members();
        LocalDate today = support.today();
        return PageResponse.from(page, bom -> RevisionSupport.toResponse(
                bom, bom.getKind().name(), refs.get(bom.getProductId()), bom.getBaseQuantity(), members, today));
    }

    @Transactional(readOnly = true)
    public BomDetail get(Bom.Kind kind, UUID id) {
        return detail(load(kind, id));
    }

    /** A product has one revision in progress at a time, so a second draft waits until the first is decided. */
    @Transactional
    public BomDetail create(Bom.Kind kind, CreateRevisionRequest request) {
        ProductReference product = products.requireActive(request.productId());
        settings.requireMadeWith(product.id(), kind, product.code());
        if (boms.existsByProductIdAndKindAndStatusIn(product.id(), kind, OPEN)) {
            throw new ConflictException("%s already has a %s in progress. Finish or discard it first."
                    .formatted(product.code(), label(kind)));
        }
        Bom source = null;
        if (request.copyFromId() != null) {
            source = load(kind, request.copyFromId());
            if (!source.getProductId().equals(product.id())) {
                throw new BusinessRuleException("Copy a revision of the same product.");
            }
        }
        boolean fromList = Boolean.TRUE.equals(request.fromProductMaterials());
        if (source != null && fromList) {
            throw new BusinessRuleException("Copy either another revision or the product's material list.");
        }
        Bom bom = new Bom(kind, product.id(), boms.lastRevision(product.id(), kind) + 1, request.changeNote());
        BigDecimal base = request.baseQuantity() != null ? request.baseQuantity()
                : source != null ? source.getBaseQuantity() : BigDecimal.ONE;
        bom.setBaseQuantity(base);
        boms.saveAndFlush(bom);
        if (source != null) {
            copyContent(source, bom);
        } else if (fromList) {
            copyProductMaterials(product.id(), bom);
        }
        audit.record(action(kind, "DRAFTED"), entity(kind), bom.getId(), null,
                Map.of("product", product.code(), "revision", bom.getRevision()));
        return detail(bom);
    }

    @Transactional
    public BomDetail update(Bom.Kind kind, UUID id, UpdateRevisionRequest request) {
        Bom bom = load(kind, id);
        bom.requireDraft();
        bom.setChangeNote(request.changeNote());
        if (request.baseQuantity() != null) {
            bom.setBaseQuantity(request.baseQuantity());
        }
        audit.record(action(kind, "UPDATED"), entity(kind), id);
        return detail(bom);
    }

    @Transactional
    public BomDetail addLine(Bom.Kind kind, UUID id, SaveLineRequest request) {
        Bom bom = load(kind, id);
        bom.requireDraft();
        List<BomLine> existing = lines.findByBomIdOrderByLineNumber(id);
        int next = existing.stream().mapToInt(BomLine::getLineNumber).max().orElse(0) + 1;
        BomLine line = new BomLine(id, next);
        apply(bom, line, request);
        lines.saveAndFlush(line);
        audit.record(action(kind, "LINE_ADDED"), entity(kind), id, null, Map.of("line", next));
        return detail(bom);
    }

    @Transactional
    public BomDetail updateLine(Bom.Kind kind, UUID id, UUID lineId, SaveLineRequest request) {
        Bom bom = load(kind, id);
        bom.requireDraft();
        BomLine line = loadLine(id, lineId);
        apply(bom, line, request);
        audit.record(action(kind, "LINE_CHANGED"), entity(kind), id, null, Map.of("line", line.getLineNumber()));
        return detail(bom);
    }

    @Transactional
    public BomDetail removeLine(Bom.Kind kind, UUID id, UUID lineId) {
        Bom bom = load(kind, id);
        bom.requireDraft();
        BomLine line = loadLine(id, lineId);
        lines.delete(line);
        lines.flush();
        audit.record(action(kind, "LINE_REMOVED"), entity(kind), id, null, Map.of("line", line.getLineNumber()));
        return detail(bom);
    }

    @Transactional
    public BomDetail addParameter(Bom.Kind kind, UUID id, SaveParameterRequest request) {
        Bom bom = load(kind, id);
        if (kind != Bom.Kind.RECIPE) {
            throw new BusinessRuleException("Process parameters belong to recipes and routing operations.");
        }
        bom.requireDraft();
        String code = request.code().trim();
        if (parameters.existsByBomIdAndCode(id, code)) {
            throw new ConflictException("This recipe already has a parameter %s.".formatted(code));
        }
        ProcessParameter parameter = ProcessParameter.ofRecipe(id);
        Parameters.apply(parameter, request);
        parameters.saveAndFlush(parameter);
        audit.record(action(kind, "PARAMETER_ADDED"), entity(kind), id, null, Map.of("parameter", code));
        return detail(bom);
    }

    @Transactional
    public BomDetail removeParameter(Bom.Kind kind, UUID id, UUID parameterId) {
        Bom bom = load(kind, id);
        bom.requireDraft();
        ProcessParameter parameter = parameters.findById(parameterId)
                .filter(found -> id.equals(found.getBomId()))
                .orElseThrow(() -> new ResourceNotFoundException("Parameter", parameterId));
        parameters.delete(parameter);
        parameters.flush();
        audit.record(action(kind, "PARAMETER_REMOVED"), entity(kind), id, null, Map.of("parameter", parameter.getCode()));
        return detail(bom);
    }

    @Transactional
    public BomDetail submit(Bom.Kind kind, UUID id) {
        Bom bom = load(kind, id);
        if (lines.findByBomIdOrderByLineNumber(id).isEmpty()) {
            throw new BusinessRuleException("Add at least one line before submitting.");
        }
        support.submit(bom);
        audit.record(action(kind, "SUBMITTED"), entity(kind), id);
        return detail(bom);
    }

    @Transactional
    public BomDetail sendBack(Bom.Kind kind, UUID id, SendBackRequest request) {
        Bom bom = load(kind, id);
        bom.sendBack(request.note().trim());
        audit.record(action(kind, "SENT_BACK"), entity(kind), id, null, Map.of("note", request.note().trim()));
        return detail(bom);
    }

    /**
     * The structure is checked again for loops, because another product's BOM may have changed since this one was
     * drafted. The snapshot keeps the lines as approved, with their codes and names.
     */
    @Transactional
    public BomDetail approve(Bom.Kind kind, UUID id, ApproveRequest request) {
        Bom bom = load(kind, id);
        ProductReference product = products.references(List.of(bom.getProductId())).get(bom.getProductId());
        List<BomLine> content = lines.findByBomIdOrderByLineNumber(id);
        Map<UUID, ProductReference> components = products.references(content.stream()
                .map(BomLine::getComponentProductId).filter(java.util.Objects::nonNull).toList());
        components.values().forEach(component -> support.requireNoCycle(bom.getProductId(), component, product.code()));
        BomDetail before = detail(bom);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("kind", kind.name());
        snapshot.put("productId", product.id());
        snapshot.put("productCode", product.code());
        snapshot.put("productName", product.name());
        snapshot.put("revision", bom.getRevision());
        snapshot.put("baseQuantity", bom.getBaseQuantity().toPlainString());
        snapshot.put("uomId", product.uomId());
        snapshot.put("lines", before.lines());
        snapshot.put("parameters", before.parameters());
        support.approve(bom, boms.findByProductIdAndKindAndStatus(bom.getProductId(), kind, Status.APPROVED),
                request, entity(kind), action(kind, "APPROVE"), snapshot);
        boms.flush();
        audit.record(action(kind, "APPROVED"), entity(kind), id, null,
                Map.of("revision", bom.getRevision(), "effectiveFrom", bom.getEffectiveFrom().toString()));
        return detail(bom);
    }

    @Transactional
    public BomDetail retire(Bom.Kind kind, UUID id, RetireRequest request) {
        Bom bom = load(kind, id);
        bom.retire(Instant.now());
        audit.record(action(kind, "RETIRED"), entity(kind), id, null, Map.of("reason", request.reason().trim()));
        return detail(bom);
    }

    /** Only a draft can be discarded. Anything submitted has been seen by an approver and stays as history. */
    @Transactional
    public void discard(Bom.Kind kind, UUID id) {
        Bom bom = load(kind, id);
        bom.requireDraft();
        if (bom.getSubmittedAt() != null) {
            throw new BusinessRuleException("This revision was submitted once, so it is kept. Edit it instead.");
        }
        parameters.deleteAll(parameters.findByBomIdOrderByCode(id));
        lines.deleteAll(lines.findByBomIdOrderByLineNumber(id));
        lines.flush();
        boms.delete(bom);
        boms.flush();
        audit.record(action(kind, "DISCARDED"), entity(kind), id);
    }

    /**
     * Every component needed for {@code quantity} of the product, level by level. A component that is a product is
     * expanded through its own BOM or recipe in effect on the date, or listed as it is when it has none.
     */
    @Transactional(readOnly = true)
    public ExplosionResponse explode(Bom.Kind kind, UUID id, BigDecimal quantity, LocalDate on) {
        Bom bom = load(kind, id);
        if (quantity == null || quantity.signum() <= 0) {
            throw new BusinessRuleException("Enter a quantity greater than zero.");
        }
        LocalDate date = on == null ? support.today() : on;
        ProductReference product = products.references(List.of(bom.getProductId())).get(bom.getProductId());
        List<ExplosionRow> rows = new ArrayList<>();
        Map<String, RequirementTotal> totals = new LinkedHashMap<>();
        Set<UUID> path = new LinkedHashSet<>(List.of(bom.getProductId()));
        walk(bom, quantity, 1, date, path, rows, totals);
        return new ExplosionResponse(quantity, product.uomId(), date, rows, List.copyOf(totals.values()));
    }

    /** Proportional lines change with the batch size; fixed lines stay as they are. */
    @Transactional(readOnly = true)
    public ScaledRecipe scale(UUID id, BigDecimal batchSize) {
        Bom recipe = load(Bom.Kind.RECIPE, id);
        if (batchSize == null || batchSize.signum() <= 0) {
            throw new BusinessRuleException("Enter a batch size greater than zero.");
        }
        BigDecimal factor = batchSize.divide(recipe.getBaseQuantity(), 6, RoundingMode.HALF_UP);
        BomDetail detail = detail(recipe);
        List<ScaledLine> scaled = detail.lines().stream()
                .map(line -> new ScaledLine(line.lineNumber(), line.componentType(), line.componentId(),
                        line.componentCode(), line.componentName(),
                        line.scales() ? scaledQuantity(line.quantity(), batchSize, recipe.getBaseQuantity())
                                : line.quantity(),
                        line.uomId(), line.scales()))
                .toList();
        return new ScaledRecipe(batchSize, detail.revision().productUomId(), factor, scaled, detail.parameters());
    }

    private void walk(
            Bom bom,
            BigDecimal quantity,
            int level,
            LocalDate on,
            Set<UUID> path,
            List<ExplosionRow> rows,
            Map<String, RequirementTotal> totals) {
        if (level > MAX_DEPTH) {
            throw new BusinessRuleException("This structure is more than %d levels deep.".formatted(MAX_DEPTH));
        }
        List<BomLine> content = lines.findByBomIdOrderByLineNumber(bom.getId());
        Map<UUID, MaterialReference> materialRefs = materials.references(
                content.stream().map(BomLine::getMaterialId).filter(java.util.Objects::nonNull).toList());
        List<UUID> productIds = content.stream()
                .map(BomLine::getComponentProductId).filter(java.util.Objects::nonNull).toList();
        Map<UUID, ProductReference> productRefs = products.references(productIds);
        Map<UUID, Bom> children = new LinkedHashMap<>();
        if (!productIds.isEmpty()) {
            boms.findInEffect(productIds, on).forEach(child -> children.putIfAbsent(child.getProductId(), child));
        }
        for (BomLine line : content) {
            BigDecimal needed = line.isScales()
                    ? line.getQuantity().multiply(quantity).divide(bom.getBaseQuantity(), 12, RoundingMode.HALF_UP)
                    : line.getQuantity();
            needed = needed.multiply(BigDecimal.ONE.add(
                    line.getScrapPercent().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
            if (line.getMaterialId() != null) {
                MaterialReference material = materialRefs.get(line.getMaterialId());
                BigDecimal own = materials.convert(round(needed), line.getUomId(), material.uomId());
                rows.add(new ExplosionRow(level, MATERIAL, material.id(), material.code(), material.name(), own,
                        material.uomId(), false));
                add(totals, MATERIAL, material.id(), material.code(), material.name(), own, material.uomId());
                continue;
            }
            ProductReference component = productRefs.get(line.getComponentProductId());
            BigDecimal own = materials.convert(round(needed), line.getUomId(), component.uomId());
            Bom child = children.get(component.id());
            boolean expand = child != null && !path.contains(component.id());
            rows.add(new ExplosionRow(level, PRODUCT, component.id(), component.code(), component.name(), own,
                    component.uomId(), expand));
            if (expand) {
                path.add(component.id());
                walk(child, own, level + 1, on, path, rows, totals);
                path.remove(component.id());
            } else {
                add(totals, PRODUCT, component.id(), component.code(), component.name(), own, component.uomId());
            }
        }
    }

    private static void add(Map<String, RequirementTotal> totals, String type, UUID id, String code, String name,
            BigDecimal quantity, UUID uomId) {
        totals.merge(type + ":" + id, new RequirementTotal(type, id, code, name, quantity, uomId),
                (a, b) -> new RequirementTotal(type, id, code, name, a.quantity().add(b.quantity()), uomId));
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaledQuantity(BigDecimal quantity, BigDecimal batchSize, BigDecimal base) {
        return quantity.multiply(batchSize).divide(base, 6, RoundingMode.HALF_UP);
    }

    private void apply(Bom bom, BomLine line, SaveLineRequest request) {
        if ((request.materialId() == null) == (request.componentProductId() == null)) {
            throw new BusinessRuleException("Choose either a material or a product for this line.");
        }
        UUID componentUom;
        if (request.materialId() != null) {
            componentUom = materials.requireActive(request.materialId()).uomId();
        } else {
            ProductReference component = products.requireActive(request.componentProductId());
            ProductReference parent = products.references(List.of(bom.getProductId())).get(bom.getProductId());
            support.requireNoCycle(bom.getProductId(), component, parent.code());
            componentUom = component.uomId();
        }
        // Refuses a unit of another kind, such as pieces for a material kept in kilograms.
        materials.convert(request.quantity(), request.uomId(), componentUom);
        line.setMaterialId(request.materialId());
        line.setComponentProductId(request.componentProductId());
        line.setQuantity(request.quantity());
        line.setUomId(request.uomId());
        line.setScrapPercent(request.scrapPercent() == null ? BigDecimal.ZERO : request.scrapPercent());
        line.setScales(request.scales() == null || request.scales());
        line.setNotes(request.notes());
    }

    private void copyContent(Bom source, Bom target) {
        for (BomLine line : lines.findByBomIdOrderByLineNumber(source.getId())) {
            BomLine copy = new BomLine(target.getId(), line.getLineNumber());
            copy.setMaterialId(line.getMaterialId());
            copy.setComponentProductId(line.getComponentProductId());
            copy.setQuantity(line.getQuantity());
            copy.setUomId(line.getUomId());
            copy.setScrapPercent(line.getScrapPercent());
            copy.setScales(line.isScales());
            copy.setNotes(line.getNotes());
            lines.save(copy);
        }
        for (ProcessParameter parameter : parameters.findByBomIdOrderByCode(source.getId())) {
            parameters.save(Parameters.copy(parameter, ProcessParameter.ofRecipe(target.getId())));
        }
        lines.flush();
    }

    /** The product's flat material list, as quantities per one unit of the product. */
    private void copyProductMaterials(UUID productId, Bom target) {
        List<BillOfMaterialResponse> list = products.get(productId).materials();
        if (list.isEmpty()) {
            throw new BusinessRuleException("This product has no materials listed to copy.");
        }
        int number = 0;
        for (BillOfMaterialResponse item : list) {
            BomLine line = new BomLine(target.getId(), ++number);
            line.setMaterialId(item.materialId());
            line.setQuantity(item.quantityPerUnit().multiply(target.getBaseQuantity()));
            line.setUomId(item.uomId());
            lines.save(line);
        }
        lines.flush();
    }

    private BomDetail detail(Bom bom) {
        List<BomLine> content = lines.findByBomIdOrderByLineNumber(bom.getId());
        Map<UUID, MaterialReference> materialRefs = materials.references(
                content.stream().map(BomLine::getMaterialId).filter(java.util.Objects::nonNull).toList());
        Set<UUID> productIds = new HashSet<>(content.stream()
                .map(BomLine::getComponentProductId).filter(java.util.Objects::nonNull).toList());
        productIds.add(bom.getProductId());
        Map<UUID, ProductReference> productRefs = products.references(productIds);
        List<LineResponse> lineResponses = content.stream().map(line -> {
            boolean material = line.getMaterialId() != null;
            String code = material ? materialRefs.get(line.getMaterialId()).code()
                    : productRefs.get(line.getComponentProductId()).code();
            String name = material ? materialRefs.get(line.getMaterialId()).name()
                    : productRefs.get(line.getComponentProductId()).name();
            return new LineResponse(line.getId(), line.getLineNumber(), material ? MATERIAL : PRODUCT,
                    line.componentId(), code, name, line.getQuantity(), line.getUomId(), line.getScrapPercent(),
                    line.isScales(), line.getNotes());
        }).toList();
        List<ParameterResponse> parameterResponses = parameters.findByBomIdOrderByCode(bom.getId()).stream()
                .map(Parameters::toResponse)
                .toList();
        return new BomDetail(RevisionSupport.toResponse(bom, bom.getKind().name(), productRefs.get(bom.getProductId()),
                bom.getBaseQuantity(), support.members(), support.today()), lineResponses, parameterResponses);
    }

    private Bom load(Bom.Kind kind, UUID id) {
        return boms.findById(id)
                .filter(bom -> bom.getKind() == kind)
                .orElseThrow(() -> new ResourceNotFoundException(entity(kind), id));
    }

    private BomLine loadLine(UUID bomId, UUID lineId) {
        return lines.findById(lineId)
                .filter(line -> line.getBomId().equals(bomId))
                .orElseThrow(() -> new ResourceNotFoundException("Line", lineId));
    }

    private static String entity(Bom.Kind kind) {
        return kind == Bom.Kind.BOM ? "BOM" : "Recipe";
    }

    private static String action(Bom.Kind kind, String event) {
        return kind.name() + "_" + event;
    }

    private static String label(Bom.Kind kind) {
        return kind == Bom.Kind.BOM ? "BOM revision" : "recipe revision";
    }
}
