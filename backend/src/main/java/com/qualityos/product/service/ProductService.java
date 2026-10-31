package com.qualityos.product.service;

import com.qualityos.common.attachment.AttachmentPolicy;
import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.material.dto.MaterialDtos.MaterialReference;
import com.qualityos.material.dto.MaterialDtos.SaveSpecificationRequest;
import com.qualityos.material.dto.MaterialDtos.SpecificationResponse;
import com.qualityos.material.service.MaterialService;
import com.qualityos.product.dto.ProductDtos.AddMaterialRequest;
import com.qualityos.product.dto.ProductDtos.AddRevisionRequest;
import com.qualityos.product.dto.ProductDtos.BillOfMaterialResponse;
import com.qualityos.product.dto.ProductDtos.ProductDetail;
import com.qualityos.product.dto.ProductDtos.ProductResponse;
import com.qualityos.product.dto.ProductDtos.RevisionResponse;
import com.qualityos.product.dto.ProductDtos.SaveProductRequest;
import com.qualityos.product.entity.Product;
import com.qualityos.product.entity.ProductMaterial;
import com.qualityos.product.entity.ProductRevision;
import com.qualityos.product.entity.ProductSpecification;
import com.qualityos.product.mapper.ProductMapper;
import com.qualityos.product.repository.ProductMaterialRepository;
import com.qualityos.product.repository.ProductRepository;
import com.qualityos.product.repository.ProductRevisionRepository;
import com.qualityos.product.repository.ProductSpecificationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService implements AttachmentPolicy {

    private static final String INITIAL_REVISION = "A";

    private final ProductRepository products;
    private final ProductRevisionRepository revisions;
    private final ProductSpecificationRepository specifications;
    private final ProductMaterialRepository productMaterials;
    private final MaterialService materials;
    private final ProductMapper mapper;
    private final AuditService audit;

    ProductService(
            ProductRepository products,
            ProductRevisionRepository revisions,
            ProductSpecificationRepository specifications,
            ProductMaterialRepository productMaterials,
            MaterialService materials,
            ProductMapper mapper,
            AuditService audit) {
        this.products = products;
        this.revisions = revisions;
        this.specifications = specifications;
        this.productMaterials = productMaterials;
        this.materials = materials;
        this.mapper = mapper;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> list(String search, Product.Type type, Pageable pageable) {
        Specification<Product> filter = Specification.<Product>where(Searches.notArchived())
                .and(Searches.contains(search, "code", "name"))
                .and(Searches.equalTo("productType", type));
        return PageResponse.from(products.findAll(filter, pageable), mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public ProductDetail get(UUID id) {
        Product product = load(id);
        List<ProductMaterial> mappings = productMaterials.findByProductId(id);
        Map<UUID, MaterialReference> referenced =
                materials.references(mappings.stream().map(ProductMaterial::getMaterialId).toList());
        return new ProductDetail(
                mapper.toResponse(product),
                revisions.findByProductIdOrderByCreatedAtDesc(id).stream().map(mapper::toResponse).toList(),
                specifications.findByProductIdOrderBySortOrderAscNameAsc(id).stream().map(mapper::toResponse).toList(),
                mappings.stream().map(mapping -> toResponse(mapping, referenced.get(mapping.getMaterialId()))).toList());
    }

    @Transactional
    public ProductResponse create(SaveProductRequest request) {
        String code = request.code().trim();
        if (products.existsByCode(code)) {
            throw new ConflictException("A product with this code already exists.");
        }
        Product product = new Product(code);
        apply(product, request);
        products.saveAndFlush(product);
        revisions.save(new ProductRevision(product.getId(), INITIAL_REVISION, "Initial revision", LocalDate.now()));
        audit.record("PRODUCT_CREATED", "Product", product.getId());
        return mapper.toResponse(product);
    }

    @Transactional
    public ProductResponse update(UUID id, SaveProductRequest request) {
        Product product = load(id);
        product.requireVersion(request.version());
        apply(product, request);
        products.saveAndFlush(product);
        audit.record("PRODUCT_UPDATED", "Product", id);
        return mapper.toResponse(product);
    }

    @Transactional
    public void archive(UUID id) {
        load(id).setArchivedAt(Instant.now());
        audit.record("PRODUCT_ARCHIVED", "Product", id);
    }

    @Transactional
    public RevisionResponse addRevision(UUID id, AddRevisionRequest request) {
        Product product = load(id);
        String revision = request.revision().trim();
        if (revisions.existsByProductIdAndRevision(id, revision)) {
            throw new ConflictException("This revision already exists.");
        }
        ProductRevision saved = revisions.save(
                new ProductRevision(id, revision, request.changeNote(), request.effectiveFrom()));
        String previous = product.getCurrentRevision();
        product.setCurrentRevision(revision);
        audit.record("PRODUCT_REVISED", "Product", id, Map.of("revision", previous), Map.of("revision", revision));
        return mapper.toResponse(saved);
    }

    @Transactional
    public SpecificationResponse addSpecification(UUID id, SaveSpecificationRequest request) {
        load(id);
        materials.requireValid(request);
        int nextOrder = specifications.findByProductIdOrderBySortOrderAscNameAsc(id).size();
        ProductSpecification specification = specifications.save(new ProductSpecification(
                id, request.name().trim(), request.nominalValue(), request.minValue(), request.maxValue(),
                request.textValue(), request.uomId(), request.notes(), nextOrder));
        audit.record("PRODUCT_SPECIFICATION_ADDED", "Product", id);
        return mapper.toResponse(specification);
    }

    @Transactional
    public void removeSpecification(UUID id, UUID specificationId) {
        ProductSpecification specification = specifications.findById(specificationId)
                .filter(found -> found.getProductId().equals(id))
                .orElseThrow(() -> new ResourceNotFoundException("Specification", specificationId));
        specifications.delete(specification);
        audit.record("PRODUCT_SPECIFICATION_REMOVED", "Product", id);
    }

    @Transactional
    public BillOfMaterialResponse addMaterial(UUID id, AddMaterialRequest request) {
        load(id);
        MaterialReference material = materials.requireActive(request.materialId());
        materials.requireUnit(request.uomId());
        if (productMaterials.existsByProductIdAndMaterialId(id, request.materialId())) {
            throw new ConflictException("This material is already mapped to the product.");
        }
        ProductMaterial mapping = productMaterials.save(
                new ProductMaterial(id, request.materialId(), request.quantityPerUnit(), request.uomId()));
        return toResponse(mapping, material);
    }

    @Transactional
    public void removeMaterial(UUID id, UUID mappingId) {
        ProductMaterial mapping = productMaterials.findById(mappingId)
                .filter(found -> found.getProductId().equals(id))
                .orElseThrow(() -> new ResourceNotFoundException("Material mapping", mappingId));
        productMaterials.delete(mapping);
    }

    @Override
    public String entityType() {
        return "PRODUCT";
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
        return products.existsById(entityId);
    }

    private Product load(UUID id) {
        return products.findById(id).orElseThrow(() -> new ResourceNotFoundException("Product", id));
    }

    private void apply(Product product, SaveProductRequest request) {
        materials.requireUnit(request.uomId());
        materials.requireCategory(request.categoryId());
        product.setName(request.name().trim());
        product.setDescription(request.description());
        product.setProductType(parseType(request.productType()));
        product.setCategoryId(request.categoryId());
        product.setUomId(request.uomId());
    }

    private static Product.Type parseType(String value) {
        try {
            return Product.Type.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessRuleException("Product type must be FINISHED or SEMI_FINISHED.");
        }
    }

    private static BillOfMaterialResponse toResponse(ProductMaterial mapping, MaterialReference material) {
        return new BillOfMaterialResponse(
                mapping.getId(),
                mapping.getMaterialId(),
                material.code(),
                material.name(),
                mapping.getQuantityPerUnit(),
                mapping.getUomId());
    }
}
