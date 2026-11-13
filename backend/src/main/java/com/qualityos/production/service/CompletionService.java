package com.qualityos.production.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.material.dto.MaterialDtos.UnitResponse;
import com.qualityos.material.service.MaterialService;
import com.qualityos.production.dto.CompletionDtos.CloseRequest;
import com.qualityos.production.dto.CompletionDtos.CompletionResponse;
import com.qualityos.production.dto.CompletionDtos.MaterialVariance;
import com.qualityos.production.dto.CompletionDtos.OperationRecord;
import com.qualityos.production.dto.CompletionDtos.ProductionRecord;
import com.qualityos.production.dto.CompletionDtos.ReceiptRequest;
import com.qualityos.production.dto.CompletionDtos.ReceiptResponse;
import com.qualityos.production.dto.ManufacturingDtos.SettingsResponse;
import com.qualityos.production.dto.ProductionQualityDtos.GateResponse;
import com.qualityos.production.entity.Bom;
import com.qualityos.production.entity.FinishedGoodsReceipt;
import com.qualityos.production.entity.Machine;
import com.qualityos.production.entity.OperationEvent;
import com.qualityos.production.entity.OperationReport;
import com.qualityos.production.entity.ReworkOrder;
import com.qualityos.production.entity.WorkCenter;
import com.qualityos.production.entity.WorkOrder;
import com.qualityos.production.entity.WorkOrderOperation;
import com.qualityos.production.entity.WorkOrderStatusChange;
import com.qualityos.production.repository.BomRepository;
import com.qualityos.production.repository.FinishedGoodsReceiptRepository;
import com.qualityos.production.repository.OperationEventRepository;
import com.qualityos.production.repository.OperationReportRepository;
import com.qualityos.production.repository.RoutingRepository;
import com.qualityos.production.repository.WorkOrderOperationRepository;
import com.qualityos.production.repository.WorkOrderRepository;
import com.qualityos.production.repository.WorkOrderStatusChangeRepository;
import com.qualityos.traceability.dto.TraceabilityDtos.AddSerialsRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.CompleteBatchRequest;
import com.qualityos.traceability.service.ProductionService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finishing a work order, in two steps. Receiving its finished goods completes the order's production batch through
 * traceability, once operations, quality gates, rework and materials are reconciled; the goods are then the batch, in
 * the existing inventory, waiting for final inspection. Closing it then completes the order, or partially completes it
 * when it came out short, once its final inspection has decided. Both lock the work order first, so neither happens
 * twice. See docs/MANUFACTURING.md.
 */
@Service
public class CompletionService {

    static final String ENTITY = WorkOrderService.ENTITY;
    private static final int MAX_KEY = 100;
    private static final String COUNT = "Count";
    private static final Set<String> ACCEPTED = Set.of("ACCEPT", "CONDITIONAL_ACCEPTANCE");

    private final WorkOrderRepository workOrders;
    private final WorkOrderOperationRepository operations;
    private final WorkOrderStatusChangeRepository history;
    private final FinishedGoodsReceiptRepository receipts;
    private final OperationEventRepository events;
    private final OperationReportRepository reports;
    private final BomRepository boms;
    private final RoutingRepository routings;
    private final ReworkService reworks;
    private final QualityGateService qualityGates;
    private final WorkOrderMaterialService orderMaterials;
    private final ManufacturingSettingsService settings;
    private final WorkCenterService workCenters;
    private final ProductionService production;
    private final MaterialService materials;
    private final FacilityScope scope;
    private final TenantAccessService access;
    private final AuditService audit;
    private final JsonMapper json;

    @SuppressWarnings("java:S107")
    CompletionService(
            WorkOrderRepository workOrders,
            WorkOrderOperationRepository operations,
            WorkOrderStatusChangeRepository history,
            FinishedGoodsReceiptRepository receipts,
            OperationEventRepository events,
            OperationReportRepository reports,
            BomRepository boms,
            RoutingRepository routings,
            ReworkService reworks,
            QualityGateService qualityGates,
            WorkOrderMaterialService orderMaterials,
            ManufacturingSettingsService settings,
            WorkCenterService workCenters,
            ProductionService production,
            MaterialService materials,
            FacilityScope scope,
            TenantAccessService access,
            AuditService audit,
            JsonMapper json) {
        this.workOrders = workOrders;
        this.operations = operations;
        this.history = history;
        this.receipts = receipts;
        this.events = events;
        this.reports = reports;
        this.boms = boms;
        this.routings = routings;
        this.reworks = reworks;
        this.qualityGates = qualityGates;
        this.orderMaterials = orderMaterials;
        this.settings = settings;
        this.workCenters = workCenters;
        this.production = production;
        this.materials = materials;
        this.scope = scope;
        this.access = access;
        this.audit = audit;
        this.json = json;
    }

    // Reading

    @Transactional(readOnly = true)
    public CompletionResponse get(UUID id) {
        WorkOrder order = workOrders.findById(id).orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
        scope.requireAllowed(order.getFacilityId(), ENTITY, order.getWorkOrderNumber());
        return view(order);
    }

    /** How the batch was made, when a work order made it; empty for a batch recorded by hand. */
    @Transactional(readOnly = true)
    public Optional<ProductionRecord> record(UUID batchId) {
        Optional<WorkOrder> found = workOrders.findByProductionBatchId(batchId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        WorkOrder order = found.get();
        scope.requireAllowed(order.getFacilityId(), ENTITY, order.getWorkOrderNumber());
        Bom definition = order.getBomId() == null ? null : boms.findById(order.getBomId()).orElse(null);
        Integer routingRevision = order.getRoutingId() == null ? null
                : routings.findById(order.getRoutingId()).map(routing -> routing.getRevision()).orElse(null);
        Map<UUID, String> members = access.activeMembers();
        List<WorkOrderOperation> steps = operations.findByWorkOrderIdOrderBySequence(order.getId());
        Map<UUID, WorkCenter> centres = workCenters.byId(steps.stream().map(WorkOrderOperation::getWorkCenterId)
                .collect(Collectors.toSet()));
        List<OperationRecord> operationRecords = new ArrayList<>();
        for (WorkOrderOperation step : steps) {
            List<OperationEvent> timeline = events.findByOperationIdOrderByCreatedAtAscIdAsc(step.getId());
            Set<UUID> people = new LinkedHashSet<>();
            timeline.stream().map(OperationEvent::getOperatorId).filter(Objects::nonNull).forEach(people::add);
            reports.findByOperationIdOrderByCreatedAtAscIdAsc(step.getId()).stream()
                    .map(OperationReport::getCreatedBy).filter(Objects::nonNull).forEach(people::add);
            Set<UUID> machineIds = new LinkedHashSet<>();
            timeline.stream().map(OperationEvent::getMachineId).filter(Objects::nonNull).forEach(machineIds::add);
            if (step.getMachineId() != null) {
                machineIds.add(step.getMachineId());
            }
            Map<UUID, Machine> machineRefs = machineIds.isEmpty() ? Map.of() : workCenters.machinesById(machineIds);
            WorkCenter centre = centres.get(step.getWorkCenterId());
            operationRecords.add(new OperationRecord(step.getSequence(), step.getName(),
                    centre == null ? null : centre.getCode(), step.getStatus().name(),
                    people.stream().map(person -> members.getOrDefault(person, "Former member")).toList(),
                    machineIds.stream().map(machineRefs::get).filter(Objects::nonNull).map(Machine::getCode).toList(),
                    step.getStartedAt(), step.getCompletedAt(), step.getGoodQuantity(), step.getRejectedQuantity(),
                    step.getScrapQuantity()));
        }
        List<String> gates = qualityGates.ofOrder(order).stream().map(CompletionService::describe).toList();
        List<String> reworkRows = reworks.ofOrder(order.getId()).stream()
                .map(rework -> "%s: %s %s%s".formatted(rework.getReworkNumber(), plain(rework.getQuantity()),
                        ReworkOrder.label(rework.getStatus()), rework.getFinalDisposition() == null ? ""
                                : ", " + rework.getFinalDisposition().toLowerCase(Locale.ROOT).replace('_', ' ')))
                .toList();
        return Optional.of(new ProductionRecord(order.getId(), order.getWorkOrderNumber(), order.getStatus(),
                definition == null ? null : definition.getKind().name(),
                definition == null ? null : definition.getRevision(), routingRevision, operationRecords, gates,
                reworkRows, receipts.findByWorkOrderId(order.getId()).map(receipt -> receipt(receipt, members))
                        .orElse(null)));
    }

    // Receiving

    /**
     * Puts the order's output into finished goods: completes its batch with the quantity the operations turned out
     * good, or the quantity given when it has none. Short output, materials used beyond the tolerance and serial
     * numbers are settled here. An {@code Idempotency-Key} already used returns the receipt as it stands.
     */
    @Transactional
    public CompletionResponse receive(UUID id, ReceiptRequest request, String key) {
        WorkOrder order = lock(id);
        Optional<FinishedGoodsReceipt> existing = receipts.findByWorkOrderId(id);
        if (repeated(key) && existing.map(FinishedGoodsReceipt::getRequestKey).filter(key::equals).isPresent()) {
            return view(order);
        }
        if (existing.isPresent()) {
            throw new BusinessRuleException("The finished goods of %s were already received."
                    .formatted(order.getWorkOrderNumber()));
        }
        List<String> blockers = receiveBlockers(order);
        if (!blockers.isEmpty()) {
            throw new BusinessRuleException("Finished goods cannot be received yet. " + String.join(" ", blockers));
        }
        SettingsResponse rules = settings.rules();
        Totals totals = totals(order);
        BigDecimal quantity;
        if (totals.expected() != null) {
            quantity = request.quantity() == null ? totals.expected() : request.quantity();
            if (quantity.compareTo(totals.expected()) != 0) {
                throw new BusinessRuleException("The operations turned out %s good, so that is what is received."
                        .formatted(plain(totals.expected())));
            }
        } else if (request.quantity() == null) {
            throw new BusinessRuleException("Say how much was made.");
        } else {
            quantity = request.quantity();
        }
        if (quantity.signum() <= 0) {
            throw new BusinessRuleException("Nothing came out good, so there is nothing to receive. Close it short.");
        }
        BigDecimal ceiling = order.getQuantity().multiply(BigDecimal.ONE.add(
                rules.overReportPercent().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
        if (quantity.compareTo(ceiling) > 0) {
            throw new BusinessRuleException("The order is for %s%s, so no more than %s can be received.".formatted(
                    plain(order.getQuantity()), rules.overReportPercent().signum() > 0
                            ? " plus " + plain(rules.overReportPercent()) + "%" : "", plain(ceiling)));
        }
        UnitResponse unit = unit(order);
        boolean counted = unit != null && COUNT.equalsIgnoreCase(unit.dimension());
        if (counted && quantity.stripTrailingZeros().scale() > 0) {
            throw new BusinessRuleException("%s are counted in whole numbers.".formatted(unit.name()));
        }
        String shortReason = blankToNull(request.shortReason());
        if (isShort(order, quantity, rules) && shortReason == null) {
            throw new BusinessRuleException("Only %s of %s were made. Say why it is short.".formatted(
                    plain(quantity), plain(order.getQuantity())));
        }
        List<MaterialVariance> variances = orderMaterials.variances(order, quantity, rules.materialVariancePercent());
        List<String> outside = variances.stream().filter(MaterialVariance::outside)
                .map(variance -> "%s: %s issued against %s needed.".formatted(variance.componentCode(),
                        plain(variance.issued()), plain(variance.expected())))
                .toList();
        String varianceReason = blankToNull(request.varianceReason());
        if (!outside.isEmpty() && varianceReason == null) {
            throw new BusinessRuleException("Material use is off by more than %s%%. %s Say why.".formatted(
                    plain(rules.materialVariancePercent()), String.join(" ", outside)));
        }
        List<String> serials = request.serialNumbers() == null ? List.of()
                : request.serialNumbers().stream().map(String::trim).toList();
        if (new HashSet<>(serials).size() != serials.size()) {
            throw new BusinessRuleException("A serial number is given twice.");
        }
        if (serialsRequired(order, rules) && BigDecimal.valueOf(serials.size()).compareTo(quantity) != 0) {
            throw new BusinessRuleException("Give one serial number for each of the %s units.".formatted(
                    plain(quantity)));
        }
        production.completeGuarded(order.getProductionBatchId(), new CompleteBatchRequest(quantity));
        if (!serials.isEmpty()) {
            production.addSerials(order.getProductionBatchId(), new AddSerialsRequest(serials));
        }
        orderMaterials.releaseAll(order, "Finished goods received");
        FinishedGoodsReceipt receipt = receipts.saveAndFlush(new FinishedGoodsReceipt(order, quantity,
                totals.expected(), totals.rejected(), totals.scrap(), totals.reworked(), shortReason, varianceReason,
                json.writeValueAsString(variances), serials.size(), key));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("receipt", receipt.getId().toString());
        after.put("quantity", plain(quantity));
        after.put("ordered", plain(order.getQuantity()));
        if (shortReason != null) {
            after.put("shortReason", shortReason);
        }
        if (varianceReason != null) {
            after.put("varianceReason", varianceReason);
        }
        if (!serials.isEmpty()) {
            after.put("serials", serials.size());
        }
        audit.record("WORK_ORDER_FINISHED_GOODS_RECEIVED", ENTITY, id, null, after);
        return view(order);
    }

    // Closing

    /**
     * Completes the order once its goods are received and every mandatory gate and rework order is settled. It closes
     * as partially completed, with a reason, when less was received than ordered beyond the tolerance, or the batch
     * was rejected.
     */
    @Transactional
    public CompletionResponse close(UUID id, CloseRequest request) {
        WorkOrder order = lock(id);
        WorkOrder.Status from = order.getStatus();
        FinishedGoodsReceipt receipt = receipts.findByWorkOrderId(id).orElse(null);
        List<String> blockers = closeBlockers(order, receipt);
        if (!blockers.isEmpty()) {
            throw new BusinessRuleException("%s cannot be closed yet. %s".formatted(order.getWorkOrderNumber(),
                    String.join(" ", blockers)));
        }
        boolean full = closesInFull(order, receipt);
        String reason = request == null ? null : blankToNull(request.reason());
        if (!full && reason == null) {
            throw new BusinessRuleException("%s closes short: %s received of %s%s. Say why.".formatted(
                    order.getWorkOrderNumber(), plain(receipt.getQuantity()), plain(order.getQuantity()),
                    "REJECTED".equals(batchStatus(order)) ? ", and the batch was rejected" : ""));
        }
        order.complete(full, TenantContext.getCurrentUserId(), Instant.now(), reason);
        workOrders.saveAndFlush(order);
        history.saveAndFlush(new WorkOrderStatusChange(order.getId(), from, order.getStatus(), reason));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", order.getStatus().name());
        after.put("received", plain(receipt.getQuantity()));
        if (reason != null) {
            after.put("reason", reason);
        }
        audit.record(full ? "WORK_ORDER_COMPLETED" : "WORK_ORDER_PARTIALLY_COMPLETED", ENTITY, id,
                Map.of("status", from.name()), after);
        return view(order);
    }

    // Rules

    /** Totals from the operations and rework, in the order's unit. */
    private record Totals(BigDecimal expected, BigDecimal rejected, BigDecimal scrap, BigDecimal reworked) {
    }

    /**
     * What came out good is the last operation's good output, plus rejects reworked from an operation and accepted on
     * reinspection. Without operations there is nothing to count from.
     */
    private Totals totals(WorkOrder order) {
        List<WorkOrderOperation> running = operations.findByWorkOrderIdOrderBySequence(order.getId()).stream()
                .filter(step -> step.getStatus() != WorkOrderOperation.Status.CANCELLED).toList();
        List<ReworkOrder> closed = reworks.ofOrder(order.getId()).stream()
                .filter(rework -> rework.getStatus() == ReworkOrder.Status.CLOSED).toList();
        BigDecimal reworked = closed.stream()
                .filter(rework -> rework.getOperationId() != null && ACCEPTED.contains(rework.getFinalDisposition()))
                .map(ReworkOrder::getReworkedQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rejected = running.stream().map(WorkOrderOperation::getRejectedQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal scrap = running.stream().map(WorkOrderOperation::getScrapQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(closed.stream().map(ReworkOrder::getScrappedQuantity).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal expected = running.isEmpty() ? null : running.getLast().getGoodQuantity().add(reworked);
        return new Totals(expected, rejected, scrap, reworked);
    }

    private List<String> receiveBlockers(WorkOrder order) {
        List<String> blockers = new ArrayList<>();
        if (order.getStatus() != WorkOrder.Status.IN_PROGRESS) {
            blockers.add("Work order %s is %s.".formatted(order.getWorkOrderNumber(), label(order.getStatus())));
            return blockers;
        }
        String batch = batchStatus(order);
        if (!"IN_PROGRESS".equals(batch)) {
            blockers.add("The batch is %s.".formatted(batch.toLowerCase(Locale.ROOT).replace('_', ' ')));
        }
        operations.findByWorkOrderIdOrderBySequence(order.getId()).stream()
                .filter(step -> !step.getStatus().isClosed())
                .forEach(step -> blockers.add("Operation %d is %s.".formatted(step.getSequence(),
                        WorkOrderOperation.label(step.getStatus()))));
        blockers.addAll(qualityGates.receiveBlockers(order));
        if (production.activeConsumptions(order.getProductionBatchId()).isEmpty()) {
            blockers.add("No material has been issued to the batch, so it could not be traced.");
        }
        return blockers;
    }

    private List<String> closeBlockers(WorkOrder order, FinishedGoodsReceipt receipt) {
        List<String> blockers = new ArrayList<>();
        if (order.getStatus() != WorkOrder.Status.IN_PROGRESS) {
            blockers.add("It is %s.".formatted(label(order.getStatus())));
            return blockers;
        }
        if (receipt == null) {
            blockers.add("Its finished goods have not been received.");
            return blockers;
        }
        String batch = batchStatus(order);
        if ("ON_HOLD".equals(batch)) {
            blockers.add("The batch is on hold.");
        } else if ("COMPLETED".equals(batch) && settings.rules().completionRequiresFinalInspection()) {
            blockers.add("The batch is waiting for its final inspection.");
        }
        blockers.addAll(qualityGates.closeBlockers(order));
        return blockers;
    }

    private boolean closesInFull(WorkOrder order, FinishedGoodsReceipt receipt) {
        return !isShort(order, receipt.getQuantity(), settings.rules()) && !"REJECTED".equals(batchStatus(order));
    }

    private static boolean isShort(WorkOrder order, BigDecimal quantity, SettingsResponse rules) {
        BigDecimal floor = order.getQuantity().multiply(BigDecimal.ONE.subtract(
                rules.completionTolerancePercent().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
        return quantity.compareTo(floor) < 0;
    }

    /** Serials are taken for discrete products when the organisation tracks them. */
    private boolean serialsRequired(WorkOrder order, SettingsResponse rules) {
        return rules.serialTracking()
                && settings.definitionKind(order.getProductId()).filter(kind -> kind == Bom.Kind.BOM).isPresent();
    }

    private String batchStatus(WorkOrder order) {
        return order.getProductionBatchId() == null ? null : production.batch(order.getProductionBatchId()).status();
    }

    private WorkOrder lock(UUID id) {
        WorkOrder order = workOrders.findForUpdate(id).orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
        scope.requireAllowed(order.getFacilityId(), ENTITY, order.getWorkOrderNumber());
        return order;
    }

    private UnitResponse unit(WorkOrder order) {
        return materials.listUnits().stream().filter(found -> found.id().equals(order.getUomId())).findFirst()
                .orElse(null);
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

    private static String describe(GateResponse gate) {
        String latest = gate.inspections().isEmpty() ? "" : " (" + gate.inspections().getLast().inspectionNumber() + ")";
        return "%s%s: %s%s".formatted(gate.name(), gate.operationSequence() == null ? ""
                : " on operation " + gate.operationSequence(), label(gate.status()), latest);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String label(Enum<?> status) {
        return status.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    // Responses

    private CompletionResponse view(WorkOrder order) {
        Map<UUID, String> members = access.activeMembers();
        SettingsResponse rules = settings.rules();
        Optional<FinishedGoodsReceipt> receipt = receipts.findByWorkOrderId(order.getId());
        Totals totals = totals(order);
        boolean started = order.getProductionBatchId() != null;
        List<MaterialVariance> variances = receipt.map(this::variancesOf).orElseGet(() -> {
            BigDecimal basis = totals.expected() != null && totals.expected().signum() > 0 ? totals.expected()
                    : order.getQuantity();
            return started ? orderMaterials.variances(order, basis, rules.materialVariancePercent()) : List.of();
        });
        List<String> receiveBlockers = receipt.isPresent() || !started ? List.of() : receiveBlockers(order);
        boolean running = order.getStatus() == WorkOrder.Status.IN_PROGRESS;
        List<String> closeBlockers = receipt.isEmpty() || !running ? List.of()
                : closeBlockers(order, receipt.get());
        WorkOrder.Status closesAs = receipt.isEmpty() ? null
                : running ? (closesInFull(order, receipt.get()) ? WorkOrder.Status.COMPLETED
                        : WorkOrder.Status.PARTIALLY_COMPLETED) : order.getStatus();
        return new CompletionResponse(order.getQuantity(), totals.expected(), totals.rejected(), totals.scrap(),
                totals.reworked(), serialsRequired(order, rules), variances, receiveBlockers,
                receipt.map(found -> receipt(found, members)).orElse(null), closeBlockers, closesAs,
                order.getCompletedAt(), order.getCompletedBy() == null ? null
                        : members.getOrDefault(order.getCompletedBy(), "Former member"));
    }

    private List<MaterialVariance> variancesOf(FinishedGoodsReceipt receipt) {
        return json.readValue(receipt.getMaterialVariances(), new TypeReference<List<MaterialVariance>>() { });
    }

    private ReceiptResponse receipt(FinishedGoodsReceipt receipt, Map<UUID, String> members) {
        return new ReceiptResponse(receipt.getId(), receipt.getQuantity(), receipt.getOrderedQuantity(),
                receipt.getExpectedQuantity(), receipt.getRejectedQuantity(), receipt.getScrapQuantity(),
                receipt.getReworkedQuantity(), receipt.getShortReason(), receipt.getVarianceReason(),
                variancesOf(receipt), receipt.getSerialCount(), receipt.getCreatedBy(),
                receipt.getCreatedBy() == null ? null : members.getOrDefault(receipt.getCreatedBy(), "Former member"),
                receipt.getCreatedAt());
    }
}
