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
import com.qualityos.identity.service.ReviewPolicy;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.notification.service.NotificationService;
import com.qualityos.notification.service.NotificationType;
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
import java.util.Set;
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
    private final ReviewPolicy reviewPolicy;
    private final com.qualityos.organization.service.IndiaPlaces places;
    private final TenantAccessService access;
    private final NotificationService notifications;

    SupplierService(
            SupplierRepository suppliers,
            SupplierCategoryRepository categories,
            SupplierContactRepository contacts,
            SupplierMapper mapper,
            AuditService audit,
            ReviewPolicy reviewPolicy,
            com.qualityos.organization.service.IndiaPlaces places,
            TenantAccessService access,
            NotificationService notifications) {
        this.access = access;
        this.notifications = notifications;
        this.places = places;
        this.reviewPolicy = reviewPolicy;
        this.suppliers = suppliers;
        this.categories = categories;
        this.contacts = contacts;
        this.mapper = mapper;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<SupplierResponse> list(String search, Status status, boolean archived, Pageable pageable) {
        Specification<Supplier> filter = Specification.<Supplier>where(Searches.archived(archived))
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
        if (suppliers.existsByCodeIgnoreCase(code)) {
            throw new ConflictException("A supplier with this code already exists.");
        }
        Supplier supplier = new Supplier(code);
        apply(supplier, request);
        suppliers.saveAndFlush(supplier);
        var created = mapper.toResponse(supplier);
        audit.record("SUPPLIER_CREATED", "Supplier", supplier.getId(), null, audit.state(created));
        return created;
    }

    @Transactional
    public SupplierResponse update(UUID id, SaveSupplierRequest request) {
        Supplier supplier = load(id);
        var before = audit.state(mapper.toResponse(supplier));
        supplier.requireVersion(request.version());
        String identity = identity(supplier);
        apply(supplier, request);
        // An approver checked who the supplier is. A new name or GSTIN is someone else, so it is checked again.
        if (supplier.getStatus() == Status.APPROVED && !identity.equals(identity(supplier))) {
            supplier.sendBackForApproval(TenantContext.getCurrentUserId(),
                    "Name, legal name or GSTIN changed, so it needs approving again.");
            tellApprovers(supplier);
        }
        suppliers.saveAndFlush(supplier);
        audit.recordChange("SUPPLIER_UPDATED", "Supplier", id, before, audit.state(mapper.toResponse(supplier)));
        return mapper.toResponse(supplier);
    }

    /** Suppliers stay on record for quality history, but cannot be used on new orders. */
    @Transactional
    public void archive(UUID id) {
        load(id).setArchivedAt(Instant.now());
        audit.record("SUPPLIER_ARCHIVED", "Supplier", id);
    }

    /** Brings an archived supplier back into lists and pickers. */
    @Transactional
    public void restore(UUID id) {
        var restored = load(id);
        if (restored.getArchivedAt() == null) {
            throw new BusinessRuleException("This supplier is not archived.");
        }
        restored.setArchivedAt(null);
        audit.record("SUPPLIER_RESTORED", "Supplier", id);
    }

    @Transactional
    public SupplierResponse changeStatus(UUID id, Status target, String note) {
        Supplier supplier = load(id);
        Status previous = supplier.getStatus();
        UUID actor = TenantContext.getCurrentUserId();
        // Approval is a second person's check, as for inspections, NCRs and CAPAs. The note is the self-review reason
        // where an organisation of one is allowed to review its own work.
        if (previous == Status.PENDING_APPROVAL && target == Status.APPROVED && !reviewPolicy.permits(
                actor, supplier.getSubmittedBy(), "Supplier", id, "SUPPLIER_APPROVE", note)) {
            throw new BusinessRuleException("You submitted this supplier, so someone else must approve it.");
        }
        if (previous == Status.PENDING_APPROVAL && target == Status.REJECTED && !reviewPolicy.permits(
                actor, supplier.getSubmittedBy(), "Supplier", id, "SUPPLIER_REJECT", note)) {
            throw new BusinessRuleException("You submitted this supplier, so someone else must review it.");
        }
        if (target == Status.APPROVED) {
            note = null;
        }
        supplier.transitionTo(target, actor, note);
        suppliers.saveAndFlush(supplier);
        if (target == Status.PENDING_APPROVAL) {
            tellApprovers(supplier);
        }
        if (previous == Status.PENDING_APPROVAL && target == Status.REJECTED) {
            tellSubmitter(supplier);
        }
        audit.record("SUPPLIER_STATUS_CHANGED", "Supplier", id,
                Map.of("status", previous), Map.of("status", target));
        return mapper.toResponse(supplier);
    }

    /** The other supplier managers learn that a supplier waits for them; the one who submitted it is left out. */
    private void tellApprovers(Supplier supplier) {
        String summary = supplier.getStatusNote() != null ? supplier.getStatusNote()
                : supplier.getCode() + (supplier.getGstin() == null ? "" : " · GSTIN " + supplier.getGstin());
        notifications.notify(access.holdersOf(Permissions.SUPPLIER_APPROVE),
                NotificationType.SUPPLIER_AWAITING_APPROVAL,
                supplier.getName() + " needs approval", summary, "/suppliers/" + supplier.getId());
    }

    /** The one review outcome the submitter must act on, so they hear of it as of an inspection sent back. */
    private void tellSubmitter(Supplier supplier) {
        if (supplier.getSubmittedBy() == null) {
            return;
        }
        notifications.notify(Set.of(supplier.getSubmittedBy()), NotificationType.SUPPLIER_REJECTED,
                supplier.getName() + " was rejected",
                supplier.getStatusNote() == null ? supplier.getCode() : supplier.getStatusNote(),
                "/suppliers/" + supplier.getId());
    }

    @Transactional
    public ContactResponse addContact(UUID supplierId, SaveContactRequest request) {
        load(supplierId);
        SupplierContact contact = contacts.save(new SupplierContact(
                supplierId, request.name().trim(), request.designation(), request.email(), request.phone(),
                request.primary()));
        audit.record("SUPPLIER_CONTACT_ADDED", "Supplier", supplierId, null, Map.of("contact", contact.getName()));
        return mapper.toResponse(contact);
    }

    @Transactional
    public void removeContact(UUID supplierId, UUID contactId) {
        SupplierContact contact = contacts.findById(contactId)
                .filter(found -> found.getSupplierId().equals(supplierId))
                .orElseThrow(() -> new ResourceNotFoundException("Contact", contactId));
        contacts.delete(contact);
        audit.record("SUPPLIER_CONTACT_REMOVED", "Supplier", supplierId, Map.of("contact", contact.getName()), null);
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
        var created = mapper.toResponse(category);
        audit.record("SUPPLIER_CATEGORY_CREATED", "SupplierCategory", category.getId(), null, audit.state(created));
        return created;
    }

    @Transactional
    public CategoryResponse renameCategory(UUID id, String name) {
        SupplierCategory category = requireCategory(id);
        String trimmed = name.trim();
        if (!trimmed.equals(category.getName()) && categories.existsByName(trimmed)) {
            throw new ConflictException("This category already exists.");
        }
        String previous = category.getName();
        category.setName(trimmed);
        audit.record("SUPPLIER_CATEGORY_RENAMED", "SupplierCategory", id, Map.of("name", previous), Map.of("name", trimmed));
        return mapper.toResponse(category);
    }

    /** The database refuses while a supplier holds the category, including an archived one. */
    @Transactional
    public void deleteCategory(UUID id) {
        var category = requireCategory(id);
        String name = category.getName();
        categories.delete(category);
        try {
            categories.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("This category is in use, so it cannot be deleted.");
        }
        audit.record("SUPPLIER_CATEGORY_DELETED", "SupplierCategory", id, Map.of("name", name), null);
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

    private static String identity(Supplier supplier) {
        return String.join("|", String.valueOf(supplier.getName()), String.valueOf(supplier.getLegalName()),
                String.valueOf(supplier.getGstin()));
    }

    private void apply(Supplier supplier, SaveSupplierRequest request) {
        places.requireGstinInState(request.gstin(), request.state());
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
