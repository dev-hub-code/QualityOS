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
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.identity.service.ReviewPolicy;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.material.service.MaterialService;
import com.qualityos.organization.service.FacilityService;
import com.qualityos.quality.dto.QualityDtos.Assignee;
import com.qualityos.quality.dto.QualityDtos.AuditFailure;
import com.qualityos.quality.dto.QualityDtos.CalibrationFailure;
import com.qualityos.quality.dto.QualityDtos.CustomerComplaint;
import com.qualityos.quality.dto.QualityDtos.ProductionReject;
import com.qualityos.quality.dto.QualityDtos.CustomerReturn;
import com.qualityos.quality.dto.QualityDtos.CapaHistory;
import com.qualityos.quality.dto.QualityDtos.NcrHistory;
import com.qualityos.quality.dto.QualityDtos.NcrOutcome;
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
import java.math.BigDecimal;
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
    private final FacilityScope scope;
    private final FacilityService facilities;
    private final ReviewPolicy reviewPolicy;
    private final QualityRules rules;
    private final CalibrationDecisions calibrationDecisions;
    private final NcrPlaces places;

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
            QualityAlerts alerts,
            FacilityScope scope,
            FacilityService facilities,
            ReviewPolicy reviewPolicy,
            QualityRules rules,
            CalibrationDecisions calibrationDecisions,
            NcrPlaces places) {
        this.places = places;
        this.rules = rules;
        this.calibrationDecisions = calibrationDecisions;
        this.reviewPolicy = reviewPolicy;
        this.facilities = facilities;
        this.scope = scope;
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

    /** Where a process incident can be said to have happened, in the caller's plants. */
    @Transactional(readOnly = true)
    public NcrPlaces.Options places() {
        return places.options(scope::allows);
    }

    /** Work that can be handed to someone, by the permission it needs. Other permissions are not listed. */
    private static final Set<String> ASSIGNABLE = Set.of(Permissions.NCR_ASSIGN, Permissions.CAPA_MANAGE,
            Permissions.AUDIT_MANAGE, Permissions.RECALL_MANAGE);

    /** @param permission null for every active member, or one of {@link #ASSIGNABLE} for those who hold it */
    @Transactional(readOnly = true)
    public List<Assignee> assignees(String permission) {
        if (permission != null && !ASSIGNABLE.contains(permission)) {
            throw new BusinessRuleException("People can be listed only for NCR, CAPA, audit or recall work.");
        }
        Set<UUID> holders = permission == null ? null : access.holdersOf(permission);
        return access.activeMembers().entrySet().stream()
                .filter(member -> holders == null || holders.contains(member.getKey()))
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
        Page<NonConformance> page = ncrs.findAll(scope.limit(filter, "facilityId"), pageable);
        Map<UUID, String> members = access.names();
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
        NonConformance.Source source = request.source() == null ? NonConformance.Source.MANUAL : request.source();
        if (!source.byHand()) {
            throw new BusinessRuleException("An NCR from this source is raised by its own record, not by hand.");
        }
        UUID facilityId = request.facilityId();
        boolean placed = request.productionLineId() != null || request.workCenterId() != null
                || request.machineId() != null;
        if (source == NonConformance.Source.PROCESS) {
            NcrPlaces.Place place = places.require(request.productionLineId(), request.workCenterId(), request.machineId());
            if (facilityId != null && !facilityId.equals(place.facilityId())) {
                throw new BusinessRuleException("Where it happened is at another plant than the one chosen.");
            }
            facilityId = place.facilityId();
        } else if (placed) {
            throw new BusinessRuleException("Only a process incident says which line, work centre or machine.");
        }
        if (source == NonConformance.Source.SUPPLIER && request.supplierId() == null) {
            throw new BusinessRuleException("A supplier issue needs its supplier.");
        }
        scope.requirePlaced(facilityId);
        if (facilityId != null) {
            facilities.requireActive(facilityId);
        }
        requireReferences(request);
        if (request.defectCategoryId() != null) {
            requireCategory(request.defectCategoryId());
        }
        return toDetail(raise(facilityId, new NonConformance(
                numbers.next(NUMBER_PREFIX),
                source,
                null,
                request.supplierId(),
                request.materialId(),
                blankToNull(request.batchNumber()),
                request.defectCategoryId(),
                request.severity(),
                request.title().trim(),
                request.description().trim(),
                request.rejectedQuantity()).rejectedIn(rejectedUnit(request))
                .at(request.productionLineId(), request.workCenterId(), request.machineId())));
    }

    /** Joins the disposition's transaction, so a failure cannot be recorded without its NCR. Repeats do nothing. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void raiseFromInspection(InspectionFailure failure) {
        if (ncrs.findByInspectionId(failure.inspectionId()).isPresent()) {
            return;
        }
        raise(failure.facilityId(), new NonConformance(
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
                failure.rejectedQuantity()).rejectedIn(failure.rejectedUomId()));
    }

    /** Joins the finding's transaction, so a finding cannot exist without its NCR. Repeats return the same NCR. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID raiseFromAudit(AuditFailure failure) {
        return ncrs.findByAuditFindingId(failure.findingId())
                .orElseGet(() -> raise(failure.facilityId(), NonConformance.fromAudit(
                        numbers.next(NUMBER_PREFIX),
                        failure.auditId(),
                        failure.findingId(),
                        failure.supplierId(),
                        failure.major() ? NonConformance.Severity.MAJOR : NonConformance.Severity.MINOR,
                        title(failure),
                        failure.description())))
                .getId();
    }

    /**
     * Joins the calibration's transaction, so a failure cannot be recorded without its NCR. Repeats return the same
     * NCR. The goods themselves are decided inspection by inspection in the calibration's impact review.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public NcrOutcome raiseFromCalibration(CalibrationFailure failure) {
        NonConformance ncr = ncrs.findByCalibrationRecordId(failure.calibrationRecordId())
                .orElseGet(() -> raise(failure.facilityId(), NonConformance.fromCalibration(
                        numbers.next(NUMBER_PREFIX),
                        failure.calibrationRecordId(),
                        failure.instrumentId(),
                        failure.instrumentCode() + " " + failure.instrumentName() + " failed calibration",
                        "Failed calibration on %s. Measurements since its last good calibration are in doubt: %s."
                                .formatted(failure.calibratedOn(), String.join(", ", failure.inspectionNumbers())))));
        return new NcrOutcome(ncr.getId(), ncr.getNcrNumber(), ncr.getStatus().name(), ncr.getStatus().isFinished(),
                List.of());
    }

    /**
     * Joins the report's transaction, so a reject cannot be recorded without its NCR. Repeats return the same NCR. The
     * part sent to rework is still a nonconformance; its rework order deals with the goods.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID raiseFromProduction(ProductionReject reject) {
        return ncrs.findByOperationReportId(reject.operationReportId())
                .orElseGet(() -> raise(reject.facilityId(), NonConformance.fromProduction(
                        numbers.next(NUMBER_PREFIX),
                        reject.workOrderId(),
                        reject.operationReportId(),
                        reject.batchNumber(),
                        reject.defectCategoryId(),
                        "%s operation %d: %s".formatted(reject.workOrderNumber(), reject.operationSequence(),
                                rejectSummary(reject)),
                        rejectDescription(reject),
                        reject.quantity()).rejectedIn(reject.uomId())))
                .getId();
    }

    /**
     * Joins the return's transaction, so a complaint cannot be recorded without its NCR. Repeats return the same NCR.
     * The returned goods themselves wait for their own inspection.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID raiseFromCustomerReturn(CustomerReturn returned) {
        return ncrs.findByDispatchReversalId(returned.dispatchReversalId())
                .orElseGet(() -> raise(returned.facilityId(), NonConformance.fromCustomerReturn(
                        numbers.next(NUMBER_PREFIX),
                        returned.dispatchReversalId(),
                        returned.batchNumbers().size() == 1 ? returned.batchNumbers().getFirst() : null,
                        "Customer return %s from %s".formatted(returned.returnNumber(), returned.customerName()),
                        "%s returned %s from dispatch %s (reference %s). Reason: %s".formatted(
                                returned.customerName(), String.join(", ", returned.lines()),
                                returned.dispatchNumber(), returned.returnReference(), returned.reason()))))
                .getId();
    }

    /** Joins the caller's transaction. A dispatch can draw any number of complaints, each its own NCR. */
    @Transactional(propagation = Propagation.MANDATORY)
    public NcrOutcome raiseFromCustomerComplaint(CustomerComplaint complaint) {
        NonConformance ncr = raise(complaint.facilityId(), NonConformance.fromCustomerComplaint(
                numbers.next(NUMBER_PREFIX),
                complaint.dispatchId(),
                complaint.batchNumber(),
                NonConformance.Severity.valueOf(complaint.severity()),
                "Customer complaint from %s about %s".formatted(complaint.customerName(), complaint.dispatchNumber()),
                "%s reported on %s (reference %s) about dispatch %s (%s): %s".formatted(
                        complaint.customerName(), complaint.receivedOn(), complaint.reference(),
                        complaint.dispatchNumber(), String.join(", ", complaint.lines()), complaint.description())));
        return new NcrOutcome(ncr.getId(), ncr.getNcrNumber(), ncr.getStatus().name(), false, List.of());
    }

    /** The complaints raised against a dispatch, oldest first. */
    @Transactional(readOnly = true)
    public List<NcrOutcome> forDispatch(UUID dispatchId) {
        return ncrs.findByDispatchIdOrderByCreatedAt(dispatchId).stream()
                .map(ncr -> new NcrOutcome(ncr.getId(), ncr.getNcrNumber(), ncr.getStatus().name(),
                        ncr.getStatus().isFinished(), List.of()))
                .toList();
    }

    /** The NCRs raised for these customer returns, keyed by return. */
    @Transactional(readOnly = true)
    public Map<UUID, NcrOutcome> forCustomerReturns(java.util.Collection<UUID> returnIds) {
        if (returnIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, NcrOutcome> found = new java.util.HashMap<>();
        ncrs.findByDispatchReversalIdIn(returnIds).forEach(ncr -> found.put(ncr.getDispatchReversalId(), outcome(ncr)));
        // Without a complaint, the NCR is the one the inspection of the returned goods raised.
        var byInspection = ncrs.findRaisedByReturnInspections(returnIds);
        Map<UUID, NonConformance> raised = ncrs.findAllById(byInspection.stream()
                        .map(NonConformanceRepository.ReturnInspectionNcr::getNcrId).toList()).stream()
                .collect(java.util.stream.Collectors.toMap(NonConformance::getId, ncr -> ncr));
        byInspection.forEach(row -> found.putIfAbsent(row.getReversalId(), outcome(raised.get(row.getNcrId()))));
        return found;
    }

    private static NcrOutcome outcome(NonConformance ncr) {
        return new NcrOutcome(ncr.getId(), ncr.getNcrNumber(), ncr.getStatus().name(), ncr.getStatus().isFinished(),
                List.of());
    }

    /** For a shop-floor report: the category must exist and be in use. */
    @Transactional(readOnly = true)
    public void requireDefectCategory(UUID id) {
        requireCategory(id);
    }

    /** Whether the organisation has defect categories to choose from, so rejects can be told apart. */
    @Transactional(readOnly = true)
    public boolean hasDefectCategories() {
        return !categories.findByArchivedAtIsNullOrderByCode().isEmpty();
    }

    private static String rejectSummary(ProductionReject reject) {
        List<String> parts = new java.util.ArrayList<>();
        if (reject.rejected().signum() > 0) {
            parts.add(plainNumber(reject.rejected()) + " rejected");
        }
        if (reject.scrapped().signum() > 0) {
            parts.add(plainNumber(reject.scrapped()) + " scrapped");
        }
        return String.join(", ", parts);
    }

    private static String rejectDescription(ProductionReject reject) {
        StringBuilder text = new StringBuilder("Reported on operation %d (%s) of %s: %s.".formatted(
                reject.operationSequence(), reject.operationName(), reject.workOrderNumber(), rejectSummary(reject)));
        if (reject.rework().signum() > 0) {
            text.append(" %s of the rejected go to rework.".formatted(plainNumber(reject.rework())));
        }
        if (reject.comment() != null && !reject.comment().isBlank()) {
            text.append(" Operator's comment: ").append(reject.comment().trim());
        }
        return text.toString();
    }

    private static String plainNumber(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** The NCR each failed calibration raised, by calibration. Like {@link #outcomes}, for a caller who sees the instrument. */
    @Transactional(readOnly = true)
    public Map<UUID, NcrOutcome> forCalibrations(Collection<UUID> calibrationRecordIds) {
        if (calibrationRecordIds.isEmpty()) {
            return Map.of();
        }
        return ncrs.findByCalibrationRecordIdIn(calibrationRecordIds).stream().collect(Collectors.toMap(
                NonConformance::getCalibrationRecordId,
                ncr -> new NcrOutcome(ncr.getId(), ncr.getNcrNumber(), ncr.getStatus().name(),
                        ncr.getStatus().isFinished(), List.of())));
    }

    /** Runs without a permission check of its own: the caller may see the audit, and this is how it stands. */
    @Transactional(readOnly = true)
    public Map<UUID, NcrOutcome> outcomes(Collection<UUID> ids) {
        return ncrs.findAllById(ids).stream().collect(Collectors.toMap(NonConformance::getId, ncr -> new NcrOutcome(
                ncr.getId(),
                ncr.getNcrNumber(),
                ncr.getStatus().name(),
                ncr.getStatus().isFinished(),
                capas.findAnsweringNcr(ncr.getId()).stream()
                        .map(capa -> new LinkedCapa(
                                capa.getId(), capa.getCapaNumber(), capa.getTitle(), capa.getStatus().name()))
                        .toList())));
    }

    @Transactional
    public NcrDetail assign(UUID id, UUID assigneeId) {
        requireMemberWho(assigneeId, Permissions.NCR_ASSIGN,
                "This person cannot work on NCRs. Assign it to someone whose role can.");
        NonConformance ncr = lock(id);
        scope.requireAllowedFor(assigneeId, ncr.getFacilityId());
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

    /**
     * Links an open NCR to a CAPA that already exists, when the same root cause is behind it, so no duplicate CAPA is
     * needed. A closed CAPA counts for closing the NCR; one that was cancelled answers nothing.
     */
    @Transactional
    public NcrDetail linkCapa(UUID id, UUID capaId, String note) {
        NonConformance ncr = lock(id);
        if (ncr.getStatus().isFinished()) {
            throw new BusinessRuleException("A finished NCR cannot be linked to a CAPA.");
        }
        CorrectiveAction capa = capas.findById(capaId)
                .filter(found -> scope.allows(found.getFacilityId()))
                .orElseThrow(() -> new ResourceNotFoundException("CAPA", capaId.toString()));
        if (capa.getStatus() == CorrectiveAction.Status.CANCELLED) {
            throw new BusinessRuleException(capa.getCapaNumber() + " was cancelled. Link a CAPA that is open or closed.");
        }
        if (id.equals(capa.getNcrId()) || capas.linkToNcr(id, capaId, note.trim(), TenantContext.getCurrentUserId()) == 0) {
            throw new BusinessRuleException(capa.getCapaNumber() + " is already linked to this NCR.");
        }
        audit.record("NCR_CAPA_LINKED", ENTITY, id, null, Map.of("capa", capa.getCapaNumber(), "note", note.trim()));
        return toDetail(ncr);
    }

    /** The organisation's rule decides which NCRs need a closed CAPA behind them. None may leave a CAPA unfinished. */
    @Transactional
    public NcrDetail requestVerification(UUID id, String note) {
        NonConformance ncr = lock(id);
        List<CorrectiveAction> linked = capas.findAnsweringNcr(id);
        boolean unfinished = linked.stream().anyMatch(capa -> !capa.getStatus().isFinished());
        if (unfinished) {
            throw new BusinessRuleException("Every linked CAPA must be closed or cancelled first.");
        }
        boolean closedCapa = linked.stream().anyMatch(capa -> capa.getStatus() == CorrectiveAction.Status.CLOSED);
        if (rules.capaRequired(ncr.getSeverity()) && !closedCapa) {
            throw new BusinessRuleException(
                    "A " + ncr.getSeverity().name().toLowerCase(java.util.Locale.ROOT) + " NCR needs a closed CAPA.");
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
        UUID actor = TenantContext.getCurrentUserId();
        return transition(id, note, ncr -> {
            requireCalibrationDecided(ncr);
            ncr.close(actor, note, Instant.now(), reviewPolicy.permits(
                    actor, ncr.getVerificationRequestedBy(), ENTITY, id, "NCR_CLOSE", note));
        });
    }

    /** A failed calibration's NCR stays open until every inspection it put in doubt has a decision. */
    private void requireCalibrationDecided(NonConformance ncr) {
        if (ncr.getCalibrationRecordId() == null) {
            return;
        }
        long pending = calibrationDecisions.pending(ncr.getCalibrationRecordId());
        if (pending > 0) {
            throw new BusinessRuleException("Decide each inspection in doubt on the instrument's page first: "
                    + pending + " still waiting.");
        }
    }

    @Transactional
    public NcrDetail reject(UUID id, String reason) {
        return transition(id, reason, ncr -> ncr.reject(reason));
    }

    @Transactional
    public NcrDetail cancel(UUID id, String reason) {
        return transition(id, reason, ncr -> ncr.cancel(reason));
    }

    @Transactional
    public void correctSubjects(Collection<UUID> inspectionIds, UUID supplierId, UUID materialId) {
        if (!inspectionIds.isEmpty()) {
            ncrs.findOpenByInspectionIdIn(inspectionIds).forEach(ncr -> ncr.correctSubject(supplierId, materialId));
        }
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
        return ncrs.findById(entityId).filter(ncr -> scope.allows(ncr.getFacilityId())).isPresent();
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

    private NonConformance raise(UUID facilityId, NonConformance ncr) {
        ncr.setFacilityId(facilityId);
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

    private UUID rejectedUnit(CreateNcrRequest request) {
        if (request.rejectedQuantity() == null) {
            return null;
        }
        if (request.rejectedUomId() != null) {
            materials.requireUnit(request.rejectedUomId());
            return request.rejectedUomId();
        }
        return request.materialId() == null ? null
                : materials.references(Set.of(request.materialId())).get(request.materialId()).uomId();
    }

    private void requireCategory(UUID id) {
        categories.findById(id)
                .filter(category -> category.getArchivedAt() == null)
                .orElseThrow(() -> new BusinessRuleException("This defect category does not exist."));
    }

    void requireMember(UUID userId) {
        if (access.activeMemberContact(userId).isEmpty()) {
            throw new BusinessRuleException("This person is not an active member of the organisation.");
        }
    }

    /**
     * An active member who holds {@code permission}, so the work handed to them is work they can do. Without it they
     * would be named on a record they cannot act on.
     */
    void requireMemberWho(UUID userId, String permission, String refusal) {
        requireMember(userId);
        if (!access.holdersOf(permission).contains(userId)) {
            throw new BusinessRuleException(refusal);
        }
    }

    private NonConformance load(UUID id) {
        NonConformance ncr = ncrs.findById(id).orElseThrow(() -> new ResourceNotFoundException("NCR", id));
        scope.requireAllowed(ncr.getFacilityId(), "NCR", id.toString());
        return ncr;
    }

    private NonConformance lock(UUID id) {
        NonConformance ncr = ncrs.findForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("NCR", id));
        scope.requireAllowed(ncr.getFacilityId(), "NCR", id.toString());
        return ncr;
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
        Map<UUID, String> members = access.names();
        List<LinkedCapa> linked = capas.findAnsweringNcr(ncr.getId()).stream()
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
                ncr.getAuditId(),
                ncr.getInstrumentId(),
                ncr.getWorkOrderId(),
                ncr.getDispatchReversalId(),
                ncr.getDispatchId(),
                ncr.getSupplierId(),
                ncr.getSupplierId() == null ? null : supplierNames(Set.of(ncr.getSupplierId())).get(ncr.getSupplierId()),
                ncr.getMaterialId(),
                ncr.getMaterialId() == null ? null : materialNames(Set.of(ncr.getMaterialId())).get(ncr.getMaterialId()),
                ncr.getProductionLineId(),
                ncr.getWorkCenterId(),
                ncr.getMachineId(),
                places.label(ncr.getProductionLineId(), ncr.getWorkCenterId(), ncr.getMachineId()),
                ncr.getBatchNumber(),
                ncr.getDefectCategoryId(),
                category.map(DefectCategory::getName).orElse(null),
                ncr.getRejectedQuantity(),
                ncr.getRejectedUomId(),
                ncr.getAssignedTo(),
                members.get(ncr.getAssignedTo()),
                ncr.getContainmentAction(),
                ncr.getRootCause(),
                ncr.getOutcomeNote(),
                members.getOrDefault(ncr.getCreatedBy(), "System or former member"),
                ncr.getVerificationRequestedBy(),
                ncr.getCreatedAt(),
                ncr.getClosedAt(),
                rules.capaRequired(ncr.getSeverity()),
                rules.effectivenessRequired(ncr.getSeverity()),
                linked,
                changes);
    }

    private static String title(AuditFailure failure) {
        String statement = failure.statement();
        return "Audit %s: %s".formatted(
                failure.auditNumber(), statement.length() > 150 ? statement.substring(0, 150) + "…" : statement);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
