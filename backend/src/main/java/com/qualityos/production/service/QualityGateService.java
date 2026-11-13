package com.qualityos.production.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.security.Permissions;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.inspection.dto.InspectionDtos.CreateInspectionRequest;
import com.qualityos.inspection.dto.InspectionDtos.InspectionDetail;
import com.qualityos.inspection.dto.InspectionDtos.InspectionHistory;
import com.qualityos.inspection.dto.TemplateDtos.TemplateSummary;
import com.qualityos.inspection.service.InspectionService;
import com.qualityos.inspection.service.InspectionTemplateService;
import com.qualityos.production.dto.ProductionQualityDtos.CapaLink;
import com.qualityos.production.dto.ProductionQualityDtos.GateInspection;
import com.qualityos.production.dto.ProductionQualityDtos.GateResponse;
import com.qualityos.production.dto.ProductionQualityDtos.GateStatus;
import com.qualityos.production.dto.ProductionQualityDtos.NcrLink;
import com.qualityos.production.dto.ProductionQualityDtos.QualityResponse;
import com.qualityos.production.dto.ProductionQualityDtos.RejectedSummary;
import com.qualityos.production.entity.QualityCheckpoint;
import com.qualityos.production.entity.QualityCheckpoint.Stage;
import com.qualityos.production.entity.QualityGate;
import com.qualityos.production.entity.QualityGateInspection;
import com.qualityos.production.entity.ReworkOrder;
import com.qualityos.production.entity.WorkOrder;
import com.qualityos.production.entity.WorkOrderOperation;
import com.qualityos.production.repository.QualityGateInspectionRepository;
import com.qualityos.production.repository.QualityGateRepository;
import com.qualityos.production.repository.WorkOrderOperationRepository;
import com.qualityos.production.repository.WorkOrderRepository;
import com.qualityos.quality.dto.QualityDtos.NcrHistory;
import com.qualityos.quality.service.NcrService;
import com.qualityos.traceability.dto.TraceabilityDtos.ProductionBatchView;
import com.qualityos.traceability.service.ProductionService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The quality gates of work orders. Release turns the checkpoints that apply into gates on the order; each gate is
 * passed by an inspection in the existing inspection engine, on the order's batch. A gate's state is read from its
 * latest inspection, never stored, so it cannot drift from the inspection record. See docs/MANUFACTURING.md.
 */
@Service
public class QualityGateService {

    static final String ENTITY = "WorkOrderQualityGate";
    private static final Set<WorkOrder.Status> ON_FLOOR = EnumSet.of(WorkOrder.Status.IN_PROGRESS,
            WorkOrder.Status.ON_HOLD);
    private static final Set<WorkOrderOperation.Status> NOT_STARTED = EnumSet.of(WorkOrderOperation.Status.PENDING,
            WorkOrderOperation.Status.READY);
    private static final Set<WorkOrderOperation.Status> UNDER_WAY = EnumSet.of(WorkOrderOperation.Status.IN_PROGRESS,
            WorkOrderOperation.Status.PAUSED, WorkOrderOperation.Status.BLOCKED);
    private static final Set<String> ACCEPTED = Set.of("ACCEPT", "CONDITIONAL_ACCEPTANCE");

    private final QualityGateRepository gates;
    private final QualityGateInspectionRepository links;
    private final WorkOrderRepository workOrders;
    private final WorkOrderOperationRepository operations;
    private final QualityCheckpointService checkpoints;
    private final ReworkService reworks;
    private final InspectionService inspections;
    private final InspectionTemplateService templates;
    private final NcrService ncrs;
    private final ProductionService production;
    private final FacilityScope scope;
    private final AuditService audit;

    @SuppressWarnings("java:S107")
    QualityGateService(
            QualityGateRepository gates,
            QualityGateInspectionRepository links,
            WorkOrderRepository workOrders,
            WorkOrderOperationRepository operations,
            QualityCheckpointService checkpoints,
            ReworkService reworks,
            InspectionService inspections,
            InspectionTemplateService templates,
            NcrService ncrs,
            ProductionService production,
            FacilityScope scope,
            AuditService audit) {
        this.gates = gates;
        this.links = links;
        this.workOrders = workOrders;
        this.operations = operations;
        this.checkpoints = checkpoints;
        this.reworks = reworks;
        this.inspections = inspections;
        this.templates = templates;
        this.ncrs = ncrs;
        this.production = production;
        this.scope = scope;
        this.audit = audit;
    }

    /**
     * At release: one gate for each checkpoint that applies, on each operation it matches, and final-release gates on
     * the order. Each gate keeps the checkpoint's name, template and whether it is mandatory as they are now.
     *
     * @return how many gates the order took
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int writeGates(WorkOrder order, List<WorkOrderOperation> steps) {
        List<QualityGate> made = new ArrayList<>();
        for (QualityCheckpoint checkpoint : checkpoints.active()) {
            if (!checkpoint.getStage().onOperation()) {
                if (checkpoint.getProductId() == null || checkpoint.getProductId().equals(order.getProductId())) {
                    made.add(new QualityGate(order.getId(), null, checkpoint));
                }
                continue;
            }
            steps.stream()
                    .filter(step -> checkpoint.appliesTo(order.getProductId(), step.getWorkCenterId(),
                            step.getSequence()))
                    .forEach(step -> made.add(new QualityGate(order.getId(), step.getId(), checkpoint)));
        }
        gates.saveAll(made);
        gates.flush();
        return made.size();
    }

    /**
     * Starts the gate's inspection with the template frozen on it. A gate is inspected when it is pending, or again
     * after it failed; a start check before its operation starts, the others while it runs. Final release is checked
     * once the batch is complete, which the inspection engine itself enforces.
     */
    @Transactional
    public GateResponse inspect(UUID orderId, UUID gateId) {
        WorkOrder order = workOrders.findForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkOrder", orderId));
        scope.requireAllowed(order.getFacilityId(), "WorkOrder", order.getWorkOrderNumber());
        QualityGate gate = gates.findById(gateId).filter(found -> found.getWorkOrderId().equals(orderId))
                .orElseThrow(() -> new ResourceNotFoundException("QualityGate", gateId));
        if (!ON_FLOOR.contains(order.getStatus()) || order.getProductionBatchId() == null) {
            throw new BusinessRuleException("Work order %s is %s. Its quality gates are inspected once production "
                    .formatted(order.getWorkOrderNumber(), label(order.getStatus())) + "has started.");
        }
        GateResponse current = responses(order).stream().filter(found -> found.id().equals(gateId)).findFirst()
                .orElseThrow();
        switch (current.status()) {
            case IN_INSPECTION -> throw new BusinessRuleException("%s already has an inspection under way."
                    .formatted(gate.getName()));
            case ON_HOLD -> throw new BusinessRuleException(
                    "%s is on hold. Its inspection needs a final decision first.".formatted(gate.getName()));
            case PASSED -> throw new BusinessRuleException("%s has passed.".formatted(gate.getName()));
            default -> { }
        }
        if (gate.getOperationId() != null) {
            WorkOrderOperation operation = operations.findById(gate.getOperationId()).orElseThrow();
            boolean due = gate.getStage() == Stage.OPERATION_START
                    ? NOT_STARTED.contains(operation.getStatus())
                    : UNDER_WAY.contains(operation.getStatus());
            if (!due) {
                throw new BusinessRuleException(gate.getStage() == Stage.OPERATION_START
                        ? "Operation %d is %s: its start check is made before it starts.".formatted(
                                operation.getSequence(), WorkOrderOperation.label(operation.getStatus()))
                        : "Operation %d is %s: this check is made while it runs.".formatted(
                                operation.getSequence(), WorkOrderOperation.label(operation.getStatus())));
            }
        }
        InspectionDetail created = inspections.create(new CreateInspectionRequest(null, order.getProductionBatchId(),
                null, gate.getTemplateId()));
        links.saveAndFlush(new QualityGateInspection(gate.getId(), created.id()));
        audit.record("QUALITY_GATE_INSPECTION_STARTED", ENTITY, gate.getId(), null, Map.of(
                "workOrder", order.getWorkOrderNumber(), "gate", gate.getName(),
                "inspection", created.inspectionNumber()));
        return responses(order).stream().filter(found -> found.id().equals(gateId)).findFirst().orElseThrow();
    }

    /** Refuses when a mandatory gate of these stages on the operation has not passed. */
    @Transactional(readOnly = true)
    public void requirePassed(WorkOrder order, WorkOrderOperation operation, Set<Stage> stages, String action) {
        List<String> open = responses(order).stream()
                .filter(gate -> operation.getId().equals(gate.operationId()) && stages.contains(gate.stage())
                        && gate.mandatory() && gate.status() != GateStatus.PASSED)
                .map(gate -> "%s is %s.".formatted(gate.name(), label(gate.status())))
                .toList();
        if (!open.isEmpty()) {
            throw new BusinessRuleException("Operation %d cannot be %s until its quality checks pass. %s".formatted(
                    operation.getSequence(), action, String.join(" ", open)));
        }
    }

    /** The gates of one operation, for the shop floor. */
    @Transactional(readOnly = true)
    public List<GateResponse> ofOperation(WorkOrder order, UUID operationId) {
        return responses(order).stream().filter(gate -> operationId.equals(gate.operationId())).toList();
    }

    @Transactional(readOnly = true)
    public QualityResponse quality(UUID orderId) {
        WorkOrder order = workOrders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkOrder", orderId));
        scope.requireAllowed(order.getFacilityId(), "WorkOrder", order.getWorkOrderNumber());
        ProductionBatchView batch = order.getProductionBatchId() == null ? null
                : production.batch(order.getProductionBatchId());
        List<GateResponse> gateRows = responses(order);
        List<NcrLink> ncrLinks = List.of();
        List<CapaLink> capaLinks = List.of();
        if (batch != null && holds(Permissions.NCR_VIEW)) {
            List<UUID> inspectionIds = inspections.history(List.of(), List.of(batch.id())).stream()
                    .map(InspectionHistory::id).toList();
            List<NcrHistory> found = ncrs.history(inspectionIds, List.of(batch.batchNumber()));
            ncrLinks = found.stream().map(ncr -> new NcrLink(ncr.id(), ncr.ncrNumber(), ncr.title(), ncr.status(),
                    ncr.severity())).toList();
            capaLinks = ncrs.capaHistory(found.stream().map(NcrHistory::id).toList()).stream()
                    .map(capa -> new CapaLink(capa.id(), capa.capaNumber(), capa.title(), capa.status(), capa.ncrId()))
                    .toList();
        }
        List<ReworkOrder> reworkRows = reworks.ofOrder(orderId);
        return new QualityResponse(
                order.getProductionBatchId(),
                batch == null ? null : batch.batchNumber(),
                batch == null ? null : batch.status(),
                batch != null && "ON_HOLD".equals(batch.status()),
                gateRows,
                ncrLinks,
                capaLinks,
                reworks.summariesOf(reworkRows),
                rejected(order, reworkRows),
                unresolved(gateRows, reworkRows));
    }

    /** What still stands in the way of completing the order: mandatory gates not passed, rework still open. */
    @Transactional(readOnly = true)
    public List<String> unresolved(WorkOrder order) {
        return unresolved(responses(order), reworks.ofOrder(order.getId()));
    }

    private static List<String> unresolved(List<GateResponse> gateRows, List<ReworkOrder> reworkRows) {
        List<String> open = new ArrayList<>();
        gateRows.stream().filter(gate -> gate.mandatory() && gate.status() != GateStatus.PASSED)
                .forEach(gate -> open.add("%s%s is %s.".formatted(gate.name(),
                        gate.operationSequence() == null ? "" : " on operation " + gate.operationSequence(),
                        label(gate.status()))));
        reworkRows.stream().filter(rework -> rework.getStatus().isOpen())
                .forEach(rework -> open.add("Rework order %s is %s.".formatted(rework.getReworkNumber(),
                        ReworkOrder.label(rework.getStatus()))));
        return open;
    }

    /**
     * Rejected on the operations, and where it went. Scrapped counts both scrap reported on the operations and what
     * rework scrapped.
     */
    private RejectedSummary rejected(WorkOrder order, List<ReworkOrder> reworkRows) {
        List<WorkOrderOperation> steps = operations.findByWorkOrderIdOrderBySequence(order.getId());
        BigDecimal rejected = sum(steps, WorkOrderOperation::getRejectedQuantity);
        BigDecimal toRework = sum(steps, WorkOrderOperation::getReworkQuantity);
        BigDecimal scrapped = sum(steps, WorkOrderOperation::getScrapQuantity).add(reworkRows.stream()
                .filter(rework -> rework.getStatus() == ReworkOrder.Status.CLOSED)
                .map(ReworkOrder::getScrappedQuantity).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal inRework = reworkRows.stream().filter(rework -> rework.getStatus().isOpen())
                .map(ReworkOrder::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal accepted = reworkRows.stream()
                .filter(rework -> rework.getStatus() == ReworkOrder.Status.CLOSED
                        && ACCEPTED.contains(rework.getFinalDisposition()))
                .map(ReworkOrder::getReworkedQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new RejectedSummary(rejected, toRework, scrapped, inRework, accepted);
    }

    private static BigDecimal sum(List<WorkOrderOperation> steps, Function<WorkOrderOperation, BigDecimal> field) {
        return steps.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // Gate state

    private List<GateResponse> responses(WorkOrder order) {
        List<QualityGate> rows = gates.findByWorkOrderIdOrderByCreatedAtAscIdAsc(order.getId());
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<QualityGateInspection>> byGate = links
                .findByGateIdInOrderByCreatedAtAscIdAsc(rows.stream().map(QualityGate::getId).toList()).stream()
                .collect(Collectors.groupingBy(QualityGateInspection::getGateId));
        Map<UUID, InspectionHistory> inspected = order.getProductionBatchId() == null ? Map.of()
                : inspections.history(List.of(), List.of(order.getProductionBatchId())).stream()
                        .collect(Collectors.toMap(InspectionHistory::id, Function.identity()));
        Map<UUID, WorkOrderOperation> steps = operations.findByWorkOrderIdOrderBySequence(order.getId()).stream()
                .collect(Collectors.toMap(WorkOrderOperation::getId, Function.identity()));
        Map<UUID, TemplateSummary> templateRefs = new HashMap<>();
        rows.stream().map(QualityGate::getTemplateId).distinct()
                .forEach(templateId -> templateRefs.put(templateId, templates.get(templateId).template()));
        return rows.stream()
                .sorted(java.util.Comparator
                        .comparing((QualityGate gate) -> gate.getOperationId() == null ? Integer.MAX_VALUE
                                : steps.get(gate.getOperationId()).getSequence())
                        .thenComparing(QualityGate::getStage))
                .map(gate -> {
                    List<GateInspection> history = byGate.getOrDefault(gate.getId(), List.of()).stream()
                            .map(link -> inspected.get(link.getInspectionId()))
                            .filter(java.util.Objects::nonNull)
                            .map(ReworkService::gateInspection)
                            .toList();
                    WorkOrderOperation step = gate.getOperationId() == null ? null : steps.get(gate.getOperationId());
                    TemplateSummary template = templateRefs.get(gate.getTemplateId());
                    return new GateResponse(gate.getId(), gate.getName(), gate.getStage(), gate.isMandatory(),
                            gate.getOperationId(), step == null ? null : step.getSequence(),
                            step == null ? null : step.getName(), gate.getTemplateId(), template.code(),
                            template.name(), status(history), history);
                })
                .toList();
    }

    /** From the latest inspection. One cancelled leaves the gate as if it had none. */
    static GateStatus status(List<GateInspection> history) {
        if (history.isEmpty()) {
            return GateStatus.PENDING;
        }
        GateInspection latest = history.getLast();
        if ("CANCELLED".equals(latest.status())) {
            return GateStatus.PENDING;
        }
        if (latest.disposition() == null) {
            return GateStatus.IN_INSPECTION;
        }
        return switch (latest.disposition()) {
            case "ACCEPT", "CONDITIONAL_ACCEPTANCE" -> GateStatus.PASSED;
            case "HOLD" -> GateStatus.ON_HOLD;
            default -> GateStatus.FAILED;
        };
    }

    private static boolean holds(String permission) {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(permission::equals);
    }

    private static String label(Enum<?> status) {
        return status.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
