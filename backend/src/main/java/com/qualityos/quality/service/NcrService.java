package com.qualityos.quality.service;

import com.qualityos.common.attachment.AttachmentPolicy;
import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.common.persistence.DocumentNumberService;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.material.service.MaterialService;
import com.qualityos.quality.dto.QualityDtos.Assignee;
import com.qualityos.quality.dto.QualityDtos.CapaHistory;
import com.qualityos.quality.dto.QualityDtos.NcrHistory;
import com.qualityos.quality.dto.QualityDtos.CreateNcrRequest;
import com.qualityos.quality.dto.QualityDtos.DefectCategoryResponse;
import com.qualityos.quality.dto.QualityDtos.InspectionFailure;
import com.qualityos.quality.dto.QualityDtos.InvestigationRequest;
import com.qualityos.quality.dto.QualityDtos.LinkedCapa;
import com.qualityos.quality.dto.QualityDtos.NcrDetail;
import com.qualityos.quality.dto.QualityDtos.NcrSummary;
import com.qualityos.quality.dto.QualityDtos.SaveDefectCategoryRequest;
import com.qualityos.quality.dto.QualityDtos.StatusChange;
import com.qualityos.quality.entity.CorrectiveAction;
import com.qualityos.quality.entity.DefectCategory;
import com.qualityos.quality.entity.NcrStatusChange;
import com.qualityos.quality.entity.NonConformance;
import com.qualityos.quality.repository.CorrectiveActionRepository;
import com.qualityos.quality.repository.DefectCategoryRepository;
import com.qualityos.quality.repository.NcrStatusChangeRepository;
import com.qualityos.quality.repository.NonConformanceRepository;
import com.qualityos.supplier.service.SupplierService;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NcrService implements AttachmentPolicy {

    private static final String ENTITY = "NonConformance";
    private static final String ATTACHMENT_TYPE = "NCR";
    private static final String NUMBER_PREFIX = "NCR";

    private final NonConformanceRepository ncrs;
    private final NcrStatusChangeRepository history;
    private final DefectCategoryRepository categories;
    private final CorrectiveActionRepository capas;
    private final SupplierService suppliers;
    private final MaterialService materials;
    private final TenantAccessService access;
    private final DocumentNumberService numbers;
    private final AuditService audit;
    private final QualityAlerts alerts;

    NcrService(
            NonConformanceRepository ncrs,
            NcrStatusChangeRepository history,
            DefectCategoryRepository categories,
            CorrectiveActionRepository capas,
            SupplierService suppliers,
            MaterialService materials,
            TenantAccessService access,
            DocumentNumberService numbers,
            AuditService audit,
            QualityAlerts alerts) {
        this.ncrs = ncrs;
        this.history = history;
        this.categories = categories;
        this.capas = capas;
        this.suppliers = suppliers;
        this.materials = materials;
        this.access = access;
        this.numbers = numbers;
        this.audit = audit;
        this.alerts = alerts;
    }

    @Transactional(readOnly = true)
    public List<Assignee> assignees() {
        return access.activeMembers().entrySet().stream()
                .map(member -> new Assignee(member.getKey(), member.getValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DefectCategoryResponse> categories() {
        return categories.findByArchivedAtIsNullOrderByCode().stream()
                .map(category -> new DefectCategoryResponse(category.getId(), category.getCode(), category.getName()))
                .toList();
    }

    @Transactional
    public DefectCategoryResponse addCategory(SaveDefectCategoryRequest request) {
        String code = request.code().trim();
        if (categories.existsByCode(code)) {
            throw new ConflictException("A defect category with this code already exists.");
        }
        DefectCategory saved = categories.save(new DefectCategory(code, request.name().trim()));
        audit.record("DEFECT_CATEGORY_CREATED", "DefectCategory", saved.getId());
        return new DefectCategoryResponse(saved.getId(), saved.getCode(), saved.getName());
    }

    @Transactional
    public void archiveCategory(UUID id) {
        categories.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DefectCategory", id))
                .setArchivedAt(Instant.now());
        audit.record("DEFECT_CATEGORY_ARCHIVED", "DefectCategory", id);
    }

    @Transactional(readOnly = true)
    public PageResponse<NcrSummary> list(
            String search, NonConformance.Status status, NonConformance.Severity severity, UUID inspectionId,
            Pageable pageable) {
        Specification<NonConformance> filter = Specification
                .<NonConformance>where(Searches.contains(search, "ncrNumber", "title", "batchNumber"))
                .and(Searches.equalTo("status", status))
                .and(Searches.equalTo("severity", severity))
                .and(Searches.equalTo("inspectionId", inspectionId));
        Page<NonConformance> page = ncrs.findAll(filter, pageable);
        Map<UUID, String> members = access.activeMembers();
        Map<UUID, String> supplierNames = supplierNames(page.map(NonConformance::getSupplierId).toSet());
        Map<UUID, String> materialNames = materialNames(page.map(NonConformance::getMaterialId).toSet());
        return PageResponse.from(page, ncr -> new NcrSummary(
                ncr.getId(),
                ncr.getNcrNumber(),
                ncr.getTitle(),
                ncr.getStatus().name(),
                ncr.getSeverity().name(),
                ncr.getSource().name(),
                supplierNames.get(ncr.getSupplierId()),
                materialNames.get(ncr.getMaterialId()),
                ncr.getBatchNumber(),
                members.get(ncr.getAssignedTo()),
                ncr.getCreatedAt()));
    }

    @Transactional(readOnly = true)
    public NcrDetail get(UUID id) {
        return toDetail(load(id));
    }

    @Transactional
    public NcrDetail create(CreateNcrRequest request) {
        requireReferences(request);
        if (request.defectCategoryId() != null) {
            requireCategory(request.defectCategoryId());
        }
        return toDetail(raise(new NonConformance(
                numbers.next(NUMBER_PREFIX),
                NonConformance.Source.MANUAL,
                null,
                request.supplierId(),
                request.materialId(),
                blankToNull(request.batchNumber()),
                request.defectCategoryId(),
                request.severity(),
                request.title().trim(),
                request.description().trim(),
                request.rejectedQuantity())));
    }

    /** Joins the disposition's transaction, so a failure cannot be recorded without its NCR. Repeats do nothing. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void raiseFromInspection(InspectionFailure failure) {
        if (ncrs.findByInspectionId(failure.inspectionId()).isPresent()) {
            return;
        }
        raise(new NonConformance(
                numbers.next(NUMBER_PREFIX),
                NonConformance.Source.INSPECTION,
                failure.inspectionId(),
                failure.supplierId(),
                failure.materialId(),
                failure.batchNumber(),
                null,
                failure.critical() ? NonConformance.Severity.CRITICAL : NonConformance.Severity.MAJOR,
                "Inspection " + failure.inspectionNumber() + " failed",
                failure.description(),
                failure.rejectedQuantity()));
    }

    @Transactional
    public NcrDetail assign(UUID id, UUID assigneeId) {
        requireMember(assigneeId);
        NonConformance ncr = lock(id);
        NonConformance.Status previous = ncr.getStatus();
        ncr.assign(assigneeId);
        record(ncr, previous, "Assigned");
        alerts.ncrAssigned(ncr);
        return toDetail(ncr);
    }

    @Transactional
    public NcrDetail recordContainment(UUID id, String action) {
        return transition(id, action.trim(), ncr -> ncr.recordContainment(action.trim()));
    }

    @Transactional
    public NcrDetail recordInvestigation(UUID id, InvestigationRequest request) {
        if (request.defectCategoryId() != null) {
            requireCategory(request.defectCategoryId());
        }
        String cause = request.rootCause().trim();
        return transition(id, null, ncr -> ncr.recordInvestigation(cause, request.defectCategoryId()));
    }

    /** A critical or major NCR needs a closed CAPA behind it, and no NCR may leave a CAPA unfinished. */
    @Transactional
    public NcrDetail requestVerification(UUID id, String note) {
        NonConformance ncr = lock(id);
        List<CorrectiveAction> linked = capas.findByNcrIdOrderByCapaNumber(id);
        boolean unfinished = linked.stream().anyMatch(capa -> !capa.getStatus().isFinished());
        if (unfinished) {
            throw new BusinessRuleException("Every linked CAPA must be closed or cancelled first.");
        }
        boolean closedCapa = linked.stream().anyMatch(capa -> capa.getStatus() == CorrectiveAction.Status.CLOSED);
        if (ncr.getSeverity() != NonConformance.Severity.MINOR && !closedCapa) {
            throw new BusinessRuleException("A critical or major NCR needs a closed CAPA.");
        }
        NonConformance.Status previous = ncr.getStatus();
        ncr.requestVerification(TenantContext.getCurrentUserId());
        record(ncr, previous, note);
        alerts.ncrAwaitingClosure(ncr);
        return toDetail(ncr);
    }

    @Transactional
    public NcrDetail sendBack(UUID id, String note) {
        return transition(id, note, NonConformance::sendBack);
    }

    @Transactional
    public NcrDetail close(UUID id, String note) {
        return transition(id, note, ncr -> ncr.close(TenantContext.getCurrentUserId(), note, Instant.now()));
    }

    @Transactional
    public NcrDetail reject(UUID id, String reason) {
        return transition(id, reason, ncr -> ncr.reject(reason));
    }

    @Transactional
    public NcrDetail cancel(UUID id, String reason) {
        return transition(id, reason, ncr -> ncr.cancel(reason));
    }

    @Transactional(readOnly = true)
    public List<NcrHistory> history(Collection<UUID> inspectionIds, Collection<String> batchNumbers) {
        Collection<UUID> inspections = inspectionIds.isEmpty() ? List.of(new UUID(0, 0)) : inspectionIds;
        Collection<String> batches = batchNumbers.isEmpty() ? List.of("") : batchNumbers;
        return ncrs.findForTrace(inspections, batches).stream()
                .map(ncr -> new NcrHistory(ncr.getId(), ncr.getNcrNumber(), ncr.getTitle(), ncr.getStatus().name(),
                        ncr.getSeverity().name(), ncr.getBatchNumber(), ncr.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CapaHistory> capaHistory(Collection<UUID> ncrIds) {
        if (ncrIds.isEmpty()) {
            return List.of();
        }
        return capas.findByNcrIdInOrderByCreatedAt(ncrIds).stream()
                .map(capa -> new CapaHistory(capa.getId(), capa.getCapaNumber(), capa.getTitle(),
                        capa.getStatus().name(), capa.getNcrId(), capa.getCreatedAt()))
                .toList();
    }

    @Override
    public String entityType() {
        return ATTACHMENT_TYPE;
    }

    @Override
    public String viewPermission() {
        return Permissions.NCR_VIEW;
    }

    @Override
    public String managePermission() {
        return Permissions.NCR_CREATE;
    }

    @Override
    public boolean parentExists(UUID entityId) {
        return ncrs.existsById(entityId);
    }

    @Override
    public boolean locked(UUID entityId) {
        return ncrs.findById(entityId).map(ncr -> ncr.getStatus().isFinished()).orElse(false);
    }

    /** For the CAPA service, which links to NCRs without reaching into their repository. */
    NonConformance requireOpen(UUID id) {
        NonConformance ncr = load(id);
        if (ncr.getStatus().isFinished()) {
            throw new BusinessRuleException("A CAPA cannot be raised against a finished NCR.");
        }
        return ncr;
    }

    private NonConformance raise(NonConformance ncr) {
        ncrs.saveAndFlush(ncr);
        history.save(new NcrStatusChange(ncr.getId(), null, ncr.getStatus().name(), null));
        audit.record("NCR_RAISED", ENTITY, ncr.getId(), null,
                Map.of("number", ncr.getNcrNumber(), "severity", ncr.getSeverity()));
        alerts.ncrRaised(ncr);
        return ncr;
    }

    private NcrDetail transition(UUID id, String note, Consumer<NonConformance> change) {
        NonConformance ncr = lock(id);
        NonConformance.Status previous = ncr.getStatus();
        change.accept(ncr);
        record(ncr, previous, note);
        return toDetail(ncr);
    }

    private void record(NonConformance ncr, NonConformance.Status previous, String note) {
        ncrs.saveAndFlush(ncr);
        history.save(new NcrStatusChange(ncr.getId(), previous.name(), ncr.getStatus().name(), note));
        audit.record("NCR_STATUS_CHANGED", ENTITY, ncr.getId(), Map.of("status", previous), Map.of("status", ncr.getStatus()));
    }

    private void requireReferences(CreateNcrRequest request) {
        if (request.supplierId() != null && !supplierNames(Set.of(request.supplierId())).containsKey(request.supplierId())) {
            throw new BusinessRuleException("This supplier does not exist.");
        }
        if (request.materialId() != null && !materialNames(Set.of(request.materialId())).containsKey(request.materialId())) {
            throw new BusinessRuleException("This material does not exist.");
        }
    }

    private void requireCategory(UUID id) {
        categories.findById(id)
                .filter(category -> category.getArchivedAt() == null)
                .orElseThrow(() -> new BusinessRuleException("This defect category does not exist."));
    }

    void requireMember(UUID userId) {
        if (access.activeMemberEmail(userId).isEmpty()) {
            throw new BusinessRuleException("This person is not an active member of the organisation.");
        }
    }

    private NonConformance load(UUID id) {
        return ncrs.findById(id).orElseThrow(() -> new ResourceNotFoundException("NCR", id));
    }

    private NonConformance lock(UUID id) {
        return ncrs.findForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("NCR", id));
    }

    private Map<UUID, String> supplierNames(Set<UUID> ids) {
        Set<UUID> known = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return suppliers.references(known).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().name()));
    }

    private Map<UUID, String> materialNames(Set<UUID> ids) {
        Set<UUID> known = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return materials.references(known).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().name()));
    }

    private NcrDetail toDetail(NonConformance ncr) {
        Map<UUID, String> members = access.activeMembers();
        List<LinkedCapa> linked = capas.findByNcrIdOrderByCapaNumber(ncr.getId()).stream()
                .map(capa -> new LinkedCapa(capa.getId(), capa.getCapaNumber(), capa.getTitle(), capa.getStatus().name()))
                .toList();
        List<StatusChange> changes = history.findByNcrIdOrderByCreatedAt(ncr.getId()).stream()
                .map(change -> new StatusChange(change.getFromStatus(), change.getToStatus(), change.getNote(),
                        members.getOrDefault(change.getCreatedBy(), "System or former member"), change.getCreatedAt()))
                .toList();
        Optional<DefectCategory> category = Optional.ofNullable(ncr.getDefectCategoryId()).flatMap(categories::findById);
        return new NcrDetail(
                ncr.getId(),
                ncr.getNcrNumber(),
                ncr.getTitle(),
                ncr.getDescription(),
                ncr.getStatus().name(),
                ncr.getSeverity().name(),
                ncr.getSource().name(),
                ncr.getInspectionId(),
                ncr.getSupplierId(),
                ncr.getSupplierId() == null ? null : supplierNames(Set.of(ncr.getSupplierId())).get(ncr.getSupplierId()),
                ncr.getMaterialId(),
                ncr.getMaterialId() == null ? null : materialNames(Set.of(ncr.getMaterialId())).get(ncr.getMaterialId()),
                ncr.getBatchNumber(),
                ncr.getDefectCategoryId(),
                category.map(DefectCategory::getName).orElse(null),
                ncr.getRejectedQuantity(),
                ncr.getAssignedTo(),
                members.get(ncr.getAssignedTo()),
                ncr.getContainmentAction(),
                ncr.getRootCause(),
                ncr.getOutcomeNote(),
                members.getOrDefault(ncr.getCreatedBy(), "System or former member"),
                ncr.getVerificationRequestedBy(),
                ncr.getCreatedAt(),
                ncr.getClosedAt(),
                linked,
                changes);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
