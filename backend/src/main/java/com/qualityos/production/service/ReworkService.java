package com.qualityos.production.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.DocumentNumberService;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.identity.service.ReviewPolicy;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.inspection.dto.InspectionDtos.CreateInspectionRequest;
import com.qualityos.inspection.dto.InspectionDtos.InspectionDetail;
import com.qualityos.inspection.dto.InspectionDtos.InspectionHistory;
import com.qualityos.inspection.service.InspectionService;
import com.qualityos.material.dto.MaterialDtos.UnitResponse;
import com.qualityos.material.service.MaterialService;
import com.qualityos.product.dto.ProductDtos.ProductReference;
import com.qualityos.product.service.ProductService;
import com.qualityos.production.dto.ProductionQualityDtos.CompleteReworkRequest;
import com.qualityos.production.dto.ProductionQualityDtos.CreateReworkRequest;
import com.qualityos.production.dto.ProductionQualityDtos.GateInspection;
import com.qualityos.production.dto.ProductionQualityDtos.ReinspectRequest;
import com.qualityos.production.dto.ProductionQualityDtos.ReworkDetail;
import com.qualityos.production.dto.ProductionQualityDtos.ReworkHistory;
import com.qualityos.production.dto.ProductionQualityDtos.ReworkSummary;
import com.qualityos.production.dto.ProductionQualityDtos.StepDoneRequest;
import com.qualityos.production.dto.ProductionQualityDtos.StepRequest;
import com.qualityos.production.dto.ProductionQualityDtos.StepResponse;
import com.qualityos.production.entity.ReworkOrder;
import com.qualityos.production.entity.ReworkOrder.Status;
import com.qualityos.production.entity.ReworkOrderStatusChange;
import com.qualityos.production.entity.ReworkOrderStep;
import com.qualityos.production.entity.WorkCenter;
import com.qualityos.production.entity.WorkOrder;
import com.qualityos.production.entity.WorkOrderOperation;
import com.qualityos.production.repository.ReworkOrderRepository;
import com.qualityos.production.repository.ReworkOrderStatusChangeRepository;
import com.qualityos.production.repository.ReworkOrderStepRepository;
import com.qualityos.production.repository.WorkOrderOperationRepository;
import com.qualityos.production.repository.WorkOrderRepository;
import com.qualityos.quality.dto.QualityDtos.NcrHistory;
import com.qualityos.quality.dto.QualityDtos.NcrOutcome;
import com.qualityos.quality.service.NcrService;
import com.qualityos.traceability.dto.TraceabilityDtos.ProductionBatchView;
import com.qualityos.traceability.service.ProductionService;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rework of rejected production, on the batch it came from. It is raised from an operation's rejected goods, an
 * inspection disposed of as rework, or an NCR; approved by someone authorised (another person when the organisation
 * separates approval); worked through its steps; and closed only by a reinspection's decision. Every change locks
 * the work order first, then the rework order. See docs/MANUFACTURING.md.
 */
@Service
public class ReworkService {

    static final String ENTITY = "ReworkOrder";
    private static final String COUNT = "Count";
    private static final Set<WorkOrder.Status> ON_FLOOR = EnumSet.of(WorkOrder.Status.IN_PROGRESS,
            WorkOrder.Status.ON_HOLD);
    private static final Set<String> NO_DECISION = Set.of("HOLD");

    private final ReworkOrderRepository reworks;
    private final ReworkOrderStepRepository steps;
    private final ReworkOrderStatusChangeRepository history;
    private final WorkOrderRepository workOrders;
    private final WorkOrderOperationRepository operations;
    private final WorkCenterService workCenters;
    private final ManufacturingSettingsService settings;
    private final InspectionService inspections;
    private final NcrService ncrs;
    private final ProductionService production;
    private final ProductService products;
    private final MaterialService materials;
    private final FacilityScope scope;
    private final ReviewPolicy reviewPolicy;
    private final TenantAccessService access;
    private final DocumentNumberService numbers;
    private final AuditService audit;

    @SuppressWarnings("java:S107")
    ReworkService(
            ReworkOrderRepository reworks,
            ReworkOrderStepRepository steps,
            ReworkOrderStatusChangeRepository history,
            WorkOrderRepository workOrders,
            WorkOrderOperationRepository operations,
            WorkCenterService workCenters,
            ManufacturingSettingsService settings,
            InspectionService inspections,
            NcrService ncrs,
            ProductionService production,
            ProductService products,
            MaterialService materials,
            FacilityScope scope,
            ReviewPolicy reviewPolicy,
            TenantAccessService access,
            DocumentNumberService numbers,
            AuditService audit) {
        this.reworks = reworks;
        this.steps = steps;
        this.history = history;
        this.workOrders = workOrders;
        this.operations = operations;
        this.workCenters = workCenters;
        this.settings = settings;
        this.inspections = inspections;
        this.ncrs = ncrs;
        this.production = production;
        this.products = products;
        this.materials = materials;
        this.scope = scope;
        this.reviewPolicy = reviewPolicy;
        this.access = access;
        this.numbers = numbers;
        this.audit = audit;
    }

    // Reading

    /** @param statuses empty for every status */
    @Transactional(readOnly = true)
    public PageResponse<ReworkSummary> list(Set<Status> statuses, UUID workOrderId, String search, Pageable pageable) {
        Optional<Set<UUID>> restriction = scope.restriction();
        Specification<ReworkOrder> filter = (root, query, cb) -> {
            List<Predicate> own = new ArrayList<>();
            restriction.ifPresent(allowed -> {
                Subquery<UUID> orders = query.subquery(UUID.class);
                Root<WorkOrder> order = orders.from(WorkOrder.class);
                orders.select(order.get("id")).where(order.get("facilityId").in(allowed));
                own.add(root.get("workOrderId").in(orders));
            });
            if (search != null && !search.isBlank()) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                Subquery<UUID> matching = query.subquery(UUID.class);
                Root<WorkOrder> order = matching.from(WorkOrder.class);
                matching.select(order.get("id")).where(cb.like(cb.lower(order.get("workOrderNumber")), term));
                own.add(cb.or(cb.like(cb.lower(root.get("reworkNumber")), term), root.get("workOrderId").in(matching)));
            }
            if (statuses != null && !statuses.isEmpty()) {
                own.add(root.get("status").in(statuses));
            }
            if (workOrderId != null) {
                own.add(cb.equal(root.get("workOrderId"), workOrderId));
            }
            return cb.and(own.toArray(Predicate[]::new));
        };
        Page<ReworkOrder> page = reworks.findAll(filter, PageRequest.of(pageable.getPageNumber(),
                pageable.getPageSize(), Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        Function<ReworkOrder, ReworkSummary> mapper = summaries(page.getContent());
        return PageResponse.from(page, mapper::apply);
    }

    @Transactional(readOnly = true)
    public ReworkDetail get(UUID id) {
        ReworkOrder rework = reworks.findById(id).orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
        WorkOrder order = workOrders.findById(rework.getWorkOrderId()).orElseThrow();
        scope.requireAllowed(order.getFacilityId(), ENTITY, rework.getReworkNumber());
        return detail(rework);
    }

    /** The rework orders of one work order, oldest first, for its quality view. */
    @Transactional(readOnly = true)
    public List<ReworkOrder> ofOrder(UUID workOrderId) {
        return reworks.findByWorkOrderIdOrderByCreatedAtAscIdAsc(workOrderId);
    }

    @Transactional(readOnly = true)
    public List<ReworkSummary> summariesOf(List<ReworkOrder> rows) {
        return rows.isEmpty() ? List.of() : rows.stream().map(summaries(rows)).toList();
    }

    // Changing

    /**
     * Raised while production is under way. From an operation it takes at most what that operation marked for rework
     * and is not on another rework order; from an inspection, only one of this batch disposed of as rework.
     */
    @Transactional
    public ReworkDetail create(CreateReworkRequest request) {
        WorkOrder order = workOrders.findForUpdate(request.workOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkOrder", request.workOrderId()));
        scope.requireAllowed(order.getFacilityId(), "WorkOrder", order.getWorkOrderNumber());
        if (!ON_FLOOR.contains(order.getStatus()) || order.getProductionBatchId() == null) {
            throw new BusinessRuleException("Work order %s is %s. Rework is ordered while its production is under way."
                    .formatted(order.getWorkOrderNumber(), label(order.getStatus())));
        }
        if (request.operationId() == null && request.inspectionId() == null && request.ncrId() == null) {
            throw new BusinessRuleException(
                    "Say what is being reworked: an operation's rejected goods, an inspection or an NCR.");
        }
        ProductionBatchView batch = production.batch(order.getProductionBatchId());
        if ("REJECTED".equals(batch.status())) {
            throw new BusinessRuleException("Batch %s was rejected, so it cannot be reworked."
                    .formatted(batch.batchNumber()));
        }
        requireWhole(order, List.of(request.quantity()));
        if (request.operationId() != null) {
            WorkOrderOperation operation = operations.findById(request.operationId())
                    .filter(found -> found.getWorkOrderId().equals(order.getId()))
                    .orElseThrow(() -> new BusinessRuleException("That operation is not on this work order."));
            BigDecimal ordered = reworks.findByOperationId(operation.getId()).stream()
                    .filter(other -> other.getStatus() != Status.CANCELLED)
                    .map(ReworkOrder::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal room = operation.getReworkQuantity().subtract(ordered).max(BigDecimal.ZERO);
            if (request.quantity().compareTo(room) > 0) {
                throw new BusinessRuleException(("Operation %d has %s marked for rework that is not on a rework order "
                        + "yet.").formatted(operation.getSequence(), plain(room)));
            }
        }
        List<InspectionHistory> batchInspections = inspections.history(List.of(), List.of(batch.id()));
        if (request.inspectionId() != null) {
            InspectionHistory source = batchInspections.stream()
                    .filter(found -> found.id().equals(request.inspectionId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessRuleException("That inspection is not of this work order's batch."));
            if (!"REWORK".equals(source.disposition())) {
                throw new BusinessRuleException("Inspection %s was not disposed of as rework."
                        .formatted(source.inspectionNumber()));
            }
        }
        if (request.ncrId() != null) {
            List<NcrHistory> batchNcrs = ncrs.history(batchInspections.stream().map(InspectionHistory::id).toList(),
                    List.of(batch.batchNumber()));
            NcrHistory ncr = batchNcrs.stream().filter(found -> found.id().equals(request.ncrId())).findFirst()
                    .orElseThrow(() -> new BusinessRuleException("That NCR is not about this work order's batch."));
            if ("CANCELLED".equals(ncr.status()) || "REJECTED".equals(ncr.status())) {
                throw new BusinessRuleException("NCR %s was %s.".formatted(ncr.ncrNumber(),
                        ncr.status().toLowerCase(Locale.ROOT)));
            }
        }
        List<StepRequest> requested = request.steps();
        for (StepRequest step : requested) {
            if (step.workCenterId() != null) {
                WorkCenter centre = workCenters.requireUsable(step.workCenterId());
                if (!centre.getFacilityId().equals(order.getFacilityId())) {
                    throw new BusinessRuleException("Work centre %s is not at this work order's facility."
                            .formatted(centre.getCode()));
                }
            }
        }
        String reason = request.reason().trim();
        ReworkOrder rework = new ReworkOrder(numbers.next("RW"), order.getId(), batch.id(), request.operationId(),
                request.ncrId(), request.inspectionId(), request.quantity(), order.getUomId(), reason,
                blankToNull(request.instructions()));
        reworks.saveAndFlush(rework);
        int sequence = 1;
        for (StepRequest step : requested) {
            steps.save(new ReworkOrderStep(rework.getId(), sequence++, step.description().trim(), step.workCenterId()));
        }
        steps.flush();
        history.save(new ReworkOrderStatusChange(rework.getId(), null, Status.OPEN, reason));
        audit.record("REWORK_ORDER_CREATED", ENTITY, rework.getId(), null, Map.of(
                "number", rework.getReworkNumber(), "workOrder", order.getWorkOrderNumber(),
                "quantity", plain(rework.getQuantity())));
        return detail(rework);
    }

    /** The disposition that lets work begin. When approval is separated, not by whoever raised it. */
    @Transactional
    public ReworkDetail approve(UUID id, String note) {
        Locked locked = lock(id);
        ReworkOrder rework = locked.rework();
        requireOnFloor(locked.order(), "approved");
        Status from = rework.getStatus();
        rework.requireStatus(Status.OPEN, "approved");
        UUID actor = TenantContext.getCurrentUserId();
        if (settings.separateApprover()
                && !reviewPolicy.permits(actor, rework.getCreatedBy(), ENTITY, id, "REWORK_APPROVE", note)) {
            throw new BusinessRuleException("You raised this rework order, so someone else must approve it.");
        }
        rework.approve(actor, Instant.now());
        return changed(rework, from, blankToNull(note), "REWORK_ORDER_APPROVED");
    }

    @Transactional
    public ReworkDetail start(UUID id) {
        Locked locked = lock(id);
        ReworkOrder rework = locked.rework();
        requireOnFloor(locked.order(), "started");
        Status from = rework.getStatus();
        rework.start(Instant.now());
        return changed(rework, from, null, "REWORK_ORDER_STARTED");
    }

    @Transactional
    public ReworkDetail completeStep(UUID id, UUID stepId, StepDoneRequest request) {
        Locked locked = lock(id);
        ReworkOrder rework = locked.rework();
        requireOnFloor(locked.order(), "worked on");
        rework.requireStatus(Status.IN_PROGRESS, "worked on");
        ReworkOrderStep step = steps.findById(stepId)
                .filter(found -> found.getReworkOrderId().equals(id))
                .orElseThrow(() -> new ResourceNotFoundException("Step", stepId));
        String note = request == null ? null : blankToNull(request.note());
        step.complete(TenantContext.getCurrentUserId(), Instant.now(), note);
        steps.saveAndFlush(step);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("step", step.getSequence());
        if (note != null) {
            after.put("note", note);
        }
        audit.record("REWORK_STEP_COMPLETED", ENTITY, id, null, after);
        return detail(rework);
    }

    /** Every step done, and what was reworked and what was scrapped account for the whole quantity. */
    @Transactional
    public ReworkDetail complete(UUID id, CompleteReworkRequest request) {
        Locked locked = lock(id);
        ReworkOrder rework = locked.rework();
        requireOnFloor(locked.order(), "completed");
        Status from = rework.getStatus();
        rework.requireStatus(Status.IN_PROGRESS, "completed");
        steps.findByReworkOrderIdOrderBySequence(id).stream().filter(step -> !step.isDone()).findFirst()
                .ifPresent(open -> {
                    throw new BusinessRuleException("Step %d is not done yet.".formatted(open.getSequence()));
                });
        requireWhole(locked.order(), List.of(request.reworkedQuantity(), request.scrappedQuantity()));
        rework.complete(request.reworkedQuantity(), request.scrappedQuantity(), Instant.now());
        return changed(rework, from, "Reworked %s, scrapped %s".formatted(plain(request.reworkedQuantity()),
                plain(request.scrappedQuantity())), "REWORK_ORDER_COMPLETED");
    }

    /**
     * Starts the inspection that decides what becomes of the reworked goods, on the same batch. A cancelled
     * reinspection can be replaced; one under way cannot.
     */
    @Transactional
    public ReworkDetail reinspect(UUID id, ReinspectRequest request) {
        Locked locked = lock(id);
        ReworkOrder rework = locked.rework();
        requireOnFloor(locked.order(), "reinspected");
        rework.requireStatus(Status.AWAITING_INSPECTION, "reinspected");
        if (rework.getReinspectionId() != null) {
            InspectionHistory current = inspection(rework, rework.getReinspectionId());
            if (current != null && !"CANCELLED".equals(current.status())) {
                throw new BusinessRuleException("Reinspection %s is already under way."
                        .formatted(current.inspectionNumber()));
            }
        }
        InspectionDetail created = inspections.create(new CreateInspectionRequest(null, rework.getProductionBatchId(),
                null, request == null ? null : request.templateId()));
        rework.reinspectedBy(created.id());
        reworks.saveAndFlush(rework);
        audit.record("REWORK_ORDER_REINSPECTION_STARTED", ENTITY, id, null,
                Map.of("inspection", created.inspectionNumber()));
        return detail(rework);
    }

    /** Takes the reinspection's decision as the rework's outcome. A hold is not a decision yet. */
    @Transactional
    public ReworkDetail close(UUID id) {
        Locked locked = lock(id);
        ReworkOrder rework = locked.rework();
        Status from = rework.getStatus();
        rework.requireStatus(Status.AWAITING_INSPECTION, "closed");
        InspectionHistory reinspection = rework.getReinspectionId() == null ? null
                : inspection(rework, rework.getReinspectionId());
        if (reinspection == null || "CANCELLED".equals(reinspection.status())) {
            throw new BusinessRuleException("Rework order %s needs a reinspection before it is closed."
                    .formatted(rework.getReworkNumber()));
        }
        if (reinspection.disposition() == null || NO_DECISION.contains(reinspection.disposition())) {
            throw new BusinessRuleException("Reinspection %s has no decision yet."
                    .formatted(reinspection.inspectionNumber()));
        }
        rework.close(reinspection.disposition(), Instant.now());
        return changed(rework, from, "Reinspection %s: %s".formatted(reinspection.inspectionNumber(),
                reinspection.disposition()), "REWORK_ORDER_CLOSED");
    }

    /** Before work begins only: once started, a rework order runs to its reinspection. */
    @Transactional
    public ReworkDetail cancel(UUID id, String reason) {
        Locked locked = lock(id);
        ReworkOrder rework = locked.rework();
        Status from = rework.getStatus();
        String why = reason.trim();
        rework.cancel(why);
        return changed(rework, from, why, "REWORK_ORDER_CANCELLED");
    }

    // Rules

    private record Locked(WorkOrder order, ReworkOrder rework) {
    }

    private Locked lock(UUID id) {
        UUID orderId = reworks.workOrderIdOf(id).orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
        WorkOrder order = workOrders.findForUpdate(orderId).orElseThrow();
        ReworkOrder rework = reworks.findForUpdate(id).orElseThrow();
        scope.requireAllowed(order.getFacilityId(), ENTITY, rework.getReworkNumber());
        return new Locked(order, rework);
    }

    private ReworkDetail changed(ReworkOrder rework, Status from, String reason, String action) {
        reworks.saveAndFlush(rework);
        history.save(new ReworkOrderStatusChange(rework.getId(), from, rework.getStatus(), reason));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", rework.getStatus().name());
        if (reason != null) {
            after.put("reason", reason);
        }
        audit.record(action, ENTITY, rework.getId(), Map.of("status", from.name()), after);
        return detail(rework);
    }

    private static void requireOnFloor(WorkOrder order, String action) {
        if (!ON_FLOOR.contains(order.getStatus())) {
            throw new BusinessRuleException("Work order %s is %s, so its rework cannot be %s.".formatted(
                    order.getWorkOrderNumber(), label(order.getStatus()), action));
        }
    }

    private void requireWhole(WorkOrder order, List<BigDecimal> quantities) {
        UnitResponse unit = materials.listUnits().stream().filter(found -> found.id().equals(order.getUomId()))
                .findFirst().orElse(null);
        if (unit != null && COUNT.equalsIgnoreCase(unit.dimension())
                && quantities.stream().anyMatch(quantity -> quantity.stripTrailingZeros().scale() > 0)) {
            throw new BusinessRuleException("%s are counted in whole numbers.".formatted(unit.name()));
        }
    }

    private InspectionHistory inspection(ReworkOrder rework, UUID inspectionId) {
        return inspections.history(List.of(), List.of(rework.getProductionBatchId())).stream()
                .filter(found -> found.id().equals(inspectionId)).findFirst().orElse(null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String label(WorkOrder.Status status) {
        return status.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    // Responses

    private ReworkDetail detail(ReworkOrder rework) {
        ReworkSummary summary = summaries(List.of(rework)).apply(rework);
        Map<UUID, String> members = access.activeMembers();
        Map<UUID, InspectionHistory> batchInspections = inspections
                .history(List.of(), List.of(rework.getProductionBatchId())).stream()
                .collect(Collectors.toMap(InspectionHistory::id, Function.identity()));
        InspectionHistory source = rework.getSourceInspectionId() == null ? null
                : batchInspections.get(rework.getSourceInspectionId());
        InspectionHistory reinspection = rework.getReinspectionId() == null ? null
                : batchInspections.get(rework.getReinspectionId());
        List<ReworkOrderStep> stepRows = steps.findByReworkOrderIdOrderBySequence(rework.getId());
        Map<UUID, WorkCenter> centres = workCenters.byId(stepRows.stream().map(ReworkOrderStep::getWorkCenterId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return new ReworkDetail(
                summary,
                rework.getReason(),
                rework.getInstructions(),
                rework.getStatusReason(),
                rework.getSourceInspectionId(),
                source == null ? null : source.inspectionNumber(),
                rework.getApprovedBy(),
                rework.getApprovedBy() == null ? null : members.get(rework.getApprovedBy()),
                rework.getApprovedAt(),
                rework.getStartedAt(),
                rework.getCompletedAt(),
                rework.getReworkedQuantity(),
                rework.getScrappedQuantity(),
                reinspection == null ? null : gateInspection(reinspection),
                rework.getClosedAt(),
                stepRows.stream().map(step -> new StepResponse(step.getId(), step.getSequence(), step.getDescription(),
                        step.getWorkCenterId(), step.getWorkCenterId() == null || !centres.containsKey(
                                step.getWorkCenterId()) ? null : centres.get(step.getWorkCenterId()).getCode(),
                        step.getCompletedBy(), step.getCompletedBy() == null ? null : members.get(step.getCompletedBy()),
                        step.getCompletedAt(), step.getNote())).toList(),
                history.findByReworkOrderIdOrderByCreatedAtAscIdAsc(rework.getId()).stream()
                        .map(change -> new ReworkHistory(change.getFromStatus(), change.getToStatus(),
                                change.getReason(), change.getCreatedBy(), members.get(change.getCreatedBy()),
                                change.getCreatedAt()))
                        .toList());
    }

    static GateInspection gateInspection(InspectionHistory inspection) {
        return new GateInspection(inspection.id(), inspection.inspectionNumber(), inspection.status(),
                inspection.finalResult(), inspection.disposition(), inspection.createdAt());
    }

    private Function<ReworkOrder, ReworkSummary> summaries(List<ReworkOrder> rows) {
        Map<UUID, WorkOrder> orders = workOrders.findAllById(rows.stream().map(ReworkOrder::getWorkOrderId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(WorkOrder::getId, Function.identity()));
        List<UUID> batchIds = rows.stream().map(ReworkOrder::getProductionBatchId).distinct().toList();
        Map<UUID, ProductionBatchView> batches = batchIds.isEmpty() ? Map.of() : production.views(batchIds).stream()
                .collect(Collectors.toMap(ProductionBatchView::id, Function.identity()));
        Map<UUID, ProductReference> productRefs = products.references(orders.values().stream()
                .map(WorkOrder::getProductId).collect(Collectors.toSet()));
        Map<UUID, Integer> sequences = operations.findAllById(rows.stream().map(ReworkOrder::getOperationId)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(WorkOrderOperation::getId, WorkOrderOperation::getSequence));
        Set<UUID> ncrIds = rows.stream().map(ReworkOrder::getNcrId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, NcrOutcome> ncrRefs = ncrIds.isEmpty() ? Map.of() : ncrs.outcomes(ncrIds);
        return rework -> {
            WorkOrder order = orders.get(rework.getWorkOrderId());
            ProductReference product = productRefs.get(order.getProductId());
            ProductionBatchView batch = batches.get(rework.getProductionBatchId());
            NcrOutcome ncr = rework.getNcrId() == null ? null : ncrRefs.get(rework.getNcrId());
            return new ReworkSummary(rework.getId(), rework.getReworkNumber(), order.getId(),
                    order.getWorkOrderNumber(), rework.getProductionBatchId(),
                    batch == null ? null : batch.batchNumber(), product == null ? null : product.code(),
                    product == null ? null : product.name(), rework.getOperationId(),
                    rework.getOperationId() == null ? null : sequences.get(rework.getOperationId()), rework.getNcrId(),
                    ncr == null ? null : ncr.ncrNumber(), rework.getQuantity(), rework.getUomId(), rework.getStatus(),
                    rework.getFinalDisposition(), rework.getCreatedAt());
        };
    }
}
