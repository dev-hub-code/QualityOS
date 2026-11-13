package com.qualityos.production.service;

import com.qualityos.common.attachment.AttachmentPolicy;
import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.material.dto.MaterialDtos.UnitResponse;
import com.qualityos.material.service.MaterialService;
import com.qualityos.organization.service.FacilityService;
import com.qualityos.product.dto.ProductDtos.ProductReference;
import com.qualityos.product.service.ProductService;
import com.qualityos.production.dto.ShopFloorDtos.AssignRequest;
import com.qualityos.production.dto.ShopFloorDtos.BlockRequest;
import com.qualityos.production.dto.ShopFloorDtos.CommentRequest;
import com.qualityos.production.dto.ShopFloorDtos.CompleteRequest;
import com.qualityos.production.dto.ShopFloorDtos.EventResponse;
import com.qualityos.production.dto.ShopFloorDtos.MachineOption;
import com.qualityos.production.dto.ShopFloorDtos.MeasurementRequest;
import com.qualityos.production.dto.ShopFloorDtos.MeasurementResponse;
import com.qualityos.production.dto.ShopFloorDtos.OperationDetail;
import com.qualityos.production.dto.ShopFloorDtos.OperationSummary;
import com.qualityos.production.dto.ShopFloorDtos.ParameterLimits;
import com.qualityos.production.dto.ShopFloorDtos.PersonOption;
import com.qualityos.production.dto.ShopFloorDtos.ReportRequest;
import com.qualityos.production.dto.ShopFloorDtos.ReportResponse;
import com.qualityos.production.dto.ShopFloorDtos.ReverseRequest;
import com.qualityos.production.dto.ShopFloorDtos.SkipRequest;
import com.qualityos.production.dto.ShopFloorDtos.StartRequest;
import com.qualityos.production.dto.ShopFloorDtos.StopRequest;
import com.qualityos.production.entity.Machine;
import com.qualityos.production.entity.OperationEvent;
import com.qualityos.production.entity.OperationEvent.LossCategory;
import com.qualityos.production.entity.OperationMeasurement;
import com.qualityos.production.entity.OperationReport;
import com.qualityos.production.entity.QualityCheckpoint.Stage;
import com.qualityos.production.entity.WorkCenter;
import com.qualityos.production.entity.WorkOrder;
import com.qualityos.production.entity.WorkOrderOperation;
import com.qualityos.production.entity.WorkOrderOperation.Status;
import com.qualityos.production.repository.OperationEventRepository;
import com.qualityos.production.repository.OperationMeasurementRepository;
import com.qualityos.production.repository.OperationReportRepository;
import com.qualityos.production.repository.WorkOrderOperationRepository;
import com.qualityos.production.repository.WorkOrderRepository;
import com.qualityos.traceability.service.ProductionService;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Running a work order's operations on the floor. Every change locks the work order, so the operations of one order
 * change one request at a time and the quantity checks across them hold. Quantities change only through reports,
 * which are never edited: a mistake is reversed by a supervisor. Reports and measurements accept an
 * {@code Idempotency-Key}. See docs/MANUFACTURING.md.
 */
@Service
public class ShopFloorService implements AttachmentPolicy {

    static final String ENTITY = "WorkOrderOperation";
    private static final String ATTACHMENT_TYPE = "WORK_ORDER_OPERATION";
    private static final int MAX_KEY = 100;
    private static final int SCALE = 6;
    private static final String COUNT = "Count";
    private static final Set<WorkOrder.Status> ON_FLOOR = EnumSet.of(WorkOrder.Status.IN_PROGRESS,
            WorkOrder.Status.ON_HOLD);
    private static final Set<WorkOrder.Status> ASSIGNABLE = EnumSet.of(WorkOrder.Status.RELEASED,
            WorkOrder.Status.IN_PROGRESS, WorkOrder.Status.ON_HOLD);
    private static final Set<Status> REPORTABLE = EnumSet.of(Status.IN_PROGRESS, Status.PAUSED);
    private static final Set<Status> OPEN = EnumSet.of(Status.READY, Status.IN_PROGRESS, Status.PAUSED,
            Status.BLOCKED);
    private static final Set<Machine.Status> FREE = EnumSet.of(Machine.Status.AVAILABLE, Machine.Status.IDLE,
            Machine.Status.RUNNING);

    private final WorkOrderRepository workOrders;
    private final WorkOrderOperationRepository operations;
    private final OperationReportRepository reports;
    private final OperationMeasurementRepository measurements;
    private final OperationEventRepository events;
    private final WorkCenterService workCenters;
    private final ManufacturingSettingsService settings;
    private final ProductService products;
    private final MaterialService materials;
    private final FacilityService facilities;
    private final FacilityScope scope;
    private final TenantAccessService access;
    private final AuditService audit;
    private final JsonMapper json;
    private final QualityGateService qualityGates;
    private final ProductionService production;

    @SuppressWarnings("java:S107")
    ShopFloorService(
            WorkOrderRepository workOrders,
            WorkOrderOperationRepository operations,
            OperationReportRepository reports,
            OperationMeasurementRepository measurements,
            OperationEventRepository events,
            WorkCenterService workCenters,
            ManufacturingSettingsService settings,
            ProductService products,
            MaterialService materials,
            FacilityService facilities,
            FacilityScope scope,
            TenantAccessService access,
            AuditService audit,
            JsonMapper json,
            QualityGateService qualityGates,
            ProductionService production) {
        this.workOrders = workOrders;
        this.operations = operations;
        this.reports = reports;
        this.measurements = measurements;
        this.events = events;
        this.workCenters = workCenters;
        this.settings = settings;
        this.products = products;
        this.materials = materials;
        this.facilities = facilities;
        this.scope = scope;
        this.access = access;
        this.audit = audit;
        this.json = json;
        this.qualityGates = qualityGates;
        this.production = production;
    }

    // Reading

    /**
     * Operations of orders on the floor (in progress or on hold), the running ones first.
     *
     * @param statuses empty for every open operation
     * @param mine only those assigned to the caller
     */
    @Transactional(readOnly = true)
    public PageResponse<OperationSummary> list(
            Set<Status> statuses, UUID facilityId, UUID workCenterId, boolean mine, String search,
            Pageable pageable) {
        Set<Status> wanted = statuses == null || statuses.isEmpty() ? OPEN : statuses;
        Optional<Set<UUID>> restriction = scope.restriction();
        List<UUID> matchingProducts = search == null || search.isBlank() ? List.of() : products.idsMatching(search);
        UUID me = TenantContext.getCurrentUserId();
        Specification<WorkOrderOperation> filter = (root, query, cb) -> {
            Subquery<UUID> orders = query.subquery(UUID.class);
            Root<WorkOrder> order = orders.from(WorkOrder.class);
            List<Predicate> onOrder = new ArrayList<>();
            onOrder.add(order.get("status").in(ON_FLOOR));
            restriction.ifPresent(allowed -> onOrder.add(order.get("facilityId").in(allowed)));
            if (facilityId != null) {
                onOrder.add(cb.equal(order.get("facilityId"), facilityId));
            }
            if (search != null && !search.isBlank()) {
                Predicate byNumber = cb.like(cb.lower(order.get("workOrderNumber")),
                        "%" + search.trim().toLowerCase(Locale.ROOT) + "%");
                onOrder.add(matchingProducts.isEmpty() ? byNumber
                        : cb.or(byNumber, order.get("productId").in(matchingProducts)));
            }
            orders.select(order.get("id")).where(onOrder.toArray(Predicate[]::new));
            List<Predicate> own = new ArrayList<>();
            own.add(root.get("workOrderId").in(orders));
            own.add(root.get("status").in(wanted));
            if (workCenterId != null) {
                own.add(cb.equal(root.get("workCenterId"), workCenterId));
            }
            if (mine) {
                own.add(cb.equal(root.get("operatorId"), me));
            }
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                query.orderBy(
                        cb.asc(cb.selectCase(root.get("status"))
                                .when(Status.IN_PROGRESS, 0).when(Status.PAUSED, 1).when(Status.BLOCKED, 2)
                                .when(Status.READY, 3).otherwise(4)),
                        cb.asc(root.get("createdAt")),
                        cb.asc(root.get("sequence")));
            }
            return cb.and(own.toArray(Predicate[]::new));
        };
        Page<WorkOrderOperation> page = operations.findAll(filter,
                org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        Function<WorkOrderOperation, OperationSummary> mapper = summaries(page.getContent());
        return PageResponse.from(page, mapper::apply);
    }

    @Transactional(readOnly = true)
    public OperationDetail get(UUID id) {
        WorkOrderOperation operation = operations.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Operation", id));
        WorkOrder order = workOrders.findById(operation.getWorkOrderId()).orElseThrow();
        scope.requireAllowed(order.getFacilityId(), "Operation", id.toString());
        return detail(order, operation);
    }

    /** Each operation of one order, with its quantities and where its input stands, for the work order page. */
    @Transactional(readOnly = true)
    public List<OperationSummary> ofOrder(WorkOrder order) {
        List<WorkOrderOperation> rows = operations.findByWorkOrderIdOrderBySequence(order.getId());
        return rows.isEmpty() ? List.of() : rows.stream().map(summaries(rows)).toList();
    }

    // Running

    /** Takes the operation for the caller when nobody has it, and the machine if one is chosen. */
    @Transactional
    public OperationDetail start(UUID id, StartRequest request) {
        Locked locked = lock(id);
        WorkOrderOperation operation = locked.operation();
        requireRunning(locked.order(), "started");
        requireRunner(operation);
        Status from = operation.getStatus();
        operation.requireStatus(Status.READY, "started");
        requireBatchOpen(locked.order(), operation, "started");
        qualityGates.requirePassed(locked.order(), operation, EnumSet.of(Stage.OPERATION_START), "started");
        UUID machineId = request != null && request.machineId() != null ? request.machineId()
                : operation.getMachineId();
        if (machineId == null && settings.rules().operationRequiresMachine()
                && workCenters.machinesAt(operation.getWorkCenterId()).stream()
                        .anyMatch(machine -> machine.getStatus() != Machine.Status.INACTIVE)) {
            throw new BusinessRuleException("Choose the machine operation %d runs on.".formatted(operation.getSequence()));
        }
        if (machineId != null) {
            occupy(locked.order(), operation, machineId);
        }
        operation.assign(operation.getOperatorId() == null ? TenantContext.getCurrentUserId()
                : operation.getOperatorId(), machineId);
        operation.start(Instant.now());
        return changed(locked, from, OperationEvent.Type.STARTED, null, null, "OPERATION_STARTED");
    }

    @Transactional
    public OperationDetail resume(UUID id) {
        Locked locked = lock(id);
        WorkOrderOperation operation = locked.operation();
        requireRunning(locked.order(), "resumed");
        requireRunner(operation);
        Status from = operation.getStatus();
        operation.requireStatus(Status.PAUSED, "resumed");
        requireBatchOpen(locked.order(), operation, "resumed");
        if (operation.getMachineId() != null) {
            occupy(locked.order(), operation, operation.getMachineId());
        }
        operation.start(Instant.now());
        return changed(locked, from, OperationEvent.Type.RESUMED, null, null, "OPERATION_RESUMED");
    }

    /** Stopping a machine for a breakdown or for maintenance puts the machine in that state too. */
    @Transactional
    public OperationDetail pause(UUID id, StopRequest request) {
        Locked locked = lock(id);
        WorkOrderOperation operation = locked.operation();
        requireRunner(operation);
        Status from = operation.getStatus();
        String reason = reason(request.category(), request.reason());
        operation.pause(Instant.now(), reason);
        releaseMachine(locked.order(), operation, request.category(), reason);
        return changed(locked, from, OperationEvent.Type.PAUSED, request.category(), blankToNull(request.reason()),
                "OPERATION_PAUSED");
    }

    /**
     * Every operation before it must be complete or skipped. Less going through than came in is allowed, with a
     * reason: the shortfall is never silent.
     */
    @Transactional
    public OperationDetail complete(UUID id, CompleteRequest request) {
        Locked locked = lock(id);
        WorkOrder order = locked.order();
        WorkOrderOperation operation = locked.operation();
        requireRunning(order, "completed");
        requireRunner(operation);
        Status from = operation.getStatus();
        operation.requireOneOf(REPORTABLE, "completed");
        requireBatchOpen(order, operation, "completed");
        locked.siblings().stream()
                .filter(other -> other.getSequence() < operation.getSequence() && !other.getStatus().isClosed())
                .findFirst()
                .ifPresent(open -> {
                    throw new BusinessRuleException("Operation %d is not complete yet, so operation %d cannot be."
                            .formatted(open.getSequence(), operation.getSequence()));
                });
        Instant now = Instant.now();
        BigDecimal setup = request == null ? null : request.setupMinutes();
        if (setup != null && setup.multiply(BigDecimal.valueOf(60))
                .compareTo(BigDecimal.valueOf(operation.runningSecondsAt(now))) > 0) {
            throw new BusinessRuleException("Setting up cannot have taken longer than the %s minutes the operation ran."
                    .formatted(BigDecimal.valueOf(operation.runningSecondsAt(now))
                            .divide(BigDecimal.valueOf(60), 0, RoundingMode.DOWN).toPlainString()));
        }
        qualityGates.requirePassed(order, operation, EnumSet.of(Stage.IN_PROCESS, Stage.OPERATION_COMPLETION),
                "completed");
        BigDecimal input = inputs(order, locked.siblings()).get(operation.getId());
        String reason = request == null ? null : blankToNull(request.reason());
        if (operation.processed().compareTo(input) < 0 && reason == null) {
            throw new BusinessRuleException("Only %s of %s went through operation %d. Say why it is complete."
                    .formatted(plain(operation.processed()), plain(input), operation.getSequence()));
        }
        operation.complete(now, setup, reason);
        releaseMachine(order, operation, null, null);
        OperationDetail detail = changed(locked, from, OperationEvent.Type.COMPLETED, null, reason,
                "OPERATION_COMPLETED");
        makeReady(order, locked.siblings());
        operations.flush();
        return detail(order, operation);
    }

    /** Stops an operation that cannot go on, whatever its state, until a supervisor unblocks it. */
    @Transactional
    public OperationDetail block(UUID id, BlockRequest request) {
        Locked locked = lock(id);
        WorkOrderOperation operation = locked.operation();
        requireOnFloor(locked.order(), "blocked");
        Status from = operation.getStatus();
        String reason = request.reason().trim();
        operation.block(Instant.now(), reason(request.category(), reason));
        releaseMachine(locked.order(), operation, request.category(), reason);
        return changed(locked, from, OperationEvent.Type.BLOCKED, request.category(), reason, "OPERATION_BLOCKED");
    }

    @Transactional
    public OperationDetail unblock(UUID id) {
        Locked locked = lock(id);
        WorkOrderOperation operation = locked.operation();
        requireOnFloor(locked.order(), "unblocked");
        Status from = operation.getStatus();
        operation.unblock();
        OperationDetail detail = changed(locked, from, OperationEvent.Type.UNBLOCKED, null, null,
                "OPERATION_UNBLOCKED");
        makeReady(locked.order(), locked.siblings());
        operations.flush();
        return detail(locked.order(), operation);
    }

    /** Skips an operation nothing was reported on. What came into it passes to the next one. */
    @Transactional
    public OperationDetail skip(UUID id, SkipRequest request) {
        Locked locked = lock(id);
        WorkOrderOperation operation = locked.operation();
        if (!ASSIGNABLE.contains(locked.order().getStatus())) {
            throw orderClosed(locked.order(), "skipped");
        }
        Status from = operation.getStatus();
        String reason = request.reason().trim();
        operation.cancel(reason);
        OperationDetail detail = changed(locked, from, OperationEvent.Type.CANCELLED, null, reason,
                "OPERATION_SKIPPED");
        makeReady(locked.order(), locked.siblings());
        operations.flush();
        return detail(locked.order(), operation);
    }

    /**
     * Gives the operation to someone who may run operations at the order's facility, and to a machine at its work
     * centre. Either may be cleared.
     */
    @Transactional
    public OperationDetail assign(UUID id, AssignRequest request) {
        Locked locked = lock(id);
        WorkOrder order = locked.order();
        WorkOrderOperation operation = locked.operation();
        if (!ASSIGNABLE.contains(order.getStatus())) {
            throw orderClosed(order, "assigned");
        }
        if (request.operatorId() != null) {
            if (!access.holdersOf(Permissions.PRODUCTION_EXECUTE).contains(request.operatorId())
                    || !access.activeMemberIds().contains(request.operatorId())) {
                throw new BusinessRuleException("This person cannot run operations.");
            }
            scope.requireAllowedFor(request.operatorId(), order.getFacilityId());
        }
        if (request.machineId() != null && !request.machineId().equals(operation.getMachineId())) {
            Machine machine = workCenters.machinesById(List.of(request.machineId())).get(request.machineId());
            if (machine == null || !machine.getWorkCenterId().equals(operation.getWorkCenterId())) {
                throw new BusinessRuleException("Choose a machine at the operation's work centre.");
            }
            if (machine.getStatus() == Machine.Status.INACTIVE) {
                throw new BusinessRuleException("Machine %s is inactive.".formatted(machine.getCode()));
            }
        }
        Status from = operation.getStatus();
        UUID before = operation.getOperatorId();
        operation.assign(request.operatorId(), request.machineId());
        operations.saveAndFlush(operation);
        events.save(new OperationEvent(operation, OperationEvent.Type.ASSIGNED, from, null, null));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("operator", Objects.toString(operation.getOperatorId(), ""));
        after.put("machine", Objects.toString(operation.getMachineId(), ""));
        audit.record("OPERATION_ASSIGNED", ENTITY, id, Map.of("operator", Objects.toString(before, "")), after);
        return detail(order, operation);
    }

    // Quantities and measurements

    /**
     * Good, rejected and scrapped must add up to the quantity reported, in the order's unit, and whole numbers when
     * that unit counts pieces. An operation cannot take more than came into it; the first may go past the order
     * quantity by the organisation's allowance.
     */
    @Transactional
    public OperationDetail report(UUID id, ReportRequest request, String key) {
        Locked locked = lock(id);
        WorkOrder order = locked.order();
        WorkOrderOperation operation = locked.operation();
        if (repeated(key) && reports.existsByOperationIdAndRequestKey(id, key)) {
            return detail(order, operation);
        }
        requireRunning(order, "reported on");
        requireRunner(operation);
        operation.requireOneOf(REPORTABLE, "reported on");
        requireBatchOpen(order, operation, "reported on");
        BigDecimal good = request.goodQuantity();
        BigDecimal rejected = request.rejectedQuantity();
        BigDecimal scrap = request.scrapQuantity();
        BigDecimal rework = request.reworkQuantity() == null ? BigDecimal.ZERO : request.reworkQuantity();
        BigDecimal total = good.add(rejected).add(scrap);
        if (total.compareTo(request.quantity()) != 0) {
            throw new BusinessRuleException("Good %s, rejected %s and scrap %s make %s, not the %s reported."
                    .formatted(plain(good), plain(rejected), plain(scrap), plain(total), plain(request.quantity())));
        }
        if (rework.compareTo(rejected) > 0) {
            throw new BusinessRuleException("Rework comes out of the rejected quantity, so it cannot be more than %s."
                    .formatted(plain(rejected)));
        }
        requireWhole(order, List.of(good, rejected, scrap, rework));
        BigDecimal ceiling = ceiling(order, locked.siblings(), operation);
        if (operation.processed().add(total).compareTo(ceiling) > 0) {
            throw new BusinessRuleException(ceilingMessage(order, locked.siblings(), operation, ceiling));
        }
        OperationReport report = reports.save(new OperationReport(order.getId(), id, good, rejected, scrap, rework,
                blankToNull(request.comment()), key));
        operation.record(good, rejected, scrap, rework);
        operations.saveAndFlush(operation);
        audit.record("OPERATION_REPORTED", ENTITY, id, null, Map.of(
                "report", report.getId().toString(), "good", plain(good), "rejected", plain(rejected),
                "scrap", plain(scrap), "rework", plain(rework)));
        return detail(order, operation);
    }

    /** Takes a mistaken report back off, unless the next operation has already worked on its output. */
    @Transactional
    public OperationDetail reverse(UUID id, UUID reportId, ReverseRequest request) {
        Locked locked = lock(id);
        WorkOrder order = locked.order();
        WorkOrderOperation operation = locked.operation();
        requireOnFloor(order, "corrected");
        if (operation.getStatus().isClosed()) {
            throw new BusinessRuleException("Operation %d is %s, so its reports cannot be reversed.".formatted(
                    operation.getSequence(), WorkOrderOperation.label(operation.getStatus())));
        }
        OperationReport report = reports.findById(reportId)
                .filter(found -> found.getOperationId().equals(id))
                .orElseThrow(() -> new ResourceNotFoundException("Report", reportId));
        if (report.getReversesReportId() != null || reports.existsByReversesReportId(reportId)) {
            throw new BusinessRuleException("This report was already reversed.");
        }
        Optional<WorkOrderOperation> next = locked.siblings().stream()
                .filter(other -> other.getSequence() > operation.getSequence()
                        && other.getStatus() != Status.CANCELLED)
                .findFirst();
        BigDecimal remainingGood = operation.getGoodQuantity().subtract(report.getGoodQuantity());
        if (next.isPresent() && next.get().processed().compareTo(remainingGood) > 0) {
            throw new BusinessRuleException("Operation %d has already processed %s of what came out of operation %d."
                    .formatted(next.get().getSequence(), plain(next.get().processed()), operation.getSequence()));
        }
        String reason = request.reason().trim();
        OperationReport reversal = reports.save(report.reversal(reason));
        operation.record(reversal.getGoodQuantity(), reversal.getRejectedQuantity(), reversal.getScrapQuantity(),
                reversal.getReworkQuantity());
        operations.saveAndFlush(operation);
        audit.record("OPERATION_REPORT_REVERSED", ENTITY, id, Map.of("report", reportId.toString()),
                Map.of("reversal", reversal.getId().toString(), "reason", reason));
        return detail(order, operation);
    }

    /** Against a parameter frozen on the operation. A value outside its limits is kept and flagged. */
    @Transactional
    public OperationDetail measure(UUID id, MeasurementRequest request, String key) {
        Locked locked = lock(id);
        WorkOrder order = locked.order();
        WorkOrderOperation operation = locked.operation();
        if (repeated(key) && measurements.existsByOperationIdAndRequestKey(id, key)) {
            return detail(order, operation);
        }
        requireRunning(order, "measured");
        requireRunner(operation);
        operation.requireOneOf(REPORTABLE, "measured");
        requireBatchOpen(order, operation, "measured");
        ParameterLimits parameter = parameters(operation).stream()
                .filter(found -> found.code().equalsIgnoreCase(request.parameterCode().trim()))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("Operation %d has no parameter %s."
                        .formatted(operation.getSequence(), request.parameterCode())));
        OperationMeasurement measurement = measurements.save(new OperationMeasurement(order.getId(), id,
                parameter.code(), parameter.name(), parameter.unit(), parameter.target(), parameter.minimum(),
                parameter.maximum(), request.value(), blankToNull(request.comment()), key));
        audit.record("OPERATION_MEASURED", ENTITY, id, null, Map.of(
                "parameter", parameter.code(), "value", plain(request.value()),
                "withinLimits", measurement.isWithinLimits()));
        return detail(order, operation);
    }

    @Transactional
    public OperationDetail comment(UUID id, CommentRequest request) {
        Locked locked = lock(id);
        if (!ASSIGNABLE.contains(locked.order().getStatus())) {
            throw orderClosed(locked.order(), "commented on");
        }
        WorkOrderOperation operation = locked.operation();
        events.save(new OperationEvent(operation, OperationEvent.Type.COMMENT, operation.getStatus(), null,
                request.comment().trim()));
        return detail(locked.order(), operation);
    }

    // Called by the work order as it changes

    /** When the order starts: the first operation, or every one, becomes ready, as the organisation runs them. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void orderStarted(WorkOrder order) {
        makeReady(order, operations.findByWorkOrderIdOrderBySequence(order.getId()));
        operations.flush();
    }

    /** When the order is put on hold: whatever is running is paused and its machine freed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void orderHeld(WorkOrder order, String reason) {
        Instant now = Instant.now();
        for (WorkOrderOperation operation : operations.findByWorkOrderIdOrderBySequence(order.getId())) {
            if (operation.getStatus() == Status.IN_PROGRESS) {
                String why = "Work order on hold: " + reason;
                operation.pause(now, why);
                releaseMachine(order, operation, LossCategory.ORDER_ON_HOLD, why);
                operations.saveAndFlush(operation);
                events.save(new OperationEvent(operation, OperationEvent.Type.PAUSED, Status.IN_PROGRESS,
                        LossCategory.ORDER_ON_HOLD, reason));
            }
        }
    }

    /**
     * Stops the order's batch for quality: the batch goes on hold and whatever is running is paused, so nothing more
     * is made or reported on it. Only an in-process reinspection of the batch releases it, through the inspection
     * engine, as with any batch held for rework.
     */
    @Transactional
    public void qualityHold(UUID orderId, String reason) {
        WorkOrder order = workOrders.findForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkOrder", orderId));
        scope.requireAllowed(order.getFacilityId(), "WorkOrder", order.getWorkOrderNumber());
        if (!ON_FLOOR.contains(order.getStatus()) || order.getProductionBatchId() == null) {
            throw new BusinessRuleException("Work order %s is %s. A quality hold stops production that is under way."
                    .formatted(order.getWorkOrderNumber(), label(order.getStatus())));
        }
        String batchStatus = production.batch(order.getProductionBatchId()).status();
        if (!"IN_PROGRESS".equals(batchStatus)) {
            throw new BusinessRuleException("The batch of %s is %s, so it cannot be put on hold.".formatted(
                    order.getWorkOrderNumber(), batchStatus.toLowerCase(Locale.ROOT).replace('_', ' ')));
        }
        String why = reason.trim();
        production.holdForReinspection(order.getProductionBatchId(), why);
        Instant now = Instant.now();
        int paused = 0;
        for (WorkOrderOperation operation : operations.findByWorkOrderIdOrderBySequence(orderId)) {
            if (operation.getStatus() == Status.IN_PROGRESS) {
                String note = "Quality hold: " + why;
                operation.pause(now, note);
                releaseMachine(order, operation, LossCategory.QUALITY_ISSUE, note);
                operations.saveAndFlush(operation);
                events.save(new OperationEvent(operation, OperationEvent.Type.PAUSED, Status.IN_PROGRESS,
                        LossCategory.QUALITY_ISSUE, why));
                paused++;
            }
        }
        audit.record("WORK_ORDER_QUALITY_HOLD", WorkOrderService.ENTITY, orderId, Map.of("batch", batchStatus),
                Map.of("batch", "ON_HOLD", "reason", why, "operationsPaused", paused));
    }

    // Attachments: photographs and other evidence from the floor

    @Override
    public String entityType() {
        return ATTACHMENT_TYPE;
    }

    @Override
    public String viewPermission() {
        return Permissions.WORK_ORDER_VIEW;
    }

    @Override
    public String managePermission() {
        return Permissions.PRODUCTION_EXECUTE;
    }

    @Override
    public boolean parentExists(UUID entityId) {
        return operations.findById(entityId)
                .flatMap(operation -> workOrders.findById(operation.getWorkOrderId()))
                .filter(order -> scope.allows(order.getFacilityId()))
                .isPresent();
    }

    @Override
    public boolean locked(UUID entityId) {
        return operations.findById(entityId)
                .flatMap(operation -> workOrders.findById(operation.getWorkOrderId()))
                .map(order -> !order.getStatus().isOpen())
                .orElse(true);
    }

    // Rules

    private record Locked(WorkOrder order, WorkOrderOperation operation, List<WorkOrderOperation> siblings) {
    }

    /** Locks the order first, then reads its operations, so that nothing read here is stale. */
    private Locked lock(UUID id) {
        UUID orderId = operations.workOrderIdOf(id).orElseThrow(() -> new ResourceNotFoundException("Operation", id));
        WorkOrder order = workOrders.findForUpdate(orderId).orElseThrow();
        scope.requireAllowed(order.getFacilityId(), "Operation", id.toString());
        List<WorkOrderOperation> siblings = operations.findByWorkOrderIdOrderBySequence(orderId);
        WorkOrderOperation operation = siblings.stream().filter(found -> found.getId().equals(id)).findFirst()
                .orElseThrow();
        return new Locked(order, operation, siblings);
    }

    private OperationDetail changed(
            Locked locked, Status from, OperationEvent.Type type, LossCategory loss, String comment, String action) {
        WorkOrderOperation operation = locked.operation();
        operations.saveAndFlush(operation);
        events.save(new OperationEvent(operation, type, from, loss, comment));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", operation.getStatus().name());
        if (loss != null) {
            after.put("category", loss.name());
        }
        if (comment != null) {
            after.put("reason", comment);
        }
        audit.record(action, ENTITY, operation.getId(), Map.of("status", from.name()), after);
        return detail(locked.order(), operation);
    }

    /** Pending operations whose turn has come become ready: all at once, or each after the one before it. */
    private void makeReady(WorkOrder order, List<WorkOrderOperation> siblings) {
        if (order.getStatus() != WorkOrder.Status.IN_PROGRESS) {
            return;
        }
        boolean inSequence = settings.rules().operationsInSequence();
        boolean earlierDone = true;
        for (WorkOrderOperation operation : siblings) {
            if (operation.getStatus() == Status.PENDING && (!inSequence || earlierDone)) {
                operation.makeReady();
                operations.save(operation);
                events.save(new OperationEvent(operation, OperationEvent.Type.READY, Status.PENDING, null, null));
            }
            if (operation.getStatus() != Status.CANCELLED) {
                earlierDone = earlierDone && operation.getStatus() == Status.COMPLETED;
            }
        }
    }

    private void occupy(WorkOrder order, WorkOrderOperation operation, UUID machineId) {
        Machine machine = workCenters.lockMachine(machineId);
        if (!machine.getWorkCenterId().equals(operation.getWorkCenterId())) {
            throw new BusinessRuleException("Choose a machine at the operation's work centre.");
        }
        if (!FREE.contains(machine.getStatus())) {
            throw new BusinessRuleException("Machine %s is %s.".formatted(machine.getCode(),
                    switch (machine.getStatus()) {
                        case BREAKDOWN -> "broken down";
                        case MAINTENANCE -> "under maintenance";
                        default -> machine.getStatus().name().toLowerCase(Locale.ROOT);
                    }));
        }
        if (operations.machineBusy(machineId, operation.getId())) {
            throw new BusinessRuleException("Machine %s is running another operation.".formatted(machine.getCode()));
        }
        workCenters.moveMachine(machine, Machine.Status.RUNNING, "%s operation %d".formatted(
                order.getWorkOrderNumber(), operation.getSequence()));
    }

    /**
     * A machine this operation was running on is freed, or put down for breakdown or maintenance. A machine someone
     * has already moved by hand is left as they set it.
     */
    private void releaseMachine(WorkOrder order, WorkOrderOperation operation, LossCategory category, String reason) {
        if (operation.getMachineId() == null) {
            return;
        }
        Machine machine = workCenters.lockMachine(operation.getMachineId());
        if (machine.getStatus() != Machine.Status.RUNNING) {
            return;
        }
        String why = "%s operation %d%s".formatted(order.getWorkOrderNumber(), operation.getSequence(),
                reason == null ? "" : ": " + reason);
        Machine.Status target = category == LossCategory.MACHINE_BREAKDOWN ? Machine.Status.BREAKDOWN
                : category == LossCategory.PLANNED_MAINTENANCE ? Machine.Status.MAINTENANCE
                : Machine.Status.AVAILABLE;
        workCenters.moveMachine(machine, target, why);
    }

    /** What can come into each operation: the order quantity first, then what the one before turned out good. */
    private static Map<UUID, BigDecimal> inputs(WorkOrder order, List<WorkOrderOperation> siblings) {
        Map<UUID, BigDecimal> inputs = new HashMap<>();
        BigDecimal coming = order.getQuantity();
        for (WorkOrderOperation operation : siblings) {
            inputs.put(operation.getId(), coming);
            if (operation.getStatus() != Status.CANCELLED) {
                coming = operation.getGoodQuantity();
            }
        }
        return inputs;
    }

    private boolean first(List<WorkOrderOperation> siblings, WorkOrderOperation operation) {
        return siblings.stream().noneMatch(other -> other.getSequence() < operation.getSequence()
                && other.getStatus() != Status.CANCELLED);
    }

    private BigDecimal ceiling(WorkOrder order, List<WorkOrderOperation> siblings, WorkOrderOperation operation) {
        if (!first(siblings, operation)) {
            return inputs(order, siblings).get(operation.getId());
        }
        BigDecimal allowance = settings.rules().overReportPercent();
        return order.getQuantity().multiply(BigDecimal.ONE.add(
                allowance.divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP)))
                .setScale(SCALE, RoundingMode.DOWN);
    }

    private String ceilingMessage(
            WorkOrder order, List<WorkOrderOperation> siblings, WorkOrderOperation operation, BigDecimal ceiling) {
        BigDecimal room = ceiling.subtract(operation.processed()).max(BigDecimal.ZERO);
        if (first(siblings, operation)) {
            return "Operation %d can take %s more: the order is for %s%s.".formatted(operation.getSequence(),
                    plain(room), plain(order.getQuantity()),
                    settings.rules().overReportPercent().signum() > 0
                            ? " plus " + plain(settings.rules().overReportPercent()) + "%" : "");
        }
        return "Operation %d can take %s more: that is what has come through good from the operation before it."
                .formatted(operation.getSequence(), plain(room));
    }

    private void requireWhole(WorkOrder order, Collection<BigDecimal> quantities) {
        UnitResponse unit = materials.listUnits().stream().filter(found -> found.id().equals(order.getUomId()))
                .findFirst().orElse(null);
        if (unit != null && COUNT.equalsIgnoreCase(unit.dimension())
                && quantities.stream().anyMatch(quantity -> quantity.stripTrailingZeros().scale() > 0)) {
            throw new BusinessRuleException("%s are counted in whole numbers.".formatted(unit.name()));
        }
    }

    /** A batch on hold for quality, or rejected, takes no more production. */
    private void requireBatchOpen(WorkOrder order, WorkOrderOperation operation, String action) {
        if (order.getProductionBatchId() == null) {
            return;
        }
        String status = production.batch(order.getProductionBatchId()).status();
        if ("ON_HOLD".equals(status) || "REJECTED".equals(status)) {
            throw new BusinessRuleException("The batch is %s for quality, so operation %d cannot be %s.".formatted(
                    "ON_HOLD".equals(status) ? "on hold" : "rejected", operation.getSequence(), action));
        }
    }

    private void requireRunning(WorkOrder order, String action) {
        if (order.getStatus() != WorkOrder.Status.IN_PROGRESS) {
            throw new BusinessRuleException("Work order %s is %s, so its operations cannot be %s.".formatted(
                    order.getWorkOrderNumber(), label(order.getStatus()), action));
        }
    }

    private void requireOnFloor(WorkOrder order, String action) {
        if (!ON_FLOOR.contains(order.getStatus())) {
            throw orderClosed(order, action);
        }
    }

    private static BusinessRuleException orderClosed(WorkOrder order, String action) {
        return new BusinessRuleException("Work order %s is %s, so its operations cannot be %s.".formatted(
                order.getWorkOrderNumber(), label(order.getStatus()), action));
    }

    /** An operation given to someone is run by them, or by a supervisor. */
    private void requireRunner(WorkOrderOperation operation) {
        UUID me = TenantContext.getCurrentUserId();
        if (operation.getOperatorId() != null && !operation.getOperatorId().equals(me)
                && !holds(Permissions.PRODUCTION_SUPERVISE)) {
            String name = access.memberName(operation.getOperatorId()).orElse("someone else");
            throw new AccessDeniedException("Operation %d is assigned to %s.".formatted(operation.getSequence(), name));
        }
    }

    private static boolean holds(String permission) {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(permission::equals);
    }

    private static boolean repeated(String key) {
        if (key == null) {
            return false;
        }
        if (key.isBlank() || key.length() > MAX_KEY) {
            throw new BusinessRuleException("An Idempotency-Key is 1 to %d characters.".formatted(MAX_KEY));
        }
        return true;
    }

    private List<ParameterLimits> parameters(WorkOrderOperation operation) {
        return json.readValue(operation.getParameters(), new TypeReference<List<ParameterLimits>>() { });
    }

    private static String reason(LossCategory category, String reason) {
        String label = category.name().charAt(0) + category.name().substring(1).toLowerCase(Locale.ROOT)
                .replace('_', ' ');
        return reason == null || reason.isBlank() ? label : label + ": " + reason.trim();
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

    private OperationDetail detail(WorkOrder order, WorkOrderOperation operation) {
        List<WorkOrderOperation> siblings = operations.findByWorkOrderIdOrderBySequence(order.getId());
        OperationSummary summary = summaries(siblings).apply(operation);
        Map<UUID, String> members = access.activeMembers();
        List<OperationReport> rows = reports.findByOperationIdOrderByCreatedAtAscIdAsc(operation.getId());
        Set<UUID> reversed = rows.stream().map(OperationReport::getReversesReportId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<Machine> machines = workCenters.machinesAt(operation.getWorkCenterId());
        Map<UUID, Machine> machineRefs = machines.stream().collect(Collectors.toMap(Machine::getId, m -> m));
        List<OperationEvent> timeline = events.findByOperationIdOrderByCreatedAtAscIdAsc(operation.getId());
        Set<UUID> otherMachines = timeline.stream().map(OperationEvent::getMachineId)
                .filter(machineId -> machineId != null && !machineRefs.containsKey(machineId))
                .collect(Collectors.toSet());
        if (!otherMachines.isEmpty()) {
            machineRefs.putAll(workCenters.machinesById(otherMachines));
        }
        Set<UUID> runners = new HashSet<>(access.holdersOf(Permissions.PRODUCTION_EXECUTE));
        runners.retainAll(members.keySet());
        List<PersonOption> operators = scope.allowedIn(order.getFacilityId(), runners).stream()
                .map(person -> new PersonOption(person, members.get(person)))
                .sorted(java.util.Comparator.comparing(PersonOption::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        boolean previousOpen = siblings.stream().anyMatch(other -> other.getSequence() < operation.getSequence()
                && !other.getStatus().isClosed());
        return new OperationDetail(
                summary,
                operation.getInstructions(),
                operation.getPlannedSetupMinutes(),
                operation.getPlannedRunMinutes(),
                operation.runningSecondsAt(Instant.now()),
                operation.getActualSetupMinutes(),
                operation.getBlockedFrom(),
                previousOpen,
                parameters(operation),
                rows.stream().map(row -> new ReportResponse(row.getId(), row.getGoodQuantity(),
                        row.getRejectedQuantity(), row.getScrapQuantity(), row.getReworkQuantity(),
                        row.getReversesReportId(), reversed.contains(row.getId()), row.getComment(),
                        row.getCreatedBy(), members.get(row.getCreatedBy()), row.getCreatedAt())).toList(),
                measurements.findByOperationIdOrderByCreatedAtAscIdAsc(operation.getId()).stream()
                        .map(row -> new MeasurementResponse(row.getId(), row.getParameterCode(),
                                row.getParameterName(), row.getUnit(), row.getTarget(), row.getMinimum(),
                                row.getMaximum(), row.getMeasuredValue(), row.isWithinLimits(), row.getComment(),
                                row.getCreatedBy(), members.get(row.getCreatedBy()), row.getCreatedAt()))
                        .toList(),
                timeline.stream().map(event -> new EventResponse(event.getId(), event.getEventType(),
                        event.getFromStatus(), event.getToStatus(), event.getLossCategory(),
                        event.getOperatorId() == null ? null : members.get(event.getOperatorId()),
                        event.getMachineId() == null || !machineRefs.containsKey(event.getMachineId()) ? null
                                : machineRefs.get(event.getMachineId()).getCode(),
                        event.getComment(), event.getCreatedBy(), members.get(event.getCreatedBy()),
                        event.getCreatedAt())).toList(),
                machines.stream()
                        .filter(machine -> machine.getStatus() != Machine.Status.INACTIVE
                                || machine.getId().equals(operation.getMachineId()))
                        .map(machine -> new MachineOption(machine.getId(), machine.getCode(), machine.getName(),
                                machine.getStatus(), operations.machineBusy(machine.getId(), operation.getId())))
                        .toList(),
                operators,
                order.getProductionBatchId() == null ? null : production.batch(order.getProductionBatchId()).status(),
                qualityGates.ofOperation(order, operation.getId()));
    }

    /** Looks up names and codes for a page of operations at once. */
    private Function<WorkOrderOperation, OperationSummary> summaries(List<WorkOrderOperation> rows) {
        Set<UUID> orderIds = rows.stream().map(WorkOrderOperation::getWorkOrderId).collect(Collectors.toSet());
        Map<UUID, WorkOrder> orders = workOrders.findAllById(orderIds).stream()
                .collect(Collectors.toMap(WorkOrder::getId, Function.identity()));
        Map<UUID, List<WorkOrderOperation>> byOrder = operations.findByWorkOrderIdInOrderBySequence(orderIds).stream()
                .collect(Collectors.groupingBy(WorkOrderOperation::getWorkOrderId));
        Map<UUID, BigDecimal> inputs = new HashMap<>();
        byOrder.forEach((orderId, siblings) -> inputs.putAll(inputs(orders.get(orderId), siblings)));
        Map<UUID, ProductReference> productRefs = products.references(
                orders.values().stream().map(WorkOrder::getProductId).collect(Collectors.toSet()));
        Set<UUID> facilityIds = orders.values().stream().map(WorkOrder::getFacilityId).collect(Collectors.toSet());
        Map<UUID, String> codes = facilityIds.isEmpty() ? Map.of() : facilities.codes(facilityIds);
        Map<UUID, WorkCenter> centres = workCenters.byId(
                rows.stream().map(WorkOrderOperation::getWorkCenterId).collect(Collectors.toSet()));
        Map<UUID, Machine> machineRefs = workCenters.machinesById(rows.stream()
                .map(WorkOrderOperation::getMachineId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, String> members = access.activeMembers();
        return operation -> {
            WorkOrder order = orders.get(operation.getWorkOrderId());
            ProductReference product = productRefs.get(order.getProductId());
            WorkCenter centre = centres.get(operation.getWorkCenterId());
            Machine machine = operation.getMachineId() == null ? null : machineRefs.get(operation.getMachineId());
            BigDecimal input = inputs.getOrDefault(operation.getId(), order.getQuantity());
            BigDecimal waiting = operation.getStatus().isClosed() ? BigDecimal.ZERO
                    : input.subtract(operation.processed()).max(BigDecimal.ZERO);
            return new OperationSummary(operation.getId(), order.getId(), order.getWorkOrderNumber(),
                    order.getStatus(), order.getPriority(), order.getPlannedEnd(), order.getProductId(),
                    product == null ? null : product.code(), product == null ? null : product.name(),
                    order.getFacilityId(), codes.get(order.getFacilityId()), operation.getSequence(),
                    operation.getName(), operation.getWorkCenterId(), centre == null ? null : centre.getCode(),
                    centre == null ? null : centre.getName(), operation.getStatus(), operation.getStatusReason(),
                    operation.getOperatorId(),
                    operation.getOperatorId() == null ? null : members.get(operation.getOperatorId()),
                    operation.getMachineId(), machine == null ? null : machine.getCode(), order.getUomId(),
                    order.getQuantity(), input, operation.processed(), operation.getGoodQuantity(),
                    operation.getRejectedQuantity(), operation.getScrapQuantity(), operation.getReworkQuantity(),
                    waiting, operation.getStartedAt(), operation.getCompletedAt());
        };
    }
}
