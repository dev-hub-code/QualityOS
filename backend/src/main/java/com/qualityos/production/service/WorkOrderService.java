package com.qualityos.production.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.persistence.DocumentNumberService;
import com.qualityos.common.persistence.Searches;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.identity.service.ReviewPolicy;
import com.qualityos.material.dto.MaterialDtos.MaterialReference;
import com.qualityos.material.service.MaterialService;
import com.qualityos.organization.service.FacilityService;
import com.qualityos.product.dto.ProductDtos.ProductReference;
import com.qualityos.product.service.ProductService;
import com.qualityos.production.dto.WorkOrderDtos.NoteRequest;
import com.qualityos.production.dto.WorkOrderDtos.ReasonRequest;
import com.qualityos.production.dto.WorkOrderDtos.ReleaseDefinition;
import com.qualityos.production.dto.WorkOrderDtos.RequirementResponse;
import com.qualityos.production.dto.WorkOrderDtos.SaveWorkOrderRequest;
import com.qualityos.production.dto.WorkOrderDtos.SendBackWorkOrderRequest;
import com.qualityos.production.dto.WorkOrderDtos.StatusChangeResponse;
import com.qualityos.production.dto.WorkOrderDtos.WorkOrderDetail;
import com.qualityos.production.dto.WorkOrderDtos.WorkOrderOperationResponse;
import com.qualityos.production.dto.WorkOrderDtos.WorkOrderResponse;
import com.qualityos.production.entity.Bom;
import com.qualityos.production.entity.BomLine;
import com.qualityos.production.entity.ProductionPlan;
import com.qualityos.production.entity.ProductionPlanLine;
import com.qualityos.production.entity.RevisionedDefinition;
import com.qualityos.production.entity.Routing;
import com.qualityos.production.entity.RoutingOperation;
import com.qualityos.production.entity.WorkCenter;
import com.qualityos.production.entity.WorkOrder;
import com.qualityos.production.entity.WorkOrderMaterial;
import com.qualityos.production.entity.WorkOrderOperation;
import com.qualityos.production.entity.WorkOrderStatusChange;
import com.qualityos.production.repository.BomLineRepository;
import com.qualityos.production.repository.BomRepository;
import com.qualityos.production.repository.ProductionPlanLineRepository;
import com.qualityos.production.repository.ProductionPlanRepository;
import com.qualityos.production.repository.RoutingOperationRepository;
import com.qualityos.production.repository.RoutingRepository;
import com.qualityos.production.repository.WorkOrderMaterialRepository;
import com.qualityos.production.repository.WorkOrderOperationRepository;
import com.qualityos.production.repository.WorkOrderRepository;
import com.qualityos.production.repository.WorkOrderStatusChangeRepository;
import com.qualityos.traceability.dto.TraceabilityDtos.CreateBatchRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.ProductionBatchView;
import com.qualityos.traceability.service.ProductionService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Work orders, from draft to started production. Every change of status is checked against
 * {@link WorkOrder#TRANSITIONS}, recorded in the order's history and audited. Release freezes what the order is made
 * from; starting it opens the production batch that traceability and quality already follow.
 */
@Service
public class WorkOrderService {

    static final String ENTITY = "WorkOrder";
    private static final String MATERIAL = "MATERIAL";
    private static final String PRODUCT = "PRODUCT";

    private final WorkOrderRepository workOrders;
    private final WorkOrderMaterialRepository requirements;
    private final WorkOrderOperationRepository steps;
    private final WorkOrderStatusChangeRepository history;
    private final ProductionPlanRepository plans;
    private final ProductionPlanLineRepository planLines;
    private final BomRepository boms;
    private final BomLineRepository bomLines;
    private final RoutingRepository routings;
    private final RoutingOperationRepository routingOperations;
    private final WorkCenterService workCenters;
    private final ManufacturingSettingsService settings;
    private final RevisionSupport support;
    private final ProductService products;
    private final MaterialService materials;
    private final FacilityService facilities;
    private final ProductionService production;
    private final WorkOrderMaterialService orderMaterials;
    private final FacilityScope scope;
    private final ReviewPolicy reviewPolicy;
    private final DocumentNumberService numbers;
    private final AuditService audit;

    WorkOrderService(
            WorkOrderRepository workOrders,
            WorkOrderMaterialRepository requirements,
            WorkOrderOperationRepository steps,
            WorkOrderStatusChangeRepository history,
            ProductionPlanRepository plans,
            ProductionPlanLineRepository planLines,
            BomRepository boms,
            BomLineRepository bomLines,
            RoutingRepository routings,
            RoutingOperationRepository routingOperations,
            WorkCenterService workCenters,
            ManufacturingSettingsService settings,
            RevisionSupport support,
            ProductService products,
            MaterialService materials,
            FacilityService facilities,
            ProductionService production,
            WorkOrderMaterialService orderMaterials,
            FacilityScope scope,
            ReviewPolicy reviewPolicy,
            DocumentNumberService numbers,
            AuditService audit) {
        this.workOrders = workOrders;
        this.requirements = requirements;
        this.steps = steps;
        this.history = history;
        this.plans = plans;
        this.planLines = planLines;
        this.boms = boms;
        this.bomLines = bomLines;
        this.routings = routings;
        this.routingOperations = routingOperations;
        this.workCenters = workCenters;
        this.settings = settings;
        this.support = support;
        this.products = products;
        this.materials = materials;
        this.facilities = facilities;
        this.production = production;
        this.orderMaterials = orderMaterials;
        this.scope = scope;
        this.reviewPolicy = reviewPolicy;
        this.numbers = numbers;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkOrderResponse> list(
            String search, WorkOrder.Status status, UUID facilityId, UUID productId, UUID planId, Pageable pageable) {
        Specification<WorkOrder> filter = Specification.<WorkOrder>where(Searches.equalTo("status", status))
                .and(Searches.equalTo("facilityId", facilityId))
                .and(Searches.equalTo("productId", productId));
        if (search != null && !search.isBlank()) {
            List<UUID> matching = products.idsMatching(search);
            Specification<WorkOrder> byNumber = Searches.contains(search, "workOrderNumber");
            filter = filter.and(matching.isEmpty() ? byNumber
                    : byNumber.or((root, query, cb) -> root.get("productId").in(matching)));
        }
        if (planId != null) {
            List<UUID> lineIds = planLines.findByPlanIdOrderByDueDateAscIdAsc(planId).stream()
                    .map(ProductionPlanLine::getId).toList();
            filter = filter.and((root, query, cb) -> lineIds.isEmpty()
                    ? cb.disjunction() : root.get("planLineId").in(lineIds));
        }
        Page<WorkOrder> page = workOrders.findAll(scope.limit(filter, "facilityId"), pageable);
        Function<WorkOrder, WorkOrderResponse> mapper = responses(page.getContent());
        return PageResponse.from(page, mapper::apply);
    }

    @Transactional(readOnly = true)
    public WorkOrderDetail get(UUID id) {
        return detail(load(id));
    }

    @Transactional
    public WorkOrderDetail create(SaveWorkOrderRequest request) {
        ProductionPlanLine line = request.planLineId() == null ? null : orderableLine(request);
        WorkOrder order = new WorkOrder(numbers.next("WO"), line == null ? null : line.getId());
        apply(order, request);
        workOrders.saveAndFlush(order);
        record(order, null, null);
        audit.record("WORK_ORDER_CREATED", ENTITY, order.getId(), null, Map.of(
                "number", order.getWorkOrderNumber(), "quantity", order.getQuantity().toPlainString()));
        return detail(order);
    }

    @Transactional
    public WorkOrderDetail update(UUID id, SaveWorkOrderRequest request) {
        WorkOrder order = load(id);
        order.requireDraft();
        if (!Objects.equals(order.getPlanLineId(), request.planLineId())) {
            throw new BusinessRuleException("A work order stays with the plan line it was raised from.");
        }
        if (order.getPlanLineId() != null) {
            orderableLine(request);
        }
        apply(order, request);
        workOrders.saveAndFlush(order);
        audit.record("WORK_ORDER_UPDATED", ENTITY, id);
        return detail(order);
    }

    @Transactional
    public WorkOrderDetail submit(UUID id) {
        WorkOrder order = load(id);
        WorkOrder.Status from = order.getStatus();
        products.requireActive(order.getProductId());
        order.submit(TenantContext.getCurrentUserId(), Instant.now());
        return saved(order, from, null, "WORK_ORDER_SUBMITTED");
    }

    @Transactional
    public WorkOrderDetail sendBack(UUID id, SendBackWorkOrderRequest request) {
        WorkOrder order = load(id);
        WorkOrder.Status from = order.getStatus();
        String note = request.note().trim();
        order.sendBack(note);
        return saved(order, from, note, "WORK_ORDER_SENT_BACK");
    }

    /** Under the organisation's rule, whoever submitted cannot approve, except as a recorded self-review. */
    @Transactional
    public WorkOrderDetail approve(UUID id, NoteRequest request) {
        WorkOrder order = load(id);
        WorkOrder.Status from = order.getStatus();
        UUID actor = TenantContext.getCurrentUserId();
        String note = request == null ? null : request.note();
        if (from == WorkOrder.Status.SUBMITTED && settings.separateApprover()
                && !reviewPolicy.permits(actor, order.getSubmittedBy(), ENTITY, id, "WORK_ORDER_APPROVE", note)) {
            throw new BusinessRuleException("You submitted this work order, so someone else must approve it.");
        }
        order.approve(actor, Instant.now());
        return saved(order, from, note, "WORK_ORDER_APPROVED");
    }

    /**
     * Freezes the BOM or recipe and the routing in effect on the planned start, and works out what the order needs
     * from them. Whether each is required is the organisation's choice. A routing's work centres must be in the
     * order's facility.
     */
    @Transactional
    public WorkOrderDetail release(UUID id) {
        WorkOrder order = load(id);
        WorkOrder.Status from = order.getStatus();
        order.requireStatus(WorkOrder.Status.APPROVED, "released");
        products.requireActive(order.getProductId());
        facilities.requireActive(order.getFacilityId());
        Resolution resolution = resolve(order);
        if (!resolution.problems().isEmpty()) {
            throw new BusinessRuleException(String.join(" ", resolution.problems()));
        }
        Bom definition = resolution.definition();
        Routing routing = resolution.routing();
        order.release(TenantContext.getCurrentUserId(), Instant.now(),
                definition == null ? null : definition.getId(), definition == null ? null : definition.getSnapshot(),
                routing == null ? null : routing.getId(), routing == null ? null : routing.getSnapshot());
        workOrders.saveAndFlush(order);
        if (definition != null) {
            writeRequirements(order, definition);
        }
        if (routing != null) {
            writeOperations(order, routing);
        }
        if (definition != null && settings.rules().reserveOnRelease()) {
            orderMaterials.reserveAvailable(order, null);
        }
        record(order, from, null);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", order.getStatus().name());
        if (definition != null) {
            details.put(definition.getKind() == Bom.Kind.BOM ? "bomRevision" : "recipeRevision",
                    definition.getRevision());
        }
        if (routing != null) {
            details.put("routingRevision", routing.getRevision());
        }
        audit.record("WORK_ORDER_RELEASED", ENTITY, id, Map.of("status", from.name()), details);
        return detail(order);
    }

    /**
     * Opens the production batch the order makes. The status check and the order's version stop a second start from
     * opening a second batch.
     */
    @Transactional
    public WorkOrderDetail start(UUID id) {
        WorkOrder order = load(id);
        WorkOrder.Status from = order.getStatus();
        order.requireStatus(WorkOrder.Status.RELEASED, "started");
        List<String> shortfalls = orderMaterials.shortfalls(order);
        if (!shortfalls.isEmpty() && settings.rules().startRequiresMaterials()) {
            throw new BusinessRuleException("Production cannot start until the material is ready. "
                    + String.join(" ", shortfalls));
        }
        var batch = production.create(new CreateBatchRequest(
                order.getProductId(), order.getFacilityId(), order.getQuantity())).batch();
        order.start(batch.id(), Instant.now());
        workOrders.saveAndFlush(order);
        // Starting short is allowed when the organisation allows it, but never silently.
        String shortNote = shortfalls.isEmpty() ? null : "Started short: " + String.join(" ", shortfalls);
        record(order, from, shortNote);
        Map<String, Object> after = new LinkedHashMap<>(Map.of(
                "status", order.getStatus().name(), "batch", batch.batchNumber()));
        if (shortNote != null) {
            after.put("shortfalls", shortfalls);
        }
        audit.record("WORK_ORDER_STARTED", ENTITY, id, Map.of("status", from.name()), after);
        return detail(order);
    }

    @Transactional
    public WorkOrderDetail hold(UUID id, ReasonRequest request) {
        WorkOrder order = load(id);
        WorkOrder.Status from = order.getStatus();
        String reason = request.reason().trim();
        order.hold(reason);
        return saved(order, from, reason, "WORK_ORDER_HELD");
    }

    @Transactional
    public WorkOrderDetail resume(UUID id) {
        WorkOrder order = load(id);
        WorkOrder.Status from = order.getStatus();
        order.resume();
        return saved(order, from, null, "WORK_ORDER_RESUMED");
    }

    /**
     * Drafts and submitted orders can be cancelled by whoever manages work orders. Once approved, the order has been
     * committed to, and only an approver can cancel it.
     */
    @Transactional
    public WorkOrderDetail cancel(UUID id, ReasonRequest request, boolean canManage, boolean canApprove) {
        WorkOrder order = load(id);
        WorkOrder.Status from = order.getStatus();
        if (order.committed() ? !canApprove : !canManage) {
            throw new org.springframework.security.access.AccessDeniedException(order.committed()
                    ? "Only an approver can cancel an approved work order."
                    : "You cannot cancel work orders.");
        }
        String reason = request.reason().trim();
        order.cancel(reason);
        steps.findByWorkOrderIdOrderBySequence(id).forEach(WorkOrderOperation::cancel);
        orderMaterials.releaseAll(order, "Work order cancelled: " + reason);
        return saved(order, from, reason, "WORK_ORDER_CANCELLED");
    }

    private WorkOrderDetail saved(WorkOrder order, WorkOrder.Status from, String reason, String action) {
        workOrders.saveAndFlush(order);
        record(order, from, reason);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", order.getStatus().name());
        if (reason != null && !reason.isBlank()) {
            after.put("reason", reason);
        }
        audit.record(action, ENTITY, order.getId(), Map.of("status", from.name()), after);
        return detail(order);
    }

    private void record(WorkOrder order, WorkOrder.Status from, String reason) {
        history.saveAndFlush(new WorkOrderStatusChange(order.getId(), from, order.getStatus(),
                reason == null || reason.isBlank() ? null : reason));
    }

    /** The plan must be approved, and the order must be for the line's product, in the plan's facility. */
    private ProductionPlanLine orderableLine(SaveWorkOrderRequest request) {
        ProductionPlanLine line = planLines.findById(request.planLineId())
                .orElseThrow(() -> new ResourceNotFoundException("Plan line", request.planLineId()));
        ProductionPlan plan = plans.findById(line.getPlanId()).orElseThrow();
        scope.requireAllowed(plan.getFacilityId(), "Production plan", plan.getPlanNumber());
        if (plan.getStatus() != ProductionPlan.Status.APPROVED) {
            throw new BusinessRuleException("Plan %s is %s. Work orders are raised from approved plans only."
                    .formatted(plan.getPlanNumber(), plan.getStatus().name().toLowerCase()));
        }
        if (!line.getProductId().equals(request.productId())) {
            throw new BusinessRuleException("This plan line is for another product.");
        }
        if (!plan.getFacilityId().equals(request.facilityId())) {
            throw new BusinessRuleException("A work order raised from plan %s is made in the plan's facility."
                    .formatted(plan.getPlanNumber()));
        }
        return line;
    }

    private void apply(WorkOrder order, SaveWorkOrderRequest request) {
        ProductReference product = products.requireActive(request.productId());
        facilities.requireActive(request.facilityId());
        scope.requireAllowed(request.facilityId(), "Facility", request.facilityId().toString());
        if (request.productionLineId() != null) {
            facilities.requireActiveLine(request.facilityId(), request.productionLineId());
        }
        if (request.plannedEnd().isBefore(request.plannedStart())) {
            throw new BusinessRuleException("The planned end cannot be before the planned start.");
        }
        order.setProductId(product.id());
        order.setUomId(product.uomId());
        order.setFacilityId(request.facilityId());
        order.setProductionLineId(request.productionLineId());
        order.setQuantity(request.quantity());
        order.setPlannedStart(request.plannedStart());
        order.setPlannedEnd(request.plannedEnd());
        order.setPriority(request.priority() == null ? WorkOrder.Priority.NORMAL : request.priority());
        order.setNotes(request.notes());
    }

    private record Resolution(Bom definition, Routing routing, List<String> problems) {
    }

    /** The revisions in effect on the planned start, and what would stop a release with them. */
    private Resolution resolve(WorkOrder order) {
        List<String> problems = new ArrayList<>();
        ProductReference product = products.references(List.of(order.getProductId())).get(order.getProductId());
        LocalDate on = order.getPlannedStart();
        Optional<Bom.Kind> kind = settings.definitionKind(order.getProductId());
        Bom definition = kind.flatMap(k -> boms.findInEffect(List.of(order.getProductId()), on).stream()
                .filter(bom -> bom.getKind() == k).findFirst()).orElse(null);
        if (definition == null && settings.workOrderRequiresBom()) {
            problems.add(kind.isEmpty()
                    ? "Choose whether %s is made by discrete or process manufacturing.".formatted(product.code())
                    : "%s has no approved %s in effect on %s.".formatted(product.code(),
                            kind.get() == Bom.Kind.BOM ? "BOM" : "recipe", on));
        }
        Routing routing = routings.findApprovedOn(order.getProductId(), on, RevisionedDefinition.Status.APPROVED)
                .stream().findFirst().orElse(null);
        if (routing == null && settings.workOrderRequiresRouting()) {
            problems.add("%s has no approved routing in effect on %s.".formatted(product.code(), on));
        }
        if (routing != null) {
            List<RoutingOperation> operations = routingOperations.findByRoutingIdOrderBySequence(routing.getId());
            Map<UUID, WorkCenter> centres = workCenters.byId(
                    operations.stream().map(RoutingOperation::getWorkCenterId).collect(Collectors.toSet()));
            centres.values().stream()
                    .filter(centre -> !centre.getFacilityId().equals(order.getFacilityId())
                            || centre.getArchivedAt() != null)
                    .map(centre -> centre.getArchivedAt() != null
                            ? "Work centre %s in the routing is archived.".formatted(centre.getCode())
                            : "Work centre %s in the routing is in another facility.".formatted(centre.getCode()))
                    .sorted()
                    .forEach(problems::add);
        }
        return new Resolution(definition, routing, problems);
    }

    /** One level deep: a sub-assembly is needed as it is, and has its own work order. */
    private void writeRequirements(WorkOrder order, Bom definition) {
        int number = 0;
        for (BomLine line : bomLines.findByBomIdOrderByLineNumber(definition.getId())) {
            BigDecimal needed = line.isScales()
                    ? line.getQuantity().multiply(order.getQuantity())
                            .divide(definition.getBaseQuantity(), 12, RoundingMode.HALF_UP)
                    : line.getQuantity();
            needed = needed.multiply(BigDecimal.ONE.add(
                    line.getScrapPercent().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
            UUID componentUom = line.getMaterialId() != null
                    ? materials.references(List.of(line.getMaterialId())).get(line.getMaterialId()).uomId()
                    : products.references(List.of(line.getComponentProductId()))
                            .get(line.getComponentProductId()).uomId();
            BigDecimal own = materials.convert(needed.setScale(6, RoundingMode.HALF_UP), line.getUomId(), componentUom)
                    .setScale(6, RoundingMode.HALF_UP);
            requirements.save(new WorkOrderMaterial(order.getId(), ++number, line.getMaterialId(),
                    line.getComponentProductId(), own, componentUom));
        }
        requirements.flush();
    }

    /** Run time in a routing is per unit of the product, so the order's run time is that times its quantity. */
    private void writeOperations(WorkOrder order, Routing routing) {
        for (RoutingOperation operation : routingOperations.findByRoutingIdOrderBySequence(routing.getId())) {
            steps.save(new WorkOrderOperation(order.getId(), operation.getSequence(), operation.getName(),
                    operation.getWorkCenterId(), operation.getSetupMinutes(),
                    operation.getRunMinutes().multiply(order.getQuantity()).setScale(4, RoundingMode.HALF_UP),
                    operation.getInstructions()));
        }
        steps.flush();
    }

    private WorkOrder load(UUID id) {
        WorkOrder order = workOrders.findById(id).orElseThrow(() -> new ResourceNotFoundException(ENTITY, id));
        scope.requireAllowed(order.getFacilityId(), ENTITY, order.getWorkOrderNumber());
        return order;
    }

    private WorkOrderDetail detail(WorkOrder order) {
        WorkOrderResponse response = responses(List.of(order)).apply(order);
        ReleaseDefinition definition;
        if (order.getReleasedAt() != null) {
            Bom bom = order.getBomId() == null ? null : boms.findById(order.getBomId()).orElseThrow();
            Routing routing = order.getRoutingId() == null ? null : routings.findById(order.getRoutingId()).orElseThrow();
            definition = definition(bom, routing, List.of());
        } else if (order.getStatus().isOpen()) {
            Resolution resolution = resolve(order);
            definition = definition(resolution.definition(), resolution.routing(), resolution.problems());
        } else {
            definition = definition(null, null, List.of());
        }
        return new WorkOrderDetail(response, definition, requirementResponses(order), operationResponses(order),
                historyResponses(order));
    }

    private static ReleaseDefinition definition(Bom bom, Routing routing, List<String> problems) {
        return new ReleaseDefinition(bom == null ? null : bom.getKind().name(), bom == null ? null : bom.getId(),
                bom == null ? null : bom.getRevision(), routing == null ? null : routing.getId(),
                routing == null ? null : routing.getRevision(), problems);
    }

    private List<RequirementResponse> requirementResponses(WorkOrder order) {
        List<WorkOrderMaterial> rows = requirements.findByWorkOrderIdOrderByLineNumber(order.getId());
        Map<UUID, MaterialReference> materialRefs = materials.references(
                rows.stream().map(WorkOrderMaterial::getMaterialId).filter(Objects::nonNull).toList());
        Map<UUID, ProductReference> productRefs = products.references(
                rows.stream().map(WorkOrderMaterial::getComponentProductId).filter(Objects::nonNull).toList());
        return rows.stream().map(row -> {
            if (row.getMaterialId() != null) {
                MaterialReference material = materialRefs.get(row.getMaterialId());
                return new RequirementResponse(row.getLineNumber(), MATERIAL, material.id(), material.code(),
                        material.name(), row.getRequiredQuantity(), row.getUomId());
            }
            ProductReference component = productRefs.get(row.getComponentProductId());
            return new RequirementResponse(row.getLineNumber(), PRODUCT, component.id(), component.code(),
                    component.name(), row.getRequiredQuantity(), row.getUomId());
        }).toList();
    }

    private List<WorkOrderOperationResponse> operationResponses(WorkOrder order) {
        List<WorkOrderOperation> rows = steps.findByWorkOrderIdOrderBySequence(order.getId());
        Map<UUID, WorkCenter> centres = workCenters.byId(
                rows.stream().map(WorkOrderOperation::getWorkCenterId).collect(Collectors.toSet()));
        return rows.stream().map(row -> {
            WorkCenter centre = centres.get(row.getWorkCenterId());
            return new WorkOrderOperationResponse(row.getId(), row.getSequence(), row.getName(), row.getWorkCenterId(),
                    centre.getCode(), centre.getName(), row.getPlannedSetupMinutes(), row.getPlannedRunMinutes(),
                    row.getInstructions(), row.getStatus());
        }).toList();
    }

    private List<StatusChangeResponse> historyResponses(WorkOrder order) {
        Map<UUID, String> members = support.members();
        return history.findByWorkOrderIdOrderByCreatedAtAscIdAsc(order.getId()).stream()
                .map(change -> new StatusChangeResponse(change.getFromStatus(), change.getToStatus(),
                        change.getReason(), change.getCreatedBy(), members.get(change.getCreatedBy()),
                        change.getCreatedAt()))
                .toList();
    }

    /** Looks up names and codes for a page of orders at once. */
    private Function<WorkOrder, WorkOrderResponse> responses(List<WorkOrder> orders) {
        Map<UUID, ProductReference> productRefs = products.references(
                orders.stream().map(WorkOrder::getProductId).collect(Collectors.toSet()));
        Set<UUID> places = new HashSet<>();
        orders.forEach(order -> {
            places.add(order.getFacilityId());
            if (order.getProductionLineId() != null) {
                places.add(order.getProductionLineId());
            }
        });
        Map<UUID, String> codes = places.isEmpty() ? Map.of() : facilities.codes(places);
        Map<UUID, ProductionPlanLine> lineRefs = planLines.findByIdIn(orders.stream()
                        .map(WorkOrder::getPlanLineId).filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(ProductionPlanLine::getId, Function.identity()));
        Map<UUID, ProductionPlan> planRefs = plans.findAllById(lineRefs.values().stream()
                        .map(ProductionPlanLine::getPlanId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(ProductionPlan::getId, Function.identity()));
        List<UUID> batchIds = orders.stream().map(WorkOrder::getProductionBatchId).filter(Objects::nonNull).toList();
        Map<UUID, String> batchNumbers = batchIds.isEmpty() ? Map.of() : production.views(batchIds).stream()
                .collect(Collectors.toMap(ProductionBatchView::id, ProductionBatchView::batchNumber));
        Map<UUID, String> members = support.members();
        return order -> {
            ProductReference product = productRefs.get(order.getProductId());
            ProductionPlanLine line = order.getPlanLineId() == null ? null : lineRefs.get(order.getPlanLineId());
            ProductionPlan plan = line == null ? null : planRefs.get(line.getPlanId());
            return new WorkOrderResponse(order.getId(), order.getWorkOrderNumber(), order.getProductId(),
                    product == null ? null : product.code(), product == null ? null : product.name(),
                    order.getFacilityId(), codes.get(order.getFacilityId()), order.getProductionLineId(),
                    order.getProductionLineId() == null ? null : codes.get(order.getProductionLineId()),
                    order.getQuantity(), order.getUomId(), order.getPlannedStart(), order.getPlannedEnd(),
                    order.getPriority(), order.getStatus(), order.getHeldFrom(), order.getStatusReason(),
                    order.getNotes(), plan == null ? null : plan.getId(), plan == null ? null : plan.getPlanNumber(),
                    order.getPlanLineId(), order.getProductionBatchId(),
                    order.getProductionBatchId() == null ? null : batchNumbers.get(order.getProductionBatchId()),
                    order.getSubmittedBy(), members.get(order.getSubmittedBy()), order.getSubmittedAt(),
                    order.getApprovedBy(), members.get(order.getApprovedBy()), order.getApprovedAt(),
                    order.getReleasedBy(), members.get(order.getReleasedBy()), order.getReleasedAt(),
                    order.getStartedAt(), order.getCreatedAt());
        };
    }
}
