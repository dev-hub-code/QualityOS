package com.qualityos.supplier.service;

import com.qualityos.common.attachment.AttachmentPolicy;
import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.supplier.dto.SupplierDtos.CategoryResponse;
import com.qualityos.supplier.dto.SupplierDtos.ContactResponse;
import com.qualityos.supplier.dto.SupplierDtos.SaveContactRequest;
import com.qualityos.supplier.dto.SupplierDtos.SaveSupplierRequest;
import com.qualityos.supplier.dto.SupplierDtos.SupplierDetail;
import com.qualityos.supplier.dto.SupplierDtos.SupplierReference;
import com.qualityos.supplier.dto.SupplierDtos.SupplierResponse;
import com.qualityos.supplier.entity.Supplier;
import com.qualityos.supplier.entity.Supplier.Status;
import com.qualityos.supplier.entity.SupplierCategory;
import com.qualityos.supplier.entity.SupplierContact;
import com.qualityos.supplier.mapper.SupplierMapper;
import com.qualityos.supplier.repository.SupplierCategoryRepository;
import com.qualityos.supplier.repository.SupplierContactRepository;
import com.qualityos.supplier.repository.SupplierRepository;
import java.time.Instant;
import java.util.Collection;
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
import org.springframework.util.StringUtils;

@Service
public class SupplierService implements AttachmentPolicy {

    private final SupplierRepository suppliers;
    private final SupplierCategoryRepository categories;
    private final SupplierContactRepository contacts;
    private final SupplierMapper mapper;
    private final AuditService audit;

    SupplierService(
            SupplierRepository suppliers,
            SupplierCategoryRepository categories,
            SupplierContactRepository contacts,
            SupplierMapper mapper,
            AuditService audit) {
        this.suppliers = suppliers;
        this.categories = categories;
        this.contacts = contacts;
        this.mapper = mapper;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<SupplierResponse> list(String search, Status status, Pageable pageable) {
        Specification<Supplier> filter = Specification.<Supplier>where(Searches.notArchived())
                .and(Searches.contains(search, "code", "name", "gstin"))
                .and(Searches.equalTo("status", status));
        return PageResponse.from(suppliers.findAll(filter, pageable), mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public SupplierDetail get(UUID id) {
        Supplier supplier = load(id);
        return new SupplierDetail(
                mapper.toResponse(supplier),
                contacts.findBySupplierIdOrderByPrimaryDescName(id).stream().map(mapper::toResponse).toList());
    }

    @Transactional
    public SupplierResponse create(SaveSupplierRequest request) {
        String code = request.code().trim();
        if (suppliers.existsByCode(code)) {
            throw new ConflictException("A supplier with this code already exists.");
        }
        Supplier supplier = new Supplier(code);
        apply(supplier, request);
        suppliers.saveAndFlush(supplier);
        audit.record("SUPPLIER_CREATED", "Supplier", supplier.getId());
        return mapper.toResponse(supplier);
    }

    @Transactional
    public SupplierResponse update(UUID id, SaveSupplierRequest request) {
        Supplier supplier = load(id);
        supplier.requireVersion(request.version());
        apply(supplier, request);
        suppliers.saveAndFlush(supplier);
        audit.record("SUPPLIER_UPDATED", "Supplier", id);
        return mapper.toResponse(supplier);
    }

    /** Suppliers stay on record for quality history, but cannot be used on new orders. */
    @Transactional
    public void archive(UUID id) {
        load(id).setArchivedAt(Instant.now());
        audit.record("SUPPLIER_ARCHIVED", "Supplier", id);
    }

    @Transactional
    public SupplierResponse changeStatus(UUID id, Status target, String note) {
        Supplier supplier = load(id);
        Status previous = supplier.getStatus();
        supplier.transitionTo(target, TenantContext.getCurrentUserId(), note);
        suppliers.saveAndFlush(supplier);
        audit.record("SUPPLIER_STATUS_CHANGED", "Supplier", id,
                Map.of("status", previous), Map.of("status", target));
        return mapper.toResponse(supplier);
    }

    @Transactional
    public ContactResponse addContact(UUID supplierId, SaveContactRequest request) {
        load(supplierId);
        return mapper.toResponse(contacts.save(new SupplierContact(
                supplierId, request.name().trim(), request.designation(), request.email(), request.phone(),
                request.primary())));
    }

    @Transactional
    public void removeContact(UUID supplierId, UUID contactId) {
        SupplierContact contact = contacts.findById(contactId)
                .filter(found -> found.getSupplierId().equals(supplierId))
                .orElseThrow(() -> new ResourceNotFoundException("Contact", contactId));
        contacts.delete(contact);
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
        SupplierCategory category = categories.save(new SupplierCategory(trimmed));
        audit.record("SUPPLIER_CATEGORY_CREATED", "SupplierCategory", category.getId());
        return mapper.toResponse(category);
    }

    @Transactional
    public CategoryResponse renameCategory(UUID id, String name) {
        SupplierCategory category = requireCategory(id);
        String trimmed = name.trim();
        if (!trimmed.equals(category.getName()) && categories.existsByName(trimmed)) {
            throw new ConflictException("This category already exists.");
        }
        category.setName(trimmed);
        audit.record("SUPPLIER_CATEGORY_RENAMED", "SupplierCategory", id);
        return mapper.toResponse(category);
    }

    /** The database refuses while a supplier holds the category, including an archived one. */
    @Transactional
    public void deleteCategory(UUID id) {
        categories.delete(requireCategory(id));
        try {
            categories.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("This category is in use, so it cannot be deleted.");
        }
        audit.record("SUPPLIER_CATEGORY_DELETED", "SupplierCategory", id);
    }

    private SupplierCategory requireCategory(UUID id) {
        return categories.findById(id).orElseThrow(() -> new ResourceNotFoundException("Category", id.toString()));
    }

    /** Orders may only be placed with an approved, active supplier. */
    @Transactional(readOnly = true)
    public SupplierReference requireApproved(UUID id) {
        Supplier supplier = load(id);
        if (supplier.getStatus() != Status.APPROVED || supplier.getArchivedAt() != null) {
            throw new BusinessRuleException("Supplier %s is not approved.".formatted(supplier.getCode()));
        }
        return mapper.toReference(supplier);
    }

    /** Includes archived suppliers, because historical records still display them. */
    @Transactional(readOnly = true)
    public SupplierReference reference(UUID id) {
        return mapper.toReference(load(id));
    }

    @Transactional(readOnly = true)
    public Map<UUID, SupplierReference> references(Collection<UUID> ids) {
        return suppliers.findAllById(ids).stream()
                .map(mapper::toReference)
                .collect(Collectors.toMap(SupplierReference::id, Function.identity()));
    }

    @Override
    public String entityType() {
        return "SUPPLIER";
    }

    @Override
    public String viewPermission() {
        return Permissions.SUPPLIER_VIEW;
    }

    @Override
    public String managePermission() {
        return Permissions.SUPPLIER_UPDATE;
    }

    @Override
    public boolean parentExists(UUID entityId) {
        return suppliers.existsById(entityId);
    }

    private Supplier load(UUID id) {
        return suppliers.findById(id).orElseThrow(() -> new ResourceNotFoundException("Supplier", id));
    }

    private void apply(Supplier supplier, SaveSupplierRequest request) {
        if (request.categoryId() != null && !categories.existsById(request.categoryId())) {
            throw new BusinessRuleException("This category does not exist.");
        }
        supplier.setName(request.name().trim());
        supplier.setLegalName(request.legalName());
        supplier.setGstin(StringUtils.hasText(request.gstin()) ? request.gstin() : null);
        supplier.setCategoryId(request.categoryId());
        supplier.setEmail(StringUtils.hasText(request.email()) ? request.email() : null);
        supplier.setPhone(request.phone());
        supplier.setAddress(request.address());
        supplier.setCity(request.city());
        supplier.setState(request.state());
        supplier.setPostalCode(request.postalCode());
    }
}
